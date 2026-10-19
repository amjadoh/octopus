package app.octopus.local

import app.octopus.local.automation.*
import app.octopus.local.domain.*
import app.octopus.local.matching.*
import app.octopus.local.pricing.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Business-flow tests with explicit fake ports. No delivery-app success is inferred from them. */
class ComparisonCoordinatorTest {
    private val desired=CanonicalCart("مطعم اختبار","branch-ref",listOf(CanonicalItem("وجبة","نص",emptyList(),1)),"address-ref")
    private class Clock(var now:Long=10_000)
    private inner class Port(override val provider:ProviderId, val clock:Clock, var total:Long=4000):ComparisonProviderPort {
        var actual=ObservedCart(desired.restaurant,desired.branch,desired.addressReference,desired.items,true)
        var preflights=0; var edits=0; var reviews=0; var opened=0; var released=0
        var account="opaque-test-session"
        var quoteProvider=provider
        var changingPrice=false
        var changeOnSecondCartOpen=false
        var failRelease=false
        override suspend fun preflight(desired:CanonicalCart):ProviderReadiness { preflights++; return ProviderReadiness("test-version",account,true) }
        override suspend fun observeCart()=actual
        override suspend fun applyAbsoluteQuantity(edit:ReconcileDecision.SetQuantity,stop:StopSignal):Boolean {
            if(stop.stopped || edit.expectedFingerprint!=actual.fingerprint)return false
            edits++
            actual=actual.copy(items=actual.items.filterNot { CartIdentity.itemKey(it)==CartIdentity.itemKey(edit.item) } +
                if(edit.targetQuantity==0)emptyList()else listOf(edit.item.copy(quantity=edit.targetQuantity)))
            return true
        }
        override suspend fun observeReview():QuoteObservation {
            reviews++
            val cost=if(changingPrice && reviews%2==0)total+100 else total
            return QuoteObservation(quoteProvider,"test-version",account,actual,
                QuoteCandidate(mapOf(PriceField.ORDER_TOTAL to cost),false),clock.now,true,true)
        }
        override suspend fun openCart():ObservedCart {
            opened++
            return if(changeOnSecondCartOpen && opened==2)actual.copy(items=actual.items.map { it.copy(quantity=2) }) else actual
        }
        override fun releaseControl(){released++;check(!failRelease)}
    }
    private fun coordinator(clock:Clock, wait:suspend(Long)->Unit={clock.now+=it})=ComparisonCoordinator(
        preparation=CartPreparationEngine(authorizeEdit={_,_->true}), clock={clock.now},waitStable=wait)

