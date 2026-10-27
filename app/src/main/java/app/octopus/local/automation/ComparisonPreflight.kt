package app.octopus.local.automation

import app.octopus.local.domain.*
import app.octopus.local.providers.HungerStationCatalog
import app.octopus.local.providers.PreviewEvidence
import app.octopus.local.providers.KeetaVersionProfile

/** Check both installed adapters before a full comparison opens or edits either provider. */
object ComparisonPreflight {
    fun failure(cart: CanonicalCart, consent: Boolean, allowed: Set<ProviderId>,
        installations: List<ProviderInstallation>): String? {
        val twoRows = PlainComparisonSupport.existingTwoRow(cart)
        if (!PreviewEvidence.supports(cart) && !twoRows)
            return "طلبك محفوظ كاملًا. التجهيز الحالي يدعم صنفًا أو صنفين دون حجم أو خيارات أو ملاحظات؛ لم تبدأ المقارنة ولم تتغير السلال."
        if (!consent) return "فعّل السماح بالمقارنة أولًا؛ لم تُفتح التطبيقات ولم تتغير السلال."
        val failures = ProviderId.entries.mapNotNull { id ->
            val installed = installations.singleOrNull { it.provider == id }
            when {
                id !in allowed -> "${id.title}: غير مفعّل ضمن التطبيقات المسموح بها."
                installed == null || !installed.installed -> "${id.title}: تعذر التحقق من تثبيت التطبيق."
                id == ProviderId.HUNGERSTATION && installed.version != HungerStationCatalog.TESTED_VERSION ->
                    "${id.title}: إصدار التطبيق يحتاج تحققًا قبل تشغيل المقارنة."
                id == ProviderId.KEETA && KeetaVersionProfile.resolve(installed.version)?.let {
                    it.allowsReading(twoRows) && it.allowsPreparation(twoRows) && it.allowsReview(twoRows)
                } != true ->
                    "${id.title}: إصدار التطبيق لا يدعم مسار هذا الطلب حاليًا."
                else -> null
            }
        }
        return failures.takeIf { it.isNotEmpty() }?.joinToString("\n", postfix =
            "\nلم تبدأ المقارنة ولم تتغير السلال.")
    }
}
