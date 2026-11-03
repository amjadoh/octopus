package app.octopus.local.automation

import app.octopus.local.domain.IdentityText
import app.octopus.local.pricing.MoneyParser
import app.octopus.local.providers.*
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

enum class BasketNavigationFailure { STOPPED, UNSUPPORTED_SCREEN, INCOMPLETE_SCREEN, WRONG_RESTAURANT,
    AMBIGUOUS_CONTROL, ACTION_FAILED, UNREADABLE_BASKET, TIMEOUT, PROVIDER_ERROR }

sealed interface BasketNavigationResult {
    /** Visible basket evidence only. This result does not establish branch, address or a final quote. */
    data class Read(val evidence: BasketEvidence) : BasketNavigationResult
    data class Blocked(val reason: BasketNavigationFailure) : BasketNavigationResult
}

/** An issued command is single-use and can only open the previously observed menu basket button. */
class BasketOpenCommand internal constructor(
    internal val frame: CatalogFrame,
    internal val node: CatalogNode,
    private val issuedAt: Long,
) {
    private val consumed = AtomicBoolean(false)
    internal val wasConsumed: Boolean get() = consumed.get()
    internal fun authorizes(current: CatalogFrame, version: String?, allowed: Boolean, now: Long): Boolean =
        !consumed.get() && allowed && version == HungerStationCatalog.TESTED_VERSION && current == frame &&
            now >= issuedAt && now - issuedAt <= 15_000 && HungerStationBasketControls.openTarget(current) == node
    internal fun consume(current: CatalogFrame, version: String?, allowed: Boolean, now: Long): Boolean {
        if (!authorizes(current, version, allowed, now)) return false
        return consumed.compareAndSet(false, true)
    }
}

interface BasketNavigationPort {
    suspend fun stableFrame(stop: StopSignal): CatalogFrame
    /** Implementations must recheck the entire observed window and consume this command before dispatch. */
    suspend fun openCart(command: BasketOpenCommand, stop: StopSignal): Boolean
}

/** The anchor and its direct button/quantity children are present in hs-after-add.xml. */
internal object HungerStationBasketControls {
    fun openTarget(frame: CatalogFrame): CatalogNode? {
        if (!frame.complete || HungerStationCatalog.screen(frame) != CatalogScreen.MENU) return null
        val anchor = frame.nodes.singleOrNull { it.id == "FlutterMenu_goToCartButton" } ?: return null
        if (!anchor.enabled || anchor.clickable || anchor.editable || anchor.className != "android.view.View") return null
        val children = frame.nodes.filter { it.parent == anchor.index }
        val target = children.singleOrNull { it.clickable } ?: return null
        if (!target.enabled || target.editable || target.id.isNotEmpty() || target.className != "android.widget.Button") return null
        val lines = target.label.replace(Regex("[\\u200e\\u200f\\u061c\\u202a-\\u202e\\u2066-\\u2069]"), "")
            .lineSequence().map(String::trim).toList()
        if (footerAmount(lines)?.let { it >= 0 } != true) return null
        val counter = frame.nodes.filter { it.parent == target.index && it.id == "FlutterMenu_goToCartQuantity" }.singleOrNull() ?: return null
        if (!counter.enabled || counter.clickable || counter.editable || counter.className != "android.view.View") return null
        val quantity = counter.label.map { c -> if (c in '٠'..'٩') ('0'.code + c.code - '٠'.code).toChar() else c }.joinToString("").toIntOrNull()
        if (quantity == null || quantity !in 1..99) return null
        return target
    }

    /** The cart-bar amount: "عرض السلة 36 §", or — S22 / HS 8.0.304, discounted item — "عرض السلة 42 § 31.50 §"
     * with the struck-through amount first; then the lower current amount, and only when it is lower. */
    fun footerAmount(lines: List<String>): Long? {
        if (lines.firstOrNull() != "عرض السلة") return null
        if (lines.size == 3 && lines[2] == "§") return MoneyParser.parse(lines[1])
        if (lines.size != 5 || lines[2] != "§" || lines[4] != "§") return null
        val struck = MoneyParser.parse(lines[1]) ?: return null
        val current = MoneyParser.parse(lines[3]) ?: return null
        return current.takeIf { it in 0 until struck }
    }
}

