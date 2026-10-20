package app.octopus.local.safety

import app.octopus.local.domain.ProviderId
import app.octopus.local.providers.CatalogFrame
import app.octopus.local.providers.CatalogScreen
import app.octopus.local.providers.HungerStationCatalog

/** A checkout button is not proof that its containing restaurant screen is checkout.
 * Such controls are blanked and disabled, never admitted as navigation targets.
 * All other financial IDs still stop capture before their contents are read. */
object CatalogFinancialBoundary {
    enum class Decision { NORMAL, DISABLED_CONTROL, STOP }

    fun classify(id: String, className: String, clickable: Boolean, editable: Boolean,
        provider: ProviderId): Decision {
        val resource = id.substringAfterLast('/').lowercase()
        if (Regex("credit.?card|bank.?card|cvv|card.?number|card.?expiry|card.?holder|card.?security")
                .containsMatchIn(resource)) return Decision.STOP
        if (!Regex("checkout|payment").containsMatchIn(resource)) return Decision.NORMAL
        val buttonId = Regex("(?:checkout|payment)[_-]?(?:button|btn)$|^(?:button|btn)[_-]?(?:checkout|payment)$")
            .containsMatchIn(resource)
        val buttonClass = className in setOf("android.widget.Button", "android.widget.TextView",
            "android.widget.FrameLayout", "android.view.View", "android.view.ViewGroup")
        return if (provider == ProviderId.HUNGERSTATION && buttonId && buttonClass && clickable && !editable)
            Decision.DISABLED_CONTROL else Decision.STOP
    }

    fun permitsDisabledControls(frame: CatalogFrame, provider: ProviderId): Boolean =
        provider == ProviderId.HUNGERSTATION && frame.complete &&
            HungerStationCatalog.screen(frame) in setOf(CatalogScreen.MENU, CatalogScreen.CART)

    /** Capture and target lookup must use the same child traversal policy. */
    fun permitsChildren(id: String, className: String, clickable: Boolean, editable: Boolean,
        provider: ProviderId): Boolean =
        !editable && classify(id, className, clickable, editable, provider) == Decision.NORMAL
}
