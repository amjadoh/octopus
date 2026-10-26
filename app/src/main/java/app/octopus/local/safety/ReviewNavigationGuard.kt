package app.octopus.local.safety

import app.octopus.local.domain.*
import app.octopus.local.providers.*

/** Verified review navigation and the observed review-header back arrow only. */
object ReviewNavigationGuard {
    /** Keeta 3.13.203 review header: back image wrapper beside the exact title. */
    fun keetaReviewBackTarget(frame: CatalogFrame, version: String): CatalogNode? {
        if (version != "3.13.203" || !frame.complete) return null
        val title = frame.nodes.singleOrNull { it.label == "إتمام الدفع" &&
            it.className == "android.widget.TextView" } ?: return null
        fun children(node: CatalogNode) = frame.nodes.filter { it.parent == node.index }
        fun plain(node: CatalogNode) = node.enabled && !node.editable && !node.omittedText &&
            node.id.isEmpty() && !node.scrollable
        if (!plain(title) || title.clickable) return null
        val titleBox = frame.nodes.singleOrNull { it.index == title.parent } ?: return null
        if (!plain(titleBox) || titleBox.className != "android.view.ViewGroup" ||
            titleBox.clickable || titleBox.label.isNotEmpty() || children(titleBox) != listOf(title)) return null
        val bar = frame.nodes.singleOrNull { it.index == titleBox.parent } ?: return null
        if (!plain(bar) || bar.className != "android.view.ViewGroup" || bar.clickable ||
            bar.label.isNotEmpty() || children(bar).size != 2) return null
        val back = children(bar).singleOrNull { it.index != titleBox.index } ?: return null
        if (!plain(back) || back.className != "android.view.ViewGroup" || !back.clickable ||
            back.label.isNotEmpty()) return null
        val icon = children(back).singleOrNull() ?: return null
        if (!plain(icon) || icon.className != "android.widget.ImageView" || icon.clickable ||
            icon.label.isNotEmpty() || children(icon).isNotEmpty()) return null
        return back
    }
    fun hungerReviewHeading(value: String?, restaurant: String?): Boolean {
        if (restaurant.isNullOrBlank() || value == null) return false
        val lines = value.trim().lines().map(String::trim)
        return lines == listOf("مراجعة الطلب", restaurant.trim())
    }
    /** Observed optional recommendations between the cart and review; never add a recommendation. */
    fun hungerRecommendationsTarget(frame: CatalogFrame, version: String): CatalogNode? {
        if (version != HungerStationCatalog.TESTED_VERSION || !frame.complete || frame.nodes.any { it.editable }) return null
        if (frame.nodes.count { it.label == "لا تفوت الفرصة!\nعادةً ما يضيف الأشخاص هذه العناصر" &&
                it.className == "android.widget.ImageView" } != 1 ||
            frame.nodes.none { it.id == "menuItemActionSingleButtonAdd" } ||
            frame.nodes.any { it.id.startsWith("FlutterCheckout") || it.label in setOf("تأكيد الطلب", "ادفع الآن", "تأكيد الدفع") }) return null
        return frame.nodes.singleOrNull { it.label == "اذهب للدفع" && it.className == "android.widget.Button" &&
            it.enabled && it.clickable && !it.editable && !it.omittedText }
    }
    fun target(provider: ProviderId, frame: CatalogFrame, cart: CanonicalCart, version: String,
        admission:PreparedReviewAdmission?=null,now:Long=System.currentTimeMillis()): CatalogNode? {
        val multi=cart.items.size>1
        if (!frame.complete || (if(multi) admission?.validFor(provider,cart,version,now)!=true else !PreviewEvidence.supports(cart))) return null
        if (provider == ProviderId.HUNGERSTATION) {
            if (version != HungerStationCatalog.TESTED_VERSION || HungerStationCatalog.screen(frame) != CatalogScreen.CART) return null
            val basket = BasketEvidenceReader.hungerStation(frame, false)
            if(multi) {
                if(frame!=admission!!.sourceFrame || BasketEvidenceReader.hungerStation(frame,true)!=admission.record.evidence)return null
            } else if (basket.restaurant != cart.restaurant || basket.lines.size != 1 || basket.lines.single().let {
                it.name != cart.items.single().name || it.quantity != cart.items.single().quantity || it.choices.isNotEmpty()
            }) return null
            val footer = frame.nodes.singleOrNull { it.id == "FlutterCart_footer_main_action_button" }
                ?: return null
            if (!footer.enabled || footer.editable) return null
            if (footer.label == "اذهب للدفع" && footer.clickable) return footer
            // Actual HS 8.0.303 cart on Honor, 2026-10-25: enabled semantics
            // wrapper with one directly labelled button. No review-screen action allowed.
            if (footer.label != "enabled" || footer.clickable || footer.className != "android.view.View") return null
            return frame.nodes.filter { it.parent == footer.index }.singleOrNull()?.takeIf {
                it.id.isEmpty() && it.label == "اذهب للدفع" && it.className == "android.widget.Button" &&
                    it.enabled && it.clickable && !it.editable && !it.omittedText
            }
        }
        if (version !in setOf("3.12.501", "3.13.203")) return null
        val menu = KeetaMenuReader(cart.restaurant, cart.items.first().name, version)
        val expectedQuantity=if(multi)cart.items.sumOf {it.quantity} else cart.items.single().quantity
        if (!menu.menu(frame) || KeetaBasketReader(menu).menuQuantity(frame) != expectedQuantity) return null
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
