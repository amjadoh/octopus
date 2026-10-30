package app.octopus.local.automation

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import app.octopus.local.domain.*
import app.octopus.local.pricing.SnapshotPriceTextReader
import app.octopus.local.safety.ProviderAccessRestriction
import app.octopus.local.safety.ProviderAccessRestrictions

/** Never returns raw screen text, identifiers, names, addresses, or editable contents. */
class SnapshotReader {
    private val blocked = setOf("تسجيل الدخول", "رمز التحقق", "كلمة المرور", "رقم البطاقة", "login", "log in", "sign in", "sign up or log in", "enter your mobile number", "password", "card number", "captcha", "verification code")
    private fun normalize(value: String) = value.lowercase().trim().replace(Regex("[\u200e\u200f\u061c]"), "").replace(Regex("\\s+"), " ").removeSuffix(":").trim()

    fun read(root: AccessibilityNodeInfo): SafeSnapshot {
        val providerPackage = root.packageName?.toString()
        val tokens = mutableListOf<SafeToken>()
        var visited = 0
        var visible = 0
        var sensitive = false
        var truncated = false
        var restriction: ProviderAccessRestriction? = null
        fun walk(node: AccessibilityNodeInfo, path: String, parent: String, depth: Int) {
            if (visited++ >= 2500 || depth > 40) { truncated = true; return }
            if (!node.isVisibleToUser) return
            visible++
            // Skip before reading text or contentDescription.
            val editable = node.isEditable || node.className?.toString()?.contains("EditText") == true
            if (node.isPassword) { sensitive = true; return }
            // Read-only card/address fields can still expose private values. Exclude their entire
            // subtree before fetching text or descriptions, just as the structural inspector does.
            if (app.octopus.local.diagnostics.InspectionRedactor.privateSubtree(node.viewIdResourceName.orEmpty())) return
            if (android.os.Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive) {
                sensitive = true
                return
            }
            if (!editable) {
                val rect = Rect().also { node.getBoundsInScreen(it) }
                listOfNotNull(node.text, node.contentDescription).distinct().forEach { sequence ->
                    ProviderAccessRestrictions.detect(sequence)?.let { restriction = it }
                    SnapshotPriceTextReader.read(sequence, providerPackage).forEach { (field, amount) ->
                        tokens += SafeToken(field, amount, rect.top, rect.bottom, rect.left, rect.right, parent)
                    }
                    if (sequence.length > 120) return@forEach
                    val text = normalize(sequence.toString())
                    if (blocked.any { text == it || text.startsWith("$it ") }) sensitive = true
                }
            }
            if (editable) return
            for (index in 0 until node.childCount.coerceAtMost(300)) {
                val child = node.getChild(index) ?: continue
                try { walk(child, "$path/$index", path, depth + 1) }
                finally { @Suppress("DEPRECATION") child.recycle() }
            }
        }
        walk(root, "root", "", 0)
        return SafeSnapshot(if (restriction != null || sensitive) emptyList() else tokens.distinct(), visible, sensitive, truncated, restriction)
    }
}
