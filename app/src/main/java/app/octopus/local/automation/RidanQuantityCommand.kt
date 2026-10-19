package app.octopus.local.automation

import app.octopus.local.providers.*

/** Single dispatch against the exact one-row cart; never removes the last meal. */
class RidanQuantityCommand(val before: Int, val increase: Boolean, private val issuedAt: Long) {
    private var consumed = false
    fun consume(frame: CatalogFrame, node: CatalogNode, now: Long): Boolean {
        if (consumed || now < issuedAt || now - issuedAt > 15_000 || target(frame, before, increase) != node) return false
        consumed = true
        return true
    }
    companion object {
        fun target(frame: CatalogFrame, before: Int, increase: Boolean): CatalogNode? {
            if (!frame.complete || HungerStationCatalog.screen(frame) != CatalogScreen.CART || before !in 1..99 ||
                (increase && before == 99) || (!increase && before == 1)) return null
            val evidence = BasketEvidenceReader.hungerStation(frame, false)
            if (evidence.restaurant != RidanCartProgram.RESTAURANT || evidence.lines.size != 1 ||
                evidence.lines.single().let { it.name != RidanCartProgram.TITLE || it.choices.isNotEmpty() || it.quantity != before }) return null
            val row = frame.nodes.singleOrNull { it.id == "FlutterCart_cartItem" } ?: return null
            val quantity = frame.descendants(row.index).singleOrNull { it.id == "menuItemActionDualButtonValue" } ?: return null
            val control = frame.nodes.singleOrNull { it.parent == quantity.index &&
                it.id == if (increase) "menuItemActionDualButtonAdd" else "menuItemActionDualButtonSubtract" } ?: return null
            if (control.className != "android.widget.Button" || !control.enabled) return null
            return frame.nodes.singleOrNull { it.parent == control.index && it.className == "android.widget.ImageView" &&
                it.clickable && it.enabled && it.label.isEmpty() }
        }
    }
}
