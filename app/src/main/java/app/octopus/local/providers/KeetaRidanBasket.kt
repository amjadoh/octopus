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

    fun emptyMenu(f: CatalogFrame): Boolean {
        if (!KeetaMenuPreview.menu(f) || f.nodes.any { it.label in setOf("إتمام الدفع", "الأصناف المضافة", "تفريغ السلة") }) return false
        val meal = KeetaMenuPreview.productTarget(f) ?: return false
        return f.descendants(meal.index).none { quantity(it) != null }
    }

    fun addTarget(f: CatalogFrame, expectedUnit: Long): CatalogNode? {
        if (!KeetaMenuPreview.product(f) || KeetaMenuPreview.price(f) != expectedUnit ||
            KeetaMenuPreview.unitPrice(f) != expectedUnit) return null
        val notes = f.nodes.singleOrNull { it.label == "طلب خاص" } ?: return null
        val body = ancestors(f, notes).firstOrNull { it.className == "android.widget.ScrollView" } ?: return null
        val content = f.descendants(body.index)
        if (body.scrollable || content.filter { it.editable }.size != 1 || content.any { it.editable && it.enteredText }) return null
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
