package app.octopus.local.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.octopus.local.BuildConfig
import app.octopus.local.OctopusApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/** Opt-in development APK only; shell DUMP permission, finite runtime commands, no UI executor. */
class CatalogTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG || intent.action != "app.octopus.local.TEST_CATALOG") return
        val mode = intent.getStringExtra("mode") ?: "snapshot"
        if (mode !in setOf("snapshot", "stop", "search", "browse", "open_menu", "open_product",
                "compare_menu", "compare_total", "resume_draft", "prepare_hs", "prepare_keeta", "handoff_hs", "handoff_keeta")) return
        val query = intent.getStringExtra("query")?.takeIf { it.isNotBlank() && it.length <= 100 }
        val item = intent.getStringExtra("item")?.takeIf { it.isNotBlank() && it.length <= 100 }
        val graph = (context.applicationContext as OctopusApplication).graph
        val pending = goAsync()
        graph.scope.launch {
            var started = false
            var reportError: String? = null
            try {
                withTimeout(7000) { graph.draftReady.first { it } }
                if (mode == "snapshot") started = true
                else if (mode == "stop") { LiveCatalogBridge.stop(); started = true }
                else {
                    withTimeout(7000) { graph.serviceConnected.first { it } }
                    check(graph.settings.value.catalogConsent)
                    started = when (mode) {
                        "resume_draft" -> graph.draft.value?.let { cart ->
                            graph.catalog.value = graph.catalog.value.copy(selectedRestaurant = cart.restaurant); true
                        } ?: false
                        "compare_total" -> LiveCatalogBridge.compareAndPrepare()
                        "search" -> query?.let(LiveCatalogBridge::search) ?: false
                        "browse" -> LiveCatalogBridge.browseRestaurants()
                        "open_menu" -> graph.catalog.value.restaurants.singleOrNull { it.name == query }
                            ?.let(LiveCatalogBridge::openMenu) ?: false
                        "open_product" -> graph.catalog.value.menu?.let { menu ->
                            menu.items.singleOrNull { it.name == item }?.let { LiveCatalogBridge.openProduct(menu.restaurant, it, false) }
                        } ?: false
                        "compare_menu" -> LiveCatalogBridge.compareDraft()
                        "prepare_hs" -> LiveCatalogBridge.prepareComparedHungerStation()
                        "prepare_keeta" -> LiveCatalogBridge.prepareComparedKeeta()
                        "handoff_hs" -> LiveCatalogBridge.openPreparedRidanBasket()
                        "handoff_keeta" -> LiveCatalogBridge.prepareComparedKeeta(true)
                        else -> false
                    }
                }
                pending.resultCode = if (started) 0 else 1
                pending.resultData = if (started) "RUNTIME_STARTED" else "RUNTIME_NOT_STARTED"
            } catch (_: Exception) { reportError = "RUNTIME_SETUP_FAILED"; pending.resultCode = 1 }
            finally { pending.finish() }
            try {
                if (started && mode !in setOf("snapshot", "stop", "resume_draft")) withTimeout(350_000) {
                    graph.catalog.first { !it.busy && !it.comparisonRunning }
                }
            } catch (_: TimeoutCancellationException) { reportError = "RUNTIME_TIMEOUT" }
            val state = graph.catalog.value
            val report = JSONObject().put("mode", mode).put("started", started)
                .put("error", reportError ?: state.error ?: JSONObject.NULL)
                .put("busy", state.busy).put("serviceConnected", graph.serviceConnected.value)
                .put("restaurantNames", JSONArray(state.restaurants.map { it.name }))
                .put("menuItemsRead", state.menu?.items?.size ?: 0)
                .put("menuItems", JSONArray(state.menu?.items?.map { it.name } ?: emptyList<String>()))
                .put("menuReachedEnd", state.menu?.reachedEnd ?: false)
                .put("draftRestaurant", graph.draft.value?.restaurant ?: JSONObject.NULL)
                .put("draftItems", graph.draft.value?.items?.size ?: 0)
                .put("finalTotalReadings", JSONArray(state.finalTotals.values.map { reading ->
                    JSONObject().put("provider",reading.provider.name).put("totalHalala",reading.total)
                        .put("capturedAt",reading.capturedAt)
                }))
                .put("preparedBasketPresent", state.preparedBasket != null)
                .put("productOptionsComplete", state.product?.optionsComplete ?: JSONObject.NULL)
                .put("productAvailable", state.product?.product?.available ?: JSONObject.NULL)
                .put("productListedPriceHalala", state.product?.product?.listedPriceHalala ?: JSONObject.NULL)
                .put("productGroupCount", state.product?.product?.groups?.size ?: JSONObject.NULL)
                .put("menuPricePreviews", JSONArray(state.previews.map { preview ->
                    JSONObject().put("provider", preview.provider.name)
                        .put("itemSubtotalHalala", preview.itemSubtotal ?: JSONObject.NULL)
                }))
                .put("preparedBasket", state.preparedBasket?.let { basket ->
                    JSONObject().put("provider", basket.provider.name).put("restaurant", basket.restaurant)
                        .put("lines", JSONArray(basket.lines.map { line ->
                            JSONObject().put("name", line.name).put("quantity", line.quantity ?: JSONObject.NULL)
                                .put("lineAmountHalala", line.lineAmount ?: JSONObject.NULL)
                                .put("choiceCount", line.choices.size)
                        }))
                } ?: JSONObject.NULL)
                .put("finalTotalsVerified", false)
            // Same app process as the real UI/service; no instrumentation restart or permission override.
            withContext(Dispatchers.IO) { context.filesDir.resolve("live-device-result.json").writeText(report.toString(), Charsets.UTF_8) }
        }
    }
}
