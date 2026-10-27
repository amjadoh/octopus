package app.octopus.local

import app.octopus.local.catalog.*
import org.junit.Assert.*
import org.junit.Test

class AreaDirectoryTest {
    @Test fun suggestionsAcceptArabicSpellingDifferencesWithoutChangingCanonicalSlugs() {
        val rows = listOf(AreaEntry("مكة-المكرمة"), AreaEntry("الرياض"), AreaEntry("جدة"))
        assertEquals("مكة-المكرمة", AreaNameSearch.rank(rows, "مكه المكرمه").first().slug)
        assertEquals("مكة-المكرمة", AreaNameSearch.exact(rows, "مكة")!!.slug)
        assertEquals("الرياض", AreaNameSearch.rank(rows, "الرياظ").first().slug)
        assertEquals("جدة", AreaNameSearch.exact(rows, "جده")!!.slug)
        assertNull(AreaNameSearch.exact(rows, "الرياظ"))
    }
    @Test fun districtPrefixAndDiacriticsAreOnlySearchNormalization() {
        val rows = listOf(AreaEntry("البحيرات"), AreaEntry("الروضة"))
        assertEquals("البحيرات", AreaNameSearch.rank(rows, "حي البُحيرات").first().slug)
    }
    @Test fun onlyCanonicalLinksForTheChosenCityAreAccepted() {
        val html = """<a href="/sa-ar/restaurants/regions/مكة-المكرمة/البحيرات">حي</a>
            <a href="https://hungerstation.com/sa-ar/restaurants/regions/الرياض/النرجس">آخر</a>
            <a href="https://evil.example/sa-ar/restaurants/regions/مكة-المكرمة/غلط">خارجي</a>
            <a href="/sa-ar/restaurants/regions/مكة-المكرمة/البحيرات?x=1">استعلام</a>
            <a href="/sa-ar/restaurants/regions/مكة-المكرمة/البحيرات/مطعم-123">مطعم</a>"""
        assertEquals(listOf(AreaEntry("البحيرات")), AreaDirectory.parse(html, AreaEntry("مكة-المكرمة")))
    }
    @Test fun observedCompoundDistrictKeepsParenthesesAndOffersGpsSubstringWithoutAutoSelecting() {
        // Public canonical link observed in the phone's cached directory on 2026-10-27.
        val slug = "الحمراء-وأم-الجود(الزايدي)"
        val html = """<a href="/sa-ar/restaurants/regions/مكة-المكرمة/$slug">الحي</a>"""
        val rows = AreaDirectory.parse(html, AreaEntry("مكة-المكرمة"))
        assertEquals(listOf(AreaEntry(slug)), rows)
        assertEquals(slug, AreaNameSearch.rank(rows, "أم الجود").first().slug)
        assertNull(AreaNameSearch.exact(rows, "أم الجود"))
        val area = CatalogArea("مكة-المكرمة", rows.single().slug)
        assertTrue(PublicCatalogUrls.allowed(area.url))
        assertEquals("/sa-ar/restaurants/regions/مكة-المكرمة/$slug", java.net.URI(area.url).path)
        listOf("a/b", "a\\b", "a%2fb", "a?b", "a#b", "../b").forEach { invalid ->
            assertTrue(runCatching { CatalogArea("مكة-المكرمة", invalid) }.isFailure)
        }
    }

    @Test fun indexNetworkPolicyDoesNotPermitRestaurantsCookiesOrExternalRedirects() {
        assertTrue(AreaDirectory.allowed(AreaDirectory.ROOT))
        assertTrue(AreaDirectory.allowed(AreaDirectory.ROOT + "/مكة-المكرمة"))
        listOf("http://hungerstation.com/sa-ar/restaurants/regions", AreaDirectory.ROOT + "/a/b",
            AreaDirectory.ROOT + "?page=1", "https://evil.example/sa-ar/restaurants/regions",
            AreaDirectory.ROOT + "/%252f", AreaDirectory.ROOT + "/../wallet").forEach {
            assertFalse(it, AreaDirectory.allowed(it))
        }
    }
    @Test fun fetchUsesProviderLinksInsteadOfGuessingAUrlFromTheSearchText() {
        val calls = mutableListOf<String>()
        val directory = AreaDirectory(client = PublicCatalogClient { url ->
            calls += url
            if (url == AreaDirectory.ROOT) "<a href=\"/sa-ar/restaurants/regions/مكة-المكرمة\">مكة</a>"
            else "<a href=\"/sa-ar/restaurants/regions/مكة-المكرمة/البحيرات\">الحي</a>"
        })
        val city = directory.cities().single()
        assertEquals("مكة-المكرمة", city.slug)
        assertEquals("البحيرات", directory.districts(city).single().slug)
        assertEquals(2, calls.size)
        assertFalse(calls.last().contains("مكه"))
    }
}