    @Test fun twoProvidersPrepareSequentiallyRankAndRetryWithoutDuplicatingItems()=runBlocking {
        val clock=Clock(); val a=Port(ProviderId.HUNGERSTATION,clock,4500); val b=Port(ProviderId.KEETA,clock,4000)
        a.actual=a.actual.copy(items=emptyList()); b.actual=b.actual.copy(items=emptyList())
        val phases=mutableListOf<Pair<ProviderId,ComparisonPhase>>()
        val coordinator=coordinator(clock)
        val result=coordinator.compare(desired,listOf(a,b),StopSignal(),progress={p,s->phases+=p to s})
        assertTrue(result.twoProviderComparison)
        assertEquals(listOf(ProviderId.KEETA,ProviderId.HUNGERSTATION),result.ranked.map { it.provider })
        assertEquals(1,a.edits); assertEquals(1,b.edits)
        assertTrue(phases.indexOf(ProviderId.HUNGERSTATION to ComparisonPhase.COMPLETE)<phases.indexOf(ProviderId.KEETA to ComparisonPhase.PREFLIGHT))
        assertTrue(coordinator.compare(desired,listOf(a,b),StopSignal()).twoProviderComparison)
        assertEquals(1,a.edits); assertEquals(1,b.edits)
        assertEquals(1,a.actual.items.single().quantity)
    }
    @Test fun differentExistingCartNeedsApprovalAndNoReviewOrMutationOccurs()=runBlocking {
        val clock=Clock(); val p=Port(ProviderId.HUNGERSTATION,clock)
        p.actual=p.actual.copy(items=listOf(CanonicalItem("وجبة أخرى",null,emptyList(),1)))
        val result=coordinator(clock).compare(desired,listOf(p),StopSignal())
        assertEquals(PreparationFailure.NEEDS_APPROVAL,result.results.single().preparationFailure)
        assertEquals(0,p.edits); assertEquals(0,p.reviews); assertTrue(result.ranked.isEmpty())
    }
    @Test fun unstableQuoteIsExcludedAndFailureIsNeverRankedAsZero()=runBlocking {
        val clock=Clock(); val a=Port(ProviderId.HUNGERSTATION,clock); a.changingPrice=true
        val b=Port(ProviderId.KEETA,clock)
        val result=coordinator(clock).compare(desired,listOf(a,b),StopSignal())
        assertFalse(result.twoProviderComparison)
        assertEquals(QuoteRejection.UNSTABLE,result.results.first().quoteRejection)
        assertEquals(listOf(4000L),result.ranked.map { it.orderCost })
    }
    @Test fun stopDuringPriceWaitPreventsAllLaterActionsAndTheSecondProvider()=runBlocking {
        val clock=Clock(); val stop=StopSignal(); val a=Port(ProviderId.HUNGERSTATION,clock); val b=Port(ProviderId.KEETA,clock)
        val result=coordinator(clock){clock.now+=it;stop.stop()}.compare(desired,listOf(a,b),stop)
        assertTrue(result.stopped); assertTrue(result.ranked.isEmpty())
        assertEquals(1,a.reviews); assertEquals(0,b.preflights); assertEquals(1,a.released)
    }
    @Test fun wrongProviderContextCannotBeAcceptedEvenIfItsTotalIsStable()=runBlocking {
        val clock=Clock(); val a=Port(ProviderId.HUNGERSTATION,clock); a.quoteProvider=ProviderId.KEETA
        val result=coordinator(clock).compare(desired,listOf(a),StopSignal())
        assertEquals(ComparisonFailure.CONTEXT_CHANGED,result.results.single().failure)
        assertTrue(result.ranked.isEmpty())
    }
    @Test fun unknownAddressBlocksPreparationBeforeOpeningTheProvider()=runBlocking {
        val clock=Clock();val a=Port(ProviderId.HUNGERSTATION,clock)
        val result=coordinator(clock).compare(desired.copy(addressReference=null),listOf(a),StopSignal())
        assertEquals(ComparisonFailure.CONTEXT_REQUIRED,result.results.single().failure)
        assertEquals(0,a.preflights);assertEquals(0,a.edits)
    }
    @Test fun handoffReportsPriceChangeAndNeverClaimsReady()=runBlocking {
        val clock=Clock();val a=Port(ProviderId.HUNGERSTATION,clock);val c=coordinator(clock)
        val selected=c.compare(desired,listOf(a),StopSignal()).results.single().accepted!!
        a.total=4200
        val handed=c.handoff(desired,selected,a,StopSignal())
        assertTrue(handed is HandoffResult.PriceChanged)
        assertEquals(4200L,(handed as HandoffResult.PriceChanged).current.orderCost)
        assertEquals(1,a.opened);assertEquals(2,a.released)
    }
    @Test fun handoffVerifiesTheFinalCartAndExpiryBeforeDoingAnything()=runBlocking {
        val clock=Clock();val a=Port(ProviderId.HUNGERSTATION,clock);val c=coordinator(clock)
        val selected=c.compare(desired,listOf(a),StopSignal()).results.single().accepted!!
        a.changeOnSecondCartOpen=true
        assertEquals(HandoffResult.CartChanged,c.handoff(desired,selected,a,StopSignal()))
        val previous=a.preflights;clock.now+=121_000
        assertEquals(HandoffResult.Expired,c.handoff(desired,selected,a,StopSignal()))
        assertEquals(previous,a.preflights)
    }
    @Test fun failedControlReleaseStopsTheSessionAndCannotReportReady()=runBlocking {
        val clock=Clock();val a=Port(ProviderId.HUNGERSTATION,clock);val b=Port(ProviderId.KEETA,clock);val c=coordinator(clock)
        val selected=c.compare(desired,listOf(a),StopSignal()).results.single().accepted!!
        a.failRelease=true
        assertEquals(HandoffResult.Stopped,c.handoff(desired,selected,a,StopSignal()))
        val result=c.compare(desired,listOf(a,b),StopSignal())
        assertTrue(result.stopped);assertTrue(result.ranked.isEmpty());assertEquals(0,b.preflights)
    }
}
