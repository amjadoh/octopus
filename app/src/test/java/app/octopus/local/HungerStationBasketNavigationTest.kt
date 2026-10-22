package app.octopus.local

import app.octopus.local.automation.*
import app.octopus.local.domain.ProviderId
import app.octopus.local.providers.*
import app.octopus.local.safety.SafetyGuard
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/** Replays the previous device layout. Port execution is controlled test I/O, not a live provider run. */
class HungerStationBasketNavigationTest {
    private val restaurant = "المضياف العربي"
    private fun recorded(name: String): CatalogFrame {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val document = checkNotNull(javaClass.getResourceAsStream("/recorded-hs/$name.xml")).use {
            factory.newDocumentBuilder().parse(it)
        }
        val nodes = mutableListOf<CatalogNode>()
        fun walk(element: Element, parent: Int) {
            val children = element.childNodes
            for (i in 0 until children.length) {
                val child = children.item(i) as? Element ?: continue
                val index = nodes.size
                nodes += CatalogNode(index, parent, child.getAttribute("resource-id"), child.getAttribute("label"),
                    child.getAttribute("class"), child.getAttribute("clickable") == "true", false,
                    child.getAttribute("scrollable") == "true", child.getAttribute("enabled") == "true")
                walk(child, index)
            }
        }
        walk(document.documentElement, -1)
        return CatalogFrame(nodes, true)
    }
    private val menu = recorded("hs-after-add-basket-navigation")
    private val basket = recorded("hs-basket-visible-details")
    private val button get() = checkNotNull(HungerStationBasketControls.openTarget(menu))
    private fun change(frame: CatalogFrame, predicate: (CatalogNode) -> Boolean, transform: (CatalogNode) -> CatalogNode) =
        frame.copy(nodes = frame.nodes.map { if (predicate(it)) transform(it) else it })
    private inner class Port(var first: CatalogFrame = menu, var second: CatalogFrame = basket) : BasketNavigationPort {
        var reads = 0
        var opens = 0
        var succeeds = true
        var fail = false
        var onRead: () -> Unit = {}
        var onOpen: () -> Unit = {}
        var beforeDispatch: CatalogFrame? = null
        var secondReadDelayMillis = 0L
        override suspend fun stableFrame(stop: StopSignal): CatalogFrame {
            onRead()
            if (reads > 0) delay(secondReadDelayMillis)
            return if (reads++ == 0) first else second
        }
        override suspend fun openCart(command: BasketOpenCommand, stop: StopSignal): Boolean {
            opens++
            onOpen()
            if (fail) error("controlled failure")
            return succeeds && command.consume(beforeDispatch ?: first, HungerStationCatalog.TESTED_VERSION, !stop.stopped, 2000)
        }
    }
    private suspend fun run(port: Port, stop: StopSignal = StopSignal()) =
        HungerStationBasketNavigation(clock = { 2000 }).read(restaurant, port, stop)
    private fun blocked(reason: BasketNavigationFailure) = BasketNavigationResult.Blocked(reason)
    @Test fun bidiWrappedBasketAmountKeepsTheSameKnownControl() {
        val changed = change(menu, { it == button }) { it.copy(label="عرض السلة\n\u202a7\n§\u202c") }
        assertNotNull(HungerStationBasketControls.openTarget(changed))
        val payment = change(changed, { it.index == button.index }) { it.copy(label="تأكيد الطلب\n7\n§") }
        assertNull(HungerStationBasketControls.openTarget(payment))
    }

    @Test fun recordedPostAddOpensExactlyOnceAndReturnsOnlyVisibleBasketEvidence() = runBlocking {
        val port = Port()
        val result = run(port) as BasketNavigationResult.Read
        assertEquals(1, port.opens)
        assertEquals(restaurant, result.evidence.restaurant)
        assertEquals("دجاج مظبي", result.evidence.lines.single().name)
        assertEquals(1, result.evidence.lines.single().quantity)
        assertEquals(3600L, result.evidence.subtotal)
        assertFalse(result.evidence.complete)
        assertFalse(result.evidence.recognizedOrderReview)
    }

    @Test fun alreadyOpenBasketDoesNotClickAnything() = runBlocking {
        val port = Port(first = basket)
        assertTrue(run(port) is BasketNavigationResult.Read)
        assertEquals(0, port.opens)
    }

