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
            if (version.isBlank() || at < 0 || evidence.restaurant != cart.restaurant) return null
            // This ledger retains observed rows; it does not enable provider actions
            // or infer address/branch identity.
            if (cart.items.any { it.size != null || it.options.isNotEmpty() || !it.note.isNullOrBlank() }) return null
            val wanted = cart.items.groupBy { it.name }.mapValues { (_, lines) -> lines.sumOf { it.quantity } }
            if (wanted.values.any { it !in 1..99 } || evidence.lines.size != wanted.size ||
                evidence.lines.map { it.name }.distinct().size != evidence.lines.size) return null
            // Existing single-row records preserve their visible-only completeness flag.
            // More than one row requires an explicit full-cart observation from its reader.
            if (wanted.size > 1 && !evidence.complete) return null
            if (evidence.lines.any { it.name !in wanted || it.quantity == null || it.quantity != wanted[it.name] || it.choices.isNotEmpty() ||
                    it.lineAmount?.let { amount -> amount < 0 } == true } ||
                evidence.subtotal?.let { it < 0 } == true) return null
            // Complete contents need complete money evidence as well. Never accept an
            // inconsistent subtotal or let integer overflow turn it into a matching record.
            if (evidence.complete && (evidence.subtotal == null || evidence.lines.any { it.lineAmount == null })) return null
            if (evidence.subtotal != null && evidence.lines.all { it.lineAmount != null }) {
                val sum = runCatching { evidence.lines.fold(0L) { total, row ->
                    Math.addExact(total, row.lineAmount!!)
                } }.getOrNull() ?: return null
                if (sum != evidence.subtotal) return null
            }
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
