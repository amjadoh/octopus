package app.octopus.local.domain

/** Display filtering only. Never use this for basket or product identity. */
object CatalogSearch {
    private fun normalized(value: String): String = value.lowercase(java.util.Locale.ROOT)
        .replace(Regex("[\\u064B-\\u065F\\u0670\\u0640]"), "")
        .replace(Regex("[أإآٱ]"), "ا").replace('ى', 'ي')
        .replace(Regex("\\s+"), " ").trim()

    fun matches(name: String, query: String): Boolean {
        val text = normalized(name)
        return normalized(query).split(' ').filter { it.isNotEmpty() }.all { it in text }
    }
}
