package app.octopus.local.diagnostics

enum class InspectionStage(val title: String) {
    SEARCH("نتائج البحث عن المطعم"), MENU("قائمة المطعم"), ITEM("تفاصيل الوجبة"),
    OPTIONS("الأحجام والإضافات"), CART("السلة"), REVIEW("مراجعة الطلب قبل التأكيد")
}

data class InspectionRequest(
    val stage: InspectionStage,
    val restaurant: String,
    val item: String,
)

/** Only exact known labels are retained as semantic tags; arbitrary screen text is discarded. */
object InspectionRedactor {
    private fun normalize(value: String) = value.trim().lowercase()
        .replace(Regex("[\u200e\u200f\u061c\u064b-\u065f]"), "")
        .replace(Regex("\\s+"), " ")
    private val labels = mapOf(
        "بحث" to "SEARCH", "ابحث" to "SEARCH", "search" to "SEARCH",
        "إضافة" to "ADD", "اضافة" to "ADD", "أضف" to "ADD", "add" to "ADD",
        "السلة" to "CART", "عرض السلة" to "CART", "عرض الطلب" to "CART", "cart" to "CART",
        "متابعة" to "CONTINUE", "continue" to "CONTINUE", "الكمية" to "QUANTITY",
        "الحجم" to "SIZE", "الإضافات" to "EXTRAS", "إضافات" to "EXTRAS",
        "نصف" to "HALF", "حبة كاملة" to "WHOLE", "دجاجة كاملة" to "WHOLE",
        "الإجمالي" to "TOTAL", "إجمالي الطلب" to "TOTAL", "total" to "TOTAL",
        "رسوم التوصيل" to "DELIVERY", "رسوم الخدمة" to "SERVICE",
        // These tags document dangerous controls; they never authorize an action.
        "تأكيد الطلب" to "FORBIDDEN_ORDER", "إرسال الطلب" to "FORBIDDEN_ORDER",
        "place order" to "FORBIDDEN_ORDER", "ادفع الآن" to "FORBIDDEN_PAYMENT",
        "الدفع" to "FORBIDDEN_PAYMENT", "pay now" to "FORBIDDEN_PAYMENT",
    )
    fun tag(value: CharSequence?, request: InspectionRequest): String? {
        if (value == null || value.length !in 1..120) return null
        val text = normalize(value.toString())
        if (text.isBlank()) return null
        if (request.restaurant.isNotBlank() && text == normalize(request.restaurant)) return "TARGET_RESTAURANT"
        if (request.item.isNotBlank() && text == normalize(request.item)) return "TARGET_ITEM"
        return labels[text]
    }
    fun resourceId(value: String?): String? = value?.takeIf {
        it.length <= 180 && Regex("[a-zA-Z][a-zA-Z0-9_.]*:id/[a-zA-Z][a-zA-Z0-9_]*").matches(it)
    }
    fun priceHints(value: CharSequence?): List<String> {
        if (value == null || value.length !in 1..120) return emptyList()
        val known = mapOf("الإجمالي" to "TOTAL", "الاجمالي" to "TOTAL", "إجمالي" to "TOTAL", "اجمالي" to "TOTAL",
            "المجموع" to "SUM", "المبلغ" to "AMOUNT", "الضريبة" to "TAX", "ضريبة" to "TAX",
            "التوصيل" to "DELIVERY", "الخدمة" to "SERVICE", "الخصم" to "DISCOUNT", "خصم" to "DISCOUNT",
            "المحفظة" to "WALLET", "المستحق" to "DUE", "شامل" to "INCLUDED", "شاملا" to "INCLUDED",
            "رسوم" to "FEE", "total" to "TOTAL", "amount" to "AMOUNT", "subtotal" to "SUBTOTAL",
            "delivery" to "DELIVERY", "fee" to "FEE", "vat" to "TAX", "tax" to "TAX", "included" to "INCLUDED")
        return normalize(value.toString()).split(Regex("[^\\p{L}]+"))
            .mapNotNull { known[it] }.distinct().take(8)
    }
    fun className(value: CharSequence?): String? = value?.toString()?.takeIf {
        it.length <= 180 && Regex("[a-zA-Z][a-zA-Z0-9_.$]*").matches(it)
    }
}
