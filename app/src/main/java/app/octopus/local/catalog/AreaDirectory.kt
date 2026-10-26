package app.octopus.local.catalog

import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest

data class AreaEntry(val slug: String) {
    val title: String get() = slug.replace('-', ' ')
}

/** Only canonical links published by the provider can become selectable places. */
class AreaDirectory(private val cache: File? = null,
    private val client: PublicCatalogClient = PublicCatalogHttpClient(::allowed)) {
    companion object {
        const val ROOT = "https://hungerstation.com/sa-ar/restaurants/regions"
        fun allowed(url: String): Boolean = runCatching {
            val u = URI(url)
            val parts = u.path.split('/').filter(String::isNotEmpty)
            u.scheme == "https" && u.host == "hungerstation.com" && u.port == -1 &&
                u.rawUserInfo == null && u.rawQuery == null && u.rawFragment == null &&
                !u.path.contains("//") && parts.size in 3..4 &&
                parts.take(3) == listOf("sa-ar", "restaurants", "regions") &&
                (parts.size == 3 || valid(parts.last()))
        }.getOrDefault(false)
        private fun valid(value: String) = value.length in 1..100 && value.matches(Regex("[\\p{L}\\p{N} -]+"))
        fun parse(html: String, city: AreaEntry? = null): List<AreaEntry> {
            require(html.toByteArray(Charsets.UTF_8).size <= HungerStationPublicParser.MAX_HTML_BYTES)
            val expected = if (city == null) 4 else 5
            return Regex("<a\\b[^>]*\\bhref=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                .findAll(html).mapNotNull { match -> runCatching {
                    val u = URI(ROOT).resolve(match.groupValues[1].replace("&amp;", "&"))
                    val parts = u.path.split('/').filter(String::isNotEmpty)
                    if (u.scheme != "https" || u.host != "hungerstation.com" || u.port != -1 ||
                        u.rawUserInfo != null || u.rawQuery != null || u.rawFragment != null || u.path.contains("//") ||
                        parts.size != expected || parts.take(3) != listOf("sa-ar", "restaurants", "regions") ||
                        parts.drop(3).any { !valid(it) } || (city != null && parts[3] != city.slug)) null
                    else AreaEntry(parts.last())
                }.getOrNull() }.distinctBy { it.slug }.take(2000).toList()
        }
    }
    private fun read(city: AreaEntry?): List<AreaEntry> {
        val url = ROOT + (city?.let { "/" + URLEncoder.encode(it.slug, "UTF-8").replace("+", "%20") } ?: "")
        require(allowed(url))
        val key = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        val saved = cache?.resolve("$key.html")
        return try {
            val html = client.get(url)
            val rows = parse(html, city)
            check(rows.isNotEmpty()) { "لم يعرض المصدر قائمة مناطق معروفة." }
            // Cache only public names; no GPS, address, cookies or account data are written here.
            runCatching { saved?.parentFile?.mkdirs(); saved?.writeText(html, Charsets.UTF_8) }
            rows
        } catch (failure: Exception) {
            val rows = runCatching { saved?.takeIf { it.length() <= HungerStationPublicParser.MAX_HTML_BYTES }
                ?.readText(Charsets.UTF_8)?.let { parse(it, city) } }.getOrNull()
            rows?.takeIf { it.isNotEmpty() } ?: throw failure
        }
    }
    fun cities() = read(null)
    fun districts(city: AreaEntry) = read(city)
}

/** Search normalization affects suggestions, never provider URL identity or automatic selection. */
object AreaNameSearch {
    fun normalized(value: String): String = value.trim().removePrefix("حي ").removePrefix("مدينة ")
        .replace(Regex("[أإآ]"), "ا").replace('ى', 'ي').replace('ة', 'ه')
        .replace(Regex("[\\u064b-\\u065f\\u0670\\u0640\\u200e\\u200f\\u061c]"), "")
        .replace(Regex("[-\\s]+"), " ").lowercase()
    private fun distance(a: String, b: String): Int {
        var prior = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val row = IntArray(b.length + 1); row[0] = i + 1
            for (j in b.indices) row[j + 1] = minOf(row[j] + 1, prior[j + 1] + 1,
                prior[j] + if (a[i] == b[j]) 0 else 1)
            prior = row
        }
        return prior[b.length]
    }
    fun rank(rows: List<AreaEntry>, query: String): List<AreaEntry> {
        val q = normalized(query).take(100)
        if (q.isBlank()) return rows.take(8)
        return rows.map { entry ->
            val name = normalized(entry.title)
            val score = when { name == q -> 0; name.startsWith(q) -> 1; name.contains(q) -> 2
                else -> 3 + distance(q, name) }
            entry to score
        }.filter { it.second <= maxOf(5, q.length / 3 + 3) }.sortedBy { it.second }.take(8).map { it.first }
    }
    fun exact(rows: List<AreaEntry>, name: String): AreaEntry? {
        val q = normalized(name)
        return rows.filter { normalized(it.title) == q ||
            (q == "مكه" && it.slug == "مكة-المكرمة") ||
            (q == "المدينه" && it.slug == "المدينة-المنورة") }.singleOrNull()
    }
}
