package app.octopus.local

import app.octopus.local.automation.*
import app.octopus.local.domain.*
import app.octopus.local.matching.ObservedCart
import app.octopus.local.providers.*
import app.octopus.local.safety.SafetyGuard
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/** Recorded Android layout replay + controlled execution faults. Not a live delivery-app test. */
class ProductExecutionTest {
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
    private val top = recorded("hs-item-options")
    private val options = recorded("hs-item-options-scrolled")
    private val bottom = recorded("hs-item-options-bottom")
    private val details = HungerStationProductReader(LiveRestaurant("المضياف العربي", null, "المضياف"),
        LiveMenuItem("دجاج مظبي", 3300, "gridMenuCell-0-0")).also {
        it.observe(top); it.observe(options); it.observe(bottom)
    }.finish(1000)
    private val selection = ProductSelection(details.product.id,
        mapOf("اختر الحجم" to setOf("نص"), "إضافات" to setOf("رز بشاور")), 1)
    private val wanted = CanonicalItem("دجاج مظبي", "نص", listOf(CartOption("إضافات", "رز بشاور")), 1)
    private val desired = CanonicalCart("المضياف العربي", "test-branch", listOf(wanted), "test-address")
    private val before = ObservedCart(desired.restaurant, desired.branch, desired.addressReference, emptyList(), true)
    private fun program() = checkNotNull(HungerStationProductProgram.create(details, selection, desired, before, 2000))
    private fun select(program: HungerStationProductProgram) {
        repeat(2) {
            val command = program.next(options, 2000)
            assertTrue(command is ProductCommand.Choose)
            program.acknowledge(command, true)
        }
    }