/** One evidenced MENU -> CART action, or a read of an already open CART. No checkout operation. */
class HungerStationBasketNavigation(
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeoutMillis: Long = 30_000,
) {
    init { require(timeoutMillis in 1000..60_000) }

    suspend fun read(expectedRestaurant: String, port: BasketNavigationPort, stop: StopSignal): BasketNavigationResult {
        fun blocked(reason: BasketNavigationFailure) = BasketNavigationResult.Blocked(reason)
        if (expectedRestaurant.isBlank() || expectedRestaurant.length > 512) return blocked(BasketNavigationFailure.WRONG_RESTAURANT)
        return try {
            withTimeout(timeoutMillis) {
                currentCoroutineContext().ensureActive()
                if (stop.stopped) return@withTimeout blocked(BasketNavigationFailure.STOPPED)
                var frame = port.stableFrame(stop)
                currentCoroutineContext().ensureActive()
                if (stop.stopped) return@withTimeout blocked(BasketNavigationFailure.STOPPED)
                if (!frame.complete) return@withTimeout blocked(BasketNavigationFailure.INCOMPLETE_SCREEN)
                when (HungerStationCatalog.screen(frame)) {
                    CatalogScreen.MENU -> {
                        if (frame.nodes.none { n -> !n.editable && n.label.lineSequence().any {
                                IdentityText.clean(it) == IdentityText.clean(expectedRestaurant) } })
                            return@withTimeout blocked(BasketNavigationFailure.WRONG_RESTAURANT)
                        val target = HungerStationBasketControls.openTarget(frame)
                            ?: return@withTimeout blocked(BasketNavigationFailure.AMBIGUOUS_CONTROL)
                        val command = BasketOpenCommand(frame, target, clock())
                        currentCoroutineContext().ensureActive()
                        if (stop.stopped) return@withTimeout blocked(BasketNavigationFailure.STOPPED)
                        val opened = port.openCart(command, stop)
                        currentCoroutineContext().ensureActive()
                        if (stop.stopped) return@withTimeout blocked(BasketNavigationFailure.STOPPED)
                        if (!opened) return@withTimeout blocked(BasketNavigationFailure.ACTION_FAILED)
                        frame = port.stableFrame(stop)
                        currentCoroutineContext().ensureActive()
                        if (stop.stopped) return@withTimeout blocked(BasketNavigationFailure.STOPPED)
                    }
                    CatalogScreen.CART -> Unit
                    else -> return@withTimeout blocked(BasketNavigationFailure.UNSUPPORTED_SCREEN)
                }
                if (!frame.complete) return@withTimeout blocked(BasketNavigationFailure.INCOMPLETE_SCREEN)
                if (HungerStationCatalog.screen(frame) != CatalogScreen.CART)
                    return@withTimeout blocked(BasketNavigationFailure.UNSUPPORTED_SCREEN)
                // A visible frame and subtotal do not prove that off-screen basket rows were read.
                val evidence = BasketEvidenceReader.hungerStation(frame, endOfCartObserved = false)
                if (evidence.restaurant?.let(IdentityText::clean) != IdentityText.clean(expectedRestaurant))
                    return@withTimeout blocked(BasketNavigationFailure.WRONG_RESTAURANT)
                if (evidence.lines.isEmpty() || evidence.lines.any { it.quantity == null })
                    return@withTimeout blocked(BasketNavigationFailure.UNREADABLE_BASKET)
                BasketNavigationResult.Read(evidence)
            }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            blocked(BasketNavigationFailure.TIMEOUT)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { blocked(if (stop.stopped) BasketNavigationFailure.STOPPED else BasketNavigationFailure.PROVIDER_ERROR) }
    }
}
