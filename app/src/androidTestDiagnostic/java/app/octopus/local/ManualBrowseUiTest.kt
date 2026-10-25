package app.octopus.local

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import app.octopus.local.domain.*
import app.octopus.local.providers.*
import app.octopus.local.automation.LiveCatalogBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** UI fixtures verify local interaction boundaries, not provider connectivity or live totals. */
class ManualBrowseUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val graph get() = (compose.activity.application as OctopusApplication).graph
    private fun ready() {
        compose.waitUntil(5_000) { graph.draftReady.value && graph.catalogReady.value }
        compose.runOnIdle {
            graph.catalog.value = LiveCatalogState()
            graph.draft.value = null
        }
    }
    private fun scrollTo(text: String) {
        compose.onNode(hasScrollToNodeAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasText(text))
    }

    @Test fun homeHasRefreshButtonAndDoesNotBeginProviderReadingOnOpen() {
        ready()
        compose.onNodeWithText("تحديث قائمة المطاعم").assertIsDisplayed()
        scrollTo("اختر طلبك داخل الأخطبوط")
        compose.onNodeWithText("اختر طلبك داخل الأخطبوط").assertIsDisplayed()
        compose.runOnIdle {
            assertFalse(graph.catalog.value.manualCaptureActive)
            assertFalse(graph.catalog.value.busy)
            assertFalse(graph.catalog.value.comparisonRunning)
        }
    }

    @Test fun searchFiltersSavedRestaurantsWithoutAProviderSession() {
        ready()
        compose.runOnIdle {
            graph.serviceConnected.value = false
            graph.catalog.value = LiveCatalogState(restaurants = listOf(
                LiveRestaurant("مطعم ألف", null, "مطعم ألف"),
                LiveRestaurant("مطعم باء", null, "مطعم باء")))
        }
        compose.onNode(hasSetTextAction()).performTextInput("ألف")
        compose.onNodeWithContentDescription("ابحث", useUnmergedTree = true).performClick()
        scrollTo("مطعم ألف")
        compose.onNodeWithText("مطعم ألف").assertIsDisplayed()
        compose.onNodeWithText("مطعم باء").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(2, graph.catalog.value.restaurants.size)
            assertFalse(graph.catalog.value.manualCaptureActive)
            assertFalse(graph.catalog.value.busy)
            assertNull(graph.catalog.value.error)
        }
    }

    @Test fun comparisonHasOneExplicitPreparationAction() {
        ready()
        compose.runOnIdle {
            val cart = CanonicalCart("مطعم اختبار", null,
                listOf(CanonicalItem("فطيرة فيتا", null, emptyList(), 3)))
            graph.draft.value = cart
            graph.catalog.value = LiveCatalogState(selectedRestaurant = cart.restaurant,
                previews = listOf(MenuCostPreview(ProviderId.HUNGERSTATION, cart.fingerprint,
                    2100, emptyList(), System.currentTimeMillis(), HungerStationCatalog.TESTED_VERSION)))
        }
        compose.onNodeWithText("المقارنة").performClick()
        scrollTo("قارن الإجمالي وجهّز السلتين")
        compose.onNodeWithText("قارن الإجمالي وجهّز السلتين").assertIsEnabled()
        compose.onNodeWithText("قارن أسعار الوجبات").assertDoesNotExist()
        compose.onNodeWithText("اختر هنقرستيشن وجهّز السلة").assertDoesNotExist()
        compose.runOnIdle { assertFalse(graph.catalog.value.comparisonRunning) }
    }

    @Test fun unsupportedOrderIsPreservedAndCannotStartAPartialComparison() {
        ready()
        compose.runOnIdle {
            val cart = CanonicalCart("مطعم اختبار", null,
                listOf(CanonicalItem("طبق", null, listOf(CartOption("الأرز", "بشاور")), 2)))
            graph.draft.value = cart
            graph.catalog.value = LiveCatalogState(selectedRestaurant = cart.restaurant)
        }
        compose.onNodeWithText("المقارنة").performClick()
        scrollTo("قارن الإجمالي وجهّز السلتين")
        compose.onNodeWithText("قارن الإجمالي وجهّز السلتين").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals("بشاور", graph.draft.value!!.items.single().options.single().value)
            assertFalse(graph.catalog.value.comparisonRunning)
        }
    }

    private fun savedProduct(restaurant: String, title: String, itemId: String, capturedAt: Long) = ConfiguredProduct(
        restaurant, MenuProduct(itemId, title, null, true, 700, emptyList()),
        CatalogProvenance(ProviderId.HUNGERSTATION, HungerStationCatalog.TESTED_VERSION, capturedAt, "$restaurant-$itemId"),
        optionsComplete = true,
    )

    /** These Room-backed fixtures require an isolated test installation, not a user's live database. */
    private fun saveFixtures(vararg updates: ManualCatalogUpdate) = runBlocking {
        withContext(Dispatchers.Main) {
            graph.clearCatalog()
            graph.clearOrders()
            updates.forEach { graph.saveCatalogObservation(it) }
        }
    }

    @Test fun savedMenuAndProductCanBeSelectedOfflineThroughTheRealLibraryWithNoService() {
        ready()
        val restaurant = LiveRestaurant("مطعم محلي للاختبار", null, "مطعم محلي للاختبار")
        val item = LiveMenuItem("فطيرة محفوظة", 700, "local-item")
        val capturedAt = 1_000L
        saveFixtures(
            ManualCatalogUpdate(CatalogScreen.MENU, menu = LiveMenu(restaurant, listOf(item), capturedAt, false)),
            ManualCatalogUpdate(CatalogScreen.PRODUCT, product = savedProduct(restaurant.name, item.name, item.selector, capturedAt)),
        )
        compose.runOnIdle {
            LiveCatalogBridge.attach(null)
            graph.serviceConnected.value = false
            assertTrue(graph.showLocalRestaurants())
        }
        scrollTo(restaurant.name)
        compose.onNodeWithText(restaurant.name).performClick()
        compose.runOnIdle {
            assertEquals(restaurant.name, graph.catalog.value.menu?.restaurant?.name)
            assertFalse(graph.serviceConnected.value)
            assertFalse(graph.catalog.value.manualCaptureActive)
        }
        scrollTo(item.name)
        compose.onNodeWithText(item.name).performClick()
        compose.onNodeWithText("أضف إلى طلبي في الأخطبوط").assertIsEnabled().performClick()
        compose.waitUntil(5_000) { graph.draft.value?.restaurant == restaurant.name }
        compose.runOnIdle {
            assertEquals(item.name, graph.draft.value!!.items.single().name)
            assertEquals(restaurant.name, graph.catalog.value.menu?.restaurant?.name)
            assertNull(graph.catalog.value.product)
            assertFalse(graph.catalog.value.busy)
            assertFalse(graph.catalog.value.comparisonRunning)
            assertTrue(graph.catalog.value.finalTotals.isEmpty())
            assertEquals(capturedAt, graph.catalogLibrary.value.configuredProduct(
                ProviderId.HUNGERSTATION, restaurant.name, item.selector)!!.source.capturedAtMillis)
            ProviderId.entries.forEach { assertNull(graph.catalog.value.preparedBaskets.forRequest(graph.draft.value!!, it)) }
        }
    }

    @Test fun missingSavedOptionsRemainLocalAndNeverReuseAnotherRestaurantsProduct() {
        ready()
        val first = LiveRestaurant("مطعم سابق للاختبار", null, "مطعم سابق للاختبار")
        val chosen = LiveRestaurant("مطعم مختار للاختبار", null, "مطعم مختار للاختبار")
        val item = LiveMenuItem("فطيرة مشتركة الاسم", 700, "same-item")
        saveFixtures(
            ManualCatalogUpdate(CatalogScreen.MENU, menu = LiveMenu(first, listOf(item), 1_000, false)),
            ManualCatalogUpdate(CatalogScreen.PRODUCT, product = savedProduct(first.name, item.name, item.selector, 1_000)),
            ManualCatalogUpdate(CatalogScreen.MENU, menu = LiveMenu(chosen, listOf(item), 2_000, false)),
        )
        compose.runOnIdle {
            LiveCatalogBridge.attach(null)
            graph.serviceConnected.value = false
            assertNull(graph.catalog.value.product)
            assertTrue(graph.showLocalRestaurants())
        }
        scrollTo(chosen.name)
        compose.onNodeWithText(chosen.name).performClick()
        scrollTo(item.name)
        compose.onNodeWithText(item.name).performClick()
        compose.runOnIdle {
            assertEquals(chosen.name, graph.catalog.value.selectedRestaurant)
            assertEquals(chosen.name, graph.catalog.value.menu?.restaurant?.name)
            assertNull(graph.catalog.value.product)
            assertNotNull(graph.catalog.value.error)
            assertFalse(graph.catalog.value.busy)
            assertFalse(graph.catalog.value.manualCaptureActive)
            assertFalse(graph.catalog.value.comparisonRunning)
        }
        compose.onNodeWithText("أضف إلى طلبي في الأخطبوط").assertDoesNotExist()
    }

    @Test fun importingRestaurantListDoesNotReopenAnOldMenuOrProduct() {
        ready()
        val restaurant = LiveRestaurant("مطعم محفوظ للاختبار", null, "مطعم محفوظ للاختبار")
        val item = LiveMenuItem("وجبة محفوظة", 700, "fixture-item")
        saveFixtures(
            ManualCatalogUpdate(CatalogScreen.MENU, menu = LiveMenu(restaurant, listOf(item), 1_000, false)),
            ManualCatalogUpdate(CatalogScreen.PRODUCT, product = savedProduct(restaurant.name, item.name, item.selector, 1_000)),
            ManualCatalogUpdate(CatalogScreen.BROWSE, restaurants = listOf(restaurant.copy(description = "فطائر"))),
        )
        compose.runOnIdle {
            assertNull(graph.catalog.value.menu)
            assertNull(graph.catalog.value.product)
            assertFalse(graph.catalog.value.comparisonRunning)
            assertTrue(graph.catalogLibrary.value.menus.isNotEmpty())
            assertTrue(graph.catalogLibrary.value.products.isNotEmpty())
        }
    }

    @Test fun completedOldReadCanPersistWithoutOverridingTheUsersNewLocalSelection() {
        ready()
        val selected = LiveRestaurant("مطعم مختار محليًا", null, "مطعم مختار محليًا")
        val previous = LiveRestaurant("مطعم من قراءة سابقة", null, "مطعم من قراءة سابقة")
        val item = LiveMenuItem("وجبة محفوظة", 700, "local-item")
        saveFixtures(ManualCatalogUpdate(CatalogScreen.MENU,
            menu = LiveMenu(selected, listOf(item), 1_000, false)))
        compose.runOnIdle {
            LiveCatalogBridge.attach(null)
            graph.serviceConnected.value = false
            assertTrue(graph.showLocalMenu(selected))
        }
        runBlocking {
            withContext(Dispatchers.Main) {
                graph.saveCatalogObservation(ManualCatalogUpdate(CatalogScreen.MENU,
                    menu = LiveMenu(previous, listOf(item), 2_000, false)), mayDisplay = { false })
            }
        }
        compose.runOnIdle {
            assertEquals(selected.name, graph.catalog.value.selectedRestaurant)
            assertEquals(selected.name, graph.catalog.value.menu?.restaurant?.name)
            assertNull(graph.catalog.value.product)
            assertNotNull(graph.catalogLibrary.value.menu(ProviderId.HUNGERSTATION, previous.name))
            assertFalse(graph.catalog.value.manualCaptureActive)
            assertFalse(graph.catalog.value.comparisonRunning)
        }
    }
}
