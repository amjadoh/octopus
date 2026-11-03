package app.octopus.local.automation

import android.os.SystemClock

/** The class of HungerStation's last window-state change (no window content), and when it came. */
object HungerWindow {
    @Volatile var className: String? = null
        set(value) { field = value; at = SystemClock.elapsedRealtime() }
    @Volatile private var at = 0L
    /** S22 / HS 8.0.304: "هل تقوم بالطلب لموقع مختلف؟" opens this transparent activity over the home screen.
     * Trusted only when it opened at or after [sinceElapsed]; an older record may already be gone. */
    fun locationPrompt(sinceElapsed: Long): Boolean =
        className?.endsWith(".ChangeLocationDialogTransparentActivity") == true && at >= sinceElapsed
}
