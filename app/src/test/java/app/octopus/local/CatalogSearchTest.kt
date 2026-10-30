package app.octopus.local

import app.octopus.local.domain.CatalogSearch
import org.junit.Assert.*
import org.junit.Test

class CatalogSearchTest {
    @Test fun arabicVariantsAndWordOrderFindVisibleNames() {
        assertTrue(CatalogSearch.matches("مطعم الأثيل العربي", "العربي اثيل"))
        assertTrue(CatalogSearch.matches("فَـطيرة جُبن", "فطيرة جبن"))
        assertTrue(CatalogSearch.matches("مقهى", "مقهي"))
    }
    @Test fun everyQueryWordIsRequired() {
        assertFalse(CatalogSearch.matches("فطيرة جبن", "جبن دجاج"))
        assertFalse(CatalogSearch.matches("مدور", "كودو"))
    }
    @Test fun whitespaceAndLatinCaseAreDisplayFriendly() {
        assertTrue(CatalogSearch.matches("KUDO Fries", "  fries   kudo "))
        assertTrue(CatalogSearch.matches("مطعم", "   "))
    }
}
