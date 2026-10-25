package app.octopus.local

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.platform.app.InstrumentationRegistry
import app.octopus.local.catalog.*
import app.octopus.local.domain.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in live public website integration, never calls provider apps or Compare. */
class PublicCatalogUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val graph get()=(compose.activity.application as OctopusApplication).graph
    private fun scrollTo(text:String) {
        compose.onNode(hasScrollToNodeAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasText(text))
    }
    @Test fun scheduledRefreshHasNetworkConstraintAndDoesNotStartComparison() {
        compose.waitUntil(10_000) {graph.catalogReady.value&&graph.draftReady.value}
        compose.runOnIdle {
            PublicCatalogRefreshJob.schedule(compose.activity)
            val scheduler=compose.activity.getSystemService(android.app.job.JobScheduler::class.java)
            val scheduled=scheduler.allPendingJobs.single {it.service.className==PublicCatalogRefreshJob::class.java.name}
            assertTrue(scheduled.isPeriodic)
            assertNotNull(scheduled.requiredNetwork)
            assertFalse(graph.catalog.value.comparisonRunning)
            assertFalse(graph.catalog.value.manualCaptureActive)
            PublicCatalogRefreshJob.cancel(compose.activity)
        }
    }
    @LivePublicNetwork
    @Test fun livePublicMenuCanBecomeLocalOrderWithoutOpeningAnyProvider() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("publicCatalogLive")=="true")
        compose.waitUntil(10_000) {graph.catalogReady.value&&graph.draftReady.value}
        compose.runOnIdle { PublicCatalogRefreshJob.schedule(compose.activity) }
        runBlocking { withContext(Dispatchers.Main) { graph.clearOrders() } }
        compose.runOnIdle {graph.publicCatalog.configure(CatalogArea("الرياض","النرجس"))}
        compose.waitUntil(45_000) {!graph.publicCatalog.state.value.refreshing&&graph.publicCatalog.state.value.directory.isNotEmpty()}
        val restaurant=graph.publicCatalog.state.value.directory.single {it.id==64579L}
        compose.runOnIdle {assertTrue(graph.showLocalMenu(restaurant.live()))}
        compose.waitUntil(45_000) {graph.catalog.value.menu?.restaurant?.name==restaurant.name}
        val meal=graph.publicCatalog.currentMenu!!.meals.single {it.id==142650964L}
        scrollTo(meal.name)
        compose.onNodeWithText(meal.name).performClick()
        compose.onNodeWithText("أضف إلى طلبي").assertIsDisplayed()
        compose.onNodeWithText("+").performClick().performClick()
        compose.onNodeWithText("أضف إلى طلبي").performClick()
        compose.waitUntil(5_000) {graph.draft.value?.restaurant==restaurant.name}
        compose.runOnIdle {
            assertEquals(meal.name,graph.draft.value!!.items.single().name)
            assertEquals(3,graph.draft.value!!.items.single().quantity)
            assertFalse(graph.catalog.value.comparisonRunning);assertFalse(graph.catalog.value.manualCaptureActive)
            assertFalse(graph.session.value.active);assertTrue(graph.catalog.value.finalTotals.isEmpty())
            assertEquals("app.octopus.local",compose.activity.packageName)
        }
        val before=graph.draft.value
        compose.runOnIdle {graph.refreshPublicRestaurants()}
        compose.waitUntil(45_000) {!graph.publicCatalog.state.value.refreshing}
        compose.runOnIdle {assertEquals(before,graph.draft.value);assertEquals(restaurant.name,graph.catalog.value.menu!!.restaurant.name)}
        scrollTo("تحديث قائمة المطاعم")
        compose.onNodeWithText("تحديث قائمة المطاعم").assertIsDisplayed()
    }
}
