package app.octopus.local

import app.octopus.local.catalog.AreaEntry
import app.octopus.local.catalog.AreaPickerResolution
import app.octopus.local.catalog.CatalogArea
import org.junit.Assert.*
import org.junit.Test

class AreaPickerResolutionTest {
    private val cities = listOf(AreaEntry("مكة-المكرمة"), AreaEntry("جدة"))
    private val districts = listOf(AreaEntry("أم-الجود"), AreaEntry("ولي-العهد"))

    @Test fun gpsSpellingUsesPublishedCityAndDistrictSlugs() {
        val resolved = AreaPickerResolution.resolve(cities, districts, "مكه المكرمه", "ام الجود")

        assertEquals(AreaEntry("مكة-المكرمة"), resolved.city)
        assertEquals(AreaEntry("أم-الجود"), resolved.district)
        assertEquals(CatalogArea("مكة-المكرمة", "أم-الجود"), resolved.area)
        assertNull(resolved.message)
    }

    @Test fun unmatchedGpsDistrictExplainsWhySaveCannotUseTheDisplayedText() {
        val resolved = AreaPickerResolution.resolve(cities, districts, "مكة المكرمة", "حي غير موجود بالدليل")

        assertEquals(AreaEntry("مكة-المكرمة"), resolved.city)
        assertNull(resolved.district)
        assertNull(resolved.area)
        assertFalse(resolved.message.isNullOrBlank())
    }

    @Test fun ambiguousNormalizedDistrictDoesNotPickAnArbitraryPublishedSlug() {
        val ambiguous = listOf(AreaEntry("أم-الجود"), AreaEntry("ام-الجود"))
        val resolved = AreaPickerResolution.resolve(cities, ambiguous, "مكة المكرمة", "أم الجود")

        assertNotNull(resolved.city)
        assertNull(resolved.district)
        assertNull(resolved.area)
        assertFalse(resolved.message.isNullOrBlank())
    }

    @Test fun missingDistrictOrUnloadedDirectoryCannotCreateAnArea() {
        listOf(
            AreaPickerResolution.resolve(cities, districts, "مكة المكرمة", " "),
            AreaPickerResolution.resolve(cities, emptyList(), "مكة المكرمة", "أم الجود")
        ).forEach { resolved ->
            assertNotNull(resolved.city)
            assertNull(resolved.district)
            assertNull(resolved.area)
            assertFalse(resolved.message.isNullOrBlank())
        }
    }

    @Test fun unrecognizedCityCannotReuseAMatchingDistrictFromThePreviousCity() {
        val resolved = AreaPickerResolution.resolve(cities, districts, "مدينة غير معروفة", "أم الجود")

        assertNull(resolved.city)
        assertNull(resolved.district)
        assertNull(resolved.area)
        assertFalse(resolved.message.isNullOrBlank())
    }
}
