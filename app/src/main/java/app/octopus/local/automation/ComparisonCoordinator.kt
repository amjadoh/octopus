package app.octopus.local.automation

import app.octopus.local.domain.*
import app.octopus.local.matching.*
import app.octopus.local.pricing.*
import kotlinx.coroutines.*

data class ProviderReadiness(val providerVersion: String, val accountContext: String, val supported: Boolean)

/** Implementations must use the shared, known-screen executor. There is no order/payment operation. */
interface ComparisonProviderPort : CartPreparationPort {
    val provider: ProviderId
    suspend fun preflight(desired: CanonicalCart): ProviderReadiness
    /** Only an evidenced final review may set recognizedOrderReview/mandatoryFeesIncluded. */
    suspend fun observeReview(): QuoteObservation
    suspend fun openCart(): ObservedCart
    /** Cancels pending work/removes overlays only; must not click or clean baskets. */
    fun releaseControl()
}

enum class ComparisonPhase { PREFLIGHT, PREPARING_CART, READING_REVIEW, COMPLETE, FAILED, STOPPED }
enum class ComparisonFailure { CONTEXT_REQUIRED, UNSUPPORTED, PREPARATION, QUOTE_REJECTED, CONTEXT_CHANGED, TIMEOUT, PROVIDER_ERROR }
data class ProviderComparisonResult(
    val provider: ProviderId,
    val accepted: QuoteValidation.Accepted? = null,
    val failure: ComparisonFailure? = null,
    val preparationFailure: PreparationFailure? = null,
    val quoteRejection: QuoteRejection? = null,
)
data class ComparisonOutcome(val results: List<ProviderComparisonResult>, val ranked: List<VerifiedQuote>, val stopped: Boolean) {
    val twoProviderComparison: Boolean get() = !stopped && ranked.map { it.provider }.toSet() == ProviderId.entries.toSet()
}
sealed interface HandoffResult {
    data class Ready(val quote: VerifiedQuote) : HandoffResult
    data class PriceChanged(val previous: VerifiedQuote, val current: VerifiedQuote) : HandoffResult
    data class PaymentContextChanged(val previous: VerifiedQuote, val current: VerifiedQuote) : HandoffResult
    data object Expired : HandoffResult
    data object CartChanged : HandoffResult
    data object RequiresUserAction : HandoffResult
    data object Failed : HandoffResult
    data object Stopped : HandoffResult
}

