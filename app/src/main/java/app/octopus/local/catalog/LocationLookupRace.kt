package app.octopus.local.catalog

import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select

/** Wait for the first usable foreground fix, not the entire timeout after all sources failed. */
internal suspend fun <T> firstUsableLocation(
    sources: List<suspend () -> T?>,
    usable: (T) -> Boolean,
): T? = coroutineScope {
    val pending = sources.map { source -> async {
        try { source() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    } }.toMutableList()
    try {
        while (pending.isNotEmpty()) {
            val (job, value) = select<Pair<Deferred<T?>, T?>> {
                pending.forEach { job -> job.onAwait { job to it } }
            }
            pending.remove(job)
            if (value != null && usable(value)) return@coroutineScope value
        }
        null
    } finally { pending.forEach { it.cancel() } }
}
