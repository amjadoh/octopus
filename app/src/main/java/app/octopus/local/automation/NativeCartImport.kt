package app.octopus.local.automation

import app.octopus.local.domain.*
import app.octopus.local.providers.*

/** Passive MENU -> CART continuity. Footer units and money must account for every row.
 * This proves item completeness only, never an address, branch or final order total.
 */
class NativeCartImport {
    private var menu: CatalogFrame? = null
    private var menuAt = 0L
    fun reset() { menu = null; menuAt = 0 }
    fun observe(frame: CatalogFrame, now: Long) {
        when (HungerStationCatalog.screen(frame)) {
            CatalogScreen.MENU -> { menu = frame; menuAt = now }
            CatalogScreen.CART -> Unit
            else -> reset()
        }
    }
    fun read(frame: CatalogFrame, version: String?, now: Long): Pair<CanonicalCart, BasketEvidence>? {
        val evidence = evidence(frame, version, now) ?: return null
        if (evidence.lines.any { it.choices.isNotEmpty() }) return null
        val cart = runCatching { CanonicalCart(evidence.restaurant ?: return null, null,
            evidence.lines.map { CanonicalItem(it.name, null, emptyList(), it.quantity ?: return null) }) }.getOrNull() ?: return null
        if (!BasketItemMatcher.compare(cart, evidence).matches) return null
        return cart to evidence
    }

    /** Complete source contents, before a configured-product schema resolves option counts. */
    fun evidence(frame: CatalogFrame, version: String?, now: Long): BasketEvidence? {
        if (failure(frame, version, now) != null) return null
        return BasketEvidenceReader.hungerStation(frame, true)
    }

    fun failure(frame: CatalogFrame, version: String?, now: Long): String? {
        if (version != HungerStationCatalog.TESTED_VERSION) return "إصدار هنقرستيشن الحالي لم يُتحقق منه؛ لم يبدأ نقل السلة."
        if (HungerStationCatalog.screen(frame) != CatalogScreen.CART)
            return "افتح سلة هنقرستيشن ثم اضغط «قارن»؛ الشاشة الحالية ليست سلة معروفة."
        if (!frame.complete || frame.nodes.any { it.enteredText || it.omittedText })
            return "قراءة سلة هنقرستيشن غير مكتملة؛ لم يبدأ نقلها."
        if (menu == null || now < menuAt || now - menuAt > 30_000)
            return "لم تثبت قراءة حديثة لعداد السلة من قائمة المطعم. ارجع لقائمة المطعم ثم افتح السلة واضغط «قارن» خلال ٣٠ ثانية."
        if (!HungerStationCompleteCartProof.matchesMenu(menu!!, BasketEvidenceReader.hungerStation(frame, true)))
            return "لم تتطابق أصناف السلة وكمياتها ومبالغها مع عداد قائمة المطعم؛ لم يبدأ نقل سلة ناقصة."
        return null
    }
}
