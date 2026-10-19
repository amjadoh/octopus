package app.octopus.local.domain

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/** Deliberately conservative: no fuzzy Arabic spelling or meal-size substitutions. */
object IdentityText {
    fun clean(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC)
        .replace(Regex("[\\u200e\\u200f\\u061c]"), "")
        .trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
}

object CartIdentity {
    // Length prefixes prevent collisions when names contain separators.
    private fun encode(parts: List<String>): String = parts.joinToString("") { "${it.length}:$it" }
    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    fun itemKey(item: CanonicalItem): String = hash(encode(listOf(
        IdentityText.clean(item.name), if (item.size == null) "0" else "1" + IdentityText.clean(item.size),
        encode(item.options.map { encode(listOf(IdentityText.clean(it.group), IdentityText.clean(it.value), it.quantity.toString())) }.sorted()),
        item.note?.let(IdentityText::clean).orEmpty(),
    )))

    fun quantities(items: List<CanonicalItem>): Map<String, Int> = items.groupBy(::itemKey)
        .mapValues { (_, rows) -> rows.sumOf { it.quantity } }

    fun contentsFingerprint(restaurant: String, branch: String?, items: List<CanonicalItem>): String = hash(encode(listOf(
        IdentityText.clean(restaurant), if (branch == null) "0" else "1" + IdentityText.clean(branch),
        encode(quantities(items).toSortedMap().map { (key, quantity) -> encode(listOf(key, quantity.toString())) }),
    )))

    fun cartFingerprint(cart: CanonicalCart): String = hash(encode(listOf(
        contentsFingerprint(cart.restaurant, cart.branch, cart.items), cart.addressReference.orEmpty(),
    )))
}