    @Test fun unknownOrPartialFirstScreenCannotAct() = runBlocking {
        val partial = Port(first = menu.copy(complete = false))
        assertEquals(blocked(BasketNavigationFailure.INCOMPLETE_SCREEN), run(partial))
        val unknown = Port(first = CatalogFrame(emptyList(), true))
        assertEquals(blocked(BasketNavigationFailure.UNSUPPORTED_SCREEN), run(unknown))
        assertEquals(0, partial.opens + unknown.opens)
    }

    @Test fun wrongRestaurantIsRejectedBeforeAndAfterNavigation() = runBlocking {
        val before = Port(first = change(menu, { it.label.startsWith(restaurant) }) { it.copy(label = "مطعم آخر") })
        assertEquals(blocked(BasketNavigationFailure.WRONG_RESTAURANT), run(before))
        assertEquals(0, before.opens)
        val after = Port(second = change(basket, { it.id == "FlutterCart_title" }) { it.copy(label = "السلة\nمطعم آخر") })
        assertEquals(blocked(BasketNavigationFailure.WRONG_RESTAURANT), run(after))
        assertEquals(1, after.opens)
    }

    @Test fun duplicatedDisabledOrMovedButtonNeverAuthorizesClick() = runBlocking {
        val cases = listOf(
            menu.copy(nodes = menu.nodes + button.copy(index = 9999)),
            change(menu, { it.index == button.index }) { it.copy(enabled = false) },
            change(menu, { it.index == button.index }) { it.copy(parent = -1) },
            change(menu, { it.id == "FlutterMenu_goToCartButton" }) { it.copy(clickable = true) },
        )
        for (frame in cases) {
            val port = Port(first = frame)
            assertEquals(blocked(BasketNavigationFailure.AMBIGUOUS_CONTROL), run(port))
            assertEquals(0, port.opens)
        }
    }

    @Test fun checkoutLabelsAndUnknownCountersCannotBecomeOpenBasketCommands() = runBlocking {
        val cases = listOf(
            change(menu, { it.index == button.index }) { it.copy(label = "اذهب للدفع") },
            change(menu, { it.index == button.index }) { it.copy(label = "تأكيد الطلب\n36\n§") },
            change(menu, { it.index == button.index }) { it.copy(label = "عرض السلة\n?\n§") },
            change(menu, { it.id == "FlutterMenu_goToCartQuantity" }) { it.copy(label = "?") },
            menu.copy(nodes = menu.nodes.filterNot { it.id == "FlutterMenu_goToCartQuantity" }),
        )
        for (frame in cases) assertNull(HungerStationBasketControls.openTarget(frame))
    }

    @Test fun commandRequiresSameFrameVersionActiveSessionAndFreshTime() {
        fun command() = BasketOpenCommand(menu, button, 2000)
        assertFalse(command().consume(menu, "other-version", true, 2000))
        assertFalse(command().consume(menu, HungerStationCatalog.TESTED_VERSION, false, 2000))
        assertFalse(command().consume(menu, HungerStationCatalog.TESTED_VERSION, true, 1999))
        assertFalse(command().consume(menu, HungerStationCatalog.TESTED_VERSION, true, 17_001))
        assertFalse(command().consume(change(menu, { it.index == button.index }) { it.copy(label = "عرض السلة\n40\n§") },
            HungerStationCatalog.TESTED_VERSION, true, 2000))
    }

    @Test fun commandCanOnlyBeConsumedOnce() {
        val command = BasketOpenCommand(menu, button, 2000)
        assertTrue(command.consume(menu, HungerStationCatalog.TESTED_VERSION, true, 2000))
        assertFalse(command.consume(menu, HungerStationCatalog.TESTED_VERSION, true, 2000))
    }

    @Test fun changedFrameOrFailedDispatchNeverTriggersRetry() = runBlocking {
        val changed = Port().apply { beforeDispatch = change(menu, { it.index == button.index }) { it.copy(enabled = false) } }
        assertEquals(blocked(BasketNavigationFailure.ACTION_FAILED), run(changed))
        val failed = Port().apply { succeeds = false }
        assertEquals(blocked(BasketNavigationFailure.ACTION_FAILED), run(failed))
        val throws = Port().apply { fail = true }
        assertEquals(blocked(BasketNavigationFailure.PROVIDER_ERROR), run(throws))
        assertEquals(listOf(1, 1, 1), listOf(changed.opens, failed.opens, throws.opens))
    }

