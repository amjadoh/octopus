package app.octopus.local

import app.octopus.local.automation.*
import app.octopus.local.domain.*
import app.octopus.local.providers.*
import org.junit.Assert.*
import org.junit.Test

class HungerStationOwnedQuantityCommandTest {
    @Test fun cartPlusMayOpenAnExactVariantButCanAuthorizeOnlyOneFurtherQuantityStep() {
        val original=com.google.gson.Gson().fromJson(javaClass.getResource("/recorded-hs/hs-8.0.303-burger-existing-variant.json")!!.readText().removePrefix("\uFEFF"),CatalogFrame::class.java)
        val sheet=original.copy(nodes=original.nodes.map{it.copy(label=it.label.replace("برجر فيليه دجاج حرّاق","جبنة الناتشوز").replace("§\n7","§\n13"))})
        val c=command()
        assertFalse(c.pendingVariant(sheet,request(),version,1001))
        assertTrue(c.consume(frame(),c.target,request(),version,1001))
        assertTrue(c.pendingVariant(sheet,request(),version,1002))
        val change=checkNotNull(c.issueVariant(sheet,request(),version,1003))
        assertNull(c.issueVariant(sheet,request(),version,1004))
        assertEquals(2,change.expectedAfter)
        assertTrue(change.consume(sheet,change.target,version,1004))
        assertTrue(c.consumeVariantContinuation(sheet,change,request(),version,1004))
        assertFalse(c.consumeVariantContinuation(sheet,change,request(),version,1005))
        assertFalse(c.updatedVariant(sheet,request(),version,1005))
        val updated=sheet.copy(nodes=sheet.nodes.map{if(it.id=="menuItemActionDualButtonValue")it.copy(label="2") else it})
        assertTrue(c.updatedVariant(updated,request(),version,1006))
        assertFalse(c.updatedVariant(updated,request(3),version,1006))
        assertTrue(c.afterMatches(frame(2),request(),version,1010))
        assertTrue(c.consumeReturn(frame(2),request(),version,1011))
        assertFalse(c.updatedVariant(updated,request(),version,1012))
    }
    private val version=HungerStationCatalog.TESTED_VERSION
    private fun request(q:Int=2)=CanonicalCart("كودو",null,listOf(
        CanonicalItem("بطاطس وسط",null,emptyList(),3),CanonicalItem("جبنة الناتشوز",null,emptyList(),q)))
    private fun frame(q:Int=1,fries:Int=3):CatalogFrame {
        fun n(i:Int,p:Int,id:String,label:String="",cls:String="android.view.View",click:Boolean=false)=CatalogNode(i,p,id,label,cls,click,false,false)
        val nodes=mutableListOf(n(0,-1,"FlutterCart_title","السلة\nكودو"),
            n(1,-1,"FlutterCart_scroll_view","المجموع\n${9*fries+13*q}\n§"))
        listOf(Triple("بطاطس وسط",fries,9),Triple("جبنة الناتشوز",q,13)).forEachIndexed {i,(name,qty,unit)->
            val row=10+i*10
            nodes+=listOf(n(row,1,"FlutterCart_cartItem","$name\n${qty*unit}\n§"),
                n(row+1,row,"menuItemActionDualButtonValue","$qty"),
                n(row+2,row+1,"menuItemActionDualButtonAdd",cls="android.widget.Button"),
                n(row+3,row+2,"",cls="android.widget.ImageView",click=true),
                n(row+4,row+1,"menuItemActionDualButtonSubtract",cls="android.widget.Button"),
                n(row+5,row+4,"",cls="android.widget.ImageView",click=true))
        }
        return CatalogFrame(nodes,true)
    }
    private fun evidence(f:CatalogFrame)=BasketEvidenceReader.hungerStation(f,true)
    private fun command(f:CatalogFrame=frame(),r:CanonicalCart=request()):HungerStationOwnedQuantityCommand {
        val before=evidence(f);val owner=PlainBasketPreparationSession.create(r,before,version)!!
        val edit=(owner.next(r,before,version,1000) as PlainBasketPreparationSession.Step.Change).edit
        return HungerStationOwnedQuantityCommand.create(f,before,r,owner,edit,version,1000)!!
    }
    @Test fun onlyRequestedRowChangesAndReturnIsSingleUse() {
        val c=command();assertEquals(23,c.target.index)
        assertTrue(c.consume(frame(),c.target,request(),version,1001))
        assertFalse(c.consume(frame(),c.target,request(),version,1002))
        assertTrue(c.afterMatches(frame(2),request(),version,1100))
        assertFalse(c.afterMatches(frame(2,4),request(),version,1100))
        assertFalse(c.afterMatches(frame(3),request(),version,1100))
        assertTrue(c.consumeReturn(frame(2),request(),version,1200))
        assertFalse(c.consumeReturn(frame(2),request(),version,1201))
    }
    @Test fun decreaseNeverRemovesLastUnit() {
        val r=request(1);val f=frame(2);val c=command(f,r)
        assertEquals(25,c.target.index)
        assertTrue(c.consume(f,c.target,r,version,1001))
        assertTrue(c.afterMatches(frame(1),r,version,1100))
        assertFalse(c.afterMatches(frame(0),r,version,1100))
    }
    @Test fun cannotDispatchAgainstChangedFrameRequestVersionOrExpiredReceipt() {
        val c=command()
        assertFalse(c.consume(frame(2),c.target,request(),version,1001))
        assertFalse(c.consume(frame(),c.target,request(3),version,1001))
        assertFalse(c.consume(frame(),c.target,request(),"other",1001))
        assertFalse(c.consume(frame(),c.target,request(),version,31001))
        assertFalse(c.afterMatches(frame(2),request(),version,1100))
    }
    @Test fun wrongSumUnknownExtrasAndPartialFrameCannotAcknowledgeOrReturn() {
        val c=command();assertTrue(c.consume(frame(),c.target,request(),version,1001))
        val after=frame(2)
        assertFalse(c.afterMatches(after.copy(complete=false),request(),version,1100))
        assertFalse(c.afterMatches(after.copy(nodes=after.nodes.map {if(it.id=="FlutterCart_scroll_view")it.copy(label="المجموع\n99\n§") else it}),request(),version,1100))
        assertFalse(c.afterMatches(after.copy(nodes=after.nodes+after.nodes.first {it.id=="FlutterCart_cartItem"}.copy(index=60)),request(),version,1100))
        assertFalse(c.consumeReturn(after,request(),version,31001))
    }
    @Test fun incompleteBaselineOrUnknownModifierCannotAuthorizeQuantity() {
        val r=request();val f=frame();val b=evidence(f);val owner=PlainBasketPreparationSession.create(r,b,version)!!
        val edit=(owner.next(r,b,version,1000) as PlainBasketPreparationSession.Step.Change).edit
        assertNull(HungerStationOwnedQuantityCommand.create(f,b.copy(complete=false),r,owner,edit,version,1000))
        val modified=f.copy(nodes=f.nodes.map {if(it.index==20)it.copy(label="جبنة الناتشوز\nملح\nتعديل\n13\n§") else it})
        assertNull(HungerStationOwnedQuantityCommand.create(modified,evidence(modified),r,owner,edit,version,1000))
    }
}
