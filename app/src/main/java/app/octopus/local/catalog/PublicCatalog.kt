package app.octopus.local.catalog

import app.octopus.local.providers.*
import com.google.gson.*
import com.google.gson.stream.JsonReader
import java.io.StringReader
import java.math.BigDecimal
import java.net.URI
import java.net.URLEncoder

/** A district chosen by the user, not a claim about their current GPS/address. */
data class CatalogArea(val city: String, val district: String) {
    init {
        require(listOf(city, district).all { it.isNotBlank() && it.length <= 100 &&
            it == it.trim() && it.matches(Regex("[\\p{L}\\p{N} -]+")) })
    }
    private fun slug(value: String) = value.replace(Regex("[ -]+"), "-")
    val citySlug: String get() = slug(city).let { if (it == "مكة") "مكة-المكرمة" else it }
    val districtSlug: String get() = slug(district)
    val url: String get() = "https://hungerstation.com/sa-ar/restaurants/regions/" +
        listOf(citySlug, districtSlug).joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
    val title: String get() = "$city • $district"
}

data class PublicRestaurant(val id: Long, val name: String, val categories: String, val url: String, val status: String) {
    fun live() = LiveRestaurant(name, categories, name)
}
data class PublicDirectory(val area: CatalogArea, val restaurants: List<PublicRestaurant>, val page: Int,
    val pages: Int, val observedAt: Long)
data class PublicMeal(val id: Long, val name: String, val category: String, val description: String,
    val price: Long?) {
    // Web ids never become accessibility selectors or proof of complete option coverage.
    fun selector(vendor: Long) = "public-hs:$vendor:$id"
}
data class PublicMenu(val restaurant: PublicRestaurant, val meals: List<PublicMeal>, val observedAt: Long) {
    fun live() = LiveMenu(restaurant.live(), meals.map { LiveMenuItem(it.name, it.price, it.selector(restaurant.id)) }, observedAt, false)
}
data class PublicCatalogState(val area: CatalogArea? = null, val refreshing: Boolean = false,
    val loadingMenu: Boolean = false, val updatedAt: Long? = null, val page: Int = 0, val pages: Int = 0,
    val error: String? = null, val directory: List<PublicRestaurant> = emptyList())

object PublicCatalogUrls {
    fun allowed(value: String): Boolean = runCatching {
        val u = URI(value)
        val segments = u.path.split('/').filter { it.isNotEmpty() }
        u.scheme == "https" && u.host == "hungerstation.com" && u.port == -1 &&
            u.rawUserInfo == null && u.rawFragment == null && segments.size in 5..6 && !u.path.contains("//") &&
            segments.take(3) == listOf("sa-ar", "restaurants", "regions") &&
            runCatching { CatalogArea(segments[3],segments[4]) }.isSuccess &&
            (segments.size == 5 || segments.last().matches(Regex(".+-[1-9][0-9]*"))) &&
            segments.drop(3).all { it.isNotBlank() && it != "." && it != ".." &&
                !it.contains('\\') && !it.contains('/') && !it.contains('%') } &&
            (u.rawQuery == null || (segments.size == 5 && u.rawQuery.matches(Regex("page=[1-9][0-9]{0,3}"))))
    }.getOrDefault(false)

    fun samePath(a: String, b: String): Boolean = runCatching { URI(a).path == URI(b).path }.getOrDefault(false)
    fun menuInArea(url: String, area: CatalogArea): Boolean = allowed(url) &&
        URI(url).rawQuery == null && URI(url).path.substringBeforeLast('/') == URI(area.url).path
}

