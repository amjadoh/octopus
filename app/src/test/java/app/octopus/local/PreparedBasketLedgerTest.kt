package app.octopus.local

import app.octopus.local.domain.*
import app.octopus.local.providers.*
import org.junit.Assert.*
import org.junit.Test

class PreparedBasketLedgerTest {
    private val cart = CanonicalCart("مدور", null, listOf(CanonicalItem("فطيرة جبن فيتا", null, emptyList(), 1)), null)
    private fun evidence(id: ProviderId) = BasketEvidence(id, cart.restaurant,
        listOf(BasketLineEvidence(cart.items.single().name, emptyList(), 1, 700)), 700, false)
    private fun record(id: ProviderId) = PreparedBasketRecord.create(cart, evidence(id), "observed-version", 1000)!!

    @Test fun preparingOrInvalidatingOneProviderPreservesTheOtherProvider() {
        val hs = record(ProviderId.HUNGERSTATION); val keeta = record(ProviderId.KEETA)
        val both = PreparedBasketLedger().record(hs).record(keeta)
        assertEquals(hs, both.forRequest(cart, ProviderId.HUNGERSTATION))
        assertEquals(keeta, both.forRequest(cart, ProviderId.KEETA))
        val failedKeeta = both.invalidate(ProviderId.KEETA)
        assertEquals(hs, failedKeeta.forRequest(cart, ProviderId.HUNGERSTATION))
        assertNull(failedKeeta.forRequest(cart, ProviderId.KEETA))
    }
    @Test fun modifiedCartNeverReusesAnEarlierPreparationRecord() {
        val ledger = PreparedBasketLedger().record(record(ProviderId.HUNGERSTATION))
        val changed = cart.copy(items = listOf(cart.items.single().copy(quantity = 2)))
        assertNull(ledger.forRequest(changed, ProviderId.HUNGERSTATION))
        assertNull(ledger.forRequest(cart.copy(addressReference = "different-reference"), ProviderId.HUNGERSTATION))
    }
    @Test fun unreadRowsUnknownQuantityAndOptionsCannotBecomeMatchingPreparationRecords() {
        val e = evidence(ProviderId.HUNGERSTATION)
        fun rejected(evidence: BasketEvidence) = assertNull(PreparedBasketRecord.create(cart, evidence, "v", 1000))
        rejected(e.copy(lines = emptyList()))
        rejected(e.copy(lines = e.lines + e.lines))
        rejected(e.copy(restaurant = "مطعم آخر"))
        rejected(e.copy(lines = listOf(e.lines.single().copy(quantity = null))))
        rejected(e.copy(lines = listOf(e.lines.single().copy(choices = listOf(BasketChoiceEvidence("إضافة", 1))))))
    }
    @Test fun savingVisibleContentsNeverUpgradesCompletenessOrInventsFinalTotals() {
        val e = record(ProviderId.HUNGERSTATION).evidence
        assertFalse(e.complete)
        assertFalse(e.recognizedOrderReview)
        assertNull(PreparedBasketRecord.create(cart, e, "", 1000))
        assertNull(PreparedBasketRecord.create(cart, e, "v", -1))
    }
}