/** Sequential orchestration. Unit ports exercise this logic but do not prove Android provider adapters. */
class ComparisonCoordinator(
    private val preparation: CartPreparationEngine = CartPreparationEngine(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val waitStable: suspend (Long) -> Unit = { delay(it) },
    private val providerTimeoutMillis: Long = 150_000,
) {
    private class Stopped : CancellationException()
    private val quotes = QuoteValidator()
    private val ranking = PriceComparisonEngine()
    init { require(providerTimeoutMillis in 1000..600_000) }
    private suspend fun check(stop: StopSignal) {
        currentCoroutineContext().ensureActive()
        if (stop.stopped) throw Stopped()
    }

    suspend fun compare(
        desired: CanonicalCart,
        providers: List<ComparisonProviderPort>,
        stop: StopSignal,
        approvals: Map<ProviderId, CartReplacementApproval> = emptyMap(),
        progress: (ProviderId, ComparisonPhase) -> Unit = { _, _ -> },
    ): ComparisonOutcome {
        require(providers.map { it.provider }.distinct().size == providers.size)
        val results = mutableListOf<ProviderComparisonResult>()
        var stopped = false
        for (port in providers) {
            try {
                check(stop)
                val result = withTimeout(providerTimeoutMillis) {
                    progress(port.provider, ComparisonPhase.PREFLIGHT)
                    if (desired.addressReference == null || desired.branch == null)
                        return@withTimeout ProviderComparisonResult(port.provider, failure = ComparisonFailure.CONTEXT_REQUIRED)
                    val ready = port.preflight(desired)
                    check(stop)
                    if (!ready.supported || ready.providerVersion.isBlank() || ready.accountContext.isBlank())
                        return@withTimeout ProviderComparisonResult(port.provider, failure = ComparisonFailure.UNSUPPORTED)
                    progress(port.provider, ComparisonPhase.PREPARING_CART)
                    val prepared = preparation.prepare(desired, port, stop, approvals[port.provider])
                    check(stop)
                    if (prepared is PreparationResult.Failed)
                        return@withTimeout ProviderComparisonResult(port.provider, failure = ComparisonFailure.PREPARATION, preparationFailure = prepared.reason)
                    progress(port.provider, ComparisonPhase.READING_REVIEW)
                    val validated = reviewPair(desired, port, ready, stop)
                    when (validated) {
                        null -> ProviderComparisonResult(port.provider, failure = ComparisonFailure.CONTEXT_CHANGED)
                        is QuoteValidation.Rejected -> ProviderComparisonResult(port.provider, failure = ComparisonFailure.QUOTE_REJECTED, quoteRejection = validated.reason)
                        is QuoteValidation.Accepted -> ProviderComparisonResult(port.provider, accepted = validated)
                    }
                }
                results += result
                progress(port.provider, if (result.accepted != null) ComparisonPhase.COMPLETE else ComparisonPhase.FAILED)
            } catch (_: Stopped) {
                stopped = true; progress(port.provider, ComparisonPhase.STOPPED); break
            } catch (_: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                results += ProviderComparisonResult(port.provider, failure = ComparisonFailure.TIMEOUT)
                progress(port.provider, ComparisonPhase.FAILED)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                results += ProviderComparisonResult(port.provider, failure = ComparisonFailure.PROVIDER_ERROR)
                progress(port.provider, ComparisonPhase.FAILED)
            } finally { if (runCatching { port.releaseControl() }.isFailure) stop.stop() }
        }
        val ranked = if (stopped || stop.stopped) emptyList() else ranking.rankFor(desired, results.mapNotNull { it.accepted?.quote }, clock())
        return ComparisonOutcome(results.toList(), ranked, stopped || stop.stopped)
    }

    private suspend fun reviewPair(desired: CanonicalCart, port: ComparisonProviderPort, ready: ProviderReadiness, stop: StopSignal): QuoteValidation? {
        check(stop)
        val first = port.observeReview()
        check(stop)
        waitStable(1000)
        check(stop)
        val last = port.observeReview()
        check(stop)
        if (listOf(first,last).any { it.provider != port.provider || it.providerVersion != ready.providerVersion || it.accountContext != ready.accountContext }) return null
        return quotes.validate(desired, first, last, clock())
    }

    suspend fun handoff(desired: CanonicalCart, selected: QuoteValidation.Accepted, port: ComparisonProviderPort, stop: StopSignal): HandoffResult {
        if (selected.quote.provider != port.provider || ranking.rankFor(desired,listOf(selected.quote),clock()).isEmpty()) return HandoffResult.Expired
        val result = try {
            withTimeout(providerTimeoutMillis) {
                check(stop)
                val ready = port.preflight(desired)
                check(stop)
                if (!ready.supported || ready.providerVersion != selected.source.providerVersion || ready.accountContext != selected.source.accountContext)
                    return@withTimeout HandoffResult.RequiresUserAction
                val cart = port.openCart()
                check(stop)
                if (!ItemMatcher().compare(desired,cart).exact) return@withTimeout HandoffResult.CartChanged
                val current = reviewPair(desired,port,ready,stop) as? QuoteValidation.Accepted ?: return@withTimeout HandoffResult.RequiresUserAction
                if (current.quote.orderCost != selected.quote.orderCost) return@withTimeout HandoffResult.PriceChanged(selected.quote,current.quote)
                if (current.quote.walletApplied != selected.quote.walletApplied || current.quote.amountDueNow != selected.quote.amountDueNow)
                    return@withTimeout HandoffResult.PaymentContextChanged(selected.quote,current.quote)
                val finalCart = port.openCart()
                check(stop)
                if (!ItemMatcher().compare(desired,finalCart).exact) HandoffResult.CartChanged else HandoffResult.Ready(current.quote)
            }
        } catch (_: Stopped) { HandoffResult.Stopped }
        catch (_: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); HandoffResult.Failed }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { HandoffResult.Failed }
        finally { if (runCatching { port.releaseControl() }.isFailure) stop.stop() }
        return if (stop.stopped) HandoffResult.Stopped else result
    }
}
