package app.octopus.local.providers

import app.octopus.local.domain.ProviderId
import app.octopus.local.pricing.MoneyParser

/** Honor 3.12.501 cart drawer only. No checkout or clear-cart selector. */
open class KeetaBasketReader(private val catalog: KeetaMenuReader, private val requestedTitles: Set<String> = setOf(catalog.title)) {
    init { require(requestedTitles.isNotEmpty() && catalog.title in requestedTitles && requestedTitles.all { it.isNotBlank() }) }
    companion object {
        fun requestedTitleCandidate(raw: String, requested: Set<String>): String? =
            raw.replace(Regex("[\\u200e\\u200f\\u061c\\u202a-\\u202e\\u2066-\\u2069]"), "").trim()
                .takeIf {it in requested}
    }
    private val title get() = catalog.title
    private fun parent(f: CatalogFrame, n: CatalogNode) = f.nodes.singleOrNull { it.index == n.parent }
    private fun ancestors(f: CatalogFrame, n: CatalogNode): List<CatalogNode> {
        val result = mutableListOf<CatalogNode>(); var current = n
        repeat(12) { current = parent(f, current) ?: return result; result += current }; return result
    }
    private fun quantity(n: CatalogNode) = n.label.takeIf { it.matches(Regex("[0-9٠-٩]{1,2}")) }
        ?.let(MoneyParser::parse)?.div(100)?.toInt()?.takeIf { it in 1..99 }

    fun emptyMenu(f: CatalogFrame): Boolean {
        if (!catalog.menu(f) || f.nodes.any { it.label in setOf("إتمام الدفع", "الأصناف المضافة", "تفريغ السلة") }) return false
        val meal = catalog.productTarget(f) ?: return false
        return f.descendants(meal.index).none { quantity(it) != null }
    }

    fun addTarget(f: CatalogFrame, expectedUnit: Long): CatalogNode? {
        if (!catalog.product(f) || catalog.price(f) != expectedUnit ||
            catalog.unitPrice(f) != expectedUnit) return null
        val body = catalog.productBody(f) ?: return null
        val content = f.descendants(body.index)
        val expectedEditors = if (catalog.profile.usesCurrentLayout && !catalog.hasOptionalPlainProof(f)) 0 else 1
        if ((body.scrollable && !catalog.hasOptionalPlainProof(f)) || content.filter { it.editable }.size != expectedEditors || content.any { it.editable && it.enteredText }) return null
        val add = f.nodes.singleOrNull { it.label == "إضافة إلى السلة" } ?: return null
        for (group in ancestors(f, add)) {
            val children = f.nodes.filter { it.parent == group.index }
            if (f.descendants(group.index).size > 35) return null
            if (children.size == 2 && children[0].clickable && children[0].enabled && children[0].label.isEmpty() &&
                children[0].className == "android.view.ViewGroup" &&
                f.descendants(children[1].index).count { it.label == "إضافة إلى السلة" } == 1 &&
                f.descendants(children[1].index).count { it.label.contains('﷼') } == 1) return children[0]
        }
        return null
    }

    fun closeDrawer(f: CatalogFrame): CatalogNode? {
        if (!catalog.basketDrawer(f)) return null
        val heading = f.nodes.singleOrNull { it.label == "الأصناف المضافة" } ?: return null
        val modal = ancestors(f, heading).getOrNull(2) ?: return null
        val close = f.nodes.firstOrNull { it.parent == modal.index } ?: return null
        val body = f.descendants(close.index)
        if (body.size > 8 || body.any { it.label.isNotEmpty() || it.omittedText } ||
            body.count { it.className == "android.widget.ImageView" } != 1) return null
        return body.singleOrNull { it.clickable && it.enabled && it.className == "android.view.ViewGroup" }
    }