    @Test fun savedShapeProducesSizeRiceAndSingleAddFollowedByBasketObservation() {
        val p = program()
        val scroll = p.next(top, 2000)
        assertTrue(scroll is ProductCommand.Scroll); p.acknowledge(scroll, true)
        val size = p.next(options, 2000) as ProductCommand.Choose
        assertEquals("اختر الحجم", size.group); assertEquals("نص", size.value)
        p.acknowledge(size, true)
        val rice = p.next(options, 2000) as ProductCommand.Choose
        assertEquals("رز بشاور", rice.value); p.acknowledge(rice, true)
        val add = p.next(options, 2000)
        assertTrue(add is ProductCommand.Add); p.acknowledge(add, true)
        assertEquals(ProductCommand.ObserveBasket, p.next(CatalogFrame(emptyList(), true), 2000))
        assertTrue(p.verifyResult(before.copy(items = listOf(wanted)), 2000))
    }
    @Test fun conflictingExistingBasketAndUnknownAddressNeverStartMutation() {
        assertNull(HungerStationProductProgram.create(details, selection, desired, before.copy(items = listOf(wanted)), 2000))
        assertNull(HungerStationProductProgram.create(details, selection, desired, before.copy(addressReference = null), 2000))
        assertNull(HungerStationProductProgram.create(details, selection, desired.copy(branch = null), before, 2000))
        assertNull(HungerStationProductProgram.create(details, selection, desired, before.copy(complete = false), 2000))
        assertNull(HungerStationProductProgram.create(details, selection,
            desired.copy(items = listOf(wanted, wanted.copy(name = "صنف آخر"))), before, 2000))
    }
    @Test fun staleSourceDifferentProviderAndUnevidencedQuantitiesFailClosed() {
        assertNull(HungerStationProductProgram.create(details, selection, desired, before, 400_000))
        assertNull(HungerStationProductProgram.create(details.copy(source = details.source.copy(provider = ProviderId.KEETA)), selection, desired, before, 2000))
        assertNull(HungerStationProductProgram.create(details, selection.copy(quantity = 2), desired.copy(items = listOf(wanted.copy(quantity = 2))), before, 2000))
    }
    @Test fun aPendingAddCannotBeIssuedTwiceAndFailedAddCannotBeRetried() {
        val p = program(); select(p)
        val add = p.next(options, 2000)
        assertSame(add, p.next(options, 2000))
        p.acknowledge(add, false)
        assertEquals(ProductCommand.Blocked(ProductBlock.ACTION_FAILED), p.next(options, 2000))
        assertThrows(IllegalArgumentException::class.java) { p.acknowledge(add, true) }
    }
    @Test fun actionPermitIsConsumedBeforeAndroidDispatchEvenWithoutAcknowledgement() {
        val p = program(); select(p)
        val add = p.next(options, 2000)
        assertTrue(p.markAttempted(add, 2000))
        assertFalse(p.markAttempted(add, 2000))
        assertFalse(SafetyGuard().allowsProductElement(add, options, ProviderId.HUNGERSTATION.packageName,
            HungerStationCatalog.TESTED_VERSION, true, true, p, 2000))
        p.acknowledge(add, false)
        assertEquals(ProductCommand.Blocked(ProductBlock.ACTION_FAILED), p.next(options, 2000))
    }
    @Test fun visibleRestrictionBlocksOtherwiseKnownProductAction() {
        val p = program()
        val action = p.next(options, 2000)
        assertTrue(SafetyGuard().allowsProductElement(action, options, ProviderId.HUNGERSTATION.packageName,
            HungerStationCatalog.TESTED_VERSION, true, true, p, 2000))
        val restricted = options.copy(nodes = options.nodes + CatalogNode(9999, -1, "", "Access error",
            "android.widget.TextView", false, false, false, true))
        assertFalse(SafetyGuard().allowsProductElement(action, restricted, ProviderId.HUNGERSTATION.packageName,
            HungerStationCatalog.TESTED_VERSION, true, true, p, 2000))
    }
    @Test fun ambiguousRiceOrDisabledChoiceNeverFallsBackToAnotherVariant() {
        val p = program(); val size = p.next(options, 2000); p.acknowledge(size, true)
        val rice = options.nodes.single { it.label.startsWith("رز بشاور\n") }
        val duplicate = options.copy(nodes = options.nodes + rice.copy(index = 999))
        assertEquals(ProductCommand.Blocked(ProductBlock.AMBIGUOUS_ELEMENT), p.next(duplicate, 2000))
        val disabled = program(); val first = disabled.next(options, 2000); disabled.acknowledge(first, true)
        assertEquals(ProductCommand.Blocked(ProductBlock.UNAVAILABLE), disabled.next(options.copy(nodes = options.nodes.map {
            if (it == rice) it.copy(enabled = false) else it
        }), 2000))
    }
    @Test fun unrelatedProductAndPartialFrameCannotIssueCommands() {
        assertEquals(ProductCommand.Blocked(ProductBlock.INCOMPLETE_SCREEN), program().next(options.copy(complete = false), 2000))
        val wrong = options.copy(nodes = options.nodes.map { if (it.label == "دجاج مظبي") it.copy(label = "وجبة أخرى") else it })
        assertEquals(ProductCommand.Blocked(ProductBlock.WRONG_PRODUCT), program().next(wrong, 2000))
        assertEquals(ProductCommand.Blocked(ProductBlock.STALE_PRODUCT), program().next(options, 200_000))
    }
    @Test fun safetyRequiresExactFrameProviderVersionAndElementFunction() {
        val guard = SafetyGuard(); val p = program(); select(p)
        val add = p.next(options, 2000) as ProductCommand.Add
        fun allowed(command: ProductCommand = add, frame: CatalogFrame = options, pkg: String? = ProviderId.HUNGERSTATION.packageName,
            version: String? = HungerStationCatalog.TESTED_VERSION, consent: Boolean = true, active: Boolean = true) =
            guard.allowsProductElement(command, frame, pkg, version, consent, active, p, 2000)
        assertTrue(allowed())
        assertFalse(allowed(pkg = ProviderId.KEETA.packageName)); assertFalse(allowed(version = "future"))
        assertFalse(allowed(consent = false)); assertFalse(allowed(active = false)); assertFalse(allowed(frame = options.copy(complete = false)))
        assertFalse(allowed(command = add.copy(node = add.node.copy(label = "تأكيد الطلب"))))
        assertFalse(allowed(command = add.copy(node = add.node.copy(id = "checkout"))))
        assertFalse(guard.allowsProductElement(add, options, ProviderId.HUNGERSTATION.packageName,
            HungerStationCatalog.TESTED_VERSION, true, true, now = 2000))
        p.acknowledge(add, true)
        assertFalse(allowed())
    }
    private inner class Port : ProductElementPort {
        val commands = mutableListOf<ProductCommand>()
        var frameReads = 0; var basketReads = 0
        var result = before.copy(items = listOf(wanted))
        var stopOnChoice = false; var failAdd = false; var throwBasket = false
        override suspend fun stableProductFrame(stop: StopSignal): CatalogFrame {
            frameReads++
            check(commands.none { it is ProductCommand.Add }) { "PDP has closed" }
            return options
        }
        override suspend fun execute(frame: CatalogFrame, command: ProductCommand, stop: StopSignal): Boolean {
            commands += command
            if (stopOnChoice) stop.stop()
            return !(failAdd && command is ProductCommand.Add)
        }
        override suspend fun readResultingBasket(stop: StopSignal): ObservedCart {
            basketReads++; check(!throwBasket); return result
        }
    }
    @Test fun runnerReadsBasketAfterAddWithoutAssumingProductScreenRemainsOpen() = runBlocking {
        val port = Port()
        assertEquals(ProductRunResult.BASKET_VERIFIED, GuardedProductRunner(clock = { 2000 }).run(program(), port, StopSignal()))
        assertEquals(3, port.frameReads); assertEquals(1, port.basketReads)
        assertEquals(1, port.commands.count { it is ProductCommand.Add })
    }
    @Test fun stopAfterChoicePreventsAddAndEveryLaterAction() = runBlocking {
        val port = Port().apply { stopOnChoice = true }
        assertEquals(ProductRunResult.STOPPED, GuardedProductRunner(clock = { 2000 }).run(program(), port, StopSignal()))
        assertEquals(1, port.commands.size); assertEquals(0, port.basketReads)
    }
    @Test fun failedAddAndMissingReceiptNeverCauseAnAutomaticSecondAdd() = runBlocking {
        val p = program(); val port = Port().apply { failAdd = true }; val runner = GuardedProductRunner(clock = { 2000 })
        assertEquals(ProductRunResult.ACTION_FAILED, runner.run(p, port, StopSignal()))
        assertEquals(ProductRunResult.BLOCKED, runner.run(p, port, StopSignal()))
        assertEquals(1, port.commands.count { it is ProductCommand.Add })
        val p2 = program(); val missing = Port().apply { throwBasket = true }
        assertEquals(ProductRunResult.BLOCKED, runner.run(p2, missing, StopSignal()))
        missing.throwBasket = false
        assertEquals(ProductRunResult.BASKET_VERIFIED, runner.run(p2, missing, StopSignal()))
        assertEquals(1, missing.commands.count { it is ProductCommand.Add })
    }
    @Test fun wrongRiceAddressOrDuplicateQuantityNeverVerifiesBasket() = runBlocking {
        val alternatives = listOf(before.copy(items = listOf(wanted.copy(quantity = 2))),
            before.copy(items = listOf(wanted.copy(options = listOf(CartOption("إضافات", "رز مندي"))))),
            before.copy(items = listOf(wanted), addressReference = "another-address"))
        for (result in alternatives) {
            val port = Port().apply { this.result = result }
            assertEquals(ProductRunResult.BASKET_MISMATCH, GuardedProductRunner(clock = { 2000 }).run(program(), port, StopSignal()))
        }
    }
}
