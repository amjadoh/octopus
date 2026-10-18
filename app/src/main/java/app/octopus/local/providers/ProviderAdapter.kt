package app.octopus.local.providers

import app.octopus.local.domain.*
import app.octopus.local.pricing.QuoteExtractor

interface ProviderAdapter {
    val provider: ProviderId
    val adapterVersion: String
    val deviceVerified: Boolean
    fun extract(snapshot: SafeSnapshot): QuoteCandidate
}

// Generic diagnostic extraction only. No selectors or screen flows are invented.
class KeetaDiagnosticAdapter : ProviderAdapter {
    override val provider = ProviderId.KEETA
    override val adapterVersion = "diagnostic-labels-0.1"
    override val deviceVerified = false
    override fun extract(snapshot: SafeSnapshot) = QuoteExtractor().extract(snapshot)
}
class HungerStationDiagnosticAdapter : ProviderAdapter {
    override val provider = ProviderId.HUNGERSTATION
    override val adapterVersion = "diagnostic-labels-0.1"
    override val deviceVerified = false
    override fun extract(snapshot: SafeSnapshot) = QuoteExtractor().extract(snapshot)
}
fun adapterFor(provider: ProviderId): ProviderAdapter = when (provider) {
    ProviderId.KEETA -> KeetaDiagnosticAdapter()
    ProviderId.HUNGERSTATION -> HungerStationDiagnosticAdapter()
}
