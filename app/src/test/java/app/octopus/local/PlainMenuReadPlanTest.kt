package app.octopus.local

import app.octopus.local.domain.*
import app.octopus.local.providers.*
import org.junit.Assert.*
import org.junit.Test

class PlainMenuReadPlanTest {
    private val first=CanonicalItem("بطاطس وسط",null,emptyList(),3)
    private val second=CanonicalItem("ماء",null,emptyList(),2)
    private val cart=CanonicalCart("كودو",null,listOf(first,second))
    private fun plan()=PlainMenuReadPlan.create(cart,ProviderId.KEETA,"v")!!
    @Test fun originalRequestFingerprintIsFrozenEvenIfCallerMutatesItsList() {
        val mutable=mutableListOf(first,second)
        val request=cart.copy(items=mutable)
        val p=PlainMenuReadPlan.create(request,ProviderId.KEETA,"v")!!
        assertTrue(p.record(request,first,900,1000,"v"))
        mutable[1]=second.copy(quantity=3)
        assertFalse(p.record(request,second,200,1200,"v"))
        assertNull(p.preview(request,2000))
    }
    @Test fun everyLineMustBeReadBeforeAnySubtotalExists() {
        val p=plan()
        assertTrue(p.record(cart,first,900,1000,"v"))
        assertNull(p.preview(cart,2000))
        assertTrue(p.record(cart,second,200,1200,"v"))
        val result=p.preview(cart,2000)!!
        assertEquals(3100L,result.itemSubtotal)
        assertEquals(cart.fingerprint,result.cartFingerprint)
        assertEquals(1000L,result.capturedAtMillis)
        assertNull(result.finalTotal)
    }
    @Test fun changedRequestOrVersionCannotMixWithEarlierLinePrices() {
        for(change in listOf(cart.copy(restaurant="آخر"),cart.copy(items=listOf(first.copy(quantity=4),second)),cart.copy(addressReference="different"))) {
            val p=plan();assertTrue(p.record(cart,first,900,1000,"v"))
            assertFalse(p.record(change,second,200,1200,"v"));assertNull(p.preview(cart,2000))
        }
        val p=plan();assertTrue(p.record(cart,first,900,1000,"v"))
        assertFalse(p.record(cart,second,200,1200,"other"));assertNull(p.preview(cart,2000))
    }
    @Test fun unreadZeroNegativeConflictingAndStaleAmountsStayDistinct() {
        val unknown=plan();assertFalse(unknown.record(cart,first,null,1000,"v"));assertNull(unknown.preview(cart,2000))
        val negative=plan();assertFalse(negative.record(cart,first,-1,1000,"v"))
        val p=plan();assertTrue(p.record(cart,first,0,1000,"v"));assertTrue(p.record(cart,second,200,1200,"v"))
        assertEquals(400L,p.preview(cart,2000)!!.itemSubtotal)
        assertNull(p.preview(cart,301001));assertNull(p.preview(cart,1100))
        assertFalse(p.record(cart,first,900,1300,"v"));assertNull(p.preview(cart,2000))
    }
    @Test fun duplicateCanonicalLinesAggregateButForeignOrChangedVariantsFail() {
        val repeated=cart.copy(items=listOf(first,first.copy(quantity=2),second))
        val p=PlainMenuReadPlan.create(repeated,ProviderId.HUNGERSTATION,"v")!!
        assertEquals(listOf(5,2),p.lines.map {it.quantity})
        assertTrue(p.record(repeated,p.lines[0],900,1000,"v"));assertTrue(p.record(repeated,p.lines[1],200,1000,"v"))
        assertEquals(4900L,p.preview(repeated,2000)!!.itemSubtotal)
        val foreign=plan();assertFalse(foreign.record(cart,second.copy(name="ماء كبير"),200,1000,"v"))
        val wrongQty=plan();assertFalse(wrongQty.record(cart,first.copy(quantity=1),900,1000,"v"))
    }
    @Test fun overflowOptionsSizesAndNotesCannotProduceAnIncompletePrice() {
        val p=plan();assertTrue(p.record(cart,first,Long.MAX_VALUE,1000,"v"));assertTrue(p.record(cart,second,200,1000,"v"))
        assertNull(p.preview(cart,2000))
        for(item in listOf(first.copy(size="كبير"),first.copy(note="بدون ملح"),first.copy(options=listOf(CartOption("إضافة","جبن")))))
            assertNull(PlainMenuReadPlan.create(cart.copy(items=listOf(item,second)),ProviderId.KEETA,"v"))
    }
}
