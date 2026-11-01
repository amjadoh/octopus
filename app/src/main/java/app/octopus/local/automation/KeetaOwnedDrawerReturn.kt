package app.octopus.local.automation

import app.octopus.local.domain.CanonicalCart
import app.octopus.local.domain.ProviderId
import app.octopus.local.providers.*

/** Observed 3.13.301 return: one Back from a complete owned drawer, optionally one
 * exact-query search result reopening, then the same restaurant with unchanged cart count.
 * No mutation, unknown-screen Back, loop or checkout authority exists here.
 */
class KeetaOwnedDrawerReturn private constructor(
    private val source: CatalogFrame,
    private val request: CanonicalCart,
    private val version: String,
    private val quantity: Int,
    private val at: Long,
    private val choices: Set<String> = emptySet(),
) {
    private val basket = if (choices.isEmpty()) KeetaBasketReader(KeetaMenuReader(request.restaurant, request.items.single().name, version))
        else KeetaBasketReader.configured(request, version)
    private val reader = KeetaMenuReader(request.restaurant, request.items.first().name, version, choices,
        request.items.map { it.name }.toSet() - request.items.first().name)
    private var backUsed = false
    private var reopenUsed = false
    private var ended = false

    private fun context(current: CanonicalCart, observedVersion: String?, now: Long) =
        !ended && current.fingerprint == request.fingerprint && observedVersion == version &&
            now >= at && now - at <= 30_000

    fun consumeBack(frame: CatalogFrame, current: CanonicalCart, observedVersion: String?, now: Long): Boolean {
        if (backUsed || !context(current, observedVersion, now) || frame != source) {
            ended = true
            return false
        }
        backUsed = true
        return true
    }

    fun searchTarget(frame: CatalogFrame, current: CanonicalCart, observedVersion: String?, now: Long): CatalogNode? =
        if (!backUsed || reopenUsed || !context(current, observedVersion, now)) null
        else reader.searchCandidate(frame)

    fun consumeReopen(frame: CatalogFrame, target: CatalogNode, current: CanonicalCart,
        observedVersion: String?, now: Long): Boolean {
        if (searchTarget(frame, current, observedVersion, now) != target) {
            ended = true
            return false
        }
        reopenUsed = true
        return true
    }

    fun menuMatches(frame: CatalogFrame, current: CanonicalCart, observedVersion: String?, now: Long): Boolean =
        backUsed && context(current, observedVersion, now) && reader.menu(frame) && basket.menuQuantity(frame) == quantity

    fun finish(frame: CatalogFrame, current: CanonicalCart, observedVersion: String?, now: Long): Boolean {
        val accepted = menuMatches(frame, current, observedVersion, now)
        ended = true
        return accepted
    }

    companion object {
        /** Read-only return for a matching configured drawer. This grants no Add or review authority.
         * [meal] may be the meals added so far; [request] is the full order the return stays bound to. */
        fun createConfigured(frame: CatalogFrame, evidence: BasketEvidence, meal: SingleChoiceMeal,
            version: String?, now: Long, request: CanonicalCart = meal.cart): KeetaOwnedDrawerReturn? {
            if (request.restaurant != meal.cart.restaurant || request.items.take(meal.cart.items.size) != meal.cart.items) return null
            if (version != "3.13.301" || now < 0 || !frame.complete || evidence.provider != ProviderId.KEETA) return null
            val resolved = meal.resolve(evidence, now) ?: return null
            if (!BasketItemMatcher.compare(meal.cart, resolved).matches) return null
            if (KeetaBasketReader.configured(meal.cart, version).evidence(frame) != evidence) return null
            return KeetaOwnedDrawerReturn(frame, request, version, meal.cart.items.size, now,
                request.items.flatMap { item -> item.options.map { it.value } }.toSet())
        }

        fun create(frame: CatalogFrame, evidence: BasketEvidence, request: CanonicalCart,
            version: String?, now: Long): KeetaOwnedDrawerReturn? {
            val profile = KeetaVersionProfile.resolve(version) ?: return null
            if (!profile.allowsOwnedDrawerReturn || !PreviewEvidence.supports(request) || now < 0 ||
                !frame.complete || !evidence.complete || evidence.provider != ProviderId.KEETA ||
                evidence.restaurant != request.restaurant || evidence.lines.size != 1) return null
            val line = evidence.lines.single()
            if (line.name != request.items.single().name || line.choices.isNotEmpty() || line.quantity !in 1..99 ||
                line.lineAmount == null || line.lineAmount < 0 || evidence.subtotal != line.lineAmount) return null
            val reader = KeetaMenuReader(request.restaurant, request.items.single().name, profile.version)
            if (KeetaBasketReader(reader).evidence(frame) != evidence) return null
            return KeetaOwnedDrawerReturn(frame, request, profile.version, line.quantity!!, now)
        }
    }
}
