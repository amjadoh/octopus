package app.octopus.local.pricing

import app.octopus.local.domain.PriceField
import app.octopus.local.domain.ProviderId

/** Price-only parsing shared by live snapshots and replay tests. Never returns source text. */
object SnapshotPriceTextReader {
    data class Value(val field: PriceField?, val amount: Long?)

    private val labels = mapOf(
        "الإجمالي" to PriceField.ORDER_TOTAL, "الاجمالي" to PriceField.ORDER_TOTAL,
        "إجمالي الطلب" to PriceField.ORDER_TOTAL, "اجمالي الطلب" to PriceField.ORDER_TOTAL,
        "مجموع الطلب" to PriceField.ORDER_TOTAL, "مجموع السلة" to PriceField.SUBTOTAL,
        "المجموع الإجمالي" to PriceField.ORDER_TOTAL, "المجموع الاجمالي" to PriceField.ORDER_TOTAL,
        "total" to PriceField.ORDER_TOTAL, "grand total" to PriceField.ORDER_TOTAL, "order total" to PriceField.ORDER_TOTAL,
        "المجموع الفرعي" to PriceField.SUBTOTAL, "مجموع الأصناف" to PriceField.SUBTOTAL,
        "مجموع المنتجات" to PriceField.SUBTOTAL, "subtotal" to PriceField.SUBTOTAL,
        "رسوم التوصيل" to PriceField.DELIVERY, "التوصيل" to PriceField.DELIVERY, "delivery fee" to PriceField.DELIVERY,
        "رسوم الخدمة" to PriceField.SERVICE, "service fee" to PriceField.SERVICE,
        "رسوم الطلب الصغير" to PriceField.SMALL_ORDER, "small order fee" to PriceField.SMALL_ORDER,
        "الضريبة" to PriceField.TAX, "ضريبة القيمة المضافة" to PriceField.TAX, "vat" to PriceField.TAX,
        "الخصم" to PriceField.DISCOUNT, "discount" to PriceField.DISCOUNT,
        "رصيد المحفظة المستخدم" to PriceField.WALLET, "wallet applied" to PriceField.WALLET,
        "المتبقي للدفع" to PriceField.AMOUNT_DUE, "المبلغ المستحق" to PriceField.AMOUNT_DUE,
        "amount due" to PriceField.AMOUNT_DUE, "amount to pay" to PriceField.AMOUNT_DUE,
    )
    private val moneyPart = Regex("[-−]?[٠-٩۰-۹0-9]+(?:[.,٫٬][٠-٩۰-۹0-9]+)*")
    private val currency = Regex("(?i)(SAR|ر\\s*[.٫]?\\s*س\\.?|ريال(?: سعودي)?|﷼|\u20c1)")
    private fun normalize(value: String) = value.lowercase().trim()
        .replace(Regex("[\u200e\u200f\u061c]"), "")
        .replace(Regex("\\s+"), " ").removeSuffix(":").trim()

    fun read(value: CharSequence, providerPackage: String?): List<Value> {
        if (value.length > 2048) return emptyList()
        // HS's recorded accessibility tree exposes its riyal glyph as §. Keep this
        // exception at this provider boundary; MoneyParser remains currency-neutral.
        val priceText = if (providerPackage == ProviderId.HUNGERSTATION.packageName)
            value.toString().replace("§", "SAR") else value.toString()
        val result = GroupedReviewFields.read(priceText).map { (field, amount) -> Value(field, amount) }.toMutableList()
        if (priceText.length > 120) return result.distinct()
        val text = normalize(priceText)
        val exact = labels[text]
        val parsed = if (exact == null) MoneyParser.parse(text) else null
        if (exact != null || parsed != null) result += Value(exact, parsed)
        else {
            // No nearest-number guesses: a merged value requires one amount and an exact label.
            val matches = moneyPart.findAll(text).toList()
            if (matches.size == 1) {
                val amountText = matches.single().value
                val labelText = normalize(currency.replace(text.replace(amountText, ""), ""))
                val field = labels[labelText]
                val amount = MoneyParser.parse(amountText)
                if (field != null && amount != null) result += Value(field, amount)
            }
        }
        return result.distinct()
    }
}
