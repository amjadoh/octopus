package app.octopus.local.providers

import app.octopus.local.domain.ProviderId

enum class IntegrationStatus { TERMS_RESTRICTION_DOCUMENTED, READ_ONLY_UNVERIFIED }

data class ProviderPolicy(
    val status: IntegrationStatus,
    val permitsDiagnosticRead: Boolean,
    val reason: String,
    val source: String,
    val reviewedOn: String,
)

/** Documents terms without misrepresenting a user-requested local test as platform permission. */
object ProviderPolicies {
    fun forProvider(provider: ProviderId): ProviderPolicy = when (provider) {
        ProviderId.KEETA -> ProviderPolicy(
            IntegrationStatus.TERMS_RESTRICTION_DOCUMENTED, true,
            "قيد موثق: البند 4.1.6 من شروط كيتا يشترط إذنًا كتابيًا لاستخراج المحتوى بالبرامج. طلب المستخدم متابعة الاختبار الشخصي لا يُعد إذنًا من المنصة. نتوقف عند أي حماية تقنية.",
            "https://www.mykeeta.com/contents/user.html?locale=en&region=SA", "2026-10-18",
        )
        ProviderId.HUNGERSTATION -> ProviderPolicy(
            IntegrationStatus.READ_ONLY_UNVERIFIED, true,
            "اختبار قراءة فقط؛ تجهيز السلة والمقارنة الآلية غير مفعّلين، ولم تُختبر القراءة على الجهاز.",
            "https://hungerstation.com/sa-ar/terms-of-use", "2026-10-18",
        )
    }
}
