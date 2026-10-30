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
    @Test fun sameProductTextWithChangedControlStateResetsReadiness() {
        val frame = CatalogFrame(listOf(node(0,"pdpCloseButton"),
            node(1,"flutterAddButton","إضافة").copy(clickable=true)),true)
        val original = CatalogStability.fingerprint(frame)
        val action = frame.nodes.last()
        for (changed in listOf(action.copy(enabled=false), action.copy(clickable=false),
            action.copy(enteredText=true), action.copy(editable=true), action.copy(omittedText=true))) {
            assertNotEquals(original, CatalogStability.fingerprint(frame.copy(nodes=frame.nodes.dropLast(1)+changed)))
        }
    }
    @Test fun incompleteProductCannotShareTheCompleteProductFingerprint() {
        val frame = CatalogFrame(listOf(node(0,"pdpCloseButton"),node(1,"price","9")),true)
        assertNotEquals(CatalogStability.fingerprint(frame),CatalogStability.fingerprint(frame.copy(complete=false)))
    }
}