    fun footerBag(f: CatalogFrame): CatalogNode? {
        if (!catalog.menu(f)) return null
        val checkout = f.nodes.singleOrNull { it.label == "إتمام الدفع" } ?: return null
        val footer = ancestors(f, checkout).firstOrNull { p ->
            f.nodes.count { it.parent == p.index && it.clickable } == 3 &&
                f.descendants(p.index).size <= 70
        } ?: return null
        return f.nodes.filter { it.parent == footer.index && it.clickable && it.enabled }.singleOrNull { child ->
            val body = f.descendants(child.index)
            body.none { it.label == "إتمام الدفع" || it.label.contains('﷼') || it.omittedText } &&
                body.count { quantity(it) != null && !it.clickable } == 1
        }
    }
    fun menuQuantity(f: CatalogFrame): Int? {
        val bag=footerBag(f) ?: return null
        return f.descendants(bag.index).filter { !it.clickable }.mapNotNull(::quantity).singleOrNull()
    }

    /** Restore only requested food names in the observed drawer's first text slot per row.
     * Other requested names in a product/options sheet remain omitted and cannot authorize Add.
     * Candidates are transient exact-name matches, never raw arbitrary screen text.
     */
    fun restoreRequestedRowTitles(f: CatalogFrame, candidates: Map<Int, String>): CatalogFrame {
        val cards = visibleCards(f) ?: return f
        val headers = cards.mapNotNull { card ->
            val node = f.descendants(card.index).firstOrNull { it.className == "android.widget.TextView" } ?: return@mapNotNull null
            val name = candidates[node.index]?.takeIf { it in requestedTitles } ?: return@mapNotNull null
            if(node.clickable || node.editable || node.id.isNotEmpty() || node.label.isNotEmpty() || !node.omittedText) null
            else node.index to name
        }.toMap()
        val scoped=f.copy(nodes=f.nodes.map { node -> headers[node.index]?.let { node.copy(label=it,omittedText=false) } ?: node })
        val variants=cards.flatMap { card ->
            val body=scoped.descendants(card.index)
            val name=body.firstOrNull {it.className=="android.widget.TextView"}?.label?.takeIf {it in requestedTitles}
                ?: return@flatMap emptyList()
            body.filter {node -> candidates[node.index]==name && node.label.isEmpty() && node.omittedText &&
                !node.clickable && !node.editable && node.id.isEmpty() && plainSkuLabel(scoped,node,name)
            }.map {it.index to name}
        }.toMap()
        return scoped.copy(nodes=scoped.nodes.map {node -> variants[node.index]?.let {node.copy(label=it,omittedText=false)} ?: node})
    }

    private fun visibleCards(f: CatalogFrame): List<CatalogNode>? {
        if (!catalog.basketDrawer(f)) return null
        val heading = f.nodes.singleOrNull { it.label == "الأصناف المضافة" } ?: return null
        val pane = parent(f, parent(f, heading) ?: return null) ?: return null
        val scroll = f.descendants(pane.index).singleOrNull { it.className == "android.widget.ScrollView" } ?: return null
        // A scrolling/multi-row pane has not proved its full contents.
        if (scroll.scrollable) return null
        val content = f.nodes.singleOrNull { it.parent == scroll.index } ?: return null
        // Observed 3.13.203 wraps each row in its own section; older captures
        // put several rows in one section. Account for every direct section/child.
        val sections=f.nodes.filter {it.parent==content.index}
        if(sections.isEmpty())return null
        val cards=mutableListOf<CatalogNode>()
        for(section in sections) {
            if(section.className!="android.view.ViewGroup" || section.clickable || section.editable ||
                section.scrollable || section.label.isNotEmpty() || section.omittedText)return null
            val children=f.nodes.filter {it.parent==section.index}
            val rows=children.filter {it.clickable && it.enabled && it.className=="android.view.ViewGroup"}
            if(rows.isEmpty())return null
            for(separator in children-rows.toSet()) {
                val body=f.descendants(separator.index)
                if(body.size>2 || body.any {it.className!="android.view.ViewGroup" || it.clickable || it.editable ||
                        it.scrollable || it.label.isNotEmpty() || it.omittedText})return null
            }
            cards+=rows
        }
        if(cards.size>requestedTitles.size)return null
        return cards
    }

