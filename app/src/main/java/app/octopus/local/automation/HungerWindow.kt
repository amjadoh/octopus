package app.octopus.local.automation

/** The class of HungerStation's last window-state change (no window content). */
object HungerWindow {
    @Volatile var className: String? = null
    /** S22 / HS 8.0.304: "هل تقوم بالطلب لموقع مختلف؟" opens this transparent activity over the home screen. */
    fun locationPrompt(): Boolean = className?.endsWith(".ChangeLocationDialogTransparentActivity") == true
}
