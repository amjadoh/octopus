package app.octopus.local

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.octopus.local.domain.*
import app.octopus.local.providers.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Room-backed fixtures for a dedicated test installation; no provider apps are operated. */
class CartManagementUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val graph get() = (compose.activity.application as OctopusApplication).graph
    private fun setup(): CanonicalCart {
        compose.waitUntil(10_000) { graph.draftReady.value && graph.catalogReady.value }
        val product = ConfiguredProduct("مطعم الحذف للاختبار",
            MenuProduct("feta", "فطيرة", null, true, 700, emptyList()),
            CatalogProvenance(ProviderId.HUNGERSTATION, HungerStationCatalog.TESTED_VERSION, 1000, "fixture"), true)
        runBlocking { withContext(Dispatchers.Main) {
            graph.clearOrders()
            graph.catalog.value = LiveCatalogState()
            assertTrue(graph.addDraftProduct(product, ProductSelection("feta", emptyMap(), 3)) is DraftChange.Changed)
        } }
        compose.runOnIdle { graph.approveExistingMatchingQuantity() }
        compose.onNodeWithText("المقارنة").performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("إدارة السلال وحذفها"))
        compose.onNodeWithText("إدارة السلال وحذفها").performClick()
        return graph.draft.value!!
    }
    @Test fun cancelPreservesCartAndConfirmationDeletesOnlyActiveDraft() {
        val request = setup()
        compose.onNodeWithText("حذف سلة الأخطبوط").performClick()
        compose.onNodeWithText("إلغاء").performClick()
        compose.runOnIdle { assertEquals(request, graph.draft.value) }
        compose.onNodeWithText("إدارة السلال وحذفها").performClick()
        compose.onNodeWithText("حذف سلة الأخطبوط").performClick()
        compose.onNodeWithText("احذف سلة الأخطبوط").performClick()
        compose.waitUntil(5_000) { graph.draft.value == null }
        compose.runOnIdle {
            assertTrue(graph.catalog.value.finalTotals.isEmpty())
            assertTrue(graph.catalog.value.previews.isEmpty())
            assertNull(graph.catalog.value.preparedBasket)
            assertNull(graph.existingQuantityConsent.value)
            assertFalse(graph.catalog.value.comparisonRunning)
        }
        runBlocking { withContext(Dispatchers.IO) { assertNull(graph.orders.load("active-order")) } }
    }
    @Test fun changedRequestOrRunningComparisonCannotBeDeletedByOldConfirmation() {
        val request = setup()
        compose.onNodeWithText("حذف سلة الأخطبوط").performClick()
        runBlocking { withContext(Dispatchers.Main) {
            graph.changeDraftQuantity(CartIdentity.itemKey(request.items.single()), 4)
        } }
        compose.onNodeWithText("احذف سلة الأخطبوط").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("تغير الطلب بعد فتح التأكيد؛ راجعه قبل الحذف.").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { assertEquals(4, graph.draft.value!!.items.single().quantity) }
        compose.onNodeWithText("إلغاء").performClick()
        val current = graph.draft.value!!
        compose.runOnIdle { graph.catalog.value = graph.catalog.value.copy(comparisonRunning = true) }
        try {
            val result = runBlocking { withContext(Dispatchers.Main) { graph.deleteActiveCart(current.fingerprint) } }
            assertTrue(result is DraftChange.Rejected)
            assertEquals(current, graph.draft.value)
        } finally { compose.runOnIdle { graph.catalog.value = graph.catalog.value.copy(comparisonRunning = false) } }
    }
    @Test fun managementDistinguishesLocalDeletionFromNativeAppHandoffs() {
        val request = setup()
        compose.onNodeWithText("حذف سلة الأخطبوط").assertIsEnabled()
        ProviderId.entries.forEach { compose.onNodeWithText("فتح ${it.title} لحذف سلته").assertIsEnabled() }
        compose.onNodeWithText("رجوع").performClick()
        compose.runOnIdle {
            assertEquals(request, graph.draft.value)
            assertFalse(graph.catalog.value.manualCaptureActive)
            assertFalse(graph.catalog.value.comparisonRunning)
        }
    }
}
