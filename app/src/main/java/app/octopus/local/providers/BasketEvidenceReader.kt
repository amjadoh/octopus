package app.octopus.local.providers

import app.octopus.local.domain.*
import app.octopus.local.pricing.MoneyParser

data class BasketChoiceEvidence(val value: String, val quantity: Int?)
data class BasketLineEvidence(val name: String, val choices: List<BasketChoiceEvidence>, val quantity: Int?, val lineAmount: Long?)
data class BasketEvidence(
    val provider: ProviderId,
    val restaurant: String?,
    val lines: List<BasketLineEvidence>,
    val subtotal: Long?,
    val complete: Boolean,
) {
    // A cart popup/subtotal is never an order-review quote, even if its components add up.
    val recognizedOrderReview: Boolean get() = false
}

/** Reads only the cart shapes recorded previously. No coordinates or cart mutations. */
object BasketEvidenceReader {
    fun hungerStation(frame: CatalogFrame, endOfCartObserved: Boolean): BasketEvidence {
        val title = frame.nodes.singleOrNull { it.id == "FlutterCart_title" }?.label?.let(::lines)
        val restaurant = title?.takeIf { it.size == 2 && it[0] == "السلة" }?.get(1)
        val cards = frame.nodes.filter { it.id == "FlutterCart_cartItem" }
        val parsed = cards.mapNotNull { card ->
            val parts = lines(card.label)
            val edit = parts.indexOf("تعديل")
            val plain = parts.size == 3 && parts.last() == "§" && MoneyParser.parse(parts[1]) != null
            if (!plain && (edit < 1 || parts.size != edit + 3 || parts.last() != "§")) return@mapNotNull null
            val optionLines = if (plain) emptyList() else parts.subList(1, edit)
            val choices = if (optionLines == listOf("لايوجد تعديلات أو إضافات")) emptyList()
                else optionLines.flatMap(::choices)
            val quantities = frame.descendants(card.index).filter { it.id == "menuItemActionDualButtonValue" }
            val quantity = quantities.singleOrNull()?.label?.let(::quantity)
            BasketLineEvidence(parts.first(), choices, quantity, MoneyParser.parse(parts[if (plain) 1 else edit+1]))
        }
        val summaries = frame.nodes.filter { it.id == "FlutterCart_scroll_view" }.flatMap { node ->
            val parts = lines(node.label)
            parts.indices.filter { parts[it] == "المجموع" }.mapNotNull { i ->
                if (parts.getOrNull(i+2) == "§") parts.getOrNull(i+1)?.let(MoneyParser::parse) else null
            }
        }.distinct()
        return BasketEvidence(ProviderId.HUNGERSTATION, restaurant, parsed, summaries.singleOrNull(),
            frame.complete && endOfCartObserved && restaurant != null && cards.isNotEmpty() && cards.size == parsed.size && parsed.all { it.quantity != null })
    }

    /** The restaurant must be read from a separately recognized header in the same active provider session. */
    fun keeta(frame: CatalogFrame, recognizedRestaurant: String?, endOfCartObserved: Boolean): BasketEvidence {
        fun fail() = BasketEvidence(ProviderId.KEETA, recognizedRestaurant, emptyList(), null, false)
        if (!frame.complete) return fail()
        val heading = frame.nodes.singleOrNull { clean(it.label) == "الأصناف المضافة" } ?: return fail()
        val header = frame.nodes.singleOrNull { it.index == heading.parent } ?: return fail()
        if (frame.descendants(header.index).none { clean(it.label) == "تفريغ السلة" }) return fail()
        val pane = frame.nodes.singleOrNull { it.index == header.parent } ?: return fail()
        val body = frame.descendants(pane.index).filter { it.index != pane.index && it.index !in frame.descendants(header.index).map { n -> n.index }.toSet() }
        val possible = body.filter { it.className == "android.view.ViewGroup" }.mapNotNull { node ->
            val leaves = frame.descendants(node.index).filter { it.className == "android.widget.TextView" && it.label.isNotBlank() }
            if (leaves.size !in 3..4) return@mapNotNull null
            val labels = leaves.map { clean(it.label) }
            if (!labels.first().any(Char::isLetter) || labels.first().contains('﷼')) return@mapNotNull null
            val price = leaves.filter { clean(it.label).contains('﷼') }
            val count = leaves.filter { it.clickable && quantity(it.label) != null }
            if (price.size != 1 || count.size != 1 || leaves.last() != count.single()) return@mapNotNull null
            if (leaves[leaves.size-2] != price.single()) return@mapNotNull null
            val options = if (leaves.size == 4) choices(labels[1]) else emptyList()
            node to BasketLineEvidence(labels.first(), options, quantity(count.single().label), MoneyParser.parse(price.single().label))
        }
        // Several layout ancestors may contain the same leaf row; retain only its smallest enclosing group.
        val rows = possible.filter { (node, _) -> possible.none { (child, _) ->
            child.index != node.index && frame.descendants(node.index).any { it.index == child.index }
        } }
        val parsed = rows.map { it.second }
        val textLeaves = body.filter { it.className == "android.widget.TextView" && it.label.isNotBlank() }
        val covered = rows.flatMap { frame.descendants(it.first.index) }.map { it.index }.toSet()
        val allRowsAccountedFor = textLeaves.all { it.index in covered }
        return BasketEvidence(ProviderId.KEETA, recognizedRestaurant, parsed, null,
            endOfCartObserved && recognizedRestaurant != null && parsed.isNotEmpty() && allRowsAccountedFor && parsed.all { it.quantity != null })
    }

