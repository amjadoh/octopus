package app.octopus.local.pricing

import app.octopus.local.domain.*
import app.octopus.local.matching.*

enum class TaxTreatment { INCLUDED, ADDITIONAL, UNKNOWN }
data class PriceBreakdown(
    val subtotal: Long?,
    val mandatoryFees: List<Long>?,
    val tax: Long?,
    val taxTreatment: TaxTreatment,
    val appliedDiscount: Long?,
)
data class QuoteObservation(
    val provider: ProviderId,
    val providerVersion: String,
    val accountContext: String,
    val observedCart: ObservedCart,
    val candidate: QuoteCandidate,
    val capturedAtMillis: Long,
    val recognizedOrderReview: Boolean,
    val mandatoryFeesIncluded: Boolean,
    val breakdown: PriceBreakdown? = null,
)
enum class QuoteRejection { UNKNOWN_CONTEXT, NOT_ORDER_REVIEW, CART_MISMATCH, FEES_UNKNOWN, TOTAL_UNKNOWN, STALE, UNSTABLE, INVALID_AMOUNT, BREAKDOWN_MISMATCH, WALLET_MISMATCH }
sealed interface QuoteValidation {
    data class Accepted(val quote: VerifiedQuote, val detailsPartial: Boolean, val source: QuoteObservation) : QuoteValidation
    data class Rejected(val reason: QuoteRejection) : QuoteValidation
}

/** A raw diagnostic candidate alone can never produce an Accepted result. */
class QuoteValidator(private val minStableMillis: Long = 1000, private val maxAgeMillis: Long = 120_000) {
    init { require(minStableMillis > 0 && maxAgeMillis >= minStableMillis) }
    fun validate(desired: CanonicalCart, first: QuoteObservation, last: QuoteObservation, now: Long): QuoteValidation {
        fun reject(reason: QuoteRejection) = QuoteValidation.Rejected(reason)
        if (first.providerVersion.isBlank() || last.providerVersion.isBlank() || first.accountContext.isBlank() || last.accountContext.isBlank())
            return reject(QuoteRejection.UNKNOWN_CONTEXT)
        if (!first.recognizedOrderReview || !last.recognizedOrderReview) return reject(QuoteRejection.NOT_ORDER_REVIEW)
        if (!ItemMatcher().compare(desired, first.observedCart).exact || !ItemMatcher().compare(desired, last.observedCart).exact)
            return reject(QuoteRejection.CART_MISMATCH)
        if (!first.mandatoryFeesIncluded || !last.mandatoryFeesIncluded) return reject(QuoteRejection.FEES_UNKNOWN)
        if (first.capturedAtMillis < 0 || last.capturedAtMillis < first.capturedAtMillis || last.capturedAtMillis > now ||
            now - first.capturedAtMillis > maxAgeMillis) return reject(QuoteRejection.STALE)
        val total = last.candidate.total ?: return reject(QuoteRejection.TOTAL_UNKNOWN)
        if (first.provider != last.provider || first.providerVersion != last.providerVersion || first.accountContext != last.accountContext ||
            first.candidate != last.candidate || first.breakdown != last.breakdown || last.capturedAtMillis - first.capturedAtMillis < minStableMillis)
            return reject(QuoteRejection.UNSTABLE)
        if (total < 0 || last.candidate.fields.any { (field, amount) -> amount == Long.MIN_VALUE || (field != PriceField.DISCOUNT && amount < 0) })
            return reject(QuoteRejection.INVALID_AMOUNT)
        val wallet = last.candidate.fields[PriceField.WALLET]
        val due = last.candidate.fields[PriceField.AMOUNT_DUE]
        if ((wallet != null && wallet > total) || (due != null && due > total) ||
            (wallet != null && due != null && total - wallet != due)) return reject(QuoteRejection.WALLET_MISMATCH)
        val breakdown = last.breakdown
        val complete = breakdown != null && breakdown.subtotal != null && breakdown.mandatoryFees != null &&
            breakdown.appliedDiscount != null && breakdown.taxTreatment != TaxTreatment.UNKNOWN &&
            (breakdown.taxTreatment == TaxTreatment.INCLUDED || breakdown.tax != null)
        // Validate every supplied component even when the breakdown is partial.
        if (breakdown != null && (listOfNotNull(breakdown.subtotal, breakdown.tax).any { it < 0 } ||
                breakdown.mandatoryFees?.any { it < 0 } == true || breakdown.appliedDiscount == Long.MIN_VALUE))
            return reject(QuoteRejection.INVALID_AMOUNT)
        if (breakdown != null) {
            val fields = last.candidate.fields
            if ((breakdown.subtotal != null && fields[PriceField.SUBTOTAL]?.let { it != breakdown.subtotal } == true) ||
                (breakdown.tax != null && fields[PriceField.TAX]?.let { it != breakdown.tax } == true) ||
                (breakdown.appliedDiscount != null && fields[PriceField.DISCOUNT]?.let {
                    kotlin.math.abs(it) != kotlin.math.abs(breakdown.appliedDiscount)
                } == true)) return reject(QuoteRejection.BREAKDOWN_MISMATCH)
        }
        if (complete) {
            val expected = runCatching {
                var sum = breakdown!!.subtotal!!
                breakdown.mandatoryFees!!.forEach { sum = Math.addExact(sum, it) }
                if (breakdown.taxTreatment == TaxTreatment.ADDITIONAL) sum = Math.addExact(sum, breakdown.tax!!)
                Math.subtractExact(sum, kotlin.math.abs(breakdown.appliedDiscount!!))
            }.getOrNull() ?: return reject(QuoteRejection.INVALID_AMOUNT)
            if (expected != total) return reject(QuoteRejection.BREAKDOWN_MISMATCH)
        }
        return QuoteValidation.Accepted(VerifiedQuote(last.provider, total, wallet, due, desired.fingerprint,
            desired.addressReference!!, cartVerified = true, addressVerified = true, branchVerified = true,
            allMandatoryFeesIncluded = true, capturedAtMillis = last.capturedAtMillis), !complete, last)
    }
}
