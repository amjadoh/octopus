package app.octopus.local
import app.octopus.local.automation.PlainBasketPreparationSession as Session
import app.octopus.local.domain.*
import app.octopus.local.providers.*
import org.junit.Assert.*
import org.junit.Test

class PlainBasketPreparationSessionTest {
    private val first=CanonicalItem("بطاطس وسط",null,emptyList(),3)
    private val second=CanonicalItem("جبنة الناتشوز",null,emptyList(),2)
    private val request=CanonicalCart("كودو",null,listOf(first,second))
    private fun basket(a:Int=3,b:Int?=null)=BasketEvidence(ProviderId.KEETA,"كودو",
        listOf(BasketLineEvidence(first.name,emptyList(),a,900L*a))+
            if(b==null)emptyList() else listOf(BasketLineEvidence(second.name,emptyList(),b,1300L*b)),900L*a+1300L*(b?:0),true)
    private fun edit(session:Session, actual:BasketEvidence, now:Long=1000)=
        (session.next(request,actual,"v",now) as Session.Step.Change).edit
    @Test fun missingLineAddsOneThenAdjustsOnlyItsQuantityAndVerifiesAllRows() {
        val session=Session.create(request,basket(),"v")!!
        val add=edit(session,basket())
        assertEquals(Session.Kind.ADD_ONE,add.kind)
        assertSame(add,edit(session,basket()))
        assertTrue(session.consume(add,request,"v",1100))
        assertTrue(session.acknowledge(add,request,basket(b=1),"v",1200))
        val increase=edit(session,basket(b=1),1300)
        assertEquals(Session.Kind.INCREASE_ONE,increase.kind)
        assertTrue(session.consume(increase,request,"v",1400))
        assertTrue(session.acknowledge(increase,request,basket(b=2),"v",1500))
        assertEquals(Session.Step.Ready,session.next(request,basket(b=2),"v",1600))
    }
    @Test fun timeoutFailureAndSecondDispatchNeverReissueAdd() {
        val timed=Session.create(request,basket(),"v")!!;val late=edit(timed,basket())
        assertFalse(timed.consume(late,request,"v",121001));assertEquals(Session.Step.Blocked,timed.next(request,basket(),"v",121002))
        val session=Session.create(request,basket(),"v")!!;val add=edit(session,basket())
        assertTrue(session.consume(add,request,"v",1100))
        assertFalse(session.consume(add,request,"v",1101))
        assertFalse(session.acknowledge(add,request,basket(b=1),"v",1200))
        assertEquals(Session.Step.Blocked,session.next(request,basket(),"v",1300))
    }
    @Test fun externalChangeOfAnotherLineCannotBecomeOwned() {
        val session=Session.create(request,basket(),"v")!!;val add=edit(session,basket())
        assertTrue(session.consume(add,request,"v",1100))
        assertFalse(session.acknowledge(add,request,basket(a=4,b=1),"v",1200))
        assertEquals(Session.Step.Blocked,session.next(request,basket(b=1),"v",1300))
        val session2=Session.create(request,basket(),"v")!!
        assertEquals(Session.Step.Blocked,session2.next(request,basket(a=2),"v",1000))
    }
    @Test fun incompleteUnknownModifiedOrForeignRowsCannotAuthorizeEdits() {
        val baseline=basket()
        val wrong=listOf(baseline.copy(complete=false),baseline.copy(restaurant="آخر"),
            baseline.copy(lines=baseline.lines+baseline.lines),
            baseline.copy(lines=listOf(baseline.lines.single().copy(choices=listOf(BasketChoiceEvidence("ملح",1))))),
            baseline.copy(lines=listOf(baseline.lines.single().copy(lineAmount=null))),
            baseline.copy(lines=listOf(baseline.lines.single().copy(name="صنف آخر"))))
        wrong.forEach {assertNull(Session.create(request,it,"v"))}
        val session=Session.create(request,baseline,"v")!!
        assertEquals(Session.Step.Blocked,session.next(request,baseline,"other-version",1000))
    }
    @Test fun changedFullRequestAndWrongPostActionQuantityAreRejected() {
        val session=Session.create(request,basket(),"v")!!;val add=edit(session,basket())
        assertFalse(session.consume(add,request.copy(items=listOf(first,second.copy(quantity=3))),"v",1100))
        val other=Session.create(request,basket(),"v")!!;val next=edit(other,basket())
        assertTrue(other.consume(next,request,"v",1100))
        assertFalse(other.acknowledge(next,request,basket(b=2),"v",1200))
    }
    @Test fun unknownOrInconsistentMoneyCannotAuthorizeAnyEdit() {
        assertNull(Session.create(request,basket().copy(subtotal=null),"v"))
        assertNull(Session.create(request,basket().copy(subtotal=1),"v"))
        val overflow=basket(b=1).copy(lines=listOf(
            BasketLineEvidence(first.name,emptyList(),3,Long.MAX_VALUE),
            BasketLineEvidence(second.name,emptyList(),1,1)),subtotal=Long.MIN_VALUE)
        assertNull(Session.create(request,overflow,"v"))
    }
    @Test fun mismatchedPostEditMoneyBlocksAcknowledgementAndRetry() {
        val session=Session.create(request,basket(),"v")!!
        val add=edit(session,basket())
        assertTrue(session.consume(add,request,"v",1100))
        assertFalse(session.acknowledge(add,request,basket(b=1).copy(subtotal=1),"v",1200))
        assertEquals(Session.Step.Blocked,session.next(request,basket(),"v",1300))
    }
    @Test fun repeatedMatchedBasketNeverCreatesAnAddOrQuantityCommand() {
        repeat(5) {
            val session=Session.create(request,basket(b=2),"v")!!
            assertEquals(Session.Step.Ready,session.next(request,basket(b=2),"v",1000))
        }
    }
}
