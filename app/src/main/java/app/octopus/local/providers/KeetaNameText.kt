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
}
