package app.octopus.local.pricing

import java.math.BigDecimal

object MoneyParser {
    private val currency = Regex("(?i)(SAR|ر\\s*[.٫]?\\s*س\\s*\\.?|ريال(?: سعودي)?|﷼|\u20c1)")
    private val allowed = Regex("^-?[0-9][0-9,.]*$")

    fun parse(raw: String): Long? {
        var value = buildString {
            raw.forEach { c -> append(when (c) {
                in '٠'..'٩' -> '0' + (c - '٠')
                in '۰'..'۹' -> '0' + (c - '۰')
                '٫' -> '.'
                '٬' -> ','
                '−' -> '-'
                else -> c
            }) }
        }
        value = currency.replace(value, "").replace(Regex("[\u200e\u200f\u061c]"), "").trim()
        if (Regex("[0-9]\\s+[0-9]").containsMatchIn(value)) return null
        value = value.replace(Regex("[\\s\u00a0]"), "")
        if (!allowed.matches(value) || value.count { it == '.' } > 1) return null
        if (',' in value) {
            value = when {
                '.' in value && Regex("^-?\\d{1,3}(,\\d{3})+\\.\\d{1,2}$").matches(value) -> value.replace(",", "")
                '.' in value -> return null
                Regex("^-?\\d+,\\d{1,2}$").matches(value) -> value.replace(',', '.')
                Regex("^-?\\d{1,3}(,\\d{3})+$").matches(value) -> value.replace(",", "")
                else -> return null
            }
        }
        if (!Regex("^-?\\d{1,7}(\\.\\d{1,2})?$").matches(value)) return null
        return runCatching { BigDecimal(value).movePointRight(2).longValueExact() }
            .getOrNull()?.takeIf { it in -10_000_000L..10_000_000L }
    }
}
