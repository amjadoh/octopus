package app.octopus.local

import app.octopus.local.domain.*
import app.octopus.local.providers.*
import org.junit.Assert.*
import org.junit.Test

class CartManagementStateTest {
    private val request = CanonicalCart("مطعم", null, listOf(CanonicalItem("وجبة", null, emptyList(), 3)))
    private fun basket(id: ProviderId) = BasketEvidence(id, request.restaurant,
        listOf(BasketLineEvidence("وجبة", emptyList(), 3, 2100)), 2100, true)
    @Test fun changedAreaDropsAllOldResultsButPreservesManualCatalog() {
        val id=ProviderId.HUNGERSTATION
        val manual=LiveRestaurant("محفوظ",null,"محفوظ")
        val public=manual.copy(origin=CatalogOrigin.PUBLIC)
        val old=LiveCatalogState(selectedRestaurant=request.restaurant,
            finalTotals=mapOf(id to FinalTotalReading(id,"v",request.fingerprint,mapOf(PriceField.ORDER_TOTAL to 2500L),1000)),
            preparedBasket=basket(id),preparedBaskets=PreparedBasketLedger().record(
                PreparedBasketRecord.create(request,basket(id),"v",1000)!!))
        val changed=old.forChangedArea(listOf(manual,public))
        assertEquals(listOf(manual),changed.restaurants)
        assertNull(changed.selectedRestaurant)
        assertTrue(changed.finalTotals.isEmpty())
        assertTrue(changed.previews.isEmpty())
        assertTrue(changed.comparisonProgress.isEmpty())
        assertTrue(changed.preparedBaskets.records.isEmpty())
        assertNull(changed.preparedBasket)
    }
    @Test fun nativeCartManagementInvalidatesOnlyThatProvidersQuoteAndReadiness() {
        val hs = ProviderId.HUNGERSTATION; val keeta = ProviderId.KEETA
        var ledger = PreparedBasketLedger()
        ProviderId.entries.forEach { ledger = ledger.record(PreparedBasketRecord.create(request, basket(it), "version", 1000)!!) }
        val totals = ProviderId.entries.associateWith { FinalTotalReading(it, "version", request.fingerprint,
            mapOf(PriceField.ORDER_TOTAL to 2500L), 1000) }
        val state = LiveCatalogState(finalTotals = totals, selectedRestaurant = request.restaurant,
            preparedBasket = basket(hs), preparedBaskets = ledger)
        val result = state.withoutProviderBasket(hs)
        assertNull(result.finalTotals[hs]); assertEquals(totals[keeta], result.finalTotals[keeta])
        assertNull(result.preparedBasket)
        assertNull(result.preparedBaskets.forRequest(request, hs))
        assertNotNull(result.preparedBaskets.forRequest(request, keeta))
        assertEquals(request.restaurant, result.selectedRestaurant)
    }
}
