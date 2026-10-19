package app.octopus.local.matching

import app.octopus.local.domain.*

data class ObservedCart(
    val restaurant: String?,
    val branch: String?,
    val addressReference: String?,
    val items: List<CanonicalItem>,
    val complete: Boolean,
) {
    val fingerprint: String get() = CartIdentity.contentsFingerprint(restaurant.orEmpty(), branch, items)
}

enum class MatchFailure { INCOMPLETE_CART, RESTAURANT, BRANCH_UNKNOWN, BRANCH, ADDRESS_UNKNOWN, ADDRESS, ITEMS }
data class CartMatch(val failures: Set<MatchFailure>) { val exact: Boolean get() = failures.isEmpty() }

class ItemMatcher {
    fun compare(desired: CanonicalCart, actual: ObservedCart): CartMatch {
        val failures = mutableSetOf<MatchFailure>()
        if (!actual.complete) failures += MatchFailure.INCOMPLETE_CART
        if (actual.restaurant == null || IdentityText.clean(desired.restaurant) != IdentityText.clean(actual.restaurant)) failures += MatchFailure.RESTAURANT
        if (desired.branch == null || actual.branch == null) failures += MatchFailure.BRANCH_UNKNOWN
        else if (IdentityText.clean(desired.branch) != IdentityText.clean(actual.branch)) failures += MatchFailure.BRANCH
        if (desired.addressReference == null || actual.addressReference == null) failures += MatchFailure.ADDRESS_UNKNOWN
        else if (desired.addressReference != actual.addressReference) failures += MatchFailure.ADDRESS
        if (CartIdentity.quantities(desired.items) != CartIdentity.quantities(actual.items)) failures += MatchFailure.ITEMS
        return CartMatch(failures)
    }
}
