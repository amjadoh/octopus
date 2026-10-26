package app.octopus.local.automation

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A nested read timeout makes that provider unavailable; stopping the parent stops all providers. */
object MenuReadTimeoutBoundary {
    suspend fun <T> read(onTimeout: () -> T, block: suspend () -> T): T = try {
        block()
    } catch (timeout: TimeoutCancellationException) {
        // A timeout owned by the enclosing comparison must never start another provider.
        currentCoroutineContext().ensureActive()
        onTimeout()
    }
}
