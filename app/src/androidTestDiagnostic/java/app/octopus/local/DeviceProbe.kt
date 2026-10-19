package app.octopus.local

import android.app.UiAutomation
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Bundle
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.runner.AndroidJUnitRunner
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import app.octopus.local.automation.LiveCatalogBridge

/** Explicit USB-only Unicode search input, packaged exclusively in the development test APK. */
class DeviceProbe : AndroidJUnitRunner() {
    private var args = Bundle()
    override fun onCreate(arguments: Bundle?) { args = arguments ?: Bundle(); super.onCreate(arguments) }
    override fun onStart() {
        if (!args.containsKey("operation")) { super.onStart(); return }
        val result = Bundle()
        var stage = "arguments"
        try {
            val operation = args.getString("operation")
            require(operation in setOf("set_search", "inspect_cards", "open_saved_cart", "run_catalog"))
            val target = args.getString("provider")
            val packageName = when (target) {
                "hs" -> "com.hungerstation.android.web"
                "keeta" -> "com.sankuai.sailor.afooddelivery"
                else -> error("Unsupported provider")
            }
            stage = "automation"
            val automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            }
            runCatching { automation.waitForIdle(500, 4000) }
            if (operation == "run_catalog") {
                require(target == "hs")
                val graph = (targetContext.applicationContext as OctopusApplication).graph
                runBlocking {
                    withTimeout(20_000) { graph.serviceConnected.first { it } }
                    graph.settingsStore.acceptCatalogConsent()
                    withTimeout(5000) { graph.settings.first { it.catalogConsent } }
                    val searchQuery = args.getString("query") ?: error("Missing query")
                    val started = withContext(Dispatchers.Main) { LiveCatalogBridge.search(searchQuery) }
                    require(started)
                    val found = withTimeout(160_000) { graph.catalog.first { !it.busy && (it.restaurants.isNotEmpty() || it.error != null) } }
                    result.putString("catalogError", found.error)
                    result.putInt("restaurantsRead", found.restaurants.size)
                    result.putString("restaurantNames", found.restaurants.joinToString(" | ") { it.name })
                    if (args.getString("readMenu") == "true" && found.restaurants.isNotEmpty()) {
                        val selected = found.restaurants.first()
                        require(withContext(Dispatchers.Main) { LiveCatalogBridge.openMenu(selected) })
                        val menu = withTimeout(170_000) { graph.catalog.first { !it.busy && (it.menu != null || it.error != null) } }
                        result.putString("menuError", menu.error)
                        result.putInt("menuItemsRead", menu.menu?.items?.size ?: 0)
                        result.putBoolean("menuReachedEnd", menu.menu?.reachedEnd == true)
                    }
                }
                finish(0, result)
                return
            }
            stage = "root"
            val root = automation.rootInActiveWindow ?: error("No root")
            require(root.packageName?.toString() == packageName)
            if (operation == "open_saved_cart") {
                require(target == "hs")
                val matches = mutableListOf<AccessibilityNodeInfo>()
                var cartList = false
                fun find(node: AccessibilityNodeInfo) {
                    if (node.contentDescription?.toString() == "delete-item-button-0") cartList = true
                    if (node.isClickable && node.className?.toString() == "android.widget.ImageView" &&
                        node.contentDescription?.toString()?.lineSequence()?.firstOrNull() == "المضياف العربي") matches += node
                    for (i in 0 until node.childCount) node.getChild(i)?.let(::find)
                }
                find(root)
                require(cartList && matches.size == 1)
                result.putBoolean("cartNavigationAction", matches.single().performAction(AccessibilityNodeInfo.ACTION_CLICK))
                finish(0, result)
                return
            }
            if (operation == "inspect_cards") {
                require(target == "keeta")
                val cards = JSONArray()
                fun collectCard(node: AccessibilityNodeInfo, card: JSONArray) {
                    if (node.isPassword || node.isEditable) return
                    val sensitive = Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive
                    card.put(JSONObject().put("id", node.viewIdResourceName.orEmpty()).put("class", node.className?.toString())
                        .put("text", if (sensitive) "<protected>" else node.text?.toString().orEmpty())
                        .put("desc", if (sensitive) "<protected>" else node.contentDescription?.toString().orEmpty())
                        .put("visible", node.isVisibleToUser).put("protected", sensitive))
                    for (i in 0 until node.childCount) node.getChild(i)?.let { collectCard(it, card) }
                }
                fun find(node: AccessibilityNodeInfo) {
                    if (node.contentDescription?.toString() == "feed shopcard") { cards.put(JSONArray().also { collectCard(node, it) }); return }
                    for (i in 0 until node.childCount) node.getChild(i)?.let(::find)
                }
                find(root)
                result.putString("cards", cards.toString())
                finish(0, result)
                return
            }
            val query = args.getString("query") ?: error("Missing query")
            require(query.isNotBlank() && query.length <= 100)
            val fields = mutableListOf<AccessibilityNodeInfo>()
            fun walk(node: AccessibilityNodeInfo) {
                if (node.isPassword) return
                if (node.isVisibleToUser && node.isEditable && node.className?.toString()?.contains("EditText") == true) fields += node
                for (i in 0 until node.childCount) node.getChild(i)?.let(::walk)
            }
            walk(root)
            stage = "input_count_${fields.size}"
            require(fields.size == 1) { "Search input must be unique" }
            val id = fields.single().viewIdResourceName.orEmpty()
            require(!id.contains("password", true) && !id.contains("phone", true))
            val input = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query) }
            result.putBoolean("searchTextSet", fields.single().performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, input))
            finish(0, result)
        } catch (failure: Exception) {
            result.putString("errorType", failure.javaClass.simpleName)
            result.putString("stage", stage)
            finish(1, result)
        }
    }
}
