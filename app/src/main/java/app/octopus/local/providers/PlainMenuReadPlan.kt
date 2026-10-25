package app.octopus.local.providers

import app.octopus.local.domain.*

/** A full-request, read-only price collection. Missing lines never become a partial subtotal. */
class PlainMenuReadPlan private constructor(
    private val requested: CanonicalCart,
    val provider: ProviderId,
    val version: String,
    val lines: List<CanonicalItem>,
) {
    private val requestFingerprint = requested.fingerprint
    private data class Read(val unit: Long, val at: Long)
    private val readings = mutableMapOf<String, Read>()
    private var rejected = false

    fun record(current: CanonicalCart, line: CanonicalItem, unit: Long?, at: Long, observedVersion: String): Boolean {
        val key = CartIdentity.itemKey(line)
        val expected = lines.singleOrNull { CartIdentity.itemKey(it) == key }
        val previous = readings[key]
        if (rejected || current.fingerprint != requestFingerprint || expected != line || unit == null ||
            unit < 0 || at < 0 || observedVersion != version || previous?.let { it.unit != unit || at < it.at } == true) {
            rejected = true
            return false
        }
        readings[key] = Read(unit, at)
        return true
    }

    fun preview(current: CanonicalCart, now: Long): MenuCostPreview? {
        if (rejected || current.fingerprint != requestFingerprint || readings.size != lines.size ||
            readings.values.any { it.at > now || now - it.at > 300_000 }) return null
        val total = runCatching { lines.fold(0L) { sum, line ->
            Math.addExact(sum, Math.multiplyExact(readings.getValue(CartIdentity.itemKey(line)).unit, line.quantity.toLong()))
        } }.getOrNull() ?: return null
        return MenuCostPreview(provider, requestFingerprint, total, emptyList(),
            readings.values.minOf { it.at }, version,
            "مجموع أسعار جميع الأصناف مقروء؛ رسوم الطلب والعروض المطبقة على السلة لم تُقرأ.")
    }

    companion object {
        const val MAX_LINES = 10
        fun create(cart: CanonicalCart, provider: ProviderId, version: String): PlainMenuReadPlan? {
            if (version.isBlank() || cart.restaurant.length > 200 || cart.items.any {
                it.name.length > 300 || it.size != null || it.options.isNotEmpty() || !it.note.isNullOrBlank()
            }) return null
            val grouped = cart.items.groupBy(CartIdentity::itemKey)
            if (grouped.size !in 1..MAX_LINES) return null
            val lines = grouped.values.map { values -> values.first().copy(quantity=values.sumOf { it.quantity }) }
            return PlainMenuReadPlan(cart, provider, version, lines)
        }
    }
}
