package app.octopus.local
import app.octopus.local.providers.*
import org.junit.Assert.*
import org.junit.Test

class CatalogStabilityTest {
    private fun node(i: Int, id: String, text: String = "") = CatalogNode(i, -1,
        "com.hungerstation.android.web:id/$id", text, "android.view.View", false, false, false)
    @Test fun rotatingHomeCaptionDoesNotResetReadiness() {
        val base = listOf(node(0,"new_search_card_view"), node(1,"input_csc"), node(2,"promo","10"))
        assertEquals(CatalogStability.fingerprint(CatalogFrame(base,true)),
            CatalogStability.fingerprint(CatalogFrame(base.dropLast(1) + node(2,"promo","9"),true)))
    }
    @Test fun changedRestaurantListDoesResetReadiness() {
        val base = listOf(node(0,"vendorsRecycler"),node(1,"hsBackButton"),node(2,"vendor_name","A"))
        assertNotEquals(CatalogStability.fingerprint(CatalogFrame(base,true)),
            CatalogStability.fingerprint(CatalogFrame(base.dropLast(1)+node(2,"vendor_name","B"),true)))
    }
    @Test fun productPriceChangesStillResetReadiness() {
        val base = listOf(node(0,"pdpCloseButton"),node(1,"price","10"))
        assertNotEquals(CatalogStability.fingerprint(CatalogFrame(base,true)),
            CatalogStability.fingerprint(CatalogFrame(base.dropLast(1)+node(1,"price","12"),true)))
    }
}
