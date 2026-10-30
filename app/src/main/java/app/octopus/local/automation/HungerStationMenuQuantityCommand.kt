package app.octopus.local.automation

import app.octopus.local.domain.IdentityText
import app.octopus.local.pricing.MoneyParser
import app.octopus.local.providers.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One quantity change on the selected menu row before opening the basket.
 * The caller must have verified the selected product's omitted options and either created this
 * basket from empty in the same session, previously verified its single plain basket row, or obtained explicit request-bound permission to adjust the matching row quantity.
 * A menu price/count alone cannot establish those facts for a pre-existing basket.
 *
 * The saved Honor menu frame establishes row -> quantity -> Button -> clickable ImageView.
 * Add/Subtract IDs are present in the Oct 25 Honor MENU capture for the selected feta row.
 * Recognition is fixture-tested; successful runtime quantity modification remains a separate check;
 * missing IDs never fall back to child order or positional clicks.
 */
class HungerStationMenuQuantityCommand private constructor(
    val before: Int,
    val requestedQuantity: Int,
    val target: CatalogNode,
    private val restaurant: String,
    private val title: String,
    private val unitPriceHalala: Long,
    private val issuedAt: Long,
) {
    val expectedAfter: Int = before + if (requestedQuantity > before) 1 else -1
    private val consumed = AtomicBoolean(false)

    fun consume(frame: CatalogFrame, node: CatalogNode, version: String?, now: Long): Boolean {
        if (version != HungerStationCatalog.TESTED_VERSION || now < issuedAt || now - issuedAt > 15_000 ||
            node != target || observedQuantity(frame, restaurant, title, unitPriceHalala) != before ||
            changeTarget(frame, title, requestedQuantity > before) != target) return false
        return consumed.compareAndSet(false, true)
    }

    companion object {
        /** Null means either already at the requested quantity or the current frame is not proven. */
        fun next(frame: CatalogFrame, restaurant: String, title: String, unitPriceHalala: Long,
                 requestedQuantity: Int, version: String?, matchingQuantityAuthorized: Boolean,
                 now: Long): HungerStationMenuQuantityCommand? {
            if (!matchingQuantityAuthorized || version != HungerStationCatalog.TESTED_VERSION ||
                requestedQuantity !in 1..99 || now < 0) return null
            val before = observedQuantity(frame, restaurant, title, unitPriceHalala) ?: return null
            if (before == requestedQuantity) return null
            val target = changeTarget(frame, title, requestedQuantity > before) ?: return null
            return HungerStationMenuQuantityCommand(before, requestedQuantity, target, restaurant,
                title, unitPriceHalala, now)
        }

        /** Cross-check the selected row against the complete basket footer, including its amount. */
        fun observedQuantity(frame: CatalogFrame, restaurant: String, title: String, unitPriceHalala: Long): Int? {
            if (!frame.complete || HungerStationCatalog.screen(frame) != CatalogScreen.MENU ||
                restaurant.isBlank() || title.isBlank() || unitPriceHalala <= 0 ||
                unitPriceHalala > Long.MAX_VALUE / 99 || frame.nodes.any { it.editable } ||
                frame.nodes.map { it.index }.distinct().size != frame.nodes.size ||
                frame.nodes.none { !it.clickable && !it.editable && it.label.lineSequence().any { line ->
                    IdentityText.clean(line) == IdentityText.clean(restaurant) } }) return null
            val row = selectedRow(frame, title) ?: return null
            if (HungerStationCatalog.menuItem(row)?.listedPrice != unitPriceHalala) return null
            // There must be only one selected row, and no second selected row hidden behind a
            // different quantity control in this frame. The footer rejects off-screen extras.
            val quantityNode = frame.nodes.singleOrNull { it.id == "menuItemActionDualButtonValue" } ?: return null
            if (quantityNode.parent != row.index || quantityNode.className != "android.view.View" ||
                quantityNode.clickable || !quantityNode.enabled || quantityNode.editable) return null
            val count = quantity(quantityNode.label) ?: return null
            val anchor = frame.nodes.singleOrNull { it.id == "FlutterMenu_goToCartButton" } ?: return null
            if (anchor.className != "android.view.View" || anchor.clickable || anchor.editable) return null
            // This button may be disabled below the minimum order. We only read it here.
            val footer = frame.nodes.singleOrNull { it.parent == anchor.index &&
                it.id.isEmpty() && it.className == "android.widget.Button" } ?: return null
            if (footer.editable) return null
            val footerCounter = frame.nodes.singleOrNull { it.id == "FlutterMenu_goToCartQuantity" } ?: return null
            if (footerCounter.parent != footer.index || footerCounter.className != "android.view.View" ||
                footerCounter.clickable || footerCounter.editable || quantity(footerCounter.label) != count) return null
            val lines = footer.label.replace(Regex("[\\u200e\\u200f\\u061c\\u202a-\\u202e\\u2066-\\u2069]"), "")
                .lineSequence().map(String::trim).toList()
            if (lines.size != 3 || lines[0] != "عرض السلة" || lines[2] != "§" ||
                MoneyParser.parse(lines[1]) != unitPriceHalala * count) return null
            return count
        }

        private fun selectedRow(frame: CatalogFrame, title: String): CatalogNode? = frame.nodes.singleOrNull {
            !it.editable && it.enabled && HungerStationCatalog.menuItem(it)?.name == title
        }

        private fun changeTarget(frame: CatalogFrame, title: String, increase: Boolean): CatalogNode? {
            val row = selectedRow(frame, title) ?: return null
            val count = frame.nodes.singleOrNull { it.parent == row.index && it.id == "menuItemActionDualButtonValue" }
                ?: return null
            val id = if (increase) "menuItemActionDualButtonAdd" else "menuItemActionDualButtonSubtract"
            val control = frame.nodes.singleOrNull { it.parent == count.index && it.id == id } ?: return null
            if (control.className != "android.widget.Button" || control.clickable || control.editable ||
                !control.enabled || control.label.isNotEmpty()) return null
            return frame.nodes.singleOrNull { it.parent == control.index && it.id.isEmpty() &&
                it.className == "android.widget.ImageView" && it.clickable && it.enabled && !it.editable &&
                it.label.isEmpty() }
        }

        private fun quantity(label: String): Int? {
            val value = label.trim().map { c -> if (c in '٠'..'٩') ('0'.code + c.code - '٠'.code).toChar() else c }.joinToString("")
            if (!value.matches(Regex("[0-9]{1,2}"))) return null
            return value.toIntOrNull()?.takeIf { it in 1..99 }
        }
    }
}
