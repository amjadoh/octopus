package app.octopus.local.safety

import app.octopus.local.domain.ProviderId
import app.octopus.local.providers.ProviderPolicies

enum class ProposedAction { CLICK, SCROLL, INPUT_TEXT, GESTURE, GLOBAL_ACTION, CREATE_ORDER, PAYMENT }

class SafetyGuard {
    // Phase one is read-only. There is intentionally no Android action executor.
    fun allowsAction(@Suppress("UNUSED_PARAMETER") action: ProposedAction): Boolean = false
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
