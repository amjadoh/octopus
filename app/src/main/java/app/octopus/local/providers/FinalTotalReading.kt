package app.octopus.local.providers

import app.octopus.local.domain.*

/** A visible review total; context equivalence is separate from successful price extraction. */
data class FinalTotalReading(val provider: ProviderId, val version: String, val cartFingerprint: String,
    val fields: Map<PriceField, Long>, val capturedAt: Long) {
    init { require(version.isNotBlank() && cartFingerprint.isNotBlank() && capturedAt >= 0 &&
        fields[PriceField.ORDER_TOTAL]?.let { it >= 0 } == true) }
    val total: Long get() = requireNotNull(fields[PriceField.ORDER_TOTAL])
    fun fresh(cart: CanonicalCart, now: Long) = cart.fingerprint == cartFingerprint &&
        capturedAt <= now && now - capturedAt in 0..120_000
}
