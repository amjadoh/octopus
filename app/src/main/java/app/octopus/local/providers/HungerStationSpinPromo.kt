package app.octopus.local.providers

/** Structure recorded on Honor / HS 8.0.303. Only its user-identified close icon is actionable. */
object HungerStationSpinPromo {
    private val securityText = Regex("captcha|security|verif|human|robot|تحقق|أمان|امان|إنسان|انسان", RegexOption.IGNORE_CASE)
    private val spinCaptions = setOf("لف", "spin")
    fun closeTarget(frame: CatalogFrame): CatalogNode? {
        brazeHomeClose(frame)?.let { return it }
        if (!frame.complete || frame.nodes.size != 26 || !frame.hasId("action_bar_root") ||
            !frame.hasId("android:id/content") ||
            frame.nodes.count { it.className == "androidx.compose.ui.platform.ComposeView" } != 1 ||
            frame.nodes.any { it.editable ||
                app.octopus.local.safety.ProviderAccessRestrictions.detect(it.label) != null ||
                securityText.containsMatchIn(it.label) }) return null
        fun parent(node: CatalogNode) = frame.nodes.singleOrNull { it.index == node.parent }
        val caption = frame.nodes.singleOrNull { it.className == "android.widget.TextView" && it.label.trim().lowercase() in spinCaptions }
            ?: return null
        val spin = parent(caption)?.takeIf { it.className == "android.view.View" && it.clickable && it.enabled }
            ?: return null
        val body = parent(spin)?.takeIf { it.className == "android.view.View" && !it.clickable } ?: return null
        val container = parent(body)?.takeIf { it.className == "android.view.View" && !it.clickable } ?: return null
        val siblings = frame.nodes.filter { it.parent == container.index }
        if (siblings.size != 2 || body !in siblings) return null
        return siblings.singleOrNull { it != body && it.className == "android.view.View" && it.clickable &&
            it.enabled && !it.editable && frame.nodes.none { child -> child.parent == it.index } }
    }
    /** Explicit Close button in the observed Braze home advert, never a security challenge. */
    private fun brazeHomeClose(frame: CatalogFrame): CatalogNode? {
        if (!frame.complete || !frame.hasId("new_search_card_view") || frame.nodes.any {
                app.octopus.local.safety.ProviderAccessRestrictions.detect(it.label) != null || securityText.containsMatchIn(it.label) }) return null
        val container=frame.nodes.singleOrNull { it.id.endsWith(":id/com_braze_inappmessage_html") &&
            it.className=="android.widget.RelativeLayout" } ?: return null
        val body=frame.descendants(container.index)
        if (body.size !in 5..30 || body.any {it.editable} || body.count {it.className=="android.webkit.WebView"} != 2) return null
        return body.singleOrNull { it.className=="android.widget.Button" && it.clickable && it.enabled &&
            it.label.trim().lowercase() in setOf("إغلاق","اغلاق","close","close button","dismiss") }
    }
}
