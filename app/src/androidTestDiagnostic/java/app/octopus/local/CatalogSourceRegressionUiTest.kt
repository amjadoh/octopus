package app.octopus.local

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.octopus.local.catalog.*
import app.octopus.local.domain.*
import app.octopus.local.providers.*
import app.octopus.local.ui.CatalogAreaDialog
import app.octopus.local.ui.CatalogScreen
import app.octopus.local.automation.LiveCatalogBridge
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Local fixtures on a dedicated emulator only. No provider apps, GPS or live prices. */
class CatalogSourceRegressionUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val area = CatalogArea("الرياض", "النرجس")
    private val name = "مطعم مصدرين للاختبار"
    private val url get() = area.url + "/مطعم-123"
    private fun html(menu: Boolean): String {
        val region = """"city":{"slug":{"ar":"الرياض"}},"district":{"slug":{"ar":"النرجس"}}"""
        val data = if (menu) """$region,"vendor":{"id":123,"chain_name":"$name","status":"OPEN"},
            "vendorMenu":[{"branch_id":123,"name":"أطباق","items":[{"id":10,"name":"وجبة موقع","price":"7","description":""}]}]"""
        else """$region,"vendorList":{"pagination":{"currentPage":1,"pageCount":1},"data":[
            {"id":123,"chainName":"$name","vertical":"restaurants","chainType":"RESTAURANT","kitchens":[],"status":"OPEN"}]}"""
        return """<script id="__NEXT_DATA__">{"props":{"pageProps":{$data}}}</script>
            <script type="application/ld+json">{"@type":"ItemList","itemListElement":[{"url":"$url"}]}</script>"""
    }
    private fun graph(): AppGraph {
        lateinit var graph: AppGraph
        compose.runOnIdle {
            graph = AppGraph(compose.activity.application, PublicCatalogClient { html(it == url) },
                AreaDirectory(client = PublicCatalogClient { value ->
                    if (value == AreaDirectory.ROOT) "<a href=\"/sa-ar/restaurants/regions/الرياض\">الرياض</a>"
                    else "<a href=\"/sa-ar/restaurants/regions/الرياض/النرجس\">النرجس</a>"
                }))
        }
        compose.waitUntil(10_000) { graph.catalogReady.value && graph.draftReady.value }
        runBlocking { withContext(Dispatchers.Main) { graph.clearCatalog(); graph.clearOrders() } }
        return graph
    }
    private fun fixture(graph: AppGraph, restaurant: LiveRestaurant) {
        val item = LiveMenuItem("وجبة يدوية محفوظة", 700, "manual-food")
        val product = ConfiguredProduct(restaurant.name, MenuProduct(item.selector, item.name, null, true, 700, emptyList()),
            CatalogProvenance(ProviderId.HUNGERSTATION, HungerStationCatalog.TESTED_VERSION, 1000, "manual"), true)
        runBlocking { withContext(Dispatchers.Main) {
            graph.saveCatalogObservation(ManualCatalogUpdate(app.octopus.local.providers.CatalogScreen.MENU,
                menu = LiveMenu(restaurant, listOf(item), 1000, false)))
            graph.saveCatalogObservation(ManualCatalogUpdate(app.octopus.local.providers.CatalogScreen.PRODUCT, product = product))
        } }
    }
    private fun configure(graph: AppGraph) {
        compose.runOnIdle { graph.publicCatalog.configure(area) }
        compose.waitUntil(10_000) { graph.publicCatalog.state.value.directory.isNotEmpty() }
    }
    private fun cleanup(graph: AppGraph) {
        graph.scope.cancel()
        LiveCatalogBridge.attachGraph((compose.activity.application as OctopusApplication).graph)
    }

    @Test fun compactHomeFiltersArabicNamesWithoutStartingComparison() {
        val graph = graph()
        try {
            fixture(graph, LiveRestaurant("مطعم الأثيل للاختبار", null, "مطعم الأثيل للاختبار"))
            compose.runOnIdle {
                assertTrue(graph.showLocalRestaurants())
                compose.activity.setContent { MaterialTheme { CatalogScreen(graph, {}, {}) } }
            }
            compose.onNodeWithText("منطقة عرض المطاعم").assertIsDisplayed()
            compose.onNodeWithText("عنوانك النشط في هنقرستيشن").assertDoesNotExist()
            compose.onNodeWithText("ابحث عن مطعم…").performTextInput("اثيل")
            compose.onNode(hasScrollToNodeAction() and SemanticsMatcher.keyIsDefined(
                androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange))
                .performScrollToNode(hasText("مطعم الأثيل للاختبار"))
            compose.onNodeWithText("مطعم الأثيل للاختبار").assertIsDisplayed()
            compose.runOnIdle {
                assertFalse(graph.catalog.value.comparisonRunning)
                assertNull(graph.draft.value)
            }
        } finally { cleanup(graph) }
    }
    @Test fun selectedAreaDoesNotHideManualMenuAbsentFromPublicDirectory() {
        val graph = graph()
        try {
            val manual = LiveRestaurant("مطعم محلي خارج الدليل", null, "مطعم محلي خارج الدليل")
            fixture(graph, manual); configure(graph)
            compose.runOnIdle {
                assertTrue(graph.showLocalRestaurants())
                assertTrue(graph.catalog.value.restaurants.any { it == manual })
                assertTrue(graph.showLocalMenu(manual))
                compose.activity.setContent { MaterialTheme { CatalogScreen(graph, {}, {}) } }
            }
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("وجبة يدوية محفوظة"))
            compose.onNodeWithText("وجبة يدوية محفوظة").assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(CatalogOrigin.MANUAL, graph.catalog.value.selectedCatalogOrigin)
                assertEquals("manual-food", graph.catalog.value.menu!!.items.single().selector)
                assertFalse(graph.catalog.value.comparisonRunning)
            }
        } finally { cleanup(graph) }
    }
    @Test fun sameNamePublicMenuCannotInterceptManualProductSelection() {
        val graph = graph()
        try {
            val manual = LiveRestaurant(name, null, name)
            fixture(graph, manual); configure(graph)
            compose.runOnIdle { assertTrue(graph.showLocalMenu(graph.publicCatalog.state.value.directory.single().live())) }
            compose.waitUntil(10_000) { graph.publicCatalog.currentMenu != null }
            compose.runOnIdle {
                assertTrue(graph.showLocalMenu(manual))
                assertNull(graph.publicCatalog.currentMenu)
                assertTrue(graph.showLocalProduct(manual, graph.catalog.value.menu!!.items.single()))
                assertEquals("manual-food", graph.catalog.value.product!!.product.id)
                assertEquals(CatalogOrigin.MANUAL, graph.catalog.value.menu!!.restaurant.origin)
            }
        } finally { cleanup(graph) }
    }
    @Test fun areaPickerAcceptsMisspelledInputThroughCanonicalSuggestions() {
        val graph = graph()
        try {
            compose.runOnIdle { compose.activity.setContent { MaterialTheme { CatalogAreaDialog(graph, {}) } } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("الرياض").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("استخدم موقعي الحالي").assertExists()
            compose.onNodeWithText("ابحث عن المدينة").performTextInput("الرياظ")
            compose.onNodeWithText("الرياض").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("النرجس").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("ابحث عن الحي").performTextInput("النرجص")
            compose.onNodeWithText("النرجس").performClick()
            compose.onNodeWithText("حفظ وتحديث قائمة المطاعم").assertIsEnabled()
            compose.runOnIdle { assertFalse(graph.catalog.value.comparisonRunning) }
        } finally { cleanup(graph) }
    }
}
