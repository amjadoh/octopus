package app.octopus.local.automation

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import app.octopus.local.domain.*
import app.octopus.local.pricing.MoneyParser

/** Never returns raw screen text, identifiers, names, addresses, or editable contents. */
class SnapshotReader {
    private val labels = mapOf(
        "الإجمالي" to PriceField.ORDER_TOTAL, "الاجمالي" to PriceField.ORDER_TOTAL,
        "إجمالي الطلب" to PriceField.ORDER_TOTAL, "اجمالي الطلب" to PriceField.ORDER_TOTAL,
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
    private val blocked = setOf("تسجيل الدخول", "رمز التحقق", "كلمة المرور", "رقم البطاقة", "login", "sign in", "password", "card number", "captcha", "verification code")
    private val moneyPart = Regex("[-−]?[٠-٩۰-۹0-9]+(?:[.,٫٬][٠-٩۰-۹0-9]+)*")
    private fun normalize(value: String) = value.lowercase().trim().replace(Regex("[\u200e\u200f\u061c]"), "").replace(Regex("\\s+"), " ").removeSuffix(":").trim()

    fun read(root: AccessibilityNodeInfo): SafeSnapshot {
        val tokens = mutableListOf<SafeToken>()
        var visited = 0
        var visible = 0
        var sensitive = false
        var truncated = false
        fun walk(node: AccessibilityNodeInfo, path: String, parent: String, depth: Int) {
            if (visited++ >= 2500 || depth > 40) { truncated = true; return }
            if (!node.isVisibleToUser) return
            visible++
            // Skip before reading text or contentDescription.
            val editable = node.isEditable || node.className?.toString()?.contains("EditText") == true
            if (node.isPassword) { sensitive = true; return }
            if (!editable) {
                val rect = Rect().also { node.getBoundsInScreen(it) }
                listOfNotNull(node.text, node.contentDescription).distinct().forEach { sequence ->
                    if (sequence.length > 120) return@forEach
                    val text = normalize(sequence.toString())
                    if (blocked.any { text == it || text.startsWith("$it ") }) sensitive = true
                    val exact = labels[text]
                    val parsed = if (exact == null) MoneyParser.parse(text) else null
                    if (exact != null || parsed != null) {
                        tokens += SafeToken(exact, parsed, rect.top, rect.bottom, rect.left, rect.right, parent)
                    } else {
                        // A merged node is accepted only with one number and a known, exact label.
                        val matches = moneyPart.findAll(text).toList()
                        if (matches.size == 1) {
                            val amountText = matches.single().value
                            val labelText = normalize(text.replace(amountText, "").replace(Regex("(?i)(SAR|ر\\s*[.٫]?\\s*س\\.?|ريال(?: سعودي)?|﷼|\u20c1)"), ""))
                            val field = labels[labelText]
                            val amount = MoneyParser.parse(amountText)
                            if (field != null && amount != null) tokens += SafeToken(field, amount, rect.top, rect.bottom, rect.left, rect.right, parent)
                        }
                    }
                }
            }
            if (editable) return
            for (index in 0 until node.childCount.coerceAtMost(300)) {
                val child = node.getChild(index) ?: continue
                try { walk(child, "$path/$index", path, depth + 1) }
                finally { @Suppress("DEPRECATION") child.recycle() }
            }
        }
        walk(root, "root", "", 0)
        return SafeSnapshot(tokens.distinct(), visible, sensitive, truncated)
    }
}
