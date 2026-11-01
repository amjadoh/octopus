package app.octopus.local

import app.octopus.local.automation.*
import app.octopus.local.domain.*
import app.octopus.local.providers.*
import app.octopus.local.safety.*
import org.junit.Assert.*
import org.junit.Test

class MultipleChoiceMealsTest {
    private val restaurant = "مطعم تجريبي"
    private val first = "وجبة أولى"
    private val second = "وجبة ثانية"
    private val firstValues = listOf("جانب أ", "مشروب ب")
    private val secondValues = listOf("جانب ج", "مشروب د")

    private fun schema(title: String, values: List<String>, at: Long = 1000) = ConfiguredProduct(restaurant,
        MenuProduct(title, title, null, false, 2000, values.mapIndexed { i, value ->
            MenuChoiceGroup("$title-$i", "مجموعة $i", 1, 1, listOf(MenuChoice("$title-c$i", value, true), MenuChoice("$title-o$i", "$value آخر", true)))
        }), CatalogProvenance(ProviderId.HUNGERSTATION, HungerStationCatalog.TESTED_VERSION, at, "synthetic"), true, null)

    private fun line(name: String, values: List<String>, amount: Long, quantity: Int = 1) =
        BasketLineEvidence(name, values.map { BasketChoiceEvidence(it, null) }, quantity, amount)

    private val source = BasketEvidence(ProviderId.HUNGERSTATION, restaurant,
        listOf(line(first, firstValues, 2000), line(second, secondValues, 2500)), 4500, true)
    private val schemas = mapOf(first to schema(first, firstValues), second to schema(second, secondValues))

    @Test fun twoMealsResolveFromTheirOwnSchemasInAnyTargetOrder() {
        val meal = SingleChoiceMeal.create(source, schemas, 1500)!!
        assertEquals(listOf(first, second), meal.cart.items.map { it.name })
        assertEquals(firstValues, meal.cart.items[0].options.map { it.value })
        assertEquals(secondValues, meal.cart.items[1].options.map { it.value })
        assertEquals(mapOf(first to firstValues.toSet(), second to secondValues.toSet()), meal.valuesByTitle)
        val target = source.copy(provider = ProviderId.KEETA, lines = source.lines.reversed())
        assertNotNull(meal.resolve(target, 2000))
        // Choices swapped between the two meals, a missing meal, a doubled meal or a wrong subtotal never match.
        val swapped = target.copy(lines = listOf(line(second, firstValues, 2500), line(first, secondValues, 2000)))
        assertNull(meal.resolve(swapped, 2000))
        assertNull(meal.resolve(target.copy(lines = target.lines.take(1), subtotal = 2500), 2000))
        assertNull(meal.resolve(target.copy(lines = target.lines.map { it.copy(quantity = 2) }), 2000))
        assertNull(meal.resolve(target.copy(subtotal = 4400), 2000))
        // The prefix proves the cart after the first add, before the second meal is opened.
        val added = meal.prefix(1)
        assertEquals(listOf(first), added.cart.items.map { it.name })
        assertNotNull(added.resolve(target.copy(lines = listOf(source.lines[0]), subtotal = 2000), 2000))
        assertNull(added.resolve(target, 2000))
        assertSame(meal, meal.prefix(2))
    }

    @Test fun unsupportedMultiMealCartsAreRejected() {
        assertNull(SingleChoiceMeal.create(source, schemas - second, 1500))
        assertNull(SingleChoiceMeal.create(source, schema(first, firstValues), 1500)) // single-schema entry needs one row
        assertNull(SingleChoiceMeal.create(source.copy(subtotal = 4000), schemas, 1500))
        assertNull(SingleChoiceMeal.create(source.copy(lines = listOf(source.lines[0], source.lines[1].copy(quantity = 2))), schemas, 1500))
        val plain = source.copy(lines = listOf(source.lines[0], BasketLineEvidence(second, emptyList(), 1, 2500)))
        assertNull(SingleChoiceMeal.create(plain, schemas, 1500))
        val sameName = source.copy(lines = listOf(source.lines[0], source.lines[0]), subtotal = 4000)
        assertNull(SingleChoiceMeal.create(sameName, mapOf(first to schema(first, firstValues)), 1500))
        val five = (1..5).map { "وجبة $it" }
        val many = BasketEvidence(ProviderId.HUNGERSTATION, restaurant, five.map { line(it, firstValues, 1000) }, 5000, true)
        assertNull(SingleChoiceMeal.create(many, five.associateWith { schema(it, firstValues) }, 1500))
        // Each meal is read in turn from the unchanged cart; two meals get two 30 s windows, not more.
        assertNotNull(SingleChoiceMeal.create(source, schemas, 60_999))
        assertNull(SingleChoiceMeal.create(source, schemas, 61_001))
    }

