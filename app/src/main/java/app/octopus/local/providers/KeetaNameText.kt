package app.octopus.local.providers

import app.octopus.local.domain.IdentityText

/** Writing-only differences between HungerStation and Keeta names. It never drops words, so a
 * different size, piece count or branch stays different; every price and basket guard still applies. */
object KeetaNameText {
    private val decorations = Regex("[\\p{So}\\p{Sk}\\p{Cf}\\uFE0E\\uFE0F]")
    private val marks = Regex("[\\u064B-\\u065F\\u0670\\u0640]")

    fun loose(value: String): String {
        val cleaned = marks.replace(decorations.replace(IdentityText.clean(value), ""), "")
        val unified = buildString {
            for (c in cleaned) append(when (c) {
                'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                'ى' -> 'ي'
                in '٠'..'٩' -> '0' + (c - '٠')
                in '۰'..'۹' -> '0' + (c - '۰')
                '–', '—', '−', '‒' -> '-'
                '،' -> ','
                else -> c
            })
        }
        return unified.replace(Regex("\\s*-\\s*"), " - ").replace(Regex("\\s*,\\s*"), ", ")
            .replace(Regex("\\s+"), " ").trim()
    }

    /** The requested title whose writing matches [raw], when exactly one does. */
    fun requested(raw: String, titles: Set<String>): String? {
        if (raw.isBlank() || raw.length > 300 || '\n' in raw) return null
        val key = loose(raw)
        if (key.isEmpty()) return null
        return titles.filter { loose(it) == key }.singleOrNull()
    }

    private val sandwich = setOf("ساندويتش", "سندويتش", "ساندوتش", "سندوتش", "ساندويش", "سندويش")
    /** Words HungerStation adds to a choice name that Keeta leaves out ("ساندوتش تشيكن ديلوكس" ↔ "ساندويتش ديلوكس"). */
    private val fillers = setOf("تشيكن", "دجاج")
    private fun words(value: String): List<String> = loose(value).replace("-", " ").replace(",", " ")
        .split(' ').filter(String::isNotBlank).map { if (it in sandwich) "ساندوتش" else it }

    /** The requested choice that [raw] names, when exactly one does: the same writing, or the same words
     * after the sandwich spelling, with HungerStation's extra words limited to [fillers]. A size, "حار" or
     * any other dropped word keeps the names different. */
    fun requestedChoice(raw: String, choices: Set<String>): String? {
        if (raw.isBlank() || raw.length > 300 || '\n' in raw) return null
        choices.filter { loose(it) == loose(raw) }.singleOrNull()?.let { return it }
        val shown = words(raw)
        if (shown.size < 2 || shown.distinct().size != shown.size) return null
        return choices.filter { choice ->
            val wanted = words(choice)
            wanted.distinct().size == wanted.size && wanted.containsAll(shown) && (wanted - shown.toSet()).all { it in fillers }
        }.singleOrNull()
    }
}
