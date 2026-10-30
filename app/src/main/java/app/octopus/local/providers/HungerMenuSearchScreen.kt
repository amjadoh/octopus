package app.octopus.local.providers

/** A resumed native restaurant search permits closing only its observed search control. */
object HungerMenuSearchScreen {
    fun closeTarget(frame: CatalogFrame): CatalogNode? {
        if (!frame.complete || frame.nodes.count { it.editable } != 1 ||
            frame.nodes.any { Regex("password|otp|payment|checkout|credit|bank", RegexOption.IGNORE_CASE).containsMatchIn(it.id) }) return null
        val input = frame.nodes.singleOrNull { it.id == "menuSearchInput" } ?: return null
        if (!input.editable || !input.enabled || input.className != "android.widget.EditText") return null
        return frame.nodes.singleOrNull { it.id == "menuSearchCloseButton" }?.takeIf {
            it.clickable && it.enabled && !it.editable && it.className == "android.widget.ImageView"
        }
    }
}