/** Observed public Next.js schema, 2026-10-25. No script execution or undocumented API. */
class HungerStationPublicParser {
    companion object { const val MAX_HTML_BYTES = 3 * 1024 * 1024 }
    private fun json(value: String): JsonElement = JsonParser.parseReader(JsonReader(StringReader(value)).apply { strictness = Strictness.STRICT })
    private fun page(html: String): JsonObject {
        require(html.toByteArray(Charsets.UTF_8).size <= MAX_HTML_BYTES)
        val scripts = Regex("<script\\b([^>]*)>(.*?)</script\\s*>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .findAll(html).filter { Regex("\\bid=[\"']__NEXT_DATA__[\"']").containsMatchIn(it.groupValues[1]) }.toList()
        require(scripts.size == 1) { "Public catalog schema unavailable" }
        return json(scripts.single().groupValues[2]).asJsonObject.getAsJsonObject("props").getAsJsonObject("pageProps")
    }
    private fun JsonObject.text(key: String, max: Int = 512): String = get(key)?.takeUnless { it.isJsonNull }
        ?.asString.orEmpty().trim().also { require(it.length <= max && !it.any { c -> c.code < 32 && c != '\n' }) }
    private fun verifyArea(p: JsonObject, area: CatalogArea) {
        require(p.getAsJsonObject("city").getAsJsonObject("slug").text("ar") == area.citySlug &&
            p.getAsJsonObject("district").getAsJsonObject("slug").text("ar") == area.districtSlug) { "Area mismatch" }
    }

    fun directory(html: String, area: CatalogArea, requestedPage: Int, now: Long): PublicDirectory {
        require(now >= 0 && requestedPage in 1..9999)
        val p = page(html); verifyArea(p, area)
        val links = Regex("<script\\b[^>]*type=[\"']application/ld\\+json[\"'][^>]*>(.*?)</script\\s*>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).findAll(html).map { json(it.groupValues[1]) }
            .filter { it.isJsonObject && it.asJsonObject.text("@type") == "ItemList" }
            .flatMap { it.asJsonObject.getAsJsonArray("itemListElement").asSequence() }
            .map { it.asJsonObject.text("url", 2048) }.filter { PublicCatalogUrls.menuInArea(it, area) }
            .groupBy { URI(it).path.substringAfterLast('-').toLongOrNull() }
        val vendorList = p.getAsJsonObject("vendorList")
        val pagination = vendorList.getAsJsonObject("pagination")
        val current = pagination.get("currentPage").asInt; val pages = pagination.get("pageCount").asInt
        require(current == requestedPage && pages in current..9999)
        val rows = vendorList.getAsJsonArray("data"); require(rows.size() <= 200)
        val parsed = rows.mapNotNull { row ->
            val v = row.asJsonObject
            if (v.text("vertical") != "restaurants" || v.text("chainType") != "RESTAURANT") return@mapNotNull null
            val id = v.get("id").asLong; val name = v.text("chainName")
            val url = links[id]?.singleOrNull() ?: return@mapNotNull null
            require(id > 0 && name.isNotBlank())
            PublicRestaurant(id, name, v.getAsJsonArray("kitchens").joinToString(" • ") { it.asString }.take(2000), url, v.text("status", 100))
        }
        require(parsed.isNotEmpty()) { "No supported public restaurants" }
        require(parsed.map { it.id }.distinct().size == parsed.size)
        return PublicDirectory(area, parsed, current, pages, now)
    }

    fun menu(html: String, area: CatalogArea, expected: PublicRestaurant, now: Long): PublicMenu {
        require(now >= 0 && PublicCatalogUrls.menuInArea(expected.url, area))
        val p = page(html); verifyArea(p, area)
        val vendor = p.getAsJsonObject("vendor")
        require(vendor.get("id").asLong == expected.id && vendor.text("chain_name") == expected.name)
        val groups = p.getAsJsonArray("vendorMenu"); require(groups.size() <= 100)
        val meals = groups.flatMap { g ->
            val group = g.asJsonObject; require(group.get("branch_id").asLong == expected.id)
            val category = group.text("name"); val items = group.getAsJsonArray("items"); require(items.size() <= 500)
            items.map { row ->
                val item = row.asJsonObject; val id = item.get("id").asLong; val name = item.text("name")
                require(id > 0 && name.isNotBlank())
                val raw = item.get("price")?.takeUnless { it.isJsonNull }?.asString
                val price = raw?.let { runCatching { BigDecimal(it).movePointRight(2).longValueExact().also { n -> require(n >= 0) } }.getOrNull() }
                PublicMeal(id, name, category, item.text("description", 2000), price)
            }
        }
        require(meals.isNotEmpty() && meals.size <= 1000 && meals.map { it.id }.distinct().size == meals.size)
        return PublicMenu(expected.copy(status = vendor.text("status",100)), meals, now)
    }
}
