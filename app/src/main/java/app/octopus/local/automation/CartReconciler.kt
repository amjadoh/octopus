package app.octopus.local.automation

import app.octopus.local.domain.*
import app.octopus.local.matching.*

/** Approval is for exactly the observed basket and desired order, never a blanket consent. */
data class CartReplacementApproval(val observedFingerprint: String, val desiredFingerprint: String)
enum class ReconcileBlock { INCOMPLETE_CART, WRONG_RESTAURANT, BRANCH_UNKNOWN, WRONG_BRANCH, WRONG_ADDRESS }
sealed interface ReconcileDecision {
    data object Matched : ReconcileDecision
    data class Blocked(val reason: ReconcileBlock) : ReconcileDecision
    data class NeedsApproval(val observedFingerprint: String, val desiredFingerprint: String) : ReconcileDecision
    data class SetQuantity(val item: CanonicalItem, val targetQuantity: Int, val expectedFingerprint: String) : ReconcileDecision
}

/** Produces ONE absolute edit. Call again only after observing the resulting complete basket. */
class CartReconciler {
    fun next(desired: CanonicalCart, actual: ObservedCart, approval: CartReplacementApproval? = null): ReconcileDecision {
        if (!actual.complete) return ReconcileDecision.Blocked(ReconcileBlock.INCOMPLETE_CART)
        if (desired.addressReference == null || desired.addressReference != actual.addressReference)
            return ReconcileDecision.Blocked(ReconcileBlock.WRONG_ADDRESS)
        val occupied = actual.items.isNotEmpty()
        val approved = approval?.observedFingerprint == actual.fingerprint && approval.desiredFingerprint == desired.fingerprint
        if (occupied && ItemMatcher().compare(desired, actual).exact) return ReconcileDecision.Matched
        // Any differing occupied basket needs a fresh, explicit approval bound to this snapshot.
        if (occupied && !approved) return ReconcileDecision.NeedsApproval(actual.fingerprint, desired.fingerprint)
        if (actual.restaurant != null && IdentityText.clean(actual.restaurant) != IdentityText.clean(desired.restaurant))
            return ReconcileDecision.Blocked(ReconcileBlock.WRONG_RESTAURANT)
        if (desired.branch == null || actual.branch == null) return ReconcileDecision.Blocked(ReconcileBlock.BRANCH_UNKNOWN)
        if (IdentityText.clean(desired.branch) != IdentityText.clean(actual.branch)) return ReconcileDecision.Blocked(ReconcileBlock.WRONG_BRANCH)
        val expected = CartIdentity.quantities(desired.items)
        val observed = CartIdentity.quantities(actual.items)
        // Do not silently leave unrelated products or variants behind.
        actual.items.firstOrNull { CartIdentity.itemKey(it) !in expected }?.let {
            return ReconcileDecision.SetQuantity(it, 0, actual.fingerprint)
        }
        desired.items.distinctBy(CartIdentity::itemKey).forEach { item ->
            val key = CartIdentity.itemKey(item)
            if (observed[key] != expected[key]) return ReconcileDecision.SetQuantity(item, expected.getValue(key), actual.fingerprint)
        }
        return ReconcileDecision.Matched
    }
}
