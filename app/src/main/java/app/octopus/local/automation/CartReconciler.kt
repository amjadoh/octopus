package app.octopus.local.automation

import app.octopus.local.domain.*
import app.octopus.local.matching.*

/** Approval is for exactly the observed basket and desired order, never a blanket consent. */
data class CartReplacementApproval(val observedFingerprint: String, val desiredFingerprint: String)
enum class ReconcileBlock { INCOMPLETE_CART, RESTAURANT_UNKNOWN, WRONG_RESTAURANT, BRANCH_UNKNOWN, WRONG_BRANCH, WRONG_ADDRESS }
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
        // A contents approval cannot supply missing context or change restaurants/branches.
        // Such transitions need an independently evidenced replacement adapter, not row edits.
        val restaurant = actual.restaurant?.takeIf { IdentityText.clean(it).isNotBlank() }
            ?: return ReconcileDecision.Blocked(ReconcileBlock.RESTAURANT_UNKNOWN)
        if (IdentityText.clean(restaurant) != IdentityText.clean(desired.restaurant))
            return ReconcileDecision.Blocked(ReconcileBlock.WRONG_RESTAURANT)
        val branch = actual.branch?.takeIf { IdentityText.clean(it).isNotBlank() }
        if (desired.branch == null || branch == null) return ReconcileDecision.Blocked(ReconcileBlock.BRANCH_UNKNOWN)
        if (IdentityText.clean(desired.branch) != IdentityText.clean(branch)) return ReconcileDecision.Blocked(ReconcileBlock.WRONG_BRANCH)
        val occupied = actual.items.isNotEmpty()
        val approved = approval?.observedFingerprint == actual.fingerprint && approval.desiredFingerprint == desired.fingerprint
        if (occupied && ItemMatcher().compare(desired, actual).exact) return ReconcileDecision.Matched
        // Only a differing occupied basket in a supported context may request contents approval.
        if (occupied && !approved) return ReconcileDecision.NeedsApproval(actual.fingerprint, desired.fingerprint)
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
