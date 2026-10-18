package app.octopus.local.automation

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import app.octopus.local.diagnostics.*
import org.json.JSONArray
import org.json.JSONObject

data class InspectionSnapshot(val nodes: String, val count: Int, val truncated: Boolean)

/** Does not return raw text, descriptions, editable values, screenshots, or window titles. */
class InspectionReader {
    fun read(root: AccessibilityNodeInfo, request: InspectionRequest): InspectionSnapshot {
        val nodes = JSONArray()
        var truncated = false
        var visited = 0
        fun walk(node: AccessibilityNodeInfo, parent: Int, depth: Int) {
            if (visited++ >= 400 || depth > 35) { truncated = true; return }
            if (!node.isVisibleToUser) return
            val index = nodes.length()
            val editable = node.isEditable || node.className?.toString()?.contains("EditText") == true
            if (node.isPassword) return
            val rect = Rect().also(node::getBoundsInScreen)
            val result = JSONObject().put("index", index).put("parent", parent)
                .put("class", InspectionRedactor.className(node.className) ?: JSONObject.NULL)
                .put("resourceId", InspectionRedactor.resourceId(node.viewIdResourceName) ?: JSONObject.NULL)
                .put("editable", editable).put("enabled", node.isEnabled).put("clickable", node.isClickable)
                .put("scrollable", node.isScrollable).put("selected", node.isSelected)
                .put("bounds", JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom)))
            if (!editable) {
                val tags = listOfNotNull(InspectionRedactor.tag(node.text, request),
                    InspectionRedactor.tag(node.contentDescription, request)).distinct()
                result.put("tags", JSONArray(tags))
                result.put("priceHints", JSONArray((InspectionRedactor.priceHints(node.text) +
                    InspectionRedactor.priceHints(node.contentDescription)).distinct()))
            }
            nodes.put(result)
            // An editable subtree may expose typed credentials through descendants.
            if (editable) return
            for (i in 0 until node.childCount.coerceAtMost(150)) {
                val child = node.getChild(i) ?: continue
                try { walk(child, index, depth + 1) }
                finally { @Suppress("DEPRECATION") child.recycle() }
            }
        }
        walk(root, -1, 0)
        return InspectionSnapshot(nodes.toString(), nodes.length(), truncated)
    }
}
