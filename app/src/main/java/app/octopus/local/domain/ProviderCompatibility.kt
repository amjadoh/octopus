package app.octopus.local.domain

/** Provider apps update themselves often. A later patch of a verified release runs on that release's
 * profile: every screen is still recognized by its structure and every basket guard still applies, so a
 * changed screen stops safely instead of the whole comparison refusing to start. */
object ProviderCompatibility {
    private val verified = mapOf(
        ProviderId.HUNGERSTATION to listOf("8.0.303"),
        ProviderId.KEETA to listOf("3.12.501", "3.13.203", "3.13.301"),
    )

    fun effective(provider: ProviderId, actual: String?): String? {
        if (actual == null) return null
        val known = verified[provider].orEmpty()
        if (actual in known) return actual
        val parts = actual.split('.').map { it.toIntOrNull() ?: return actual }
        if (parts.size != 3) return actual
        // The newest verified release of the same major.minor line, when the installed patch is later.
        return known.mapNotNull { v -> v.split('.').map(String::toInt).takeIf { it.size == 3 }?.let { v to it } }
            .filter { (_, k) -> k[0] == parts[0] && k[1] == parts[1] && parts[2] > k[2] && parts[2] - k[2] <= 99 }
            .maxByOrNull { (_, k) -> k[2] }?.first ?: actual
    }
}
