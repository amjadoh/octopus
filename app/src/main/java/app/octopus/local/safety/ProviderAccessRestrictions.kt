package app.octopus.local.safety

enum class ProviderAccessRestriction(val userMessage: String) {
    UNUSUAL_ACTIVITY("قيّدت المنصة الوصول بسبب نشاط غير معتاد. أوقفت العملية دون إعادة محاولة الدخول؛ راجع دعم المنصة."),
    TOO_MANY_ATTEMPTS("أعلنت المنصة بلوغ حد المحاولات. أوقفت العملية دون إعادة المحاولة؛ مدة الانتظار غير معروفة."),
    ACCESS_ERROR("تعذر الوصول إلى بيانات المنصة. أوقفت العملية دون تحديث متكرر أو استخدام أسعار بديلة."),
}

/** Recognizes messages actually observed; keeps neither reference codes nor account data. */
object ProviderAccessRestrictions {
    fun detect(label: CharSequence?): ProviderAccessRestriction? {
        if (label == null || label.length > 512) return null
        val text = label.toString().lowercase().replace(Regex("[\\u200e\\u200f\\u061c\\u202a-\\u202e\\u2066-\\u2069]"), "")
            .replace(Regex("\\s+"), " ").trim()
        return when {
            text.startsWith("unusual activity detected.") && text.contains("is restricted") -> ProviderAccessRestriction.UNUSUAL_ACTIVITY
            text.startsWith("you've reached our maximum number of attempts.") ||
                text.startsWith("you’ve reached our maximum number of attempts.") -> ProviderAccessRestriction.TOO_MANY_ATTEMPTS
            text == "access error" -> ProviderAccessRestriction.ACCESS_ERROR
            else -> null
        }
    }
}