    @Test fun stopBeforeReadingAfterReadingOrAfterOpeningPreventsAnyLaterOperation() = runBlocking {
        val stop = StopSignal().apply { stop() }
        val never = Port()
        assertEquals(blocked(BasketNavigationFailure.STOPPED), run(never, stop))
        assertEquals(0, never.reads)
        val duringRead = StopSignal()
        val read = Port().apply { onRead = { duringRead.stop() } }
        assertEquals(blocked(BasketNavigationFailure.STOPPED), run(read, duringRead))
        assertEquals(0, read.opens)
        val duringOpen = StopSignal()
        val opened = Port().apply { onOpen = { duringOpen.stop() } }
        assertEquals(blocked(BasketNavigationFailure.STOPPED), run(opened, duringOpen))
        assertEquals(1, opened.reads)
    }

    @Test fun unsuccessfulNavigationNeverReadsMenuAsBasketOrRepeatsOpen() = runBlocking {
        val port = Port(second = menu)
        assertEquals(blocked(BasketNavigationFailure.UNSUPPORTED_SCREEN), run(port))
        assertEquals(1, port.opens)
    }

    @Test fun partialOrUnparseableResultIsRejected() = runBlocking {
        assertEquals(blocked(BasketNavigationFailure.INCOMPLETE_SCREEN), run(Port(second = basket.copy(complete = false))))
        val noQuantity = change(basket, { it.id == "menuItemActionDualButtonValue" }) { it.copy(label = "?") }
        assertEquals(blocked(BasketNavigationFailure.UNREADABLE_BASKET), run(Port(second = noQuantity)))
        val noItems = basket.copy(nodes = basket.nodes.filterNot { it.id == "FlutterCart_cartItem" })
        assertEquals(blocked(BasketNavigationFailure.UNREADABLE_BASKET), run(Port(second = noItems)))
    }

    @Test fun changedItemsRiceAndQuantityStayAsObservedAndNeverBecomeDesiredOrVerified() = runBlocking {
        val alternatives = listOf(
            change(basket, { it.id == "FlutterCart_cartItem" }) { it.copy(label = it.label.replace("دجاج مظبي", "صنف آخر")) },
            change(basket, { it.id == "FlutterCart_cartItem" }) { it.copy(label = it.label.replace("رز بشاور", "رز مندي")) },
            change(basket, { it.id == "menuItemActionDualButtonValue" }) { it.copy(label = "2") },
        )
        val results = alternatives.map { (run(Port(second = it)) as BasketNavigationResult.Read).evidence }
        assertEquals("صنف آخر", results[0].lines.single().name)
        assertTrue(results[1].lines.single().choices.any { it.value == "رز مندي" })
        assertEquals(2, results[2].lines.single().quantity)
        assertTrue(results.all { !it.complete && !it.recognizedOrderReview })
    }

    @Test fun timeoutWaitingForBasketDoesNotRepeatOpen() = runBlocking {
        val port = Port().apply { secondReadDelayMillis = 5000 }
        val result = HungerStationBasketNavigation(clock = { 2000 }, timeoutMillis = 1000).read(restaurant, port, StopSignal())
        assertEquals(blocked(BasketNavigationFailure.TIMEOUT), result)
        assertEquals(1, port.opens)
    }

    @Test fun sharedGuardRequiresPackageConsentActiveSessionAndUnusedKnownCommand() {
        val guard = SafetyGuard()
        val command = BasketOpenCommand(menu, button, 2000)
        fun allowed(provider: String? = ProviderId.HUNGERSTATION.packageName, consent: Boolean = true,
            active: Boolean = true, frame: CatalogFrame = menu, candidate: BasketOpenCommand = command) =
            guard.allowsBasketOpen(candidate, frame, provider, HungerStationCatalog.TESTED_VERSION, consent, active, 2000)
        assertTrue(allowed())
        assertFalse(allowed(provider = ProviderId.KEETA.packageName))
        assertFalse(allowed(consent = false))
        assertFalse(allowed(active = false))
        val restricted = menu.copy(nodes = menu.nodes + CatalogNode(9999, -1, "", "Unusual activity detected. Log-in or sign-up is restricted.",
            "android.widget.TextView", false, false, false))
        assertFalse(allowed(frame = restricted, candidate = BasketOpenCommand(restricted, button, 2000)))
        assertTrue(command.consume(menu, HungerStationCatalog.TESTED_VERSION, true, 2000))
        assertFalse(allowed())
    }
}
