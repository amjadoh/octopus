package app.octopus.local

import android.app.KeyguardManager
import android.app.UiAutomation
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.octopus.local.automation.InspectionReader
import app.octopus.local.diagnostics.*
import app.octopus.local.domain.ProviderId
import app.octopus.local.safety.CatalogFinancialBoundary
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit local investigation of the installed version. Never adds/clears carts or opens checkout. */
@RunWith(AndroidJUnit4::class)
class KeetaReadProbeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val provider = "com.sankuai.sailor.afooddelivery"
    private val automation by lazy {
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).also {
            it.serviceInfo = it.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
        }
    }
    private fun label(n: AccessibilityNodeInfo): String =
        if (n.isEditable || n.isPassword) "" else (n.text ?: n.contentDescription)?.toString()?.trim().orEmpty()
    private fun visit(n: AccessibilityNodeInfo, action: (AccessibilityNodeInfo) -> Unit, depth: Int = 0) {
        if (depth > 35 || !n.isVisibleToUser || n.isPassword ||
            InspectionRedactor.privateSubtree(n.viewIdResourceName.orEmpty()) ||
            (android.os.Build.VERSION.SDK_INT >= 34 && n.isAccessibilityDataSensitive)) return
        action(n)
        if (n.isEditable) return
        for (i in 0 until n.childCount.coerceAtMost(100)) {
            val child = n.getChild(i) ?: continue
            try { visit(child, action, depth + 1) } finally { @Suppress("DEPRECATION") child.recycle() }
        }
    }
    private fun find(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val matches = mutableListOf<AccessibilityNodeInfo>()
        visit(root, { if (predicate(it)) matches += AccessibilityNodeInfo.obtain(it) })
        if (matches.size == 1) return matches.single()
        matches.forEach { @Suppress("DEPRECATION") it.recycle() }
        return null
    }
    private fun has(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): Boolean {
        var seen = false
        visit(root, { if (predicate(it)) seen = true })
        return seen
    }
    private fun first(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        var result: AccessibilityNodeInfo? = null
        visit(root, { if (result == null && predicate(it)) result = AccessibilityNodeInfo.obtain(it) })
        return result
    }
    private fun sameName(node: AccessibilityNodeInfo, name: String) =
        app.octopus.local.domain.IdentityText.clean(label(node)) == app.octopus.local.domain.IdentityText.clean(name)
    private fun id(n: AccessibilityNodeInfo, suffix: String) = n.viewIdResourceName.orEmpty().endsWith(":id/$suffix")
    private fun awaitRoot(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            val lock = instrumentation.targetContext.getSystemService(KeyguardManager::class.java)
            check(!lock.isKeyguardLocked) { "Device locked" }
            val root = automation.rootInActiveWindow
            if (root != null) {
                if (root.packageName?.toString() == provider && predicate(root)) return root
                @Suppress("DEPRECATION") root.recycle()
            }
            SystemClock.sleep(200)
        }
        error("Expected Keeta screen not observed; no guessed action attempted")
    }
    private fun click(root: AccessibilityNodeInfo, node: AccessibilityNodeInfo) {
        check(root.packageName?.toString() == provider && node.refresh() && node.isVisibleToUser &&
            node.isEnabled && node.isClickable && !node.isEditable && !node.isPassword &&
            node.packageName?.toString() == provider && node.windowId == root.windowId)
        check(CatalogFinancialBoundary.classify(node.viewIdResourceName.orEmpty(),
            node.className?.toString().orEmpty(), node.isClickable, node.isEditable,
            ProviderId.KEETA) == CatalogFinancialBoundary.Decision.NORMAL)
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun record(root: AccessibilityNodeInfo, phase: String, restaurant: String, item: String) {
        val snapshot = InspectionReader().read(root, InspectionRequest(InspectionStage.ITEM, restaurant, item))
        val frameNodes = org.json.JSONArray()
        val reader = app.octopus.local.providers.KeetaMenuReader(restaurant, item, "3.13.203")
        var complete = true
        fun capture(node: AccessibilityNodeInfo, parent: Int, depth: Int, publicProduct: Boolean = false) {
            if (depth > 35 || frameNodes.length() >= 1800) { complete = false; return }
            if (!node.isVisibleToUser || node.isPassword || InspectionRedactor.privateSubtree(node.viewIdResourceName.orEmpty())) return
            if (android.os.Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive) { complete = false; return }
            val index = frameNodes.length()
            val raw = if (node.isEditable) "" else (node.text ?: node.contentDescription)?.toString().orEmpty()
            val inProduct = publicProduct || (phase == "product" && node.className?.toString() == "android.widget.ScrollView" &&
                has(node) { sameName(it, item) } && !has(node) { app.octopus.local.providers.KeetaRestaurantName.matches(restaurant, label(it)) } &&
                !has(node) { it.isEditable })
            val value = if (inProduct) raw else reader.functionalLabel(raw)
            frameNodes.put(JSONObject().put("index", index).put("parent", parent)
                .put("id", InspectionRedactor.resourceId(node.viewIdResourceName).orEmpty())
                .put("label", value).put("className", InspectionRedactor.className(node.className).orEmpty())
                .put("clickable", node.isClickable).put("editable", node.isEditable).put("scrollable", node.isScrollable)
                .put("enabled", node.isEnabled).put("omittedText", raw.any(Char::isLetter) && value.isBlank()))
            if (node.isEditable) return
            for (i in 0 until node.childCount.coerceAtMost(100)) {
                val child = node.getChild(i) ?: continue
                try { capture(child, index, depth + 1, inProduct) } finally { child.recycle() }
            }
        }
        capture(root, -1, 0)
        val report = JSONObject().put("phase", phase).put("version", "3.13.203")
            .put("nodes", org.json.JSONArray(snapshot.nodes)).put("truncated", snapshot.truncated)
            .put("catalogFrame", JSONObject().put("complete", complete).put("nodes", frameNodes))
        instrumentation.targetContext.filesDir.resolve("live-keeta-$phase.json").writeText(report.toString(), Charsets.UTF_8)
        instrumentation.sendStatus(0, Bundle().apply { putString("keetaReadPhase", phase); putInt("nodes", snapshot.count) })
    }
    @Test fun searchRestaurantAndReadProduct() {
        val args = InstrumentationRegistry.getArguments()
        val restaurant = args.getString("restaurant") ?: error("Missing restaurant")
        val item = args.getString("item") ?: error("Missing item")
        require(restaurant.length in 1..100 && item.length in 1..100)
        @Suppress("DEPRECATION")
        check(instrumentation.targetContext.packageManager.getPackageInfo(provider, 0).versionName == "3.13.203")
        var root = awaitRoot { has(it) { n -> label(n) == "homepage header searchbar" } }
        try {
            val search = find(root) { n -> label(n) == "homepage header searchbar" && n.isClickable &&
                n.className?.toString() == "android.widget.FrameLayout" && n.parent?.let { p ->
                    try { id(p, "search_bar_container") } finally { p.recycle() }
                } == true } ?: error("Home search anchor not unique")
            try { record(root, "home", restaurant, item); click(root, search) } finally { search.recycle() }
        } finally { root.recycle() }
        root = awaitRoot { has(it) { n -> label(n) == "search page box" } }
        try {
            val box = find(root) { label(it) == "search page box" && it.className?.toString() == "android.widget.FrameLayout" }
                ?: error("Search box not unique")
            try {
                val input = find(box) { it.isEditable && it.isEnabled && it.className?.toString() == "android.widget.EditText" }
                    ?: error("Search editor not unique")
                try {
                    check(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, restaurant)
                    }))
                } finally { input.recycle() }
            } finally { box.recycle() }
        } finally { root.recycle() }
        root = awaitRoot { has(it) { n -> label(n) == "search page btn" && n.isClickable } }
        try {
            val submit = find(root) { label(it) == "search page btn" && it.isClickable &&
                it.className?.toString() == "android.view.ViewGroup" } ?: error("Search submit not unique")
            try { click(root, submit) } finally { submit.recycle() }
        } finally { root.recycle() }
        root = awaitRoot { has(it) { n -> id(n,"flash_list_view") } &&
            has(it) { n -> id(n,"tv_keyword") && label(n) == restaurant } }
        try {
            record(root, "results", restaurant, item)
            // Search cards expose their promotions but not their restaurant names on this version.
            // This is read-only candidate navigation; the menu must verify its own name below.
            val holder = first(root) { id(it, "dl_item_root") } ?: error("No public restaurant candidate")
            try {
                val card = find(holder) { n -> label(n) == "feed shopcard" && n.isClickable &&
                    n.className?.toString() == "android.view.ViewGroup" }
                    ?: error("Candidate button not unique")
                try { click(root, card) } finally { card.recycle() }
            } finally { holder.recycle() }
        } finally { root.recycle() }
        readRestaurantProduct(restaurant, item)
    }
    @Test fun readOpenedRestaurantAndProduct() {
        val args = InstrumentationRegistry.getArguments()
        val restaurant = args.getString("restaurant") ?: error("Missing restaurant")
        val item = args.getString("item") ?: error("Missing item")
        require(restaurant.length in 1..100 && item.length in 1..100)
        @Suppress("DEPRECATION")
        check(instrumentation.targetContext.packageManager.getPackageInfo(provider, 0).versionName == "3.13.203")
        readRestaurantProduct(restaurant, item)
    }
    @Test fun recordOpenedProduct() {
        val args = InstrumentationRegistry.getArguments()
        val restaurant = args.getString("restaurant") ?: error("Missing restaurant")
        val item = args.getString("item") ?: error("Missing item")
        require(restaurant.length in 1..100 && item.length in 1..100)
        @Suppress("DEPRECATION")
        check(instrumentation.targetContext.packageManager.getPackageInfo(provider, 0).versionName == "3.13.203")
        val root = awaitRoot { has(it) { n -> sameName(n,item) } && has(it) { n -> label(n) == "إضافة إلى السلة" } }
        try { record(root, "product", restaurant, item) } finally { root.recycle() }
    }
    @Test fun recordOpenedCartMenu() {
        val args = InstrumentationRegistry.getArguments()
        val restaurant = args.getString("restaurant") ?: error("Missing restaurant")
        val item = args.getString("item") ?: error("Missing item")
        val root = awaitRoot { has(it) { n -> app.octopus.local.providers.KeetaRestaurantName.matches(restaurant, label(n)) } &&
            has(it) { n -> label(n) == "إتمام الدفع" } && !has(it) { n -> label(n) == "إضافة إلى السلة" } }
        try { record(root, "cart-menu", restaurant, item) } finally { root.recycle() }
    }
    private fun readRestaurantProduct(restaurant: String, item: String) {
        var root = awaitRoot { has(it) { n -> app.octopus.local.providers.KeetaRestaurantName.matches(restaurant, label(n)) } &&
            has(it) { n -> n.className?.toString() == "androidx.recyclerview.widget.RecyclerView" } &&
            !has(it) { n -> id(n,"flash_list_view") } }
        try {
            record(root, "menu", restaurant, item)
            var title = find(root) { sameName(it, item) }
            var pages = 0
            while (title == null && pages++ < 12) {
                val list = find(root) { it.isScrollable && it.className?.toString() == "androidx.recyclerview.widget.RecyclerView" }
                    ?: error("Menu scroller not unique")
                try { check(list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) } finally { list.recycle() }
                root.recycle()
                root = awaitRoot { has(it) { n -> app.octopus.local.providers.KeetaRestaurantName.matches(restaurant, label(n)) } && !has(it) { n -> id(n,"flash_list_view") } }
                title = find(root) { sameName(it, item) }
            }
            val meal = title ?: error("Exact meal not found; no substitution")
            var card: AccessibilityNodeInfo? = null
            try {
                var parent = meal.parent
                for (i in 0 until 6) {
                    val current = parent ?: break
                    if (current.isClickable && current.className?.toString() == "android.widget.FrameLayout" &&
                        has(current) { id(it, "card_root") } && has(current) { id(it, "product_name") && sameName(it, item) }) { card=current; break }
                    parent = current.parent; current.recycle()
                }
                val target = card ?: error("Product card anchor unknown")
                check(has(target) { sameName(it, item) })
                click(root, target)
            } finally { card?.recycle(); meal.recycle() }
        } finally { root.recycle() }
        root = awaitRoot { has(it) { n -> sameName(n, item) } && has(it) { n -> label(n) == "إضافة إلى السلة" } }
        try { record(root, "product", restaurant, item) } finally { root.recycle() }
    }
}
