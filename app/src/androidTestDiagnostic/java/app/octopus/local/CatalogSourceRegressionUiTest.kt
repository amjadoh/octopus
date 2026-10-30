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
import java.util.concurrent.atomic.AtomicInteger

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
    private fun graph(areaNames: AreaDirectory? = null): AppGraph {
        lateinit var graph: AppGraph
        compose.runOnIdle {
            graph = AppGraph(compose.activity.application, PublicCatalogClient { html(it == url) },
                areaNames ?: AreaDirectory(client = PublicCatalogClient { value ->
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

    @Test fun savingNewRegionKeepsDraftAndManualMenuButInvalidatesComparison() {
        val graph=graph()
        try {
            val manual=LiveRestaurant("مطعم محفوظ للاختبار",null,"مطعم محفوظ للاختبار")
            fixture(graph,manual)
            val product=ConfiguredProduct(manual.name, MenuProduct("manual-food","وجبة يدوية محفوظة",null,true,700,emptyList()),
                CatalogProvenance(ProviderId.HUNGERSTATION,HungerStationCatalog.TESTED_VERSION,1000,"fixture"),true)
            runBlocking { withContext(Dispatchers.Main) {
                graph.addDraftProduct(product,ProductSelection("manual-food",emptyMap(),3))
                graph.approveExistingMatchingQuantity()
                val request=graph.draft.value!!
                graph.catalog.value=graph.catalog.value.copy(finalTotals=mapOf(ProviderId.HUNGERSTATION to
                    FinalTotalReading(ProviderId.HUNGERSTATION,"v",request.fingerprint,mapOf(PriceField.ORDER_TOTAL to 2500L),1000)))
                val next=if(graph.settings.value.catalogArea==area)CatalogArea("الرياض","الياسمين") else area
                graph.changeCatalogArea(next)
                assertEquals(request,graph.draft.value)
                assertEquals(next,graph.settings.value.catalogArea)
                assertNull(graph.existingQuantityConsent.value)
                assertTrue(graph.catalog.value.finalTotals.isEmpty())
                assertFalse(graph.catalog.value.busy)
                assertFalse(graph.catalog.value.comparisonRunning)
                assertTrue(graph.catalog.value.restaurants.contains(manual))
            } }
        } finally { cleanup(graph) }
    }
    @Test fun runningComparisonCannotHaveItsRegionChanged() {
        val graph=graph()
        try {
            runBlocking { withContext(Dispatchers.Main) {
                val before=graph.settings.value.catalogArea
                graph.catalog.value=graph.catalog.value.copy(comparisonRunning=true)
                assertTrue(runCatching { graph.changeCatalogArea(area) }.isFailure)
                assertEquals(before,graph.settings.value.catalogArea)
                graph.catalog.value=graph.catalog.value.copy(comparisonRunning=false)
            } }
        } finally { cleanup(graph) }
    }

    @Test fun compactHomeFiltersArabicNamesWithoutStartingComparison() {
        val graph = graph()
        try {
            fixture(graph, LiveRestaurant("مطعم الأثيل للاختبار", null, "مطعم الأثيل للاختبار"))
            compose.runOnIdle {
                assertTrue(graph.showLocalRestaurants())
                compose.activity.setContent { MaterialTheme { CatalogScreen(graph, {}, {}) } }
            }
            compose.onNodeWithText("منطقة عرض المطاعم").assertDoesNotExist()
            compose.onNodeWithText("ابدأ من هنقرستيشن").assertIsDisplayed()
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
            compose.onNodeWithText("ابحث عن المدينة").performTextReplacement("الرياظ")
            compose.onNodeWithText("الرياض").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("النرجس").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("ابحث عن الحي").performTextReplacement("النرجص")
            compose.onNodeWithText("النرجس").performClick()
            compose.onNodeWithText("حفظ وتحديث قائمة المطاعم").assertIsEnabled()
            compose.runOnIdle { assertFalse(graph.catalog.value.comparisonRunning) }
        } finally { cleanup(graph) }
    }

    @Test fun sameCityGpsRetriesFailedDistrictLoadAndSavesTheNewCanonicalDistrict() {
        val districtCalls = AtomicInteger()
        val dismissals = AtomicInteger()
        val next = CatalogArea("الرياض", "الورود")
        val graph = graph(AreaDirectory(client = PublicCatalogClient { value ->
            if (value == AreaDirectory.ROOT) "<a href=\"/sa-ar/restaurants/regions/الرياض\">الرياض</a>"
            else {
                check(districtCalls.incrementAndGet() > 1) { "Fixture directory temporarily unavailable" }
                """<a href="/sa-ar/restaurants/regions/الرياض/النرجس">النرجس</a>
                    <a href="/sa-ar/restaurants/regions/الرياض/الورود">الورود</a>"""
            }
        }))
        try {
            runBlocking { withContext(Dispatchers.Main) { graph.changeCatalogArea(area) } }
            // graph() clears the controller; an already persisted area does not emit a new setting.
            configure(graph)
            compose.runOnIdle {
                compose.activity.setContent { MaterialTheme {
                    CatalogAreaDialog(graph, { dismissals.incrementAndGet() },
                        locationLookup = { CurrentAreaHint("الرياض", "الورود") })
                } }
            }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("تعذر تحديث أسماء الأحياء من المصدر", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("استخدم موقعي الحالي").performScrollTo().performClick()
            compose.waitUntil(10_000) {
                districtCalls.get() >= 2 && compose.onAllNodes(hasText("حفظ وتحديث قائمة المطاعم") and isEnabled())
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("ابحث عن الحي").assertTextContains("الورود")
            compose.onNodeWithText("تعذر تحديث أسماء الأحياء من المصدر", substring = true).assertDoesNotExist()
            compose.runOnIdle { assertEquals(area, graph.settings.value.catalogArea) }
            compose.onNodeWithText("حفظ وتحديث قائمة المطاعم").performClick()
            compose.waitUntil(10_000) { graph.settings.value.catalogArea == next && dismissals.get() == 1 }
            compose.runOnIdle {
                assertFalse(graph.catalog.value.comparisonRunning)
                assertFalse(graph.catalog.value.busy)
            }
        } finally { cleanup(graph) }
    }

    @Test fun unknownGpsDistrictExplainsDisabledSaveAndOffersCanonicalChoices() {
        val graph = graph()
        try {
            runBlocking { withContext(Dispatchers.Main) { graph.changeCatalogArea(area) } }
            // Configure the fixture explicitly even when changeCatalogArea is a same-area no-op.
            configure(graph)
            compose.runOnIdle {
                compose.activity.setContent { MaterialTheme {
                    CatalogAreaDialog(graph, {},
                        locationLookup = { CurrentAreaHint("الرياض", "حي خارج دليل المطاعم") })
                } }
            }
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasText("استخدم موقعي الحالي") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("استخدم موقعي الحالي").performScrollTo().performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("لم أتمكن من مطابقة اسم الحي", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("حفظ وتحديث قائمة المطاعم").assertIsNotEnabled()
            compose.onNodeWithText("عرض الأحياء المتاحة").performScrollTo().performClick()
            compose.onNode(hasText("النرجس") and hasClickAction()).performScrollTo().performClick()
            compose.onNodeWithText("حفظ وتحديث قائمة المطاعم").assertIsEnabled()
            compose.onNodeWithText("لم أتمكن من مطابقة اسم الحي", substring = true).assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(area, graph.settings.value.catalogArea)
                assertFalse(graph.catalog.value.comparisonRunning)
            }
        } finally { cleanup(graph) }
    }

    @Test fun restaurantMenuHidesSavedLocationWithoutChangingItsSelectedRestaurant() {
        val graph = graph()
        try {
            val manual = LiveRestaurant("مطعم موقع للاختبار", null, "مطعم موقع للاختبار")
            fixture(graph, manual); configure(graph)
            compose.runOnIdle {
                assertTrue(graph.showLocalMenu(manual))
                compose.activity.setContent { MaterialTheme { CatalogScreen(graph, {}, {}) } }
            }
            compose.onNodeWithText("تغيير الموقع").assertDoesNotExist()
            compose.onNodeWithText("منطقة قائمة المطاعم").assertDoesNotExist()
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("وجبة يدوية محفوظة"))
            compose.onNodeWithText("وجبة يدوية محفوظة").assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(manual.name, graph.catalog.value.menu?.restaurant?.name)
                assertFalse(graph.catalog.value.comparisonRunning)
            }
        } finally { cleanup(graph) }
    }

    @Test fun restaurantNavigationStartsAtTopButRefreshingTheSameMenuKeepsScroll() {
        val graph = graph()
        try {
            val first = LiveRestaurant("مطعم التمرير الأول", null, "مطعم التمرير الأول")
            val second = LiveRestaurant("مطعم التمرير الثاني", null, "مطعم التمرير الثاني")
            fun menu(restaurant: LiveRestaurant, label: String) = LiveMenu(restaurant,
                (1..24).map { LiveMenuItem("وجبة $label $it", 700, "$label-$it") }, 1000, true)
            runBlocking { withContext(Dispatchers.Main) {
                graph.saveCatalogObservation(ManualCatalogUpdate(app.octopus.local.providers.CatalogScreen.MENU,
                    menu = menu(first, "أولى")))
                graph.saveCatalogObservation(ManualCatalogUpdate(app.octopus.local.providers.CatalogScreen.MENU,
                    menu = menu(second, "ثانية")))
            } }
            configure(graph)
            compose.runOnIdle {
                assertTrue(graph.showLocalRestaurants())
                val preceding = (1..15).map { LiveRestaurant("مطعم ترتيب $it", null, "مطعم ترتيب $it") }
                graph.catalog.value = graph.catalog.value.copy(restaurants = preceding + first + second)
                compose.activity.setContent { MaterialTheme { CatalogScreen(graph, {}, {}) } }
            }
            val list = compose.onNode(hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(
                androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange))
            list.performScrollToNode(hasText(first.name))
            compose.onNodeWithText("ابدأ من هنقرستيشن").assertIsNotDisplayed()
            compose.onNodeWithText(first.name).performClick()
            compose.onNodeWithText("اختر هنا، وقارن عندما يكتمل طلبك").assertIsDisplayed()
            compose.runOnIdle { assertEquals(first.name, graph.catalog.value.menu?.restaurant?.name) }

            list.performScrollToNode(hasText("وجبة أولى 20"))
            compose.onNodeWithText("وجبة أولى 20").assertIsDisplayed()
            compose.runOnIdle {
                val old = graph.catalog.value
                graph.catalog.value = old.copy(menu = old.menu!!.copy(capturedAtMillis = 2000))
            }
            compose.onNodeWithText("وجبة أولى 20").assertIsDisplayed()
            compose.onNodeWithText("اختر هنا، وقارن عندما يكتمل طلبك").assertIsNotDisplayed()

            compose.runOnIdle { assertTrue(graph.showLocalMenu(second)) }
            compose.onNodeWithText("اختر هنا، وقارن عندما يكتمل طلبك").assertIsDisplayed()
            compose.runOnIdle { assertEquals(second.name, graph.catalog.value.menu?.restaurant?.name) }
            list.performScrollToNode(hasText("وجبة ثانية 20"))
            list.performScrollToNode(hasText("العودة للمطاعم"))
            compose.onNodeWithText("العودة للمطاعم").performClick()
            compose.onNodeWithText("ابدأ من هنقرستيشن").assertIsDisplayed()
            compose.runOnIdle {
                assertNull(graph.catalog.value.menu)
                assertFalse(graph.catalog.value.comparisonRunning)
            }
        } finally { cleanup(graph) }
    }
}
