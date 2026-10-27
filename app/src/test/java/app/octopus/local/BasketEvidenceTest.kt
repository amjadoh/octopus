package app.octopus.local

import app.octopus.local.domain.*
import app.octopus.local.providers.*
import org.junit.Assert.*
import org.junit.Test

/** Replays the previously recorded cart shapes. These are not new on-device integration results. */
class BasketEvidenceTest {
    private fun node(i:Int,parent:Int,id:String="",text:String="",click:Boolean=false,cls:String="android.view.View") =
        CatalogNode(i,parent,id,text,cls,click,false,false)
    private val desired=CanonicalCart("المضياف العربي",null,listOf(CanonicalItem("دجاج مظبي","نص",listOf(CartOption("إضافات","رز بشاور")),1)))
    private fun hs(qty:String="1",options:String="1x رز بشاور, نص") = CatalogFrame(listOf(
        node(0,-1,"FlutterCart_title","السلة\nالمضياف العربي"),
        node(1,-1,"FlutterCart_scroll_view","ملخص الطلب\nالمجموع\n36.00\n§"),
        node(2,1,"FlutterCart_cartItem","دجاج مظبي\n$options\nتعديل\n36\n§",true),
        node(3,2,"menuItemActionDualButtonValue",qty),
        node(4,1,text="سلطة حار\n3\n§"),
        node(5,-1,text="اذهب للدفع",click=true)),true)
    private fun keeta() = CatalogFrame(listOf(
        node(0,-1,cls="android.view.ViewGroup"),
        node(1,0,cls="android.view.ViewGroup"),
        node(2,1,text="الأصناف المضافة",cls="android.widget.TextView"),
        node(3,1,text="تفريغ السلة",cls="android.widget.TextView"),
        node(4,0,cls="android.widget.FrameLayout"),
        node(5,4,cls="android.view.ViewGroup"),
        node(6,5,cls="android.view.ViewGroup"),
        node(7,6,text="دجاج مظبي",cls="android.widget.TextView"),
        node(8,6,text="نص، رز بشاور",cls="android.widget.TextView"),
        node(9,5,cls="android.view.ViewGroup"),
        node(10,9,text="‏36.00 ﷼‏",cls="android.widget.TextView"),
        node(11,9,text="١",click=true,cls="android.widget.TextView"),
        // Menu recommendations and footer amounts outside the cart pane must not become added items.
        node(12,-1,text="سندوتش كباب دجاج",cls="android.widget.TextView"),
        node(13,-1,text="12.00 ﷼",cls="android.widget.TextView"),
        node(14,-1,text="16 ﷼ رسوم توصيل",cls="android.widget.TextView")),true)

    @Test fun hungerCartMergedLabelsPreserveOptionsQuantityAndSubtotalOnly() {
        val read=BasketEvidenceReader.hungerStation(hs(),true)
        assertTrue(read.complete)
        assertEquals(3600L,read.subtotal)
        assertEquals(1,read.lines.single().quantity)
        assertEquals(listOf("رز بشاور","نص"),read.lines.single().choices.map { it.value })
        assertFalse(read.recognizedOrderReview)
        assertTrue(BasketItemMatcher.compare(desired,read).matches)
    }
    @Test fun parsedHungerSizeAndRiceReachLedgerOnlyWithCompleteMatchingEvidence() {
        val read=BasketEvidenceReader.hungerStation(hs(),true)
        val record=PreparedBasketRecord.create(desired,read,"recorded-shape",1000)
        assertNotNull(record)
        assertNull(PreparedBasketRecord.create(desired,BasketEvidenceReader.hungerStation(hs(),false),"recorded-shape",1000))
        assertNull(PreparedBasketRecord.create(desired,BasketEvidenceReader.hungerStation(hs(options="1x رز شعبي, نص"),true),"recorded-shape",1000))
    }
    @Test fun keetaRowAncestryExcludesMenuPricesAndPreservesUnknownOptionCount() {
        val read=BasketEvidenceReader.keeta(keeta(),"المضياف العربي",true)
        assertTrue(read.complete)
        assertEquals(1,read.lines.size)
        assertEquals(3600L,read.lines.single().lineAmount)
        assertEquals(1,read.lines.single().quantity)
        assertNull(read.subtotal)
        assertFalse(read.recognizedOrderReview)
        assertTrue(BasketItemMismatch.OPTION_QUANTITY_UNKNOWN in BasketItemMatcher.compare(desired,read).issues)
    }
    @Test fun partialScanUnknownQuantityAndMalformedCartNeverBecomeComplete() {
        assertFalse(BasketEvidenceReader.hungerStation(hs(),false).complete)
        assertFalse(BasketEvidenceReader.hungerStation(hs("?"),true).complete)
        assertFalse(BasketEvidenceReader.hungerStation(hs().copy(complete=false),true).complete)
        assertFalse(BasketEvidenceReader.keeta(keeta(),null,true).complete)
        assertFalse(BasketEvidenceReader.keeta(keeta().copy(nodes=keeta().nodes.filterNot { it.index==3 }),"المضياف العربي",true).complete)
    }
    @Test fun wrongRiceSizeOrItemQuantityCannotMatchTheDesiredOrder() {
        assertTrue(BasketItemMismatch.OPTIONS in BasketItemMatcher.compare(desired,BasketEvidenceReader.hungerStation(hs(options="1x رز شعبي, نص"),true)).issues)
        assertTrue(BasketItemMismatch.QUANTITY in BasketItemMatcher.compare(desired,BasketEvidenceReader.hungerStation(hs(qty="2"),true)).issues)
        assertTrue(BasketItemMismatch.OPTIONS in BasketItemMatcher.compare(desired.copy(items=desired.items.map { it.copy(size="كامل") }),BasketEvidenceReader.hungerStation(hs(),true)).issues)
    }
    @Test fun unknownNotesAndUnrelatedItemsPreventExactMatching() {
        val read=BasketEvidenceReader.hungerStation(hs(),true)
        assertTrue(BasketItemMismatch.NOTES_UNKNOWN in BasketItemMatcher.compare(desired.copy(items=desired.items.map { it.copy(note="بدون فلفل") }),read).issues)
        val extra=read.copy(lines=read.lines+BasketLineEvidence("ماء",emptyList(),1,100))
        assertTrue(BasketItemMismatch.ITEM in BasketItemMatcher.compare(desired,extra).issues)
    }
    @Test fun identicalRowsAreAmbiguousInsteadOfSilentlyDroppingDuplicates() {
        val read=BasketEvidenceReader.hungerStation(hs(),true)
        assertTrue(BasketItemMismatch.AMBIGUOUS in BasketItemMatcher.compare(desired,read.copy(lines=read.lines+read.lines)).issues)
    }
}