    private fun plainSkuLabel(f:CatalogFrame,node:CatalogNode,name:String=node.label):Boolean {
        val control=parent(f,node) ?: return false
        val wrapper=parent(f,control) ?: return false
        val parts=f.nodes.filter {it.parent==wrapper.index}
        return control.clickable && control.enabled && control.className=="android.view.ViewGroup" &&
            f.descendants(control.index).size==2 && parts.size==2 && parts[1]==control &&
            f.descendants(parts[0].index).size==3 &&
            f.descendants(parts[0].index).count {it.className=="android.widget.ImageView"}==1 &&
            f.descendants(parts[0].index).count {it.label==name && !it.clickable}==1 &&
            f.descendants(parts[0].index).none {it.label.isNotEmpty() && it.label!=name || it.omittedText || it.clickable}
    }

    private fun rows(f: CatalogFrame): List<CatalogNode>? {
        val cards = visibleCards(f) ?: return null
        val names = mutableSetOf<String>()
        for (card in cards) {
        if (!card.clickable || !card.enabled) return null
        val body = f.descendants(card.index)
        val named = body.filter { it.label in requestedTitles }
        val name = named.map {it.label}.distinct().singleOrNull() ?: return null
        if(named.size !in 1..2 || (named.size==2 && !plainSkuLabel(f,named[1]))) return null
        if (!names.add(name)) return null
        if (body.size > 65 || body.any { it.omittedText || it.editable } ||
            body.filter { it.label.isNotEmpty() }.any { it.label != name && quantity(it) == null && !it.label.contains('﷼') } ||
            body.count { it.label == name } !in 1..2 || body.count { quantity(it) != null && it.clickable } != 1 ||
            body.count { it.label.contains('﷼') && MoneyParser.parse(it.label) != null } != 1) return null
        }
        return cards
    }

    private fun row(f: CatalogFrame): CatalogNode? = rows(f)?.singleOrNull { card ->
        f.descendants(card.index).any { it.label == title }
    }

    fun evidence(f: CatalogFrame): BasketEvidence? {
        val cards = rows(f) ?: return null
        val lines = cards.map { card ->
            val body = f.descendants(card.index)
            val name = body.first { it.label in requestedTitles }.label
            val count = body.singleOrNull { quantity(it) != null && it.clickable }?.let(::quantity) ?: return null
            val amount = body.singleOrNull { it.label.contains('﷼') }?.label?.let(MoneyParser::parse) ?: return null
            BasketLineEvidence(name, emptyList(), count, amount)
        }
        val subtotal = runCatching { lines.fold(0L) { total, line -> Math.addExact(total,line.lineAmount!!) } }.getOrNull() ?: return null
        return BasketEvidence(ProviderId.KEETA, catalog.restaurant, lines, subtotal, true)
    }

    fun quantityTarget(f: CatalogFrame, increase: Boolean): CatalogNode? {
        val card = row(f) ?: return null
        val number = f.descendants(card.index).singleOrNull { quantity(it) != null && it.clickable } ?: return null
        if (!increase && quantity(number) == 1) return null
        if (increase && quantity(number) == 99) return null
        val group = parent(f, number) ?: return null
        val children = f.nodes.filter { it.parent == group.index }
        if (children.size != 3 || children[1] != number) return null
        val side = children[if (increase) 2 else 0]
        val controls = f.nodes.filter { it.parent == side.index }
        if (controls.size != 2 || controls[1].className != "android.widget.ImageView") return null
        return controls[0].takeIf { it.clickable && it.enabled && it.label.isEmpty() && it.className == "android.view.ViewGroup" }
    }
}

/** Legacy recorded fixture context only. */
object KeetaRidanBasket : KeetaBasketReader(KeetaMenuPreview)
