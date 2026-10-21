package app.octopus.local

import app.octopus.local.automation.*
import app.octopus.local.providers.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Replays observed option grammar; these are local regression tests, not live provider runs. */
class HungerStationProductReadSessionTest {
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
