package app.octopus.local
import app.octopus.local.automation.*
import app.octopus.local.providers.*
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
class HungerStationExistingVariantTest {
    @Test fun currentBurgerSheetCanBeReadBeforeClosingWithoutAnotherAdd() {
        val current=Gson().fromJson(javaClass.getResource("/recorded-hs/hs-8.0.303-burger-existing-variant.json")!!.readText().removePrefix("\uFEFF"),CatalogFrame::class.java)
        val read=checkNotNull(HungerStationExistingVariant.read(current,"برجر فيليه دجاج حرّاق"))
        assertEquals(1,read.quantity)
        assertEquals(700L,read.unit)
        val two=current.copy(nodes=current.nodes.map {if(it.id=="menuItemActionDualButtonValue")it.copy(label="2") else it.copy(label=it.label.replace("§\n7","§\n14"))})
        assertEquals(2,HungerStationExistingVariant.read(two,"برجر فيليه دجاج حرّاق")!!.quantity)
        assertEquals(700L,HungerStationExistingVariant.read(two,"برجر فيليه دجاج حرّاق")!!.unit)
    }
    private val title="فطيرة جبن فيتا"
    private val frame=Gson().fromJson(javaClass.getResource("/recorded-hs-existing-variant.json")!!.readText(),CatalogFrame::class.java)
    @Test fun actualExistingPlainVariantCanChangeOnlyOneQuantityStep() {
        val read=checkNotNull(HungerStationExistingVariant.read(frame,title))
        assertEquals(1,read.quantity);assertEquals(700L,read.unit)
        val command=checkNotNull(HungerStationVariantQuantityCommand.next(frame,title,700,3,HungerStationCatalog.TESTED_VERSION,1000))
        assertEquals(2,command.expectedAfter)
        assertTrue(command.target.label.isEmpty())
        assertTrue(command.consume(frame,command.target,HungerStationCatalog.TESTED_VERSION,1001))
        assertFalse(command.consume(frame,command.target,HungerStationCatalog.TESTED_VERSION,1002))
        assertNotEquals("صنف جديد للتعديل",command.target.label)
    }
    @Test fun additionsWrongFoodWrongPriceUnknownVersionAndExpiredCommandAreRejected() {
        assertNull(HungerStationExistingVariant.read(frame,"آخر"))
        val changed=frame.copy(nodes=frame.nodes.map{it.copy(label=it.label.replace("لايوجد تعديلات أو إضافات","شيبس"))})
        assertNull(HungerStationExistingVariant.read(changed,title))
        assertNull(HungerStationVariantQuantityCommand.next(frame,title,800,3,HungerStationCatalog.TESTED_VERSION,1000))
        assertNull(HungerStationVariantQuantityCommand.next(frame,title,700,3,"unknown",1000))
        val command=checkNotNull(HungerStationVariantQuantityCommand.next(frame,title,700,3,HungerStationCatalog.TESTED_VERSION,1000))
        assertFalse(command.consume(frame,command.target,HungerStationCatalog.TESTED_VERSION,16001))
        assertNull(HungerStationExistingVariant.target(frame,title,false))
    }
}
