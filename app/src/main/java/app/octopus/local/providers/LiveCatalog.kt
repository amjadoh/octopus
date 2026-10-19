package app.octopus.local.providers

import app.octopus.local.domain.ProviderId
import app.octopus.local.pricing.MoneyParser
import java.util.Locale

data class CatalogNode(val index: Int, val parent: Int, val id: String, val label: String, val className: String,
    val clickable: Boolean, val editable: Boolean, val scrollable: Boolean)
enum class CatalogScreen { HOME, SEARCH, RESULTS, RESTAURANT_NOTICE, MENU, PRODUCT, CART, UNKNOWN }
data class CatalogFrame(val nodes: List<CatalogNode>, val complete: Boolean) {
    fun hasId(suffix: String) = nodes.any { it.id == suffix || it.id.endsWith(":id/$suffix") }
    fun descendants(index: Int): List<CatalogNode> {
        val found = mutableSetOf(index)
        nodes.forEach { if (it.parent in found) found += it.index }
        return nodes.filter { it.index in found }
    }
}
data class LiveRestaurant(val name: String, val description: String?, val searchQuery: String,
    val source: ProviderId = ProviderId.HUNGERSTATION)
data class LiveMenuItem(val name: String, val listedPrice: Long?, val selector: String)
data class LiveMenu(val restaurant: LiveRestaurant, val items: List<LiveMenuItem>, val capturedAtMillis: Long,
    val reachedEnd: Boolean)
data class LiveCatalogState(
    val busy: Boolean = false,
    val message: String = "ابحث لقراءة المطاعم المتاحة من هنقرستيشن.",
    val restaurants: List<LiveRestaurant> = emptyList(),
    val menu: LiveMenu? = null,
    val error: String? = null,
)

/** Selectors recorded on Honor / HS 8.0.302. Unknown screens never authorize an action. */
object HungerStationCatalog {
    const val TESTED_VERSION = "8.0.303"
    fun screen(frame: CatalogFrame): CatalogScreen = when {
        !frame.complete -> CatalogScreen.UNKNOWN
        frame.hasId("pdpCloseButton") -> CatalogScreen.PRODUCT
        frame.hasId("FlutterCart_title") -> CatalogScreen.CART
        frame.hasId("menuBackButton") && frame.hasId("menuSearchButton") -> CatalogScreen.MENU
        frame.hasId("searchVendorsRecycler") -> CatalogScreen.RESULTS
        listOf("عرض قائمة المطعم", "اكتشف المطاعم", "رفض").all { label -> frame.nodes.any { it.label == label && it.clickable } } -> CatalogScreen.RESTAURANT_NOTICE
        frame.hasId("new_search_card_view") && frame.hasId("input_csc") -> CatalogScreen.HOME
        frame.nodes.any { it.editable } &&
            frame.nodes.any { it.id.startsWith("UniversalSearch_") || it.label == "البحث عن طريق المطابخ" } -> CatalogScreen.SEARCH
        else -> CatalogScreen.UNKNOWN
    }

    fun searchInputs(frame: CatalogFrame): List<CatalogNode> {
        val editable = frame.nodes.filter { it.editable }
        val native = editable.filter { it.className.contains("EditText") }
        return if (native.isNotEmpty()) native else editable
    }

    fun restaurantSuggestions(frame: CatalogFrame): List<CatalogNode> = frame.nodes.filter {
        it.clickable && it.id.matches(Regex("UniversalSearch_(autocomplete_cell|autocomplete_recent_cell|static_vertical_cell)_\\d+")) &&
            it.label.lineSequence().lastOrNull()?.trim() == "في المطاعم"
    }.sortedBy {
        when {
            it.id.startsWith("UniversalSearch_autocomplete_cell_") -> 0
            it.id.startsWith("UniversalSearch_autocomplete_recent_cell_") -> 1
            else -> 2
        }
    }

    fun restaurants(frame: CatalogFrame, query: String): List<LiveRestaurant> {
        if (screen(frame) != CatalogScreen.RESULTS) return emptyList()
        val results = mutableListOf<LiveRestaurant>()
        frame.nodes.filter { it.id.endsWith(":id/title") && it.label.isNotBlank() }.forEach { title ->
            var parent = frame.nodes.firstOrNull { it.index == title.parent }
            while (parent != null && !parent.clickable) parent = frame.nodes.firstOrNull { it.index == parent?.parent }
            if (parent == null) return@forEach
            val children = frame.descendants(parent.index)
            if (children.none { it.index == title.index }) return@forEach
            val description = children.firstOrNull { it.id.endsWith(":id/description") }?.label
            results += LiveRestaurant(title.label, description, query)
        }
        // Identically named branches cannot be distinguished from this screen's evidence.
        return results.groupBy { it.name }.filterValues { it.size == 1 }.values.map { it.single() }
    }

    fun menuItems(frame: CatalogFrame): List<LiveMenuItem> {
        if (screen(frame) != CatalogScreen.MENU) return emptyList()
        return frame.nodes.filter { it.clickable && (it.id.matches(Regex("gridMenuCell-\\d+-\\d+")) ||
            (it.id.isEmpty() && it.className == "android.view.View")) }.mapNotNull { cell ->
            val lines = cell.label.replace(Regex("[\\u200e\\u200f\\u061c\\u202a-\\u202e\\u2066-\\u2069]"), "")
                .lineSequence().map(String::trim).filter(String::isNotBlank).toList()
            val name = lines.firstOrNull { line -> line.any(Char::isLetter) && !line.contains("سعرة") &&
                !line.contains("خصم") && !line.contains('%') && !line.contains('٪') &&
                !line.matches(Regex("[0-9٠-٩.,٬+\\s]+طلب(?:ات)?")) && line.uppercase(Locale.ROOT) != "SAR" }
                ?: return@mapNotNull null
            val currencyIndex = lines.indexOfFirst { it == "§" || it == "﷼" || it.uppercase(Locale.ROOT) == "SAR" }
            if (cell.id.isEmpty() && currencyIndex < 0) return@mapNotNull null
            val currencyCount = lines.count { it == "§" || it == "﷼" || it.uppercase(Locale.ROOT) == "SAR" }
            if (cell.id.isEmpty() && currencyCount != 1) return@mapNotNull null
            val price = if (currencyIndex >= 0 && currencyCount == 1) lines.getOrNull(currencyIndex + 1)?.let(MoneyParser::parse) else null
            LiveMenuItem(name, price, cell.id.ifEmpty { "label:$name" })
        }
    }
}