    @Test fun hungerStationOpensOnlyTheNamedMealRow() {
        fun node(i: Int, p: Int, id: String, label: String, click: Boolean = false) =
            CatalogNode(i, p, id, label, "android.view.View", click, false, false)
        val frame = CatalogFrame(listOf(
            node(0, -1, "FlutterCart_title", "السلة\n$restaurant"),
            node(1, -1, "FlutterCart_scroll_view", "المجموع\n45\n§"),
            node(2, 1, "FlutterCart_cartItem", "$first\n${firstValues.joinToString("، ")}\nتعديل\n20\n§", true),
            node(3, 2, "menuItemActionDualButtonValue", "1"),
            node(4, 1, "FlutterCart_cartItem", "$second\n${secondValues.joinToString("، ")}\nتعديل\n25\n§", true),
            node(5, 4, "menuItemActionDualButtonValue", "1")), true)
        assertEquals(source, BasketEvidenceReader.hungerStation(frame, true))
        assertNull(HungerStationCartProductCommand.create(frame, source, 2000))
        assertEquals(2, HungerStationCartProductCommand.create(frame, source, first, 2000)!!.target.index)
        assertEquals(4, HungerStationCartProductCommand.create(frame, source, second, 2000)!!.target.index)
        assertNull(HungerStationCartProductCommand.create(frame, source, "وجبة غير موجودة", 2000))
        val meal = SingleChoiceMeal.create(source, schemas, 1500)!!
        assertNotNull(ConfiguredBasketVerification.source(meal, frame, source, 2000))
    }

    private fun reader(title: String) = KeetaMenuReader(restaurant, title, "3.13.301",
        (firstValues + secondValues).toSet(), setOf(first, second) - title)

    /** Same observed 3.13.301 product-sheet shape as [KeetaMealChoicesTest]. */
    private fun product(title: String, values: List<String>, done: Set<String>): CatalogFrame {
        val nodes = mutableListOf<CatalogNode>()
        fun node(parent: Int, label: String = "", click: Boolean = false, cls: String = "android.view.ViewGroup", scroll: Boolean = false): Int {
            val i = nodes.size
            nodes += CatalogNode(i, parent, "", label, cls, click, false, scroll)
            return i
        }
        val root = node(-1)
        node(root, restaurant, cls = "android.widget.TextView")
        val modal = node(root)
        val box = node(modal, cls = "android.widget.FrameLayout")
        val scroll = node(box, cls = "android.widget.ScrollView", scroll = true)
        node(scroll, title, cls = "android.widget.TextView")
        for (value in values) {
            val group = node(scroll)
            val header = node(group)
            node(header, "مجموعة $value", cls = "android.widget.TextView")
            node(header, "مطلوب", cls = "android.widget.TextView")
            node(header, if (value in done) "تم" else "اختر 1", cls = "android.widget.TextView")
            node(node(node(group), click = true), value, cls = "android.widget.TextView")
        }
        node(modal)
        val footer = node(modal)
        val action = node(node(node(footer)))
        node(action, click = true)
        val label = node(action)
        node(label, "إضافة إلى السلة", cls = "android.widget.TextView")
        node(label, "20.00 ﷼", cls = "android.widget.TextView")
        node(footer, "1", cls = "android.widget.TextView")
        return CatalogFrame(nodes, true)
    }

    private fun readySelection(cart: CanonicalCart, item: CanonicalItem, values: List<String>): KeetaMealChoices {
        val selection = KeetaMealChoices(cart, item)
        val menu = reader(item.name)
        val done = mutableSetOf<String>()
        repeat(values.size) {
            val f = product(item.name, values, done)
            val step = selection.next(f, menu) as KeetaMealChoices.Step.Select
            assertTrue(selection.consume(step, f, menu))
            done += step.value
        }
        selection.next(product(item.name, values, done), menu)
        selection.endObserved()
        assertEquals(KeetaMealChoices.Step.Ready, selection.next(product(item.name, values, done), menu))
        return selection
    }

    private fun drawer(rows: List<Triple<String, List<String>, String>>): Pair<CatalogFrame, Int> {
        val nodes = mutableListOf<CatalogNode>()
        fun n(p: Int, text: String = "", cls: String = "android.view.ViewGroup", click: Boolean = false): Int {
            val i = nodes.size; nodes += CatalogNode(i, p, "", text, cls, click, false, false); return i
        }
        val root = n(-1); n(root, restaurant, "android.widget.TextView")
        val pane = n(root); val header = n(pane)
        n(header, "الأصناف المضافة", "android.widget.TextView"); n(header, "تفريغ السلة", "android.widget.TextView")
        val scroll = n(pane, cls = "android.widget.ScrollView"); val content = n(scroll)
        for ((name, values, price) in rows) {
            val row = n(n(content), click = true)
            n(row, name, "android.widget.TextView")
            n(row, (listOf(name) + values).joinToString("، "), "android.widget.TextView")
            n(row, price, "android.widget.TextView"); n(row, "1", "android.widget.TextView", true)
        }
        val footer = n(root, click = true); val action = n(footer, click = true); val label = n(footer)
        n(label, "إتمام الدفع", "android.widget.TextView")
        return CatalogFrame(nodes, true) to action
    }

