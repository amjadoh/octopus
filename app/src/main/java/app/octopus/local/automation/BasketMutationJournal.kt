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
    }
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    private fun seen(provider: ProviderId, restaurant: String) = "seen.${provider.name}.${hash(restaurant)}"
    @Synchronized fun requireFirstAdd(provider: ProviderId, request: CanonicalCart) {
        val values = store.read()
        check(values["pending.${provider.name}"] == null) { UNRESOLVED }
        check(values[seen(provider, request.restaurant)] == null) { HIDDEN }
    }
    /** Commit before dispatch. Failed dispatch and cancellation deliberately remain unresolved. */
    @Synchronized fun beforeDispatch(provider: ProviderId, request: CanonicalCart) {
        val values = store.read().toMutableMap()
        check(values["pending.${provider.name}"] == null) { UNRESOLVED }
        values["pending.${provider.name}"] = hash(request.restaurant) + ":" + hash(request.fingerprint)
        values[seen(provider, request.restaurant)] = "1"
        store.write(values)
    }
    /** Recovery requires complete, positively parsed rows belonging to the current full request. */
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
        if (values[pendingKey]?.substringBefore(':') == hash(request.restaurant)) values.remove(pendingKey)
        values[seen(evidence.provider, request.restaurant)] = "1"
        store.write(values)
        return true
    }
}
