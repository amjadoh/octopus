package app.octopus.local
import app.octopus.local.automation.HungerStationProductQuantityCommand
import app.octopus.local.automation.RidanCartProgram
import app.octopus.local.domain.ProductSelection
import app.octopus.local.providers.*
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
class HungerStationProductQuantityCommandTest {
    private val title="فطيرة جبن فيتا"
    private val original=Gson().fromJson(javaClass.getResource("/recorded-hs-product-quantity.json")!!.readText(),CatalogFrame::class.java)
    private fun frame(count:Int)=original.copy(nodes=original.nodes.map {
        when {it.index==9 -> it.copy(label="$count")
            it.id=="flutterAddButton" -> it.copy(label="إضافة\n§\n${count*7}")
            else -> it}
    })
    @Test fun recordedPdpTargetsOnlyStepperAndChecksAmountAfterEveryStep() {
        val one=checkNotNull(HungerStationProductQuantityCommand.next(original,title,700,3,HungerStationCatalog.TESTED_VERSION,1000))
        assertEquals("quantityStepperIncrease",one.target.id)
        assertEquals(2,one.expectedAfter)
        assertTrue(one.consume(original,one.target,HungerStationCatalog.TESTED_VERSION,1001))
        assertFalse(one.consume(original,one.target,HungerStationCatalog.TESTED_VERSION,1002))
        val two=checkNotNull(HungerStationProductQuantityCommand.next(frame(2),title,700,3,HungerStationCatalog.TESTED_VERSION,1000))
        assertEquals(3,two.expectedAfter)
        assertNull(HungerStationProductQuantityCommand.next(frame(3),title,700,3,HungerStationCatalog.TESTED_VERSION,1000))
    }
    @Test fun wrongAmountUnknownVersionChangedTitleAndExpiredCommandAreRejected() {
        assertNull(HungerStationProductQuantityCommand.next(frame(2),title,800,3,HungerStationCatalog.TESTED_VERSION,1000))
        assertNull(HungerStationProductQuantityCommand.next(original,title,700,3,"unknown",1000))
        assertNull(HungerStationProductQuantityCommand.next(original,"آخر",700,3,HungerStationCatalog.TESTED_VERSION,1000))
        val command=checkNotNull(HungerStationProductQuantityCommand.next(original,title,700,3,HungerStationCatalog.TESTED_VERSION,1000))
        assertFalse(command.consume(original,command.target,HungerStationCatalog.TESTED_VERSION,16001))
        assertFalse(command.consume(frame(2),command.target,HungerStationCatalog.TESTED_VERSION,1001))
    }
    @Test fun addPermitRequiresExactRequestedQuantityAndRemainsSingleUse() {
        val reader=HungerStationProductReader(LiveRestaurant("مدور",null,"مدور"),LiveMenuItem(title,700,"feta"))
        reader.observe(original)
        val details=reader.finish(1000)
        val menu=CatalogFrame(listOf(CatalogNode(0,-1,"menuBackButton","","android.view.View",false,false,false),
            CatalogNode(1,-1,"menuSearchButton","","android.view.View",false,false,false),
            CatalogNode(2,-1,"","مدور","android.view.View",false,false,false)),true)
        val permit=checkNotNull(RidanCartProgram.create(details,ProductSelection("feta",emptyMap(),1),menu,1000,3))
        assertFalse(permit.consumeAdd(original,original.nodes.single{it.id=="flutterAddButton"},1001))
        val three=frame(3)
        assertTrue(permit.consumeAdd(three,three.nodes.single{it.id=="flutterAddButton"},1002))
        assertFalse(permit.consumeAdd(three,three.nodes.single{it.id=="flutterAddButton"},1003))
    }
}
