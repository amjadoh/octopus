package app.octopus.local.automation

import app.octopus.local.matching.ObservedCart
import app.octopus.local.providers.CatalogFrame
import kotlinx.coroutines.*

/** Sources must return stable live frames, never a saved diagnostic replay in the installed app. */
interface ProductElementPort {
    suspend fun stableProductFrame(stop: StopSignal): CatalogFrame
    /** Re-read and compare the entire frame/window before performing the single exact element action. */
    suspend fun execute(frame: CatalogFrame, command: ProductCommand, stop: StopSignal): Boolean
    /** Must navigate/read the actual complete basket. A successful Add click is insufficient. */
    suspend fun readResultingBasket(stop: StopSignal): ObservedCart
}

enum class ProductRunResult { BASKET_VERIFIED, STOPPED, BLOCKED, ACTION_FAILED, BASKET_MISMATCH, TIMEOUT }

/** Does not own, create or confirm an order. A stopped/failed attempt cannot replay Add. */
class GuardedProductRunner(
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeoutMillis: Long = 90_000,
) {
    init { require(timeoutMillis in 1000..120_000) }
    suspend fun run(program: HungerStationProductProgram, port: ProductElementPort, stop: StopSignal): ProductRunResult {
        try {
            return withTimeout(timeoutMillis) {
                repeat(40) {
                    currentCoroutineContext().ensureActive()
                    if (stop.stopped) return@withTimeout ProductRunResult.STOPPED
                    // Add may close the PDP. Read the basket before requesting another product frame.
                    if (program.awaitingBasket) {
                        val basket = port.readResultingBasket(stop)
                        currentCoroutineContext().ensureActive()
                        if (stop.stopped) return@withTimeout ProductRunResult.STOPPED
                        return@withTimeout if (program.verifyResult(basket, clock())) ProductRunResult.BASKET_VERIFIED else ProductRunResult.BASKET_MISMATCH
                    }
                    val frame = port.stableProductFrame(stop)
                    currentCoroutineContext().ensureActive()
                    if (stop.stopped) return@withTimeout ProductRunResult.STOPPED
                    when (val command = program.next(frame, clock())) {
                        is ProductCommand.Blocked -> return@withTimeout ProductRunResult.BLOCKED
                        ProductCommand.ObserveBasket -> {
                            val basket = port.readResultingBasket(stop)
                            currentCoroutineContext().ensureActive()
                            if (stop.stopped) return@withTimeout ProductRunResult.STOPPED
                            return@withTimeout if (program.verifyResult(basket, clock())) ProductRunResult.BASKET_VERIFIED else ProductRunResult.BASKET_MISMATCH
                        }
                        else -> {
                            var succeeded = false
                            try { succeeded = port.execute(frame, command, stop) }
                            finally { program.acknowledge(command, succeeded) }
                            currentCoroutineContext().ensureActive()
                            if (stop.stopped) return@withTimeout ProductRunResult.STOPPED
                            if (!succeeded) return@withTimeout ProductRunResult.ACTION_FAILED
                        }
                    }
                }
                ProductRunResult.BLOCKED
            }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            return ProductRunResult.TIMEOUT
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return if (stop.stopped) ProductRunResult.STOPPED else ProductRunResult.BLOCKED }
    }
}
