package app.octopus.local.pricing

import app.octopus.local.domain.PriceField

/** Extracts exact label/currency pairs from merged accessibility nodes, never raw text. */
object GroupedReviewFields {
    private val fields = mapOf(
        "رسوم التوصيل" to PriceField.DELIVERY, "رسوم الخدمة" to PriceField.SERVICE,
        "رسوم الطلب الصغير" to PriceField.SMALL_ORDER, "المجموع الفرعي" to PriceField.SUBTOTAL,
        "مجموع الأصناف" to PriceField.SUBTOTAL, "الإجمالي" to PriceField.ORDER_TOTAL,
        "إجمالي الطلب" to PriceField.ORDER_TOTAL, "الخصم" to PriceField.DISCOUNT,
        "مجموع الطلب" to PriceField.ORDER_TOTAL, "مجموع السلة" to PriceField.SUBTOTAL,
        "ضريبة القيمة المضافة" to PriceField.TAX,
    )
    fun read(value: CharSequence): List<Pair<PriceField, Long>> {
        if (value.length > 2048) return emptyList()
        val lines = value.toString().replace(Regex("[\\u200e\\u200f\\u202a-\\u202e\\u2066-\\u2069]"), "")
            .lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        return lines.mapIndexedNotNull { i, label ->
            val field = fields[label] ?: return@mapIndexedNotNull null
            val one = lines.getOrNull(i + 1) ?: return@mapIndexedNotNull null
            val candidate = if (one in setOf("§", "﷼", "SAR")) "$one ${lines.getOrNull(i+2).orEmpty()}"
                else if (lines.getOrNull(i+2) in setOf("§", "﷼", "SAR")) "$one ${lines[i+2]}" else one
            if (!candidate.contains('§') && !candidate.contains('﷼') && !candidate.contains("SAR")) return@mapIndexedNotNull null
            MoneyParser.parse(candidate.replace("§", "SAR"))?.let { field to it }
        }.distinct()
    }
}
