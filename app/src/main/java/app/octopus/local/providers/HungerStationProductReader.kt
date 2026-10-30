package app.octopus.local.providers

import app.octopus.local.domain.*
import java.security.MessageDigest

/** Parses observed required/single-choice and optional-add/single-choice PDP sections. */
class HungerStationProductReader(private val restaurant: LiveRestaurant, private val item: LiveMenuItem) {
    private data class Group(val title: String, val minimum: Int = 1, val maximum: Int = 1, val choices: LinkedHashMap<String, MenuChoice> = linkedMapOf())
    private val groups = linkedMapOf<String, Group>()
    private val bodies = mutableListOf<String>()
    private var titleSeen = false
    private var bottomSeen = false
    private var unsupported = false
    private var enabled = false

    fun observe(frame: CatalogFrame) {
        if (!frame.complete) unsupported = true
        require(HungerStationCatalog.screen(frame) == CatalogScreen.PRODUCT) { "تعذر التعرف على خيارات الوجبة." }
        val nodes = frame.nodes.filter { it.label.isNotBlank() }
        val headings = nodes.withIndex().count { (i, n) -> !n.clickable && clean(n.label) == item.name &&
            !(nodes.getOrNull(i+1)?.label?.let(::clean) == "مطلوب" && nodes.getOrNull(i+2)?.label?.let(::clean) == "اختيار واحد") }
        if (frame.nodes.any { it.enteredText } || headings > 1) unsupported = true
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
            // Recorded ALBAIK optional groups use a group heading plus an explicit upper limit;
            // required sections retain the separate "مطلوب" marker below.
            val optionalLimit = nodes.getOrNull(i + 1)?.takeIf { !it.clickable }?.label?.let(::clean)
            // Observed optional sections on HS 8.0.303. The imported cart must separately
            // prove that none of these multi-select additions was selected.
            val optionalMaximum = optionalLimit?.let { Regex("ما يصل إلى ([1-9]) خيارات").matchEntire(it)?.groupValues?.get(1)?.toInt() }
            if (!node.clickable && text in setOf("الاختيار من الاضافات", "اضافة") && optionalMaximum != null) {
                if (!headersInFrame.add(text)) unsupported = true
                group = groups.getOrPut(text) { Group(text, 0, optionalMaximum) }
                if (group.minimum != 0 || group.maximum != optionalMaximum) unsupported = true
                i += 2; continue
            }
            if (!node.clickable && text.matches(Regex("اختر من اضافات-(مبرده| من غير صوص|صوص|بطاطس) إلى .{1,100}")) &&
                optionalLimit in setOf("اختيار واحد", "ما يصل إلى خيارين")) {
                val maximum = if(optionalLimit == "ما يصل إلى خيارين") 2 else 1
                if(!headersInFrame.add(text)) unsupported=true
                group=groups.getOrPut(text) {Group(text,0,maximum)}
                if(group.minimum!=0 || group.maximum!=maximum) unsupported=true
                i+=2;continue
            }
            // Honor / HS 8.0.303, Madwar feta product: an unchecked optional checkbox,
            // headed by these two exact labels. Do not infer optionality for other grammars.
            if (text in setOf("يمكنك اضافة", "اضافات فطاير") && !node.clickable && i + 1 < nodes.size &&
                clean(nodes[i + 1].label) == "اختيار واحد" && !nodes[i + 1].clickable) {
                if (!headersInFrame.add(text)) unsupported = true
                group = groups.getOrPut(text) { Group(text, 0) }
                if (group.minimum != 0) unsupported = true
                i += 2; continue
            }
            if (i + 2 < nodes.size && clean(nodes[i+1].label) == "مطلوب" && clean(nodes[i+2].label) == "اختيار واحد" && !node.clickable) {
                if (text.length > 100 || text.contains('\n')) { unsupported = true; return }
                if (!headersInFrame.add(text)) unsupported = true
                group = groups.getOrPut(text) { Group(text) }
                if (group.minimum != 1) unsupported = true
                i += 3; continue
            }
            // A scroll can clip the heading while leaving its limit and an overlapping
            // already-read choice. Restore only the uniquely proven single-choice group.
            if (group == null && !node.clickable && text == "اختيار واحد") {
                val overlap = nodes.getOrNull(i + 1)?.takeIf { it.clickable }?.label?.let(::clean)?.lineSequence()?.first()
                val known = groups.values.singleOrNull { it.maximum == 1 && overlap in it.choices }
                if (known != null) { group = known; i++; continue }
            }
            if (text == "مطلوب" || text == "اختياري" || text.startsWith("اختيار ")) unsupported = true
            // Observed dismiss scrim and nutrition notice, before any option section.
            // They are read-only metadata here and never become actionable choices.
            // The same fixed scrim/nutrition controls can reappear on each scrolled capture.
            // Their role depends on their position in this frame, not accumulated option groups.
            if (group == null && node.clickable && (text in setOf("تمويه", "تنبيهات") ||
                    (text == "الحقائق الغذائية" && node.className == "android.widget.ImageView"))) { i++; continue }
            if (node.clickable && node.id != "flutterAddButton" && node.id != "pdpCloseButton" && node.id != "pdpZoomButton") {
                val value = text.lineSequence().first().trim()
                // Recorded choice rows are merged names with order-count/price suffixes; recommendation rows are excluded above.
                val choiceShape = text.contains('\n') && (text.contains("طلب") || text.contains('§') || text.contains("سعرة")) ||
                    (group?.title == "اختر العنصر" && !text.contains('\n') && text.startsWith(item.name + " - ")) ||
                    (text in setOf("بدون سلطة", "بدون صلصة", "بدون مخلل حار", "بدون مخلل", "بدون بطاطس", "بدون صلصة ثوم") &&
                        node.className in setOf("android.view.View", "android.widget.ImageView"))
                if (!choiceShape && value.any(Char::isLetter)) unsupported = true
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
        // Completeness follows the observed option grammar and end marker, never a restaurant name.
        val complete = bottomSeen && !unsupported && groups.values.all { it.choices.size >= it.maximum }
        val options = groups.values.filter { it.choices.size >= it.maximum }.map { MenuChoiceGroup(it.title, it.title, it.minimum, it.maximum, it.choices.values.toList()) }
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(bodies.joinToString().toByteArray()).joinToString("") { "%02x".format(it) }
        return ConfiguredProduct(restaurant.name, MenuProduct(item.selector, item.name, null, enabled, item.listedPrice, options),
            CatalogProvenance(ProviderId.HUNGERSTATION, HungerStationCatalog.TESTED_VERSION, atMillis, fingerprint), complete,
            options.singleOrNull { it.title == "اختر الحجم" }?.id)
    }
    val optionsEndObserved: Boolean get() = bottomSeen
    private fun clean(value: String) = value.replace(Regex("[\\u200e\\u200f\\u061c\\u202a-\\u202e\\u2066-\\u2069]"), "").trim()
}
