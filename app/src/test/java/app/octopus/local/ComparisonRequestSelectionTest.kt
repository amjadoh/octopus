package app.octopus.local
import app.octopus.local.domain.*
import org.junit.Assert.*
import org.junit.Test
class ComparisonRequestSelectionTest {
    private val old = CanonicalCart("مدور", null, listOf(CanonicalItem("فطيرة جبن فيتا", null, emptyList(), 3)))
    @Test fun choosingAnotherRestaurantBlocksPreviousDraft() {
        assertFalse(ComparisonRequestSelection.matches("مطعم آخر", old))
        assertTrue(ComparisonRequestSelection.matches("مدور", old))
    }
    @Test fun newlySavedDraftCanCompareOnlyWithItsRestaurant() {
        val changed = old.copy(restaurant = "مطعم آخر")
        assertTrue(ComparisonRequestSelection.matches("مطعم آخر", changed))
        assertFalse(ComparisonRequestSelection.matches("مدور", changed))
    }
    @Test fun restoredDraftIsUsableButNoDraftIsNeverUsable() {
        assertTrue(ComparisonRequestSelection.matches(null, old))
        assertFalse(ComparisonRequestSelection.matches("مدور", null))
    }
}
