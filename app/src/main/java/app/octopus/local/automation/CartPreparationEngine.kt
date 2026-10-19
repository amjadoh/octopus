package app.octopus.local.automation

import app.octopus.local.domain.*
import app.octopus.local.matching.*
import app.octopus.local.safety.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Ports must recognize an evidenced provider screen. No provider implements edits yet. */
interface CartPreparationPort {
    suspend fun observeCart(): ObservedCart
    /** Must recheck expectedFingerprint immediately before a permitted, known-screen UI action. */
    suspend fun applyAbsoluteQuantity(edit: ReconcileDecision.SetQuantity, stop: StopSignal): Boolean
}
class StopSignal {
    private val cancelled = AtomicBoolean(false)
    fun stop() { cancelled.set(true) }
    val stopped: Boolean get() = cancelled.get()
}
enum class PreparationFailure { STOPPED, NEEDS_APPROVAL, UNSAFE_CONTEXT, EDIT_FAILED, UNEXPECTED_CHANGE, LIMIT_EXCEEDED, PROVIDER_ERROR, ACTION_NOT_AUTHORIZED }
sealed interface PreparationResult {
    data class Ready(val cart: ObservedCart) : PreparationResult
    data class Failed(val reason: PreparationFailure) : PreparationResult
}

/** Owns only verified changes made during this call. An external edit invalidates ownership. */
class CartPreparationEngine(
    private val maxEdits: Int = 200,
    private val authorizeEdit: (ReconcileDecision.SetQuantity, ObservedCart) -> Boolean = { _, _ ->
        SafetyGuard().allowsAction(ProposedAction.CLICK)
    },
) {
    init { require(maxEdits in 1..1000) }
    suspend fun prepare(
        desired: CanonicalCart,
        port: CartPreparationPort,
        stop: StopSignal,
        initialApproval: CartReplacementApproval? = null,
    ): PreparationResult {
        var ownedFingerprint: String? = null
        val reconciler = CartReconciler()
        repeat(maxEdits + 1) { index ->
            currentCoroutineContext().ensureActive()
            if (stop.stopped) return PreparationResult.Failed(PreparationFailure.STOPPED)
            val actual = try { port.observeCart() } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { return PreparationResult.Failed(PreparationFailure.PROVIDER_ERROR) }
            currentCoroutineContext().ensureActive()
            if (stop.stopped) return PreparationResult.Failed(PreparationFailure.STOPPED)
            if (ownedFingerprint != null && actual.fingerprint != ownedFingerprint)
                return PreparationResult.Failed(PreparationFailure.UNEXPECTED_CHANGE)
            val approval = if (ownedFingerprint != null) CartReplacementApproval(actual.fingerprint, desired.fingerprint) else initialApproval
            when (val next = reconciler.next(desired, actual, approval)) {
                ReconcileDecision.Matched -> return PreparationResult.Ready(actual)
                is ReconcileDecision.NeedsApproval -> return PreparationResult.Failed(PreparationFailure.NEEDS_APPROVAL)
                is ReconcileDecision.Blocked -> return PreparationResult.Failed(PreparationFailure.UNSAFE_CONTEXT)
                is ReconcileDecision.SetQuantity -> {
                    if (index == maxEdits) return PreparationResult.Failed(PreparationFailure.LIMIT_EXCEEDED)
                    currentCoroutineContext().ensureActive()
                    if (stop.stopped) return PreparationResult.Failed(PreparationFailure.STOPPED)
                    if (!authorizeEdit(next, actual)) return PreparationResult.Failed(PreparationFailure.ACTION_NOT_AUTHORIZED)
                    val edited = try { port.applyAbsoluteQuantity(next, stop) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { return PreparationResult.Failed(PreparationFailure.PROVIDER_ERROR) }
                    if (!edited) return PreparationResult.Failed(if (stop.stopped) PreparationFailure.STOPPED else PreparationFailure.EDIT_FAILED)
                    // Only exactly the expected resulting basket may become session-owned.
                    val key = CartIdentity.itemKey(next.item)
                    val expectedItems = actual.items.filter { CartIdentity.itemKey(it) != key } +
                        if (next.targetQuantity == 0) emptyList() else listOf(next.item.copy(quantity = next.targetQuantity))
                    ownedFingerprint = CartIdentity.contentsFingerprint(desired.restaurant, desired.branch, expectedItems)
                }
            }
        }
        return PreparationResult.Failed(PreparationFailure.LIMIT_EXCEEDED)
    }
}
