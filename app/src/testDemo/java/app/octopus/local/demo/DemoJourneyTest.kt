package app.octopus.local.demo

import app.octopus.local.domain.ProviderId
import org.junit.Assert.*
import org.junit.Test

class DemoJourneyTest {
    private fun burger(): DemoJourneyController = DemoJourneyController().also { it.chooseRestaurant("garden"); it.openItem("beef") }
    private fun readyBurger(): DemoJourneyController = burger().also { it.drink("cola"); assertTrue(it.addDraft()) }
    private fun finish(c: DemoJourneyController) {
        val epoch = c.beginComparison() ?: error("Expected a valid comparison")
        repeat(5) { c.advanceComparison(epoch) }
    }
    @Test fun firstStateHasNoFabricatedResults() {
        val value = DemoJourneyController().state.value
        assertTrue(value.cart.isEmpty()); assertTrue(value.quotes.isEmpty()); assertNull(value.selected)
        assertEquals(JourneyPage.RESTAURANTS, value.page)
    }
    @Test fun missingRequiredDrinkPreventsAdding() {
        val c = burger()
        assertFalse(c.addDraft()); assertTrue(c.state.value.cart.isEmpty())
        c.drink("lemon"); assertTrue(c.addDraft())
    }
    @Test fun requiredOptionsAndQuantityAreValidated() {
        assertFalse(DemoPricing.valid(DemoDraft("beef", "regular", "missing")))
        assertFalse(DemoPricing.valid(DemoDraft("beef", "invalid", "cola")))
        assertFalse(DemoPricing.valid(DemoDraft("beef", "regular", "cola", setOf("unknown"))))
        assertFalse(DemoPricing.valid(DemoDraft("beef", "regular", "cola", quantity = 0)))
        assertFalse(DemoPricing.valid(DemoDraft("water", "invented")))
        assertFalse(DemoPricing.valid(DemoDraft("water", null, "cola")))
        assertTrue(DemoPricing.valid(DemoDraft("water", null)))
    }
    @Test fun sizesExtrasAndQuantitiesProduceExactHalala() {
        val c = burger()
        c.size("large"); c.drink("lemon"); c.extra("cheese", true); c.draftQuantity(1)
        assertTrue(c.addDraft())
        assertEquals(7800L, DemoPricing.subtotal(c.state.value.cart))
        assertEquals("كبير • ليمون بالنعناع • جبن إضافي", DemoPricing.summary(c.state.value.cart.single()))
    }
    @Test fun multipleItemsMaintainIndependentCustomisations() {
        val c = readyBurger()
        c.openItem("beef"); c.drink("lemon"); c.extra("cheese", true); c.addDraft()
        c.openItem("fries"); c.addDraft()
        assertEquals(3, c.state.value.cart.size)
        assertEquals(setOf("cola", "lemon", null), c.state.value.cart.map { it.drinkId }.toSet())
    }
    @Test fun identicalSelectionsMergeButDifferentOptionsDoNot() {
        val c = readyBurger()
        c.openItem("beef"); c.drink("cola"); c.addDraft()
        assertEquals(1, c.state.value.cart.size); assertEquals(2, c.state.value.cart.single().quantity)
        c.openItem("beef"); c.drink("water"); c.addDraft()
        assertEquals(2, c.state.value.cart.size)
    }
    @Test fun doubleSubmitCannotDuplicateLine() {
        val c = burger(); c.drink("cola")
        assertTrue(c.addDraft()); assertFalse(c.addDraft())
        assertEquals(1, c.state.value.cart.single().quantity)
    }
    @Test fun unknownChoicesCannotChangeDraft() {
        val c = burger(); c.size("fake"); c.drink("fake"); c.extra("fake", true)
        assertEquals("regular", c.state.value.draft?.sizeId)
        assertNull(c.state.value.draft?.drinkId); assertTrue(c.state.value.draft!!.extras.isEmpty())
    }
    @Test fun quantityClampsToValidRangeInDraftAndCart() {
        val c = burger(); c.draftQuantity(-100); assertEquals(1, c.state.value.draft!!.quantity)
        c.draftQuantity(100); assertEquals(10, c.state.value.draft!!.quantity)
        c.drink("cola"); c.addDraft()
        val id = c.state.value.cart.single().id
        c.quantity(id, 100); assertEquals(10, c.state.value.cart.single().quantity)
        c.quantity(id, -100); assertEquals(1, c.state.value.cart.single().quantity)
    }
    @Test fun restaurantReplacementRequiresExplicitConfirmation() {
        val c = readyBurger(); c.chooseRestaurant("oven")
        assertEquals("garden", c.state.value.restaurantId); assertEquals("oven", c.state.value.pendingRestaurant)
        assertEquals(1, c.state.value.cart.size)
        c.dismissRestaurantChange(); assertNull(c.state.value.pendingRestaurant); assertEquals(1, c.state.value.cart.size)
        c.chooseRestaurant("oven"); c.confirmRestaurantChange()
        assertEquals("oven", c.state.value.restaurantId); assertTrue(c.state.value.cart.isEmpty())
    }
    @Test fun cannotAddItemFromAnotherRestaurant() {
        val c = readyBurger(); c.openItem("margherita")
        assertNull(c.state.value.draft); assertEquals(1, c.state.value.cart.size)
    }
    @Test fun editRetainsLineIdentityWithoutDuplicating() {
        val c = readyBurger(); val original = c.state.value.cart.single()
        c.openItem("beef", original.id); c.size("large"); c.extra("cheese", true); c.addDraft()
        assertEquals(1, c.state.value.cart.size); assertEquals(original.id, c.state.value.cart.single().id)
        assertEquals(3500L, DemoPricing.subtotal(c.state.value.cart))
    }
    @Test fun emptyCartOrOnlyOnePlatformCannotCompare() {
        assertNull(DemoJourneyController().beginComparison())
        val c = readyBurger(); c.allow(ProviderId.HUNGERSTATION, false); assertNull(c.beginComparison())
    }
    @Test fun fullOrderJourneyCalculatesBothQuotesExactly() {
        val c = burger(); c.size("large"); c.drink("lemon"); c.extra("cheese", true); c.draftQuantity(1); c.addDraft()
        c.openItem("fries"); c.addDraft(); finish(c)
        assertEquals(JourneyPage.RESULTS, c.state.value.page)
        assertEquals(listOf(8650L, 9150L), c.state.value.quotes.map { it.orderCost })
        val second = c.state.value.quotes.last(); assertEquals(1500L, second.wallet); assertEquals(7650L, second.dueNow)
        c.select(ProviderId.HUNGERSTATION); c.navigate(JourneyPage.READY); c.navigate(JourneyPage.HANDOFF)
        assertEquals(JourneyPage.HANDOFF, c.state.value.page); assertEquals(2, c.state.value.cart.size)
    }
    @Test fun walletsNeverChangeCostRanking() {
        val c = readyBurger(); finish(c)
        val quotes = c.state.value.quotes
        assertEquals(ProviderId.KEETA, quotes.first().provider)
        assertTrue(quotes.last().dueNow < quotes.first().dueNow)
        assertTrue(quotes.last().orderCost > quotes.first().orderCost)
    }
    @Test fun everyQuoteReconcilesWithoutDoubleTaxOrDiscount() {
        DemoCatalog.restaurants.forEach { restaurant ->
            val c = DemoJourneyController(); c.chooseRestaurant(restaurant.id)
            DemoCatalog.items.filter { it.restaurantId == restaurant.id }.forEach { item ->
                c.openItem(item.id); item.drinks.firstOrNull()?.let { c.drink(it.id) }; c.addDraft()
            }
            finish(c)
            c.state.value.quotes.forEach { q ->
                assertEquals(q.items + q.delivery + q.service + q.smallOrder - q.discount, q.orderCost)
                assertEquals(q.orderCost, q.wallet + q.dueNow)
            }
        }
    }
    @Test fun cancelledComparisonRejectsLateCallbacks() {
        val c = readyBurger(); val epoch = c.beginComparison()!!; c.advanceComparison(epoch); c.cancelComparison()
        repeat(10) { c.advanceComparison(epoch) }
        assertEquals(JourneyPage.CART, c.state.value.page); assertTrue(c.state.value.quotes.isEmpty())
    }
    @Test fun leavingComparisonAlsoRejectsLateCallbacks() {
        val c = readyBurger(); val epoch = c.beginComparison()!!; c.navigate(JourneyPage.MORE)
        repeat(5) { c.advanceComparison(epoch) }
        assertEquals(JourneyPage.MORE, c.state.value.page); assertTrue(c.state.value.quotes.isEmpty())
    }
    @Test fun changingAddressInvalidatesQuotesAndSelectedProvider() {
        val c = readyBurger(); finish(c); c.select(ProviderId.KEETA); c.address(DemoAddress.WORK)
        assertTrue(c.state.value.quotes.isEmpty()); assertNull(c.state.value.selected)
        assertEquals(JourneyPage.CART, c.state.value.page); assertEquals(DemoAddress.WORK, c.state.value.address)
    }
    @Test fun cartChangesInvalidateQuotes() {
        val c = readyBurger(); finish(c); c.select(ProviderId.KEETA)
        c.quantity(c.state.value.cart.single().id, 1)
        assertTrue(c.state.value.quotes.isEmpty()); assertNull(c.state.value.selected)
        finish(c); c.remove(c.state.value.cart.single().id)
        assertTrue(c.state.value.cart.isEmpty()); assertTrue(c.state.value.quotes.isEmpty())
    }
    @Test fun selectionAndHandoffRequireCurrentQuote() {
        val c = readyBurger(); c.select(ProviderId.KEETA); assertNull(c.state.value.selected)
        c.navigate(JourneyPage.HANDOFF); assertEquals(JourneyPage.RESULTS, c.state.value.page)
    }
    @Test fun staleEpochCannotFinishANewComparison() {
        val c = readyBurger(); val first = c.beginComparison()!!; c.cancelComparison(); val second = c.beginComparison()!!
        repeat(5) { c.advanceComparison(first) }; assertEquals(0, c.state.value.progress)
        repeat(5) { c.advanceComparison(second) }; assertEquals(JourneyPage.RESULTS, c.state.value.page)
    }
}