    private fun choices(text: String): List<BasketChoiceEvidence> = clean(text).split(',', '،').filter { it.isNotBlank() }.map {
        val value = it.trim()
        val prefix = Regex("^([0-9٠-٩]+)[x×]\\s*(.+)$").matchEntire(value)
        if (prefix == null) BasketChoiceEvidence(value, null)
        else BasketChoiceEvidence(prefix.groupValues[2].trim(), quantity(prefix.groupValues[1]))
    }
    private fun quantity(text: String): Int? {
        val normalized = clean(text).map { c -> if (c in '٠'..'٩') ('0'.code+c.code-'٠'.code).toChar() else c }.joinToString("")
        return normalized.toIntOrNull()?.takeIf { it in 1..99 }
    }
    private fun lines(text: String) = clean(text).lineSequence().map(String::trim).filter(String::isNotBlank).toList()
    private fun clean(text: String) = text.replace(Regex("[\\u200e\\u200f\\u061c\\u202a-\\u202e\\u2066-\\u2069]"), "").trim()
}

enum class BasketItemMismatch { PARTIAL, RESTAURANT, ITEM, OPTIONS, QUANTITY, AMBIGUOUS, OPTION_MULTIPLICITY, OPTION_QUANTITY_UNKNOWN, NOTES_UNKNOWN }
data class BasketItemMatch(val issues: Set<BasketItemMismatch>) { val matches: Boolean get() = issues.isEmpty() }

/** Confirms item labels/quantities only. Branch, address, account and final totals require their own evidence. */
object BasketItemMatcher {
    fun compare(desired: CanonicalCart, actual: BasketEvidence): BasketItemMatch {
        val issues = mutableSetOf<BasketItemMismatch>()
        if (!actual.complete) issues += BasketItemMismatch.PARTIAL
        if (actual.restaurant == null || IdentityText.clean(actual.restaurant) != IdentityText.clean(desired.restaurant)) issues += BasketItemMismatch.RESTAURANT
        val remaining = actual.lines.toMutableList()
        val wanted = desired.items.groupBy(CartIdentity::itemKey).values.mapNotNull { group ->
            val quantity = group.sumOf { it.quantity.toLong() }
            if (quantity !in 1L..99L) {
                issues += BasketItemMismatch.QUANTITY
                null
            } else group.first().copy(quantity = quantity.toInt())
        }
        if (desired.items.isEmpty()) issues += BasketItemMismatch.ITEM
        for (item in wanted) {
            if (!item.note.isNullOrBlank()) issues += BasketItemMismatch.NOTES_UNKNOWN
            val expected = (listOfNotNull(item.size) + item.options.map { it.value }).map(IdentityText::clean).sorted()
            if (expected.distinct().size != expected.size) { issues += BasketItemMismatch.AMBIGUOUS; continue }
            if (item.options.any { it.quantity != 1 }) { issues += BasketItemMismatch.OPTION_MULTIPLICITY; continue }
            val named = remaining.filter { IdentityText.clean(it.name) == IdentityText.clean(item.name) }
            if (named.isEmpty()) { issues += BasketItemMismatch.ITEM; continue }
            val variants = named.filter { line -> line.choices.map { IdentityText.clean(it.value) }.sorted() == expected }
            if (variants.isEmpty()) { issues += BasketItemMismatch.OPTIONS; continue }
            if (variants.size != 1) { issues += BasketItemMismatch.AMBIGUOUS; continue }
            val found = variants.single()
            remaining.remove(found)
            if (found.quantity != item.quantity) issues += BasketItemMismatch.QUANTITY
            if (found.choices.any { it.quantity != null && it.quantity != 1 }) issues += BasketItemMismatch.OPTION_MULTIPLICITY
            if (found.choices.any { choice -> choice.quantity == null && item.options.any { IdentityText.clean(it.value) == IdentityText.clean(choice.value) } })
                issues += BasketItemMismatch.OPTION_QUANTITY_UNKNOWN
        }
        if (remaining.isNotEmpty()) issues += BasketItemMismatch.ITEM
        return BasketItemMatch(issues)
    }
}
