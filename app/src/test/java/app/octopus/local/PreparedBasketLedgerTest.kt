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

    @Test fun completePlainMultiItemRecordRequiresEveryRequestedLineAndQuantity() {
        val multi = cart.copy(items=cart.items + CanonicalItem("ماء",null,emptyList(),2))
        val full = evidence(ProviderId.KEETA).copy(complete=true,
            lines=evidence(ProviderId.KEETA).lines + BasketLineEvidence("ماء",emptyList(),2,400),subtotal=1100)
        assertNotNull(PreparedBasketRecord.create(multi,full,"v",1000))
        assertNull(PreparedBasketRecord.create(multi,full.copy(complete=false),"v",1000))
        assertNull(PreparedBasketRecord.create(multi,full.copy(lines=full.lines.dropLast(1)),"v",1000))
        assertNull(PreparedBasketRecord.create(multi,full.copy(lines=full.lines + BasketLineEvidence("شيبس",emptyList(),1,300)),"v",1000))
        assertNull(PreparedBasketRecord.create(multi,full.copy(lines=full.lines.map {if(it.name=="ماء")it.copy(quantity=1) else it}),"v",1000))
        assertNull(PreparedBasketRecord.create(multi,full.copy(lines=full.lines.map {if(it.name=="ماء")it.copy(quantity=null) else it}),"v",1000))
    }
    @Test fun multiItemRecordRejectsVariantsAmbiguousRowsAndUnrequestedChoices() {
        val multi = cart.copy(items=cart.items + CanonicalItem("ماء",null,emptyList(),2))
        val full = evidence(ProviderId.KEETA).copy(complete=true,
            lines=evidence(ProviderId.KEETA).lines + BasketLineEvidence("ماء",emptyList(),2,400))
        assertNull(PreparedBasketRecord.create(multi.copy(items=multi.items.map {if(it.name=="ماء")it.copy(size="كبير") else it}),full,"v",1000))
        assertNull(PreparedBasketRecord.create(multi,full.copy(lines=full.lines.map {if(it.name=="ماء")it.copy(choices=listOf(BasketChoiceEvidence("إضافة",1))) else it}),"v",1000))
        assertNull(PreparedBasketRecord.create(multi,full.copy(lines=listOf(full.lines.first(),full.lines.first())),"v",1000))
        assertNull(PreparedBasketRecord.create(multi,full.copy(lines=full.lines.map {if(it.name=="ماء")it.copy(name="ماء كبير") else it}),"v",1000))
    }
    @Test fun repeatedCanonicalPlainLineMustMatchItsCombinedQuantityWithoutDuplicateObservedRows() {
        val repeated=cart.copy(items=cart.items + cart.items.single().copy(quantity=2))
        val observed=evidence(ProviderId.HUNGERSTATION).copy(lines=listOf(evidence(ProviderId.HUNGERSTATION).lines.single().copy(quantity=3,lineAmount=2100)),subtotal=2100)
        assertNotNull(PreparedBasketRecord.create(repeated,observed,"v",1000))
        assertNull(PreparedBasketRecord.create(repeated,observed.copy(lines=observed.lines + observed.lines),"v",1000))
        val ledger=PreparedBasketLedger().record(PreparedBasketRecord.create(repeated,observed,"v",1000)!!)
        assertNull(ledger.forRequest(repeated.copy(items=repeated.items + CanonicalItem("ماء",null,emptyList(),1)),ProviderId.HUNGERSTATION))
    }
    @Test fun invalidReadAmountsCannotBecomePreparationEvidence() {
        val e=evidence(ProviderId.HUNGERSTATION)
        assertNull(PreparedBasketRecord.create(cart,e.copy(subtotal=-1),"v",1000))
        assertNull(PreparedBasketRecord.create(cart,e.copy(lines=e.lines.map {it.copy(lineAmount=-1)}),"v",1000))
    }
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
        rejected(e.copy(lines = listOf(e.lines.single().copy(name = "صنف مجهول", quantity = null))))
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
