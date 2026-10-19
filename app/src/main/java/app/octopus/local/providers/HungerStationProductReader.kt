package app.octopus.local.providers

import app.octopus.local.domain.*
import java.security.MessageDigest

/** Parses the recorded PDP's required/single-choice shape. Other choice rules fail closed. */
class HungerStationProductReader(private val restaurant: LiveRestaurant, private val item: LiveMenuItem) {
    private data class Group(val title: String, val choices: LinkedHashMap<String, MenuChoice> = linkedMapOf())
    private val groups = linkedMapOf<String, Group>()
    private val bodies = mutableListOf<String>()
    private var titleSeen = false
    private var bottomSeen = false
    private var unsupported = false
    private var enabled = false

    fun observe(frame: CatalogFrame) {
        require(HungerStationCatalog.screen(frame) == CatalogScreen.PRODUCT) { "تعذر التعرف على خيارات الوجبة." }
        val nodes = frame.nodes.filter { it.label.isNotBlank() }
        titleSeen = titleSeen || nodes.any { !it.clickable && clean(it.label) == item.name }
        enabled = frame.nodes.singleOrNull { it.id == "flutterAddButton" }?.enabled == true
        bodies += nodes.joinToString("|") { it.id + clean(it.label) }
        var group: Group? = null
        val headersInFrame = mutableSetOf<String>()
        var i = 0
        while (i < nodes.size) {
            val node = nodes[i]
            val text = clean(node.label)
            if (text == "غالبًا ما يتم طلبه مع") { bottomSeen = true; break }
            if (i + 2 < nodes.size && clean(nodes[i+1].label) == "مطلوب" && clean(nodes[i+2].label) == "اختيار واحد" && !node.clickable) {
                if (text.length > 100 || text.contains('\n')) { unsupported = true; return }
                if (!headersInFrame.add(text)) unsupported = true
                group = groups.getOrPut(text) { Group(text) }; i += 3; continue
            }
            if (text == "مطلوب" || text == "اختياري" || text.startsWith("اختيار ")) unsupported = true
            if (node.clickable && node.id != "flutterAddButton" && node.id != "pdpCloseButton" && node.id != "pdpZoomButton") {
                val value = text.lineSequence().first().trim()
                // Recorded choice rows are merged names with order-count/price suffixes; recommendation rows are excluded above.
                val choiceShape = text.contains('\n') && (text.contains("طلب") || text.contains('§'))
                if (choiceShape && value.any(Char::isLetter)) {
                    val current = group
                    if (current == null) {
                        if (groups.values.none { it.choices.containsKey(value) }) unsupported = true
                    } else {
                        val available = node.enabled && !Regex("غير متاح|غير متوفر|نفد").containsMatchIn(value)
                        val choice = MenuChoice(value, value, available)
                        val previous = current.choices[value]
                        if (previous != null && previous != choice) unsupported = true
                        current.choices[value] = choice
                    }
                }
            }
            i++
        }
    }

    fun finish(atMillis: Long): ConfiguredProduct {
        require(titleSeen) { "اسم الوجبة داخل خياراتها لم يطابق اختيارك." }
        // The saved evidence covers this bounded configuration. Other PDP shapes remain readable but not selectable yet.
        val evidencedConfiguration = IdentityText.clean(restaurant.name) == "المضياف العربي" &&
            IdentityText.clean(item.name) == "دجاج مظبي" && groups.keys.toList() == listOf("اختر الحجم", "إضافات")
        val ridanFixedMeal = IdentityText.clean(restaurant.name) == "ريدان" &&
            IdentityText.clean(item.name) == "نصف دجاج مضغوط" && groups.isEmpty()
        val complete = (evidencedConfiguration || ridanFixedMeal) && bottomSeen && !unsupported &&
            groups.values.all { it.choices.isNotEmpty() }
        val options = groups.values.filter { it.choices.isNotEmpty() }.map { MenuChoiceGroup(it.title, it.title, 1, 1, it.choices.values.toList()) }
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(bodies.joinToString().toByteArray()).joinToString("") { "%02x".format(it) }
        return ConfiguredProduct(restaurant.name, MenuProduct(item.selector, item.name, null, enabled, item.listedPrice, options),
            CatalogProvenance(ProviderId.HUNGERSTATION, HungerStationCatalog.TESTED_VERSION, atMillis, fingerprint), complete,
            options.singleOrNull { it.title == "اختر الحجم" }?.id)
    }
    val optionsEndObserved: Boolean get() = bottomSeen
    private fun clean(value: String) = value.replace(Regex("[\\u200e\\u200f\\u061c\\u202a-\\u202e\\u2066-\\u2069]"), "").trim()
}
