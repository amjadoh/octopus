package app.octopus.local.automation

import app.octopus.local.domain.ProviderId
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class CartComparisonStep { READING_MENU, PREPARING_CART, TOTAL_READ, REVIEW_UNAVAILABLE, PREPARATION_FAILED }
data class CartComparisonProgress(val provider: ProviderId, val step: CartComparisonStep, val detail: String? = null)

/** Runs existing guarded adapters sequentially. A prepared basket is never a final quote. */
class CartComparisonFlow {
    suspend fun run(
        readMenus: suspend () -> Unit,
        available: (ProviderId) -> Boolean,
        prepare: suspend (ProviderId) -> String?,
        unchanged: () -> Boolean,
        progress: (CartComparisonProgress) -> Unit,
        totalAvailable: (ProviderId) -> Boolean = { false },
        providerOrder: List<ProviderId> = ProviderId.entries,
    ) {
        require(providerOrder.size == ProviderId.entries.size && providerOrder.toSet() == ProviderId.entries.toSet())
        suspend fun checkRequest() {
            currentCoroutineContext().ensureActive()
            check(unchanged()) { "تغير الطلب؛ توقفت المقارنة دون تعديل سلة أخرى." }
        }
        checkRequest()
        ProviderId.entries.forEach { progress(CartComparisonProgress(it, CartComparisonStep.READING_MENU,
            "التحقق من أسعار الأصناف وخياراتها قبل تجهيز السلتين؛ لم يبدأ تعديل السلة.")) }
        readMenus()
        for (provider in providerOrder) {
            checkRequest()
            if (!available(provider)) {
                progress(CartComparisonProgress(provider, CartComparisonStep.PREPARATION_FAILED,
                    "لم تكتمل قراءة الوجبة وخياراتها؛ لم تُجهز السلة."))
                continue
            }
            progress(CartComparisonProgress(provider, CartComparisonStep.PREPARING_CART))
            val error = prepare(provider)
            checkRequest()
            progress(CartComparisonProgress(provider,
                if (totalAvailable(provider)) CartComparisonStep.TOTAL_READ else if (error == null) CartComparisonStep.REVIEW_UNAVAILABLE else CartComparisonStep.PREPARATION_FAILED,
                if (totalAvailable(provider)) "قُرئ إجمالي مراجعة الطلب؛ راجع تطابق العنوان والفرع قبل اعتماد المقارنة." else error ?: "السلة متحققة؛ لم تُقرأ مراجعة الإجمالي بعد."))
        }
    }
}
