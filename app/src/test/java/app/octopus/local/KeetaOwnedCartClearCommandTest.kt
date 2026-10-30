package app.octopus.local
import app.octopus.local.automation.*
import app.octopus.local.domain.*
import app.octopus.local.providers.*
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
class KeetaOwnedCartClearCommandTest {
    private val frame=Gson().fromJson(javaClass.getResource("/recorded-keeta/keeta-3.13.203-owned-clear-drawer-redacted.json")!!.readText(),CatalogFrame::class.java)
    private val request=CanonicalCart("كودو",null,listOf(CanonicalItem("بطاطس وسط",null,emptyList(),3),CanonicalItem("جبنة الناتشوز",null,emptyList(),2)))
    private val reader=KeetaBasketReader(KeetaMenuReader("كودو","بطاطس وسط","3.13.203"),request.items.map {it.name}.toSet())
    private val actual=reader.evidence(frame)!!
    private fun command()=KeetaOwnedCartClearCommand.create(frame,request,actual,"3.13.203",1000)!!
    @Test fun recordedHeaderIsBoundToExactOwnedCartAndSingleDispatch() {
        val c=command();assertEquals(234,c.target.index)
        assertTrue(c.consume(frame,c.target,request,"3.13.203",1001))
        assertFalse(c.consume(frame,c.target,request,"3.13.203",1002))
    }
    @Test fun partialForeignChangedRequestOrExpiredFrameCannotClear() {
        assertNull(KeetaOwnedCartClearCommand.create(frame.copy(complete=false),request,actual,"3.13.203",1000))
        assertNull(KeetaOwnedCartClearCommand.create(frame,request,actual.copy(complete=false),"3.13.203",1000))
        assertNull(KeetaOwnedCartClearCommand.create(frame,request.copy(restaurant="آخر"),actual,"3.13.203",1000))
        val c=command()
        assertFalse(c.consume(frame,c.target,request.copy(items=request.items.take(1)),"3.13.203",1001))
        assertFalse(c.consume(frame,c.target,request,"other",1001))
        assertFalse(c.consume(frame,c.target,request,"3.13.203",16001))
        assertFalse(c.consume(frame.copy(nodes=frame.nodes.dropLast(1)),c.target,request,"3.13.203",1001))
    }
    @Test fun checkoutOrUnknownHeaderCannotBeClearTarget() {
        val malformed=frame.copy(nodes=frame.nodes.map {if(it.index==236)it.copy(label="إتمام الدفع") else it})
        assertNull(KeetaOwnedCartClearCommand.create(malformed,request,actual,"3.13.203",1000))
        val editable=frame.copy(nodes=frame.nodes.map {if(it.index==234)it.copy(editable=true) else it})
        assertNull(KeetaOwnedCartClearCommand.create(editable,request,actual,"3.13.203",1000))
    }
}
