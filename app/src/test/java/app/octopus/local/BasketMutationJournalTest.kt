package app.octopus.local

import app.octopus.local.automation.*
import app.octopus.local.domain.*
import app.octopus.local.providers.*
import org.junit.Assert.*
import org.junit.Test

class BasketMutationJournalTest {
    private class Store : BasketJournalStore {
        var values: Map<String, String> = emptyMap()
        var fail = false
        override fun read() = values.toMap()
        override fun write(values: Map<String, String>) {
            check(!fail) { "disk unavailable" }; this.values = values.toMap()
        }
    }
    private val hs = ProviderId.HUNGERSTATION
    private val request = CanonicalCart("مطعم", null, listOf(CanonicalItem("فطيرة", null, emptyList(), 3)))
    private fun actual(quantity: Int = 3) = BasketEvidence(hs, request.restaurant,
        listOf(BasketLineEvidence("فطيرة", emptyList(), quantity, quantity * 700L)), quantity * 700L, true)
    private fun rejected(action: () -> Unit) { assertTrue(runCatching(action).isFailure) }

    @Test fun repeatedCompareReusesKnownCartAndNeverPermitsAnotherFirstAdd() {
        val store = Store(); var journal = BasketMutationJournal(store)
        journal.requireFirstAdd(hs, request); journal.beforeDispatch(hs, request)
        assertTrue(journal.observe(request, actual()))
        repeat(3) {
            journal = BasketMutationJournal(store)
            rejected { journal.requireFirstAdd(hs, request) }
            assertTrue(journal.observe(request, actual()))
        }
        assertFalse(store.values.toString().contains(request.restaurant))
        assertFalse(store.values.toString().contains("فطيرة"))
    }
    @Test fun interruptedAddSurvivesRestartUntilCompleteRowsAreRead() {
        val store = Store(); BasketMutationJournal(store).beforeDispatch(hs, request)
        val restarted = BasketMutationJournal(store)
        rejected { restarted.beforeDispatch(hs, request) }
        rejected { restarted.requireFirstAdd(hs, request) }
        assertFalse(restarted.observe(request, actual().copy(complete = false)))
        rejected { restarted.beforeDispatch(hs, request) }
        assertTrue(restarted.observe(request, actual(1)))
        restarted.beforeDispatch(hs, request)
        rejected { restarted.beforeDispatch(hs, request) }
    }
    @Test fun foreignRowsWrongRestaurantUnknownAmountsOrModifiersCannotClearPending() {
        val store = Store(); val journal = BasketMutationJournal(store)
        journal.beforeDispatch(hs, request)
        val cases = listOf(actual().copy(restaurant = "مطعم آخر"), actual().copy(subtotal = null),
            actual().copy(subtotal = 1), actual().copy(lines = emptyList()),
            actual().copy(lines = actual().lines + BasketLineEvidence("شيبس", emptyList(), 1, 700)),
            actual().copy(lines = actual().lines.map { it.copy(choices = listOf(BasketChoiceEvidence("إضافة", 1))) }))
        cases.forEach { assertFalse(journal.observe(request, it)); rejected { journal.beforeDispatch(hs, request) } }
    }
    @Test fun providersAreIndependentButRequestChangesDoNotErasePendingActions() {
        val store = Store(); val journal = BasketMutationJournal(store)
        journal.beforeDispatch(hs, request)
        rejected { journal.beforeDispatch(hs, request.copy(restaurant = "آخر")) }
        journal.requireFirstAdd(ProviderId.KEETA, request)
        journal.beforeDispatch(ProviderId.KEETA, request)
        assertTrue(journal.observe(request, actual()))
        rejected { journal.beforeDispatch(ProviderId.KEETA, request) }
    }
    @Test fun storageFailurePreventsTheDispatchPermit() {
        val store = Store().apply { fail = true }; val journal = BasketMutationJournal(store)
        rejected { journal.beforeDispatch(hs, request) }
        assertTrue(store.values.isEmpty())
    }
    @Test fun observingAnotherRestaurantCannotEraseAnUnresolvedEdit() {
        val store = Store(); val journal = BasketMutationJournal(store)
        journal.beforeDispatch(hs, request)
        val other = request.copy(restaurant = "آخر")
        assertTrue(journal.observe(other, actual().copy(restaurant = other.restaurant)))
        rejected { journal.beforeDispatch(hs, request) }
        rejected { journal.beforeDispatch(hs, other) }
        assertTrue(journal.observe(request, actual()))
        journal.beforeDispatch(hs, request)
    }
}
