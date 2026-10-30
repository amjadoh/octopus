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

    private fun configuredCart() = cart.copy(items=listOf(cart.items.single().copy(
        size="وسط", options=listOf(CartOption("إضافات", "شيبس")), quantity=3)))
    private fun configuredEvidence() = evidence(ProviderId.HUNGERSTATION).copy(complete=true,
        lines=listOf(BasketLineEvidence(cart.items.single().name,
            listOf(BasketChoiceEvidence("وسط",null), BasketChoiceEvidence("شيبس",1)),3,3900)),subtotal=3900)

    @Test fun completeConfiguredContentsCanBeRecordedWithoutDiscardingOptions() {
        val request=configuredCart(); val observed=configuredEvidence()
        val record=PreparedBasketRecord.create(request,observed,"v",1000)
        assertNotNull(record)
        assertEquals(observed,record!!.evidence)
        assertFalse(record.matches(request.copy(items=listOf(request.items.single().copy(options=emptyList())))))
    }
    @Test fun configuredRecordsRejectPartialUnknownMissingAndExtraOptions() {
        val request=configuredCart(); val observed=configuredEvidence(); val row=observed.lines.single()
        val variants=listOf(observed.copy(complete=false),
            observed.copy(lines=listOf(row.copy(choices=row.choices.dropLast(1)))),
            observed.copy(lines=listOf(row.copy(choices=row.choices + BasketChoiceEvidence("صلصة",1)))),
            observed.copy(lines=listOf(row.copy(choices=row.choices.map { it.copy(quantity=null) }))),
            observed.copy(lines=listOf(row.copy(quantity=6))), observed.copy(subtotal=3901))
        variants.forEach { assertNull(PreparedBasketRecord.create(request,it,"v",1000)) }
    }
    @Test fun sameProductWithDifferentOptionsRequiresDistinctExactlyMatchingRows() {
        val first=configuredCart().items.single()
        val second=first.copy(options=listOf(CartOption("إضافات","صلصة")),quantity=1)
        val request=configuredCart().copy(items=listOf(first,second))
        val observed=configuredEvidence().copy(lines=configuredEvidence().lines +
            BasketLineEvidence(first.name,listOf(BasketChoiceEvidence("وسط",null),BasketChoiceEvidence("صلصة",1)),1,900),subtotal=4800)
        assertNotNull(PreparedBasketRecord.create(request,observed,"v",1000))
        assertNull(PreparedBasketRecord.create(request,observed.copy(lines=listOf(observed.lines.first().copy(quantity=4))),"v",1000))
    }
    @Test fun configuredNotesAndRepeatedOptionAmountsRemainUnsupported() {
        val request=configuredCart(); val row=request.items.single()
        assertNull(PreparedBasketRecord.create(request.copy(items=listOf(row.copy(note="بدون ملح"))),configuredEvidence(),"v",1000))
        assertNull(PreparedBasketRecord.create(request.copy(items=listOf(row.copy(options=listOf(row.options.single().copy(quantity=2))))),configuredEvidence(),"v",1000))
    }
    @Test fun configuredLedgerDoesNotAuthorizePlainReviewExecutor() {
        val request=configuredCart().copy(items=configuredCart().items + CanonicalItem("ماء",null,emptyList(),1))
        val observed=configuredEvidence().copy(lines=configuredEvidence().lines + BasketLineEvidence("ماء",emptyList(),1,100),subtotal=4000)
        val record=PreparedBasketRecord.create(request,observed,HungerStationCatalog.TESTED_VERSION,1000)!!
        assertNull(app.octopus.local.safety.PreparedReviewAdmission.create(ProviderId.HUNGERSTATION,
            request,record,CatalogFrame(emptyList(),true),HungerStationCatalog.TESTED_VERSION,1000))
    }
    @Test fun duplicateConfiguredRowsCannotBeMistakenForOnePreparedVariant() {
        val observed=configuredEvidence()
        assertNull(PreparedBasketRecord.create(configuredCart(),
            observed.copy(lines=observed.lines + observed.lines,subtotal=7800),"v",1000))
    }

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
    @Test fun mismatchingSubtotalCannotBecomeReadyEvenWhenRowsAndQuantitiesMatch() {
        val e = evidence(ProviderId.KEETA)
        for (complete in listOf(false, true)) {
            assertNull(PreparedBasketRecord.create(cart, e.copy(complete=complete, subtotal=701), "v", 1000))
        }
        val multi = cart.copy(items=cart.items + CanonicalItem("ماء",null,emptyList(),2))
        val full = e.copy(complete=true, lines=e.lines + BasketLineEvidence("ماء",emptyList(),2,400), subtotal=700)
        assertNull(PreparedBasketRecord.create(multi,full,"v",1000))
        assertNotNull(PreparedBasketRecord.create(multi,full.copy(subtotal=1100),"v",1000))
    }
    @Test fun completeRecordRequiresReadAmountsAndSubtotalWithoutInventingThem() {
        val e = evidence(ProviderId.KEETA).copy(complete=true)
        assertNull(PreparedBasketRecord.create(cart,e.copy(subtotal=null),"v",1000))
        assertNull(PreparedBasketRecord.create(cart,e.copy(lines=e.lines.map {it.copy(lineAmount=null)}),"v",1000))
        // A visible-only historical observation stays partial, never promoted to complete.
        assertNotNull(PreparedBasketRecord.create(cart,e.copy(complete=false,subtotal=null),"v",1000))
    }
    @Test fun rowAmountOverflowCannotWrapIntoAnAcceptedSubtotal() {
        val multi=cart.copy(items=cart.items + CanonicalItem("ماء",null,emptyList(),1))
        val e=evidence(ProviderId.KEETA).copy(complete=true,subtotal=0,
            lines=listOf(BasketLineEvidence(cart.items.single().name,emptyList(),1,Long.MAX_VALUE),
                BasketLineEvidence("ماء",emptyList(),1,1)))
        assertNull(PreparedBasketRecord.create(multi,e,"v",1000))
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
