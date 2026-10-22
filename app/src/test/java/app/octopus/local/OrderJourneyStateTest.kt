package app.octopus.local

import app.octopus.local.domain.*
import app.octopus.local.providers.*
import org.junit.Assert.*
import org.junit.Test

class OrderJourneyStateTest {
    private val cart = CanonicalCart("مطعم مختار", null,
        listOf(CanonicalItem("وجبة", null, emptyList(), 1)))
    private fun previous(): LiveCatalogState {
        val basket = BasketEvidence(ProviderId.KEETA, cart.restaurant,
            listOf(BasketLineEvidence("وجبة", emptyList(), 1, 700)), 700, true)
        val record = checkNotNull(PreparedBasketRecord.create(cart, basket, "3.13.203", 1000))
        return LiveCatalogState(selectedRestaurant = cart.restaurant, preparedBasket = basket,
            preparedBaskets = PreparedBasketLedger().record(record),
            previews = listOf(MenuCostPreview(ProviderId.KEETA, cart.fingerprint, 700,
                emptyList(), 1000, "3.13.203")))
    }

    @Test fun changingQuantityClearsBothPricesAndReadyCartBadges() {
        val changed = previous().forChangedDraft(cart.copy(items = listOf(cart.items.single().copy(quantity = 3))))
        assertTrue(changed.previews.isEmpty())
        assertTrue(changed.preparedBaskets.records.isEmpty())
        assertNull(changed.preparedBasket)
        assertEquals(cart.restaurant, changed.selectedRestaurant)
    }

    @Test fun choosingNewRestaurantCannotRestoreEarlierPreparedCartByReturningToOldOrder() {
        val changed = previous().forChangedDraft(cart.copy(restaurant = "مطعم جديد"))
        assertEquals("مطعم جديد", changed.selectedRestaurant)
        assertNull(changed.preparedBaskets.forRequest(cart, ProviderId.KEETA))
        assertTrue(changed.forChangedDraft(cart).preparedBaskets.records.isEmpty())
    }

    @Test fun deletingOrdersClearsSelectedRestaurantAndComparisonState() {
        val cleared = previous().forChangedDraft(null)
        assertNull(cleared.selectedRestaurant)
        assertTrue(cleared.previews.isEmpty())
        assertTrue(cleared.comparisonProgress.isEmpty())
        assertTrue(cleared.preparedBaskets.records.isEmpty())
    }
}
