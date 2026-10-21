package app.octopus.local.providers

import app.octopus.local.domain.CanonicalCart
import app.octopus.local.domain.ProviderId

/** Last matching visible contents. Retains completeness as observed, never upgrades it. */
data class PreparedBasketRecord private constructor(
    val evidence: BasketEvidence,
    val requestFingerprint: String,
    val providerVersion: String,
    val observedAtMillis: Long,
) {
    fun matches(cart: CanonicalCart): Boolean = requestFingerprint == cart.fingerprint

    companion object {
        fun create(cart: CanonicalCart, evidence: BasketEvidence, version: String, at: Long): PreparedBasketRecord? {
            if (version.isBlank() || at < 0 || evidence.restaurant != cart.restaurant ||
                cart.items.size != 1 || evidence.lines.size != 1) return null
            val wanted = cart.items.single()
            val actual = evidence.lines.single()
            // The current preparation adapters support only a fully read, plain single variant.
            if (wanted.size != null || wanted.options.isNotEmpty() || !wanted.note.isNullOrBlank() ||
                wanted.quantity !in 1..99 || actual.name != wanted.name || actual.quantity != wanted.quantity ||
                actual.choices.isNotEmpty()) return null
            return PreparedBasketRecord(evidence, cart.fingerprint, version, at)
        }
    }
}

/** Updating one provider must not discard the other provider's successfully prepared cart. */
data class PreparedBasketLedger(val records: Map<ProviderId, PreparedBasketRecord> = emptyMap()) {
    fun record(value: PreparedBasketRecord) = copy(records = records + (value.evidence.provider to value))
    fun invalidate(provider: ProviderId) = copy(records = records - provider)
    fun forRequest(cart: CanonicalCart, provider: ProviderId): PreparedBasketRecord? =
        records[provider]?.takeIf { it.matches(cart) }
}
