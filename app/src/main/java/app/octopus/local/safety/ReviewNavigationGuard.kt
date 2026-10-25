package app.octopus.local.safety

import app.octopus.local.domain.*
import app.octopus.local.providers.*

/** One transition from a verified cart/menu. Never permits an action on the resulting review. */
object ReviewNavigationGuard {
    fun target(provider: ProviderId, frame: CatalogFrame, cart: CanonicalCart, version: String): CatalogNode? {
        if (!frame.complete || !PreviewEvidence.supports(cart)) return null
        if (provider == ProviderId.HUNGERSTATION) {
            if (version != HungerStationCatalog.TESTED_VERSION || HungerStationCatalog.screen(frame) != CatalogScreen.CART) return null
            val basket = BasketEvidenceReader.hungerStation(frame, false)
            if (basket.restaurant != cart.restaurant || basket.lines.size != 1 || basket.lines.single().let {
                it.name != cart.items.single().name || it.quantity != cart.items.single().quantity || it.choices.isNotEmpty()
            }) return null
            return frame.nodes.singleOrNull { it.id == "FlutterCart_footer_main_action_button" &&
                it.label == "اذهب للدفع" && it.enabled && it.clickable && !it.editable }
        }
        if (version !in setOf("3.12.501", "3.13.203")) return null
        val menu = KeetaMenuReader(cart.restaurant, cart.items.single().name, version)
        if (!menu.menu(frame) || KeetaBasketReader(menu).menuQuantity(frame) != cart.items.single().quantity) return null
        val label = frame.nodes.singleOrNull { it.label == "إتمام الدفع" && it.enabled } ?: return null
        var current = label
        repeat(4) {
            if (current.clickable && !current.editable && current.enabled) {
                if (version=="3.13.203") {
                    // Current React Native footer: the labelled wrapper is clickable but its
                    // direct empty action child owns the press handler. Never guess by position
                    // unless the complete observed two-child label/action structure matches.
                    val children=frame.nodes.filter {it.parent==current.index}
                    if(children.size!=2 || children[0].className!="android.view.ViewGroup" ||
                        !children[0].clickable || !children[0].enabled || children[0].editable ||
                        children[0].label.isNotEmpty() || children[0].omittedText || children[0].id.isNotEmpty() ||
                        children[1].clickable || frame.descendants(children[1].index).count {it==label}!=1)
                        return null
                    return children[0]
                }
                return current
            }
            current = frame.nodes.singleOrNull { it.index == current.parent } ?: return null
        }
        return null
    }
}
