package app.octopus.local

import app.octopus.local.providers.*
import app.octopus.local.domain.*
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class HungerAlbaikOptionsTest {
    @Test fun burgerFreePickleChoicesDoNotCrashOrLoseTheTwoChoiceGroup() {
        val frames=Gson().fromJson(javaClass.getResource("/recorded-hs/hs-8.0.303-albaik-burger-options-redacted.json")!!.readText().removePrefix("\uFEFF"),Array<CatalogFrame>::class.java)
        val reader=HungerStationProductReader(LiveRestaurant("البيك",null,"البيك"),LiveMenuItem("برجر فيليه دجاج حرّاق",700,"burger"))
        frames.forEach(reader::observe)
        val result=reader.finish(1000)
        assertTrue(result.optionsComplete)
        assertEquals(listOf(1,2,1),result.product.groups.map {it.maximum})
        assertEquals(setOf("بدون مخلل حار","بدون مخلل"),result.product.groups[1].choices.map{it.title}.toSet())
        assertTrue(HungerAlbaikPlainOptions.supports(result))
        assertTrue(HungerAlbaikPlainOptions.verified(frames.last(),result,1100))
        val selected=frames.last().copy(nodes=frames.last().nodes.map {if(it.label=="بدون صلصة")it.copy(className="android.widget.ImageView") else it})
        assertFalse(HungerAlbaikPlainOptions.verified(selected,result,1100))
    }
    private fun pages(): Array<CatalogFrame> = Gson().fromJson(javaClass.getResource(
        "/recorded-hs/hs-8.0.303-albaik-bigbaik-options-redacted.json")!!.readText(),Array<CatalogFrame>::class.java)
    private fun reader()=HungerStationProductReader(LiveRestaurant("البيك",null,"البيك"),LiveMenuItem("بيج بيك",1750,"label:بيج بيك"))
    @Test fun realThreePageOptionalGroupsKeepFreeChoicesAndTheirLimits() {
        val reader=reader();pages().forEach(reader::observe)
        val result=reader.finish(1000)
        assertTrue(result.optionsComplete)
        assertEquals(3,result.product.groups.size)
        assertTrue(result.product.groups.all {it.minimum==0})
        assertEquals(listOf(1,2,1),result.product.groups.map {it.maximum})
        assertEquals(setOf("مع الجبنة","بدون مخلل","بدون سلطة","بدون صلصة"),result.product.groups.flatMap {it.choices}.map {it.title}.toSet())
        assertTrue(MenuSelectionValidator().selectConfigured(result,ProductSelection(result.product.id,emptyMap(),1),1100) is SelectionResult.Accepted)
    }
    @Test fun finalFrameProvesEveryFreeAndPaidChoiceUncheckedWithoutUsingPriceAsState() {
        val r=reader();val frames=pages();frames.forEach(r::observe);val details=r.finish(1000)
        assertTrue(HungerAlbaikPlainOptions.supports(details))
        assertTrue(HungerAlbaikPlainOptions.verified(frames.last(),details,1100))
        assertTrue(app.octopus.local.automation.RidanCartProgram.supports(details,ProductSelection(details.product.id,emptyMap(),1),1100))
        for ((name,type) in listOf("بدون صلصة" to "android.widget.ImageView", "مع الجبنة" to "android.view.View")) {
            val selected=frames.last().copy(nodes=frames.last().nodes.map {if(it.label.lineSequence().first()==name)it.copy(className=type) else it})
            assertFalse(HungerAlbaikPlainOptions.verified(selected,details,1100))
        }
        assertFalse(HungerAlbaikPlainOptions.verified(frames.last(),details,121001))
    }
    @Test fun missingEndOrUnknownClickableChoiceCannotMakeAnIncompleteProductReady() {
        val reader=reader();pages().dropLast(1).forEach(reader::observe)
        assertFalse(reader.finish(1000).optionsComplete)
        val changed=reader()
        pages().forEach { frame -> changed.observe(frame.copy(nodes=frame.nodes.map {
            if(it.label=="بدون صلصة")it.copy(label="خيار غير مدعوم") else it
        })) }
        assertFalse(changed.finish(1000).optionsComplete)
    }
}
