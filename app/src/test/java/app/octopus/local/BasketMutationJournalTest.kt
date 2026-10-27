package app.octopus.local

import app.octopus.local.automation.*
import app.octopus.local.domain.*
import app.octopus.local.providers.*
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

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
    private fun legacyHash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

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

    @Test fun equivalentRestaurantFormattingCannotReopenFirstAddAfterCompletedComparison() {
        val store = Store(); val journal = BasketMutationJournal(store)
        val first = request.copy(restaurant = "Kudo   مطعم")
        journal.beforeDispatch(hs, first)
        assertTrue(journal.observe(first, actual().copy(restaurant = first.restaurant)))
        listOf("kudo مطعم", "  KUDO مطعم  ", "Kudo\u200f مطعم").forEach { name ->
            val equivalent = request.copy(restaurant = name)
            assertEquals(first.fingerprint, equivalent.fingerprint)
            rejected { BasketMutationJournal(store).requireFirstAdd(hs, equivalent) }
        }
    }

    @Test fun interruptedActionRecoversFromCompleteEquivalentNameObservationWithoutAnotherAdd() {
        val store = Store(); val journal = BasketMutationJournal(store)
        val first = request.copy(restaurant = "مطعم   جديد")
        val equivalent = first.copy(restaurant = "مطعم جديد\u200f")
        journal.beforeDispatch(hs, first)
        val restarted = BasketMutationJournal(store)
        rejected { restarted.requireFirstAdd(hs, equivalent) }
        assertTrue(restarted.observe(equivalent, actual(1).copy(restaurant = equivalent.restaurant)))
        rejected { restarted.requireFirstAdd(hs, equivalent) }
        // Reading all existing rows permits the separately guarded quantity adjustment.
        restarted.beforeDispatch(hs, equivalent)
        rejected { restarted.beforeDispatch(hs, equivalent) }
    }

    @Test fun unknownLegacyFormattingCannotSilentlyBecomeAnEmptyCartAfterUpgrade() {
        val old = request.copy(restaurant = "مطعم   قديم")
        val store = Store().apply {
            values = mapOf("seen.${hs.name}.${legacyHash(old.restaurant)}" to "1")
        }
        val journal = BasketMutationJournal(store)
        val equivalent = old.copy(restaurant = "مطعم قديم")
        assertEquals(old.fingerprint, equivalent.fingerprint)
        rejected { journal.requireFirstAdd(hs, equivalent) }
        // Even a different name cannot prove that the irreversible old hash is unrelated.
        rejected { journal.requireFirstAdd(hs, request) }
        journal.requireFirstAdd(ProviderId.KEETA, request)
        assertTrue(store.values.keys.any { it.startsWith("seen.${hs.name}.") })
    }

    @Test fun completeLegacyNameObservationMigratesOnlyThatRestaurantAndKeepsSeenBarrier() {
        val old = request.copy(restaurant = "مطعم   قديم")
        val store = Store().apply {
            values = mapOf("seen.${hs.name}.${legacyHash(old.restaurant)}" to "1",
                "pending.${hs.name}" to legacyHash(old.restaurant) + ":" + legacyHash(old.fingerprint))
        }
        val journal = BasketMutationJournal(store)
        assertFalse(journal.observe(old, actual().copy(restaurant = old.restaurant, complete = false)))
        rejected { journal.requireFirstAdd(hs, request) }
        assertTrue(journal.observe(old, actual().copy(restaurant = old.restaurant)))
        assertFalse(store.values.keys.any { it.startsWith("seen.${hs.name}.") || it.startsWith("pending.") })
        rejected { journal.requireFirstAdd(hs, old.copy(restaurant = "مطعم قديم")) }
        journal.requireFirstAdd(hs, request)
    }

    @Test fun observingUnrelatedCartCannotMigrateAnotherLegacyHash() {
        val store = Store().apply {
            values = mapOf("seen.${hs.name}.${legacyHash("مطعم آخر")}" to "1")
        }
        val journal = BasketMutationJournal(store)
        assertTrue(journal.observe(request, actual()))
        assertTrue(store.values.keys.any { it.startsWith("seen.${hs.name}.") })
        rejected { journal.requireFirstAdd(hs, request.copy(restaurant = "ثالث")) }
    }
}
