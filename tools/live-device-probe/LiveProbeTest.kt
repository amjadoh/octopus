package app.octopus.local

import android.app.UiAutomation
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.octopus.local.automation.InspectionReader
import app.octopus.local.diagnostics.InspectionRequest
import app.octopus.local.diagnostics.InspectionStage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Shell-invoked, structure-only live probe. Standard runner initializes Application before this. */
@RunWith(AndroidJUnit4::class)
class LiveProbeTest {
    /** Read-only known recommendation sheet; no click, Back, input or scrolling. */
    @Test fun captureHungerRecommendations() {
        val i=InstrumentationRegistry.getInstrumentation()
        val pkg="com.hungerstation.android.web"
        @Suppress("DEPRECATION")
        val version=i.targetContext.packageManager.getPackageInfo(pkg,0).versionName
        check(version=="8.0.303") {"Unsupported HungerStation version"}
        val a=i.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        a.serviceInfo=a.serviceInfo.apply { flags=flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS }
        runCatching {a.waitForIdle(650,4000)}
        val root=a.rootInActiveWindow ?: error("No active window")
        val headings=setOf("لا تفوت الفرصة!\nعادةً ما يضيف الأشخاص هذه العناصر",
            "لا تفوت الفرصة!\nفرصتك لإضافة المزيد لطلبك")
        val footer="اذهب للدفع"
        val nodes=mutableListOf<app.octopus.local.providers.CatalogNode>()
        try {
            check(root.packageName?.toString()==pkg) {"HungerStation is not foreground"}
            fun walk(n:android.view.accessibility.AccessibilityNodeInfo,parent:Int,depth:Int) {
                check(depth<=35 && nodes.size<1800 && n.childCount<=200) {"Incomplete recommendation structure"}
                if(!n.isVisibleToUser)return
                val id=n.viewIdResourceName.orEmpty()
                check(!n.isEditable && !n.isPassword &&
                    !app.octopus.local.diagnostics.InspectionRedactor.privateSubtree(id) &&
                    !Regex("checkout|payment|credit|bank|cvv",RegexOption.IGNORE_CASE).containsMatchIn(id) &&
                    !(android.os.Build.VERSION.SDK_INT>=34 && n.isAccessibilityDataSensitive)) {"Protected or non-recommendation content"}
                val raw=(n.text ?: n.contentDescription)?.toString().orEmpty().trim()
                val label=raw.takeIf {it in headings || it==footer}.orEmpty()
                val index=nodes.size
                nodes+=app.octopus.local.providers.CatalogNode(index,parent,id,label,n.className?.toString().orEmpty(),
                    n.isClickable,false,n.isScrollable,n.isEnabled,omittedText=raw.isNotEmpty() && label.isEmpty())
                for(k in 0 until n.childCount)n.getChild(k)?.let {try{walk(it,index,depth+1)}finally{it.recycle()}}
            }
            walk(root,-1,0)
            check(nodes.count {it.label in headings}==1) {"Known recommendation heading missing or ambiguous"}
            check(nodes.singleOrNull {it.label==footer}?.let {it.enabled && it.clickable &&
                it.className=="android.widget.Button"}==true) {"Known single review navigation button missing"}
            check(nodes.any {it.id=="menuItemActionSingleButtonAdd"}) {"Recommendation Add anchors missing"}
            val frame=app.octopus.local.providers.CatalogFrame(nodes,true)
            val output=JSONObject().put("provider","HUNGERSTATION").put("version",version)
                .put("capturedAt",System.currentTimeMillis()).put("actionsPerformed",0)
                .put("catalogFrame",JSONObject(com.google.gson.Gson().toJson(frame)))
            i.targetContext.filesDir.resolve("live-hs-recommendations.json").writeText(output.toString(),Charsets.UTF_8)
        } finally {root.recycle()}
    }

