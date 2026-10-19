package app.octopus.local.pricing

import app.octopus.local.domain.*
import kotlin.math.abs

class QuoteExtractor {
    fun extract(snapshot: SafeSnapshot): QuoteCandidate {
        if (snapshot.sensitiveScreen || snapshot.truncated) return QuoteCandidate(emptyMap(), true)
        val found = mutableMapOf<PriceField, MutableSet<Long>>()
        var ambiguous = false
        snapshot.tokens.filter { it.label != null }.forEach { label ->
            val amounts = if (label.amount != null) setOf(label.amount) else {
                snapshot.tokens.filter { token ->
                    token.amount != null && token.label == null &&
                        abs((token.top + token.bottom) - (label.top + label.bottom)) <=
                        maxOf(12, minOf(token.bottom - token.top, label.bottom - label.top)) &&
                        (token.right <= label.left || token.left >= label.right) &&
                        (token.parent == label.parent || abs(token.top - label.top) <= 8)
                }.mapNotNull { it.amount }.toSet()
            }
            if (amounts.size == 1) found.getOrPut(label.label!!) { mutableSetOf() }.add(amounts.first())
            if (amounts.size > 1) ambiguous = true
        }
        if (found.any { it.value.size > 1 }) ambiguous = true
        val fields = found.filterValues { it.size == 1 }.mapValues { it.value.single() }
        return QuoteCandidate(fields, ambiguous)
    }
}

class PriceComparisonEngine(private val maxAgeMillis: Long = 120_000) {
    init { require(maxAgeMillis >= 0) }
    fun rank(quotes: List<VerifiedQuote>, now: Long): List<VerifiedQuote> {
        val valid = quotes.filter {
            it.orderCost != null && it.orderCost >= 0 && it.cartVerified && it.addressVerified &&
                it.branchVerified && it.allMandatoryFeesIncluded &&
                it.cartFingerprint.isNotBlank() && it.addressReference.isNotBlank() &&
                it.capturedAtMillis >= 0 && it.capturedAtMillis <= now && now - it.capturedAtMillis <= maxAgeMillis
        }
        if (valid.map { it.cartFingerprint to it.addressReference }.distinct().size > 1) return emptyList()
        // One result per provider; never silently choose between conflicting simultaneous observations.
        return valid.groupBy { it.provider }.values.mapNotNull { readings ->
            val newest = readings.maxOf { it.capturedAtMillis }
            readings.filter { it.capturedAtMillis == newest }.distinct().singleOrNull()
        }.sortedWith(compareBy<VerifiedQuote> { it.orderCost }.thenBy { it.provider.ordinal })
    }

    fun rankFor(cart: CanonicalCart, quotes: List<VerifiedQuote>, now: Long): List<VerifiedQuote> =
        rank(quotes.filter { it.cartFingerprint == cart.fingerprint && it.addressReference == cart.addressReference }, now)
}
