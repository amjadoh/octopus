package app.octopus.local.automation

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A nested read timeout makes that provider unavailable; stopping the parent stops all providers. */
object MenuReadTimeoutBoundary {
    suspend fun <T> read(retryOnce: Boolean = false, onTimeout: () -> T, block: suspend () -> T): T {
        repeat(if (retryOnce) 2 else 1) { attempt ->
            try { return block() }
            catch (timeout: TimeoutCancellationException) {
                // Parent cancellation must never restart navigation or continue another provider.
                currentCoroutineContext().ensureActive()
                if (!retryOnce || attempt == 1) return onTimeout()
            }
        }
        error("Unreachable")
    }
}