    @Test fun keetaCartIsProvenAfterEachAddAndReviewsOnlyTheFullOrder() {
        val meal = SingleChoiceMeal.create(source, schemas, 1500)!!
        val cart = meal.cart
        val selections = listOf(readySelection(cart, cart.items[0], firstValues), readySelection(cart, cart.items[1], secondValues))
        val (afterFirst, _) = drawer(listOf(Triple(first, firstValues, "20.00 ﷼")))
        val (full, action) = drawer(listOf(Triple(first, firstValues, "20.00 ﷼"), Triple(second, secondValues, "25.00 ﷼")))

        // After the first add only the first meal may be in the cart.
        val added = meal.prefix(1)
        val step = ConfiguredBasketVerification.target(added, selections.take(1), "3.13.301", afterFirst, 2000)!!
        assertEquals(listOf(first), step.resolved.lines.map { it.name })
        assertNull(ConfiguredBasketVerification.target(added, selections.take(1), "3.13.301", full, 2000))
        assertNull(ConfiguredBasketVerification.target(meal, selections.take(1), "3.13.301", full, 2000))
        assertNull(ConfiguredBasketVerification.target(meal, selections.reversed(), "3.13.301", full, 2000))
        assertNull(ConfiguredBasketVerification.target(meal, selections, "future", full, 2000))
        val swapped = drawer(listOf(Triple(first, secondValues, "20.00 ﷼"), Triple(second, firstValues, "25.00 ﷼"))).first
        assertNull(ConfiguredBasketVerification.target(meal, selections, "3.13.301", swapped, 2000))

        // The journal clears the pending first add only from that proof, so the second add may be dispatched.
        val store = object : BasketJournalStore {
            var values = emptyMap<String, String>()
            override fun read() = values
            override fun write(values: Map<String, String>) { this.values = values }
        }
        val journal = BasketMutationJournal(store)
        journal.requireFirstAdd(ProviderId.KEETA, cart)
        journal.beforeDispatch(ProviderId.KEETA, cart)
        assertTrue(runCatching { journal.beforeDispatch(ProviderId.KEETA, cart) }.isFailure)
        assertTrue(journal.observeConfigured(added.cart, step, afterFirst, "3.13.301", 2000))
        journal.beforeDispatch(ProviderId.KEETA, cart)

        // Leaving the partial drawer stays bound to the full order and its one-meal footer count.
        val partialEvidence = KeetaBasketReader.configured(cart, "3.13.301").evidence(afterFirst)!!
        val back = KeetaOwnedDrawerReturn.createConfigured(afterFirst, partialEvidence, added, "3.13.301", 2050, cart)!!
        assertFalse(back.consumeBack(afterFirst, added.cart, "3.13.301", 2060))
        val back2 = KeetaOwnedDrawerReturn.createConfigured(afterFirst, partialEvidence, added, "3.13.301", 2050, cart)!!
        assertTrue(back2.consumeBack(afterFirst, cart, "3.13.301", 2060))
        assertNull(KeetaOwnedDrawerReturn.createConfigured(afterFirst, partialEvidence, meal.prefix(1), "3.13.301", 2050,
            cart.copy(items = cart.items.reversed())))

        val proof = ConfiguredBasketVerification.target(meal, selections, "3.13.301", full, 3000)!!
        assertTrue(journal.observeConfigured(cart, proof, full, "3.13.301", 3000))
        assertEquals(listOf(listOf(1, 1), listOf(1, 1)), proof.resolved.lines.map { l -> l.choices.map { it.quantity } })
        val record = PreparedBasketRecord.create(cart, proof.resolved, "3.13.301", 3000)!!
        assertNull(PreparedReviewAdmission.create(ProviderId.KEETA, cart, record, afterFirst, "3.13.301", 3000, proof))
        val permit = PreparedReviewAdmission.create(ProviderId.KEETA, cart, record, full, "3.13.301", 3000, proof)!!
        val target = ReviewNavigationGuard.target(ProviderId.KEETA, full, cart, "3.13.301", permit, 3000)!!
        assertEquals(action, target.index)
        assertNull(ReviewNavigationGuard.target(ProviderId.KEETA, full, added.cart, "3.13.301", permit, 3000))
        assertTrue(permit.consume(full, target, ProviderId.KEETA, cart, "3.13.301", 3000))
        assertFalse(permit.consume(full, target, ProviderId.KEETA, cart, "3.13.301", 3000))
    }
}
