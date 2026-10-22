package app.octopus.local.domain

/** Browsing a new restaurant must never silently launch automation for a previous draft. */
object ComparisonRequestSelection {
    fun matches(selectedRestaurant: String?, cart: CanonicalCart?): Boolean = cart != null &&
        selectedRestaurant != null && selectedRestaurant == cart.restaurant
}
