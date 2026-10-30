package app.octopus.local.automation

import app.octopus.local.domain.*
import app.octopus.local.providers.BasketEvidence
import java.security.MessageDigest

interface BasketJournalStore {
    fun read(): Map<String, String>
    fun write(values: Map<String, String>)
}

/** Durable ambiguity barrier, not a substitute for reading the live provider cart. */
class BasketMutationJournal(private val store: BasketJournalStore) {
    companion object {
        const val UNRESOLVED = "لم تُحسم نتيجة تعديل سابق؛ يجب قراءة السلة كاملة قبل أي إضافة أو تعديل جديد."
        const val HIDDEN = "سبق وجود سلة لهذا المطعم لكن محتوياتها غير ظاهرة الآن؛ لم أعتبرها فارغة ولم أكرر الإضافة."
        const val LEGACY_UNRESOLVED = "يوجد سجل قديم لسلة لم تُطابق هويتها بعد؛ يجب قراءة السلة السابقة كاملة قبل السماح بإضافة أولى جديدة."
    }
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    // Use the same conservative name identity as the request fingerprint. Display-only case,
    // whitespace or direction marks must not make a previously seen cart look like a new one.
    private fun restaurantHash(restaurant: String) = hash(IdentityText.clean(restaurant))
    private fun seen(provider: ProviderId, restaurant: String) = "seen2.${provider.name}.${restaurantHash(restaurant)}"
    private fun legacyKeys(provider: ProviderId, restaurant: String) =
        setOf(hash(restaurant), restaurantHash(restaurant)).map { "seen.${provider.name}.$it" }
    private fun pendingMatches(value: String?, restaurant: String): Boolean {
        if (value == null) return false
        val parts = value.split(':')
        return when {
            parts.size == 3 && parts[0] == "2" -> parts[1] == restaurantHash(restaurant)
            parts.size == 2 -> parts[0] in setOf(hash(restaurant), restaurantHash(restaurant))
            else -> false
        }
    }
    @Synchronized fun requireFirstAdd(provider: ProviderId, request: CanonicalCart) {
        val values = store.read()
        check(values["pending.${provider.name}"] == null) { UNRESOLVED }
        check(values[seen(provider, request.restaurant)] == null) { HIDDEN }
        check(legacyKeys(provider, request.restaurant).none { it in values }) { HIDDEN }
        // A legacy raw-name hash cannot be reversed to detect formatting aliases. Do not
        // silently discard it; a complete observation of that known name migrates it below.
        check(values.keys.none { it.startsWith("seen.${provider.name}.") }) { LEGACY_UNRESOLVED }
    }
    /** Explicit user attestation after clearing this restaurant in the provider app.
     * This resets only the durable ambiguity barrier; the executor still requires its
     * fresh, provider-specific empty-cart proof before any first add. Never call on launch. */
    @Synchronized fun confirmExternalClear(provider: ProviderId, request: CanonicalCart) {
        val values = store.read().toMutableMap()
        val pendingKey = "pending.${provider.name}"
        check(values[pendingKey] == null || pendingMatches(values[pendingKey], request.restaurant)) { UNRESOLVED }
        values.remove(pendingKey)
        values.remove(seen(provider, request.restaurant))
        legacyKeys(provider, request.restaurant).forEach(values::remove)
        store.write(values)
    }
    /** Commit before dispatch. Failed dispatch and cancellation deliberately remain unresolved. */
    @Synchronized fun beforeDispatch(provider: ProviderId, request: CanonicalCart) {
        val values = store.read().toMutableMap()
        check(values["pending.${provider.name}"] == null) { UNRESOLVED }
        values["pending.${provider.name}"] = "2:" + restaurantHash(request.restaurant) + ":" + hash(request.fingerprint)
        values[seen(provider, request.restaurant)] = "1"
        store.write(values)
    }
    /** Recovery requires complete, positively parsed rows belonging to the current full request. */
    @Synchronized fun observeConfigured(request: CanonicalCart,
        proof: app.octopus.local.safety.ConfiguredBasketVerification,
        frame: app.octopus.local.providers.CatalogFrame, version: String, now: Long): Boolean {
        if (!proof.matches(request, frame, version, now) ||
            !app.octopus.local.providers.BasketItemMatcher.compare(request, proof.resolved).matches) return false
        val provider = proof.resolved.provider
        val values = store.read().toMutableMap()
        val key = "pending.${provider.name}"
        if (values[key] != null && !pendingMatches(values[key], request.restaurant)) return false
        values.remove(key)
        legacyKeys(provider, request.restaurant).forEach(values::remove)
        values[seen(provider, request.restaurant)] = "1"
        store.write(values)
        return true
    }

    @Synchronized fun observe(request: CanonicalCart, evidence: BasketEvidence): Boolean {
        if (!evidence.complete || evidence.restaurant != request.restaurant || evidence.lines.isEmpty() ||
            evidence.lines.map { it.name }.distinct().size != evidence.lines.size ||
            request.items.any { it.options.isNotEmpty() || it.size != null || !it.note.isNullOrBlank() } ||
            evidence.lines.any { row -> row.choices.isNotEmpty() || row.quantity !in 1..99 ||
                row.lineAmount == null || row.lineAmount < 0 || request.items.none { it.name == row.name } }) return false
        val sum = runCatching { evidence.lines.fold(0L) { total, row -> Math.addExact(total, row.lineAmount!!) } }.getOrNull()
        if (sum == null || sum != evidence.subtotal) return false
        val values = store.read().toMutableMap()
        val pendingKey = "pending.${evidence.provider.name}"
        if (pendingMatches(values[pendingKey], request.restaurant)) values.remove(pendingKey)
        legacyKeys(evidence.provider, request.restaurant).forEach(values::remove)
        values[seen(evidence.provider, request.restaurant)] = "1"
        store.write(values)
        return true
    }
}
