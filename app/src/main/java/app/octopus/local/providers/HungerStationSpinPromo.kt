package app.octopus.local.providers

/** Structure recorded on Honor / HS 8.0.303. Only its user-identified close icon is actionable. */
object HungerStationSpinPromo {
    private val securityText = Regex("captcha|security|verif|human|robot|تحقق|أمان|امان|إنسان|انسان", RegexOption.IGNORE_CASE)
    private val spinCaptions = setOf("لف", "spin")
    fun closeTarget(frame: CatalogFrame): CatalogNode? {
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
}
