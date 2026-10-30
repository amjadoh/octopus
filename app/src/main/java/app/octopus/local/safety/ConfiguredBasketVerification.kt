package app.octopus.local.safety

import app.octopus.local.automation.SingleChoiceMeal
import app.octopus.local.domain.*
import app.octopus.local.providers.*

/** Short-lived proof retains both raw contents and schema-resolved counts. */
class ConfiguredBasketVerification private constructor(
    val raw: BasketEvidence,
    val resolved: BasketEvidence,
    val frame: CatalogFrame,
    private val fingerprint: String,
    private val version: String,
    private val at: Long,
) {
    fun matches(cart: CanonicalCart, current: CatalogFrame, observedVersion: String, now: Long): Boolean =
        current == frame && cart.fingerprint == fingerprint && observedVersion == version && now >= at && now - at <= 15_000

    companion object {
        fun source(meal: SingleChoiceMeal, frame: CatalogFrame, raw: BasketEvidence, now: Long): ConfiguredBasketVerification? {
            if (raw.provider != ProviderId.HUNGERSTATION || raw != meal.source ||
                BasketEvidenceReader.hungerStation(frame, true) != raw) return null
            return create(meal, frame, raw, HungerStationCatalog.TESTED_VERSION, now)
        }

        fun target(meal: SingleChoiceMeal, selection: KeetaMealChoices, menu: KeetaMenuReader,
            frame: CatalogFrame, now: Long): ConfiguredBasketVerification? {
            if (!selection.ready || selection.cart != meal.cart || menu.VERSION != "3.13.301" ||
                menu.requestedChoices != meal.values) return null
            val raw = KeetaBasketReader(menu).evidence(frame) ?: return null
            return create(meal, frame, raw, menu.VERSION, now)
        }

        private fun create(meal: SingleChoiceMeal, frame: CatalogFrame, raw: BasketEvidence, version: String,
            now: Long): ConfiguredBasketVerification? {
            if (!frame.complete || frame.nodes.any { it.enteredText }) return null
            val resolved = meal.resolve(raw, now) ?: return null
            if (!BasketItemMatcher.compare(meal.cart, resolved).matches) return null
            return ConfiguredBasketVerification(raw, resolved, frame, meal.cart.fingerprint, version, now)
        }
    }
}
