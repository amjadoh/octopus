package app.octopus.local

import app.octopus.local.providers.*
import org.junit.Assert.*
import org.junit.Test

class CatalogSourcesTest {
    @Test fun sameNameManualAndPublicEntriesHaveIndependentIdentities() {
        val manual = LiveRestaurant("مطعم", null, "مطعم")
        val public = manual.copy(origin = CatalogOrigin.PUBLIC)
        val rows = CatalogSources.restaurants(listOf(public), listOf(manual))
        assertEquals(2, rows.size)
        assertEquals(2, rows.map { it.catalogKey }.distinct().size)
    }
    @Test fun publicRefreshCannotRemoveAManualRestaurantMissingFromItsDirectory() {
        val manual = LiveRestaurant("مطعم محفوظ", null, "مطعم محفوظ")
        assertTrue(CatalogSources.restaurants(emptyList(), listOf(manual)).contains(manual))
        assertTrue(CatalogSources.restaurants(listOf(LiveRestaurant("عام", null, "عام", origin = CatalogOrigin.PUBLIC)),
            listOf(manual)).contains(manual))
    }
    @Test fun ambiguousBranchesInOneSourceAreNotSilentlySelected() {
        val first = LiveRestaurant("مطعم", "فرع أول", "مطعم")
        assertTrue(CatalogSources.restaurants(emptyList(), listOf(first, first.copy(description = "فرع آخر"))).isEmpty())
    }
    @Test fun endOfManualReadingDistinguishesNamesOnlyFromObservedMeals() {
        val restaurant = LiveRestaurant("مطعم", null, "مطعم")
        val summary = ManualCaptureSummary()
        summary.record(ManualCatalogUpdate(CatalogScreen.BROWSE, restaurants = listOf(restaurant)))
        assertTrue(summary.message.contains("لم تُقرأ قائمة وجبات"))
        val update = ManualCatalogUpdate(CatalogScreen.MENU, menu = LiveMenu(restaurant,
            listOf(LiveMenuItem("فطيرة", 700, "a")), 1000, false))
        summary.record(update); summary.record(update)
        assertTrue(summary.message.contains("1 وجبات"))
    }
}
