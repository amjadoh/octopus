package app.octopus.local.providers

import app.octopus.local.domain.ProviderId
import app.octopus.local.pricing.MoneyParser

/** Honor 3.12.501 cart drawer only. No checkout or clear-cart selector. */
object KeetaRidanBasket {
    private const val TITLE = "نصف دجاج مضغوط"
    private fun parent(f: CatalogFrame, n: CatalogNode) = f.nodes.singleOrNull { it.index == n.parent }
    private fun ancestors(f: CatalogFrame, n: CatalogNode): List<CatalogNode> {
        val result = mutableListOf<CatalogNode>(); var current = n
        repeat(12) { current = parent(f, current) ?: return result; result += current }; return result
    }
    private fun quantity(n: CatalogNode) = n.label.takeIf { it.matches(Regex("[0-9٠-٩]{1,2}")) }
        ?.let(MoneyParser::parse)?.div(100)?.toInt()?.takeIf { it in 1..99 }

    fun closeDrawer(f: CatalogFrame): CatalogNode? {
        if (!KeetaMenuPreview.basketDrawer(f)) return null
        val heading = f.nodes.singleOrNull { it.label == "الأصناف المضافة" } ?: return null
        val modal = ancestors(f, heading).getOrNull(2) ?: return null
        val close = f.nodes.firstOrNull { it.parent == modal.index } ?: return null
        val body = f.descendants(close.index)
        if (body.size > 8 || body.any { it.label.isNotEmpty() || it.omittedText } ||
            body.count { it.className == "android.widget.ImageView" } != 1) return null
        return body.singleOrNull { it.clickable && it.enabled && it.className == "android.view.ViewGroup" }
    }

    fun footerBag(f: CatalogFrame): CatalogNode? {
        if (!KeetaMenuPreview.menu(f)) return null
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

    private fun row(f: CatalogFrame): CatalogNode? {
        if (!KeetaMenuPreview.basketDrawer(f)) return null
        val heading = f.nodes.singleOrNull { it.label == "الأصناف المضافة" } ?: return null
        val pane = parent(f, parent(f, heading) ?: return null) ?: return null
        val scroll = f.descendants(pane.index).singleOrNull { it.className == "android.widget.ScrollView" } ?: return null
        // A scrolling/multi-row pane has not proved its full contents.
        if (scroll.scrollable) return null
        val content = f.nodes.singleOrNull { it.parent == scroll.index } ?: return null
        val rows = f.nodes.filter { it.parent == content.index }.firstOrNull() ?: return null
        val cards = f.nodes.filter { it.parent == rows.index }
        val card = cards.singleOrNull()?.takeIf { it.clickable && it.enabled } ?: return null
        val body = f.descendants(card.index)
        if (body.size > 65 || body.any { it.omittedText || it.editable } ||
            body.filter { it.label.isNotEmpty() }.any { it.label != TITLE && quantity(it) == null && !it.label.contains('﷼') } ||
            body.count { it.label == TITLE } != 1 || body.count { quantity(it) != null && it.clickable } != 1 ||
            body.count { it.label.contains('﷼') && MoneyParser.parse(it.label) != null } != 1) return null
        return card
    }

    fun evidence(f: CatalogFrame): BasketEvidence? {
        val row = row(f) ?: return null
        val body = f.descendants(row.index)
        val count = body.singleOrNull { quantity(it) != null && it.clickable }?.let(::quantity) ?: return null
        val amount = body.singleOrNull { it.label.contains('﷼') }?.label?.let(MoneyParser::parse) ?: return null
        return BasketEvidence(ProviderId.KEETA, "ريدان", listOf(BasketLineEvidence(TITLE, emptyList(), count, amount)), amount, true)
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
