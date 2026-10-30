package app.octopus.local

import app.octopus.local.automation.*
import app.octopus.local.providers.*
import app.octopus.local.domain.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Replays observed option grammar; these are local regression tests, not live provider runs. */
class HungerStationProductReadSessionTest {
    @Test fun observedTawookOptionalRemovalsDoNotBlockTheUnmodifiedProduct() = runBlocking {
        val restaurant=LiveRestaurant("البيك",null,"البيك")
        val item=LiveMenuItem("طاووق",1050,"tawook")
        val labels=listOf("تمويه","طاووق","اختر من اضافات-بطاطس إلى طاووق","اختيار واحد","بدون بطاطس",
            "اختر من اضافات- من غير صوص إلى طاووق","اختيار واحد","بدون مخلل",
            "اختر من اضافات-صوص إلى طاووق","اختيار واحد","بدون صلصة ثوم","غالبًا ما يتم طلبه مع")
        val frame=CatalogFrame(listOf(CatalogNode(0,-1,"pdpCloseButton","","android.widget.ImageView",true,false,false))+
            labels.mapIndexed { i,label -> CatalogNode(i+1,-1,"",label,"android.view.View",label=="تمويه" || label.startsWith("بدون "),false,false) }+
            CatalogNode(labels.size+1,-1,"flutterAddButton","إضافة\n§\n10.50","android.widget.Button",true,false,false),true)
        val details=HungerStationProductReadSession().read(restaurant,item,{frame},{error("Options end is already visible")},{1000}).details
        assertTrue(details.optionsComplete)
        assertEquals(1050L,details.product.listedPriceHalala)
        assertEquals(3,details.product.groups.size)
        assertTrue(details.product.groups.all { it.minimum==0 })
        assertTrue(MenuSelectionValidator().selectConfigured(details,ProductSelection(details.product.id,emptyMap(),1),1000) is SelectionResult.Accepted)
    }
    private val restaurant = LiveRestaurant("مدور", null, "مدور")
    private val item = LiveMenuItem("فطيرة جبن فيتا", 700, "recorded-item")
    private fun page(end: Boolean, choice: Boolean = true): CatalogFrame {
        val labels = mutableListOf("تمويه" to true, item.name to false, "تنبيهات" to true,
            "يمكنك اضافة" to false, "اختيار واحد" to false)
        if (choice) labels += "بطاطس\n(§\n6\n+)" to true
        if (end) labels += "غالبًا ما يتم طلبه مع" to false
        return CatalogFrame(listOf(CatalogNode(0, -1, "pdpCloseButton", "", "android.widget.ImageView", true, false, false)) +
            labels.mapIndexed { i, (label, clickable) -> CatalogNode(i + 1, -1, "", label, "android.view.View", clickable, false, false) } +
            CatalogNode(labels.size + 1, -1, "flutterAddButton", "إضافة\n§\n7", "android.widget.Button", true, false, false), true)
    }
    @Test fun endMarkerStopsReadingBeforeAnyFurtherScrollOrRecommendationPage() = runBlocking {
        var frames = 0
        val result = HungerStationProductReadSession().read(restaurant, item,
            frame = { frames++; page(true) }, scroll = { error("Must not scroll past the options end") }, now = { 1000 })
        assertEquals(1, frames)
        assertTrue(result.details.optionsComplete)
        assertEquals(0, result.details.product.groups.single().minimum)
        assertEquals(700L, result.details.product.listedPriceHalala)
    }
    @Test fun readsTheFollowingPageOnlyWhenOptionsEndHasNotBeenObserved() = runBlocking {
        var frames = 0; var scrolls = 0
        val result = HungerStationProductReadSession().read(restaurant, item,
            frame = { page(++frames == 2) }, scroll = { scrolls++; true }, now = { 1000 })
        assertEquals(2, frames); assertEquals(1, scrolls)
        assertTrue(result.details.optionsComplete)
    }
    @Test fun noProgressOrPageLimitNeverInventsCompleteOptions() = runBlocking {
        var scrolls = 0
        val read = HungerStationProductReadSession().read(restaurant, item,
            frame = { page(false) }, scroll = { scrolls++; true }, now = { 1000 })
        assertEquals(1, scrolls); assertFalse(read.details.optionsComplete)
        val limited = HungerStationProductReadSession(1).read(restaurant, item,
            frame = { page(false) }, scroll = { error("No scroll beyond limit") }, now = { 1000 })
        assertFalse(limited.details.optionsComplete)
    }
    @Test fun cancellationBetweenCaptureAndScrollPreventsAnyAction() = runBlocking {
        var scrolls = 0
        val task = launch {
            HungerStationProductReadSession().read(restaurant, item,
                frame = { currentCoroutineContext().cancel(); page(false) },
                scroll = { scrolls++; true }, now = { 1000 })
        }
        task.join()
        assertTrue(task.isCancelled); assertEquals(0, scrolls)
    }
}
