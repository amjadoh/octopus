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
        if (version != HungerStationCatalog.TESTED_VERSION || now < menuAt || now - menuAt > 30_000 ||
            HungerStationCatalog.screen(frame) != CatalogScreen.CART ||
            frame.nodes.any { it.enteredText || it.omittedText }) return null
        val evidence = BasketEvidenceReader.hungerStation(frame, true)
        if (!HungerStationCompleteCartProof.matchesMenu(menu ?: return null, evidence) ||
            evidence.lines.any { it.choices.isNotEmpty() }) return null
        val cart = runCatching { CanonicalCart(evidence.restaurant ?: return null, null,
            evidence.lines.map { CanonicalItem(it.name, null, emptyList(), it.quantity ?: return null) }) }.getOrNull() ?: return null
        if (!BasketItemMatcher.compare(cart, evidence).matches) return null
        return cart to evidence
    }
}
