package app.octopus.local.automation

import app.octopus.local.domain.ProviderId
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class CartComparisonStep { READING_MENU, PREPARING_CART, REVIEW_UNAVAILABLE, PREPARATION_FAILED }
data class CartComparisonProgress(val provider: ProviderId, val step: CartComparisonStep, val detail: String? = null)

/** Runs existing guarded adapters sequentially. A prepared basket is never a final quote. */
class CartComparisonFlow {
    suspend fun run(
        readMenus: suspend () -> Unit,
        available: (ProviderId) -> Boolean,
        prepare: suspend (ProviderId) -> String?,
        unchanged: () -> Boolean,
        progress: (CartComparisonProgress) -> Unit,
    ) {
        suspend fun checkRequest() {
            currentCoroutineContext().ensureActive()
            check(unchanged()) { "تغير الطلب؛ توقفت المقارنة دون تعديل سلة أخرى." }
        }
        checkRequest()
        readMenus()
        for (provider in ProviderId.entries) {
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
                if (error == null) CartComparisonStep.REVIEW_UNAVAILABLE else CartComparisonStep.PREPARATION_FAILED,
                error ?: "السلة متحققة؛ قراءة الإجمالي النهائي لهذه المنصة لم تكتمل بعد. لا يوجد سعر نهائي مؤكد."))
        }
    }
}