    @Test fun inspectHungerProductQuantity() {
        val i=InstrumentationRegistry.getInstrumentation()
        val a=i.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        a.serviceInfo=a.serviceInfo.apply {flags=flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS}
        val title=InstrumentationRegistry.getArguments().getString("item") ?: error("Missing selected food")
        fun nodes(root:android.view.accessibility.AccessibilityNodeInfo):List<android.view.accessibility.AccessibilityNodeInfo> {
            val result=mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
            fun walk(n:android.view.accessibility.AccessibilityNodeInfo,depth:Int) {
                if(depth>30 || result.size>=1000 || !n.isVisibleToUser || n.isEditable || n.isPassword ||
                    app.octopus.local.diagnostics.InspectionRedactor.privateSubtree(n.viewIdResourceName.orEmpty()) ||
                    (android.os.Build.VERSION.SDK_INT>=34 && n.isAccessibilityDataSensitive)) return
                result+=android.view.accessibility.AccessibilityNodeInfo.obtain(n)
                for(k in 0 until n.childCount.coerceAtMost(100)) n.getChild(k)?.let {try{walk(it,depth+1)}finally{it.recycle()}}
            }
            walk(root,0); return result
        }
        var pendingRoot=a.rootInActiveWindow
        val rootDeadline=android.os.SystemClock.elapsedRealtime()+8000
        while(pendingRoot==null && android.os.SystemClock.elapsedRealtime()<rootDeadline){android.os.SystemClock.sleep(200);pendingRoot=a.rootInActiveWindow}
        val root=pendingRoot ?: error("No active window")
        try {
            check(root.packageName.toString()=="com.hungerstation.android.web")
            val all=nodes(root)
            try {
                if(all.none {it.viewIdResourceName=="pdpCloseButton"}) {
                    check(all.any {it.viewIdResourceName=="menuBackButton"})
                    val selected=all.single {it.isClickable && (it.text ?: it.contentDescription)?.toString()?.lineSequence()?.first()?.trim()==title}
                    check(selected.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                }
            } finally {all.forEach {it.recycle()}}
        } finally {root.recycle()}
        val deadline=android.os.SystemClock.elapsedRealtime()+8000
        while(android.os.SystemClock.elapsedRealtime()<deadline) {
            val current=a.rootInActiveWindow
            if(current!=null) try {
                check(current.packageName.toString()=="com.hungerstation.android.web")
                val all=nodes(current)
                try {
                    if(all.any {it.viewIdResourceName=="pdpCloseButton"} && all.any {it.viewIdResourceName=="flutterAddButton"} &&
                        all.any {(it.text ?: it.contentDescription)?.toString()?.trim()==title}) {
                        val output=JSONArray()
                        fun record(n:android.view.accessibility.AccessibilityNodeInfo,parent:Int,depth:Int) {
                            if(depth>30 || !n.isVisibleToUser || n.isEditable || n.isPassword ||
                                app.octopus.local.diagnostics.InspectionRedactor.privateSubtree(n.viewIdResourceName.orEmpty()))return
                            val index=output.length()
                            output.put(JSONObject().put("index",index).put("parent",parent).put("id",n.viewIdResourceName.orEmpty())
                                .put("label",(n.text ?: n.contentDescription)?.toString().orEmpty()).put("className",n.className.toString())
                                .put("clickable",n.isClickable).put("enabled",n.isEnabled).put("editable",false).put("scrollable",n.isScrollable))
                            for(k in 0 until n.childCount.coerceAtMost(100))n.getChild(k)?.let {try{record(it,index,depth+1)}finally{it.recycle()}}
                        }
                        record(current,-1,0)
                        i.targetContext.filesDir.resolve("live-hs-product-quantity.json").writeText(JSONObject().put("complete",true).put("nodes",output).toString(),Charsets.UTF_8)
                        return
                    }
                }finally{all.forEach{it.recycle()}}
            }finally{current.recycle()}
            android.os.SystemClock.sleep(200)
        }
        error("Selected product did not appear; no add performed")
    }
    @Test fun captureHungerMenuQuantity() {
        val i=InstrumentationRegistry.getInstrumentation()
        val a=i.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        a.serviceInfo=a.serviceInfo.apply { flags=flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        runCatching { a.waitForIdle(750,5000) }
        val root=a.rootInActiveWindow ?: error("No root")
        val item=InstrumentationRegistry.getArguments().getString("item") ?: error("Missing item")
        try {
            check(root.packageName?.toString()=="com.hungerstation.android.web")
            val nodes=JSONArray()
            fun walk(n:android.view.accessibility.AccessibilityNodeInfo,parent:Int,publicRow:Boolean=false,depth:Int=0) {
                if(depth>35 || nodes.length()>1800 || !n.isVisibleToUser || n.isPassword || n.isEditable ||
                    app.octopus.local.diagnostics.InspectionRedactor.privateSubtree(n.viewIdResourceName.orEmpty()) ||
                    (android.os.Build.VERSION.SDK_INT>=34 && n.isAccessibilityDataSensitive)) return
                val id=n.viewIdResourceName.orEmpty()
                val raw=(n.text ?: n.contentDescription)?.toString().orEmpty()
                val cell=app.octopus.local.providers.CatalogNode(0,-1,id,raw,n.className?.toString().orEmpty(),n.isClickable,false,n.isScrollable,n.isEnabled)
                val keep=publicRow || app.octopus.local.providers.HungerStationCatalog.menuItem(cell)?.name==item || id=="FlutterMenu_goToCartButton"
                val index=nodes.length()
                nodes.put(JSONObject().put("index",index).put("parent",parent).put("id",id)
                    .put("label",if(keep)raw else "").put("className",cell.className).put("clickable",n.isClickable)
                    .put("enabled",n.isEnabled).put("editable",false).put("scrollable",n.isScrollable))
                for(k in 0 until n.childCount.coerceAtMost(200)) n.getChild(k)?.let { child ->
                    try {walk(child,index,keep,depth+1)} finally {child.recycle()}
                }
            }
            walk(root,-1)
            val report=JSONObject().put("complete",true).put("nodes",nodes)
            i.targetContext.filesDir.resolve("live-hs-menu-quantity.json").writeText(report.toString(),Charsets.UTF_8)
            i.sendStatus(0,Bundle().apply {putInt("publicMenuNodes",nodes.length())})
        } finally {root.recycle()}
    }
    @Test fun captureStructure() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val lock = instrumentation.targetContext.getSystemService(android.content.Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        check(!lock.isKeyguardLocked) { "Phone locked; no capture performed" }
        val args = InstrumentationRegistry.getArguments()
        val expected = when (args.getString("provider")) {
            "hs" -> "com.hungerstation.android.web"
            "keeta" -> "com.sankuai.sailor.afooddelivery"
            else -> error("Unsupported provider")
        }
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        runCatching { automation.waitForIdle(500, 3000) }
        val windows = automation.windows
        val metadata = JSONArray()
        var captured: app.octopus.local.automation.InspectionSnapshot? = null
        windows.forEach { window ->
            val root = window.root
            try {
                val matches = root?.packageName?.toString() == expected
                metadata.put(JSONObject().put("type", window.type).put("active", window.isActive)
                    .put("focused", window.isFocused).put("expectedProvider", matches))
                if (matches && window.isActive && captured == null && root != null) {
                    captured = InspectionReader().read(root, InspectionRequest(InspectionStage.REVIEW, "", ""))
                }
            } finally { @Suppress("DEPRECATION") root?.recycle() }
        }
        val result = JSONObject().put("windows", metadata).put("captured", captured != null)
            .put("nodes", JSONArray(captured?.nodes ?: "[]")).put("truncated", captured?.truncated ?: true)
        val controls = JSONArray()
        val known = mapOf("إغلاق" to "CLOSE", "اغلاق" to "CLOSE", "close" to "CLOSE",
            "close button" to "CLOSE", "dismiss" to "CLOSE", "تخطي" to "SKIP", "skip" to "SKIP",
            "لف" to "SPIN", "لف واربح" to "SPIN_PROMO", "spin" to "SPIN", "عجلة الحظ" to "SPIN_PROMO",
            "حاول مرة أخرى" to "RETRY", "إعادة المحاولة" to "RETRY", "retry" to "RETRY", "موافق" to "OK")
        var visited = 0
        val publicCartControls = JSONArray()
        val pricingOnly = JSONArray()
        val pricingLabels = setOf("إتمام الدفع", "تأكيد معلومات الطلب", "تفاصيل الطلب", "تأكيد معلومات التوصيل", "مراجعة الطلب", "تأكيد الطلب", "الدفع", "Checkout", "Order review","الإجمالي", "المجموع", "المجموع الفرعي", "إجمالي الطلب", "المبلغ الإجمالي", "رسوم التوصيل", "رسوم الخدمة", "رسوم الطلب الصغير", "الخصم", "ضريبة القيمة المضافة", "Total", "Subtotal", "Delivery fee", "Service fee")
        val amountPattern = "[0-9٠-٩]{1,5}(?:[.,٫][0-9٠-٩]{1,2})?"
        val currencyPattern = "(?:[§﷼]|SAR|ر\\.س)"
        val publicMoney = Regex("(?:$currencyPattern\\s*$amountPattern|$amountPattern\\s*$currencyPattern)")
        fun inspectControls(node: android.view.accessibility.AccessibilityNodeInfo, publicCart: Boolean = false) {
            if (visited++ >= 400 || !node.isVisibleToUser || node.isPassword || node.isEditable ||
                app.octopus.local.diagnostics.InspectionRedactor.privateSubtree(node.viewIdResourceName.orEmpty()) ||
                (android.os.Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive)) return
            val cart = publicCart || (expected == "com.hungerstation.android.web" && node.viewIdResourceName in
                setOf("FlutterMenu_goToCartButton", "FlutterMenu_goToCartQuantity", "FlutterCart_title", "FlutterCart_cartItem"))
            // Exact public pricing labels/amounts only; never persist arbitrary review text.
            listOfNotNull(node.text, node.contentDescription).map {
                it.toString().replace(Regex("[\\u200e\\u200f\\u202a-\\u202e\\u2066-\\u2069]"), "").trim()
            }.filter { it in pricingLabels || publicMoney.matches(it) }.distinct().forEach {
                pricingOnly.put(JSONObject().put("value", it).put("id", node.viewIdResourceName.orEmpty()))
            }
            listOfNotNull(node.text, node.contentDescription).forEach { raw ->
                val lines = raw.toString().replace(Regex("[\\u200e\\u200f\\u202a-\\u202e\\u2066-\\u2069]"), "")
                    .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
                lines.forEachIndexed { i, label ->
                    if (label in pricingLabels) {
                        val following = lines.drop(i + 1).take(2)
                        val amount = following.firstOrNull()?.takeIf { publicMoney.matches(it) }
                            ?: following.joinToString(" ").takeIf { publicMoney.matches(it) }
                        if (amount != null) pricingOnly.put(JSONObject().put("label", label).put("value", amount))
                    }
                }
            }
            if (cart) {
                val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
                publicCartControls.put(JSONObject().put("id",node.viewIdResourceName.orEmpty())
                    .put("label",(node.text ?: node.contentDescription)?.toString().orEmpty())
                    .put("class",node.className?.toString()).put("clickable",node.isClickable)
                    .put("bounds",JSONArray(listOf(bounds.left,bounds.top,bounds.right,bounds.bottom))))
            }
            listOfNotNull(node.text, node.contentDescription).map { it.toString().trim().lowercase() }
                .mapNotNull { known[it] }.distinct().forEach { tag ->
                    val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
                    controls.put(JSONObject().put("tag", tag).put("clickable", node.isClickable)
                        .put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom))))
                }
            for (i in 0 until node.childCount.coerceAtMost(100)) {
                val child = node.getChild(i) ?: continue
                try { inspectControls(child, cart) } finally { @Suppress("DEPRECATION") child.recycle() }
            }
        }
        windows.filter { it.isActive }.forEach { window ->
            val root = window.root
            try { if (root?.packageName?.toString() == expected) {
                inspectControls(root)
                val snapshot = app.octopus.local.automation.SnapshotReader().read(root)
                val quote = app.octopus.local.pricing.QuoteExtractor().extract(snapshot)
                result.put("safeQuote", JSONObject()
                    .put("capturedAt", System.currentTimeMillis())
                    .put("sensitive", snapshot.sensitiveScreen)
                    .put("truncated", snapshot.truncated)
                    .put("fields", JSONObject(quote.fields.mapKeys { it.key.name })))
            } }
            finally { @Suppress("DEPRECATION") root?.recycle() }
        }
        result.put("functionalControls", controls)
        result.put("publicCartControls", publicCartControls)
        result.put("pricingOnly", pricingOnly)
        instrumentation.targetContext.filesDir.resolve("live-structure.json").writeText(result.toString(), Charsets.UTF_8)
        instrumentation.sendStatus(0, Bundle().apply {
            putBoolean("liveCaptured", captured != null)
            putString("safeQuote", result.optJSONObject("safeQuote")?.toString())
        })
        check(captured != null) { "Expected provider foreground window unavailable" }
    }
}
