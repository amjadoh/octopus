package app.octopus.local

import app.octopus.local.automation.HungerStationMenuQuantityCommand
import app.octopus.local.providers.*
import org.junit.Assert.*
import org.junit.Test

/** Structured regression scenarios, not a claim that a provider was run on a device. */
class HungerStationMenuQuantityCommandTest {
    private val restaurant = "مدور"
    private val title = "فطيرة جبن فيتا"
    private val version = HungerStationCatalog.TESTED_VERSION
    private fun frame(count: Int, footerEnabled: Boolean = count >= 3): CatalogFrame {
        fun n(i: Int, p: Int, id: String = "", label: String = "", cls: String = "android.view.View",
              click: Boolean = false, enabled: Boolean = true) =
            CatalogNode(i,p,id,label,cls,click,false,false,enabled)
        return CatalogFrame(listOf(
            n(0,-1,label="$restaurant\nالحد الأدنى للطلب\n§\n15"),
            n(1,0,"menuBackButton",cls="android.widget.ImageView",click=true),
            n(2,0,"menuSearchButton",cls="android.widget.ImageView",click=true),
            n(3,-1,label="$title\n§\n7",click=true),
            n(4,3,"menuItemActionDualButtonValue","$count"),
            n(5,4,"menuItemActionDualButtonSubtract",cls="android.widget.Button"),
            n(6,5,cls="android.widget.ImageView",click=true),
            n(7,4,"menuItemActionDualButtonAdd",cls="android.widget.Button"),
            n(8,7,cls="android.widget.ImageView",click=true),
            n(9,-1,"FlutterMenu_goToCartButton"),
            n(10,9,label="عرض السلة\n${count * 7}\n§",cls="android.widget.Button",click=true,enabled=footerEnabled),
            n(11,10,"FlutterMenu_goToCartQuantity","$count"),
        ),true)
    }
    private fun next(frame: CatalogFrame, desired: Int = 3, actualVersion: String? = version, matchingQuantityAuthorized: Boolean = true) =
        HungerStationMenuQuantityCommand.next(frame,restaurant,title,700,desired,actualVersion,matchingQuantityAuthorized,1000)
    private fun changed(frame: CatalogFrame, index: Int, transform: (CatalogNode)->CatalogNode) =
        frame.copy(nodes=frame.nodes.map { if(it.index == index) transform(it) else it })

    @Test fun actualHonorMenuFrameHasTheSelectedFetaStepperAndMatchingFooter() {
        val recorded=com.google.gson.Gson().fromJson(javaClass.getResource("/recorded-hs-menu-feta-quantity.json")!!.readText(),CatalogFrame::class.java)
        assertEquals(1,HungerStationMenuQuantityCommand.observedQuantity(recorded,"مدور","فطيرة جبن فيتا",700))
        val command=checkNotNull(HungerStationMenuQuantityCommand.next(recorded,"مدور","فطيرة جبن فيتا",700,3,version,true,1000))
        assertTrue(command.target.clickable)
        assertEquals("android.widget.ImageView",command.target.className)
        assertEquals(2,command.expectedAfter)
        assertTrue(command.consume(recorded,command.target,version,1001))
        assertFalse(command.consume(recorded,command.target,version,1002))
    }
    @Test fun adjustsOneToThreeBeforeOpeningTheDisabledBasket() {
        var frame = frame(1)
        val first = checkNotNull(next(frame))
        assertFalse(frame.nodes.single { it.index==10 }.enabled)
        assertEquals(8,first.target.index)
        assertEquals(2,first.expectedAfter)
        assertTrue(first.consume(frame,first.target,version,1001))
        assertFalse(first.consume(frame,first.target,version,1002))
        frame = frame(2)
        val second = checkNotNull(next(frame))
        assertEquals(3,second.expectedAfter)
        assertTrue(second.consume(frame,second.target,version,1003))
        assertEquals(3,HungerStationMenuQuantityCommand.observedQuantity(frame(3),restaurant,title,700))
        assertNull(next(frame(3)))
    }

    @Test fun neverAddsBeyondUserQuantityToSatisfyMinimumOrder() {
        assertNull(next(frame(1,false),desired=1))
        assertNull(next(frame(3,false),desired=3))
        val decrease = checkNotNull(next(frame(4),desired=3))
        assertEquals(6,decrease.target.index)
        assertEquals(3,decrease.expectedAfter)
        assertNull(next(frame(1),desired=0))
        assertNull(next(frame(99),desired=100))
    }

    @Test fun rejectsStaleStateExpiredCommandAndUnknownVersion() {
        val command=checkNotNull(next(frame(1)))
        assertFalse(command.consume(frame(2),command.target,version,1001))
        assertFalse(command.consume(frame(1),command.target,version,999))
        assertFalse(command.consume(frame(1),command.target,version,16001))
        assertFalse(command.consume(frame(1),command.target,"8.9.999",1001))
        assertFalse(command.consume(frame(1),command.target.copy(parent=5),version,1001))
        assertTrue(command.consume(frame(1),command.target,version,1001))
        assertNull(next(frame(1),actualVersion=null))
    }

    @Test fun rejectsChipsOrOtherOffscreenItemsThroughFooterCountAndAmount() {
        val original=frame(3)
        assertNull(next(changed(original,10) { it.copy(label="عرض السلة\n27\n§") },desired=4))
        assertNull(next(changed(original,11) { it.copy(label="4") },desired=4))
        assertNull(next(original.copy(nodes=original.nodes + original.nodes[4].copy(index=12,parent=-1)),desired=4))
    }

    @Test fun rejectsWrongRestaurantItemPriceOrIncompleteFrame() {
        val original=frame(1)
        assertNull(next(original,matchingQuantityAuthorized=false))
        assertNull(next(original.copy(complete=false)))
        assertNull(next(changed(original,0) { it.copy(label="مطعم آخر") }))
        assertNull(next(changed(original,3) { it.copy(label="فطيرة جبن آخر\n§\n7") }))
        assertNull(next(changed(original,3) { it.copy(label="$title\n§\n8") }))
        assertNull(next(original.copy(nodes=original.nodes+original.nodes[3].copy(index=12))))
    }

    @Test fun requiresObservedNamedControlAndNeverGuessesByPosition() {
        val original=frame(1)
        assertNull(next(changed(original,7) { it.copy(id="") }))
        assertNull(next(changed(original,7) { it.copy(enabled=false) }))
        assertNull(next(changed(original,8) { it.copy(clickable=false) }))
        assertNull(next(changed(original,8) { it.copy(parent=5) }))
        assertNull(next(changed(original,8) { it.copy(label="تأكيد الطلب") }))
        assertNull(next(changed(original,8) { it.copy(editable=true) }))
        assertNull(next(original.copy(nodes=original.nodes+original.nodes[8].copy(index=12))))
    }

    @Test fun rejectsFractionalAndUnknownCountsAndAcceptsArabicDigits() {
        val original=frame(1)
        for (bad in listOf("1.9","١٫٩","0","100","?","1 ريال")) {
            assertNull(next(changed(original,4) { it.copy(label=bad) }))
            assertNull(next(changed(original,11) { it.copy(label=bad) }))
        }
        val arabic=changed(changed(original,4) { it.copy(label="١") },11) { it.copy(label="١") }
        assertNotNull(next(arabic))
    }
}
