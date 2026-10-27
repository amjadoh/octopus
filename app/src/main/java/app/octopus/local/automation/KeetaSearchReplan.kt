package app.octopus.local.automation

import app.octopus.local.providers.CatalogFrame
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Only a rejected, undispatched search may obtain a fresh plan. Never repeats a dispatch. */
object KeetaSearchReplan {
    class Dispatch {
        var attempted = false
            private set
        fun mark() { check(!attempted); attempted = true }
    }
    sealed interface Result {
        data object Dispatched : Result
        data class ChangedBeforeDispatch(val failure: Throwable) : Result
    }

    suspend fun run(initial: CatalogFrame, observe: suspend () -> CatalogFrame,
        attempt: suspend (CatalogFrame, Dispatch) -> Result) {
        var frame = initial
        repeat(3) { index ->
            currentCoroutineContext().ensureActive()
            val dispatch = Dispatch()
            when (val result = attempt(frame, dispatch)) {
                Result.Dispatched -> { check(dispatch.attempted); return }
                is Result.ChangedBeforeDispatch -> {
                    // Even a misclassified result after sending input may not repeat it.
                    if (dispatch.attempted || index == 2) throw result.failure
                    currentCoroutineContext().ensureActive()
                    frame = observe()
                }
            }
        }
    }
}
