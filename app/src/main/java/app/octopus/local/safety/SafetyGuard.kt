package app.octopus.local.safety

import app.octopus.local.domain.ProviderId
import app.octopus.local.providers.ProviderPolicies
import app.octopus.local.providers.CatalogScreen

enum class ProposedAction { CLICK, SCROLL, INPUT_TEXT, GESTURE, GLOBAL_ACTION, CREATE_ORDER, PAYMENT }
enum class CatalogAction { OPEN_SEARCH, SET_QUERY, SELECT_SUGGESTION, OPEN_RESTAURANT, VIEW_MENU_NOTICE, SCROLL_MENU }

class SafetyGuard {
    // Phase one is read-only. There is intentionally no Android action executor.
    fun allowsAction(@Suppress("UNUSED_PARAMETER") action: ProposedAction): Boolean = false
    fun allowsCatalogAction(action: CatalogAction, screen: CatalogScreen, foregroundPackage: String?,
        consent: Boolean, active: Boolean, complete: Boolean, label: String): Boolean {
        if (!consent || !active || !complete || foregroundPackage != ProviderId.HUNGERSTATION.packageName) return false
        if (Regex("تأكيد|إتمام|اتمام|ادفع|دفع|شراء|اشتراك|place order|pay now|confirm|subscribe", RegexOption.IGNORE_CASE).containsMatchIn(label)) return false
        return when (action) {
            CatalogAction.OPEN_SEARCH -> screen == CatalogScreen.HOME
            CatalogAction.SET_QUERY, CatalogAction.SELECT_SUGGESTION -> screen == CatalogScreen.SEARCH
            CatalogAction.OPEN_RESTAURANT -> screen == CatalogScreen.RESULTS
            CatalogAction.VIEW_MENU_NOTICE -> screen == CatalogScreen.RESTAURANT_NOTICE && label == "عرض قائمة المطعم"
            CatalogAction.SCROLL_MENU -> screen == CatalogScreen.MENU
        }
    }
    fun allowsRead(provider: ProviderId, foregroundPackage: String?, active: Boolean, consent: Boolean, allowed: Boolean): Boolean =
        ProviderPolicies.forProvider(provider).permitsDiagnosticRead &&
            active && consent && allowed && foregroundPackage == provider.packageName
}

class StabilityGate(private val minimumDurationMillis: Long = 1_000) {
    private var fingerprint: String? = null
    private var firstSeen = 0L
    fun observe(value: String, atMillis: Long): Boolean {
        if (value != fingerprint) { fingerprint = value; firstSeen = atMillis; return false }
        return atMillis - firstSeen >= minimumDurationMillis
    }
    fun reset() { fingerprint = null; firstSeen = 0L }
}
