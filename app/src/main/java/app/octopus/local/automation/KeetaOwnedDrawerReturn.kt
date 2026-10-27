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
) {
    private val reader = KeetaMenuReader(request.restaurant, request.items.single().name, version)
    private val basket = KeetaBasketReader(reader)
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
