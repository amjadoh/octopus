package app.octopus.local.automation

import app.octopus.local.domain.*
import app.octopus.local.matching.ObservedCart
import app.octopus.local.providers.*
import app.octopus.local.pricing.MoneyParser

/** A bounded program for the previously recorded PDP. No checkout operation exists. */
sealed interface ProductCommand {
    data class Choose(val group: String, val value: String, val node: CatalogNode) : ProductCommand
    data class Scroll(val node: CatalogNode) : ProductCommand
    data class Add(val node: CatalogNode) : ProductCommand
    data object ObserveBasket : ProductCommand
    data class Blocked(val reason: ProductBlock) : ProductCommand
}

enum class ProductBlock {
    CONTEXT, UNSUPPORTED, STALE_PRODUCT, WRONG_PRODUCT, INCOMPLETE_SCREEN,
    AMBIGUOUS_ELEMENT, UNAVAILABLE, OPTION_NOT_FOUND, ACTION_FAILED, BASKET_NOT_VERIFIED,
}

/**
 * This is an executor component, not an end-to-end provider adapter. Its caller must obtain the
 * complete basket/context from the live provider before starting, and read the basket afterwards.
 * In particular a successful click never establishes product selection or basket readiness.
 */
class HungerStationProductProgram private constructor(
    private val details: ConfiguredProduct,
    val desired: CanonicalCart,
    val before: ObservedCart,
    private val requested: CanonicalItem,
    private val choices: List<Pair<String, String>>,
    private val startedAt: Long,
) {
    private val completedGroups = mutableSetOf<String>()
    private var pending: ProductCommand? = null
    private var attempted: ProductCommand? = null
    private var addAttempted = false
    private var failure: ProductBlock? = null
    private val scrolls = mutableMapOf<String, Int>()
    private var lastScrollBody: String? = null
    val awaitingBasket: Boolean get() = addAttempted && failure == null
    internal fun authorizes(command: ProductCommand, now: Long): Boolean = pending === command && attempted !== command && failure == null &&
        !addAttempted && now >= startedAt && now - startedAt <= 120_000
    internal fun markAttempted(command: ProductCommand, now: Long): Boolean {
        if (!authorizes(command, now)) return false
        attempted = command
        return true
    }

    fun next(frame: CatalogFrame, now: Long): ProductCommand {
        failure?.let { return ProductCommand.Blocked(it) }
        if (now < startedAt || now - startedAt > 120_000) return block(ProductBlock.STALE_PRODUCT)
        if (addAttempted) return ProductCommand.ObserveBasket
        // Repeating next() cannot issue a second action before acknowledgement of the first.
        pending?.let { return it }
        if (!frame.complete) return block(ProductBlock.INCOMPLETE_SCREEN)
        if (HungerStationCatalog.screen(frame) != CatalogScreen.PRODUCT) return block(ProductBlock.WRONG_PRODUCT)
        val titles = frame.nodes.filter { !it.clickable && clean(it.label) == details.product.title }
        if (titles.size != 1) return block(ProductBlock.WRONG_PRODUCT)
        val wanted = choices.firstOrNull { it.first !in completedGroups }
        if (wanted != null) {
            val (group, value) = wanted
            val nodes = frame.nodes.filter { it.label.isNotBlank() }
            val candidates = mutableListOf<CatalogNode>()
            var current: String? = null
            var i = 0
            while (i < nodes.size) {
                val n = nodes[i]
                val label = clean(n.label)
                if (label == "غالبًا ما يتم طلبه مع") break
                if (!n.clickable && clean(nodes.getOrNull(i + 1)?.label.orEmpty()) == "مطلوب" &&
                    clean(nodes.getOrNull(i + 2)?.label.orEmpty()) == "اختيار واحد") {
                    current = label; i += 3; continue
                }
                if (current == group && n.clickable && n.id.isEmpty() && label.lineSequence().first() == value &&
                    label.contains('\n') && (label.contains("طلب") || label.contains('§'))) candidates += n
                i++
            }
            if (candidates.size > 1) return block(ProductBlock.AMBIGUOUS_ELEMENT)
            if (candidates.size == 1) {
                val node = candidates.single()
                if (!node.enabled) return block(ProductBlock.UNAVAILABLE)
                return ProductCommand.Choose(group, value, node).also { pending = it }
            }
            if ((scrolls[group] ?: 0) >= 12) return block(ProductBlock.OPTION_NOT_FOUND)
            val body = frame.nodes.joinToString("|") { it.id + ":" + it.label }
            if (body == lastScrollBody) return block(ProductBlock.OPTION_NOT_FOUND)
            val scroller = frame.nodes.filter { it.scrollable && it.enabled && it.className == "android.widget.ScrollView" }
            if (scroller.size != 1) return block(ProductBlock.AMBIGUOUS_ELEMENT)
            return ProductCommand.Scroll(scroller.single()).also { pending = it; lastScrollBody = body }
        }
        val buttons = frame.nodes.filter { it.id == "flutterAddButton" && it.clickable &&
            it.className == "android.widget.Button" && isAddLabel(it.label) }
        if (buttons.size != 1) return block(ProductBlock.AMBIGUOUS_ELEMENT)
        if (!buttons.single().enabled) return block(ProductBlock.UNAVAILABLE)
        return ProductCommand.Add(buttons.single()).also { pending = it }
    }

    /** The same command instance must be acknowledged once. False/exception never triggers a retry. */
    fun acknowledge(command: ProductCommand, succeeded: Boolean) {
        require(pending === command) { "Command is not pending" }
        pending = null
        if (command is ProductCommand.Add) addAttempted = true
        if (!succeeded) { failure = ProductBlock.ACTION_FAILED; return }
        when (command) {
            is ProductCommand.Choose -> { completedGroups += command.group; lastScrollBody = null }
            is ProductCommand.Scroll -> {
                val group = choices.first { it.first !in completedGroups }.first
                scrolls[group] = (scrolls[group] ?: 0) + 1
            }
            else -> Unit
        }
    }

    fun verifyResult(observed: ObservedCart, now: Long): Boolean {
        if (!addAttempted || failure != null || now < startedAt || now - startedAt > 120_000 || !observed.complete ||
            observed.addressReference != before.addressReference || observed.branch != before.branch ||
            observed.restaurant?.let(IdentityText::clean) != IdentityText.clean(desired.restaurant)) {
            failure = ProductBlock.BASKET_NOT_VERIFIED
            return false
        }
        val expected = before.items + requested
        val matches = CartIdentity.quantities(observed.items) == CartIdentity.quantities(expected)
        if (!matches) failure = ProductBlock.BASKET_NOT_VERIFIED
        return matches
    }

    private fun block(reason: ProductBlock): ProductCommand.Blocked {
        failure = reason
        return ProductCommand.Blocked(reason)
    }

    companion object {
        internal fun isAddLabel(label: String): Boolean {
            val lines = clean(label).lineSequence().map(String::trim).toList()
            return lines.size == 3 && lines[0] == "إضافة" && lines[1] == "§" &&
                MoneyParser.parse(lines[2])?.let { it >= 0 } == true
        }
        fun create(details: ConfiguredProduct, selection: ProductSelection, desired: CanonicalCart,
            before: ObservedCart, now: Long): HungerStationProductProgram? {
            if (details.source.provider != ProviderId.HUNGERSTATION ||
                details.source.providerVersion != HungerStationCatalog.TESTED_VERSION ||
                IdentityText.clean(details.restaurant) != "المضياف العربي" ||
                IdentityText.clean(details.product.title) != "دجاج مظبي" ||
                details.product.groups.map { it.title } != listOf("اختر الحجم", "إضافات") ||
                details.product.groups.any { it.minimum != 1 || it.maximum != 1 }) return null
            val item = (MenuSelectionValidator().selectConfigured(details, selection, now) as? SelectionResult.Accepted)?.item ?: return null
            // Quantity controls and cross-restaurant basket replacement are not evidenced by this PDP.
            if (item.quantity != 1 || !item.note.isNullOrBlank() || !before.complete || before.items.isNotEmpty() ||
                desired.branch == null || desired.branch != before.branch || desired.addressReference == null ||
                desired.addressReference != before.addressReference ||
                IdentityText.clean(desired.restaurant) != IdentityText.clean(details.restaurant) ||
                (before.restaurant != null && IdentityText.clean(before.restaurant) != IdentityText.clean(desired.restaurant)) ||
                desired.items.size != 1 || desired.items.single() != item) return null
            val choices = details.product.groups.map { g ->
                g.title to g.choices.single { it.id == selection.choices.getValue(g.id).single() }.title
            }
            if (choices.any { (group, value) -> if (group == "اختر الحجم") value != "نص"
                    else value !in setOf("رز بشاور", "رز شعبي", "رز مندي") }) return null
            return HungerStationProductProgram(details, desired, before, item, choices, now)
        }
        private fun clean(value: String) = value.replace(Regex("[\\u200e\\u200f\\u061c\\u202a-\\u202e\\u2066-\\u2069]"), "").trim()
    }
}
