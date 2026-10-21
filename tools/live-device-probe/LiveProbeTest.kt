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
        fun inspectControls(node: android.view.accessibility.AccessibilityNodeInfo) {
            if (visited++ >= 400 || !node.isVisibleToUser || node.isPassword || node.isEditable ||
                app.octopus.local.diagnostics.InspectionRedactor.privateSubtree(node.viewIdResourceName.orEmpty()) ||
                (android.os.Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive)) return
            listOfNotNull(node.text, node.contentDescription).map { it.toString().trim().lowercase() }
                .mapNotNull { known[it] }.distinct().forEach { tag ->
                    val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
                    controls.put(JSONObject().put("tag", tag).put("clickable", node.isClickable)
                        .put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom))))
                }
            for (i in 0 until node.childCount.coerceAtMost(100)) {
                val child = node.getChild(i) ?: continue
                try { inspectControls(child) } finally { @Suppress("DEPRECATION") child.recycle() }
            }
        }
        windows.filter { it.isActive }.forEach { window ->
            val root = window.root
            try { if (root?.packageName?.toString() == expected) inspectControls(root) }
            finally { @Suppress("DEPRECATION") root?.recycle() }
        }
        result.put("functionalControls", controls)
        instrumentation.targetContext.filesDir.resolve("live-structure.json").writeText(result.toString(), Charsets.UTF_8)
        instrumentation.sendStatus(0, Bundle().apply { putString("liveCapture", result.toString()) })
        check(captured != null) { "Expected provider foreground window unavailable" }
    }
}
