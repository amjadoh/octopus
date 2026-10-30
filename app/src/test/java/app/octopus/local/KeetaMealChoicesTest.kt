package app.octopus.local

import app.octopus.local.domain.*
import app.octopus.local.providers.*
import org.junit.Assert.*
import org.junit.Test

class KeetaMealChoicesTest {
    @Test fun configuredSelectionToVerifiedReviewKeepsOptionsAndTotalBoundToSameRequest() {
        val source = BasketEvidence(ProviderId.HUNGERSTATION, cart.restaurant, listOf(
            BasketLineEvidence(reader.title, cart.items.single().options.map { BasketChoiceEvidence(it.value, null) }, 1, 2000)), 2000, true)
        val groups = cart.items.single().options.mapIndexed { i, option ->
            MenuChoiceGroup("group$i", option.group, 1, 1, listOf(MenuChoice("choice$i", option.value, true)))
        }
        val schema = ConfiguredProduct(cart.restaurant, MenuProduct("meal", reader.title, null, false, 2000, groups),
            CatalogProvenance(ProviderId.HUNGERSTATION, HungerStationCatalog.TESTED_VERSION, 1000, "synthetic"), true, null)
        val meal = app.octopus.local.automation.SingleChoiceMeal.create(source, schema, 1100)!!
        assertEquals(cart, meal.cart)
        val selection = KeetaMealChoices(meal.cart)
        val done = mutableSetOf<String>()
        repeat(2) {
            val productFrame = frame(done, nestedRadio=true)
            val step = selection.next(productFrame, reader) as KeetaMealChoices.Step.Select
            assertTrue(selection.consume(step, productFrame, reader))
            done += step.value
        }
        selection.next(frame(done), reader)
        selection.endObserved()
        assertEquals(KeetaMealChoices.Step.Ready, selection.next(frame(done), reader))
        val nodes = mutableListOf<CatalogNode>()
        fun n(p: Int, text: String = "", cls: String = "android.view.ViewGroup", click: Boolean = false): Int {
            val i=nodes.size; nodes += CatalogNode(i,p,"",text,cls,click,false,false); return i
        }
        val root=n(-1); n(root,cart.restaurant,"android.widget.TextView")
        val pane=n(root); val header=n(pane)
        n(header,"الأصناف المضافة","android.widget.TextView"); n(header,"تفريغ السلة","android.widget.TextView")
        val scroll=n(pane,cls="android.widget.ScrollView"); val content=n(scroll); val section=n(content); val row=n(section,click=true)
        n(row,reader.title,"android.widget.TextView")
        n(row,"وجبة تجريبية، جانب أ، مشروب ب","android.widget.TextView")
        n(row,"20.00 ﷼","android.widget.TextView"); n(row,"1","android.widget.TextView",true)
        val footer=n(root,click=true); val action=n(footer,click=true); val label=n(footer)
        n(label,"إتمام الدفع","android.widget.TextView")
        val drawer=CatalogFrame(nodes,true)
        val proof=app.octopus.local.safety.ConfiguredBasketVerification.target(meal,selection,reader,drawer,1200)!!
        assertTrue(proof.raw.lines.single().choices.all { it.quantity == null })
        assertTrue(proof.resolved.lines.single().choices.all { it.quantity == 1 })
        val record=PreparedBasketRecord.create(meal.cart,proof.resolved,reader.VERSION,1200)!!
        val permit=app.octopus.local.safety.PreparedReviewAdmission.create(ProviderId.KEETA,meal.cart,record,drawer,reader.VERSION,1250,proof)!!
        val target=app.octopus.local.safety.ReviewNavigationGuard.target(ProviderId.KEETA,drawer,meal.cart,reader.VERSION,permit,1300)!!
        assertEquals(action,target.index)
        assertTrue(permit.consume(drawer,target,ProviderId.KEETA,meal.cart,reader.VERSION,1300))
        assertFalse(permit.consume(drawer,target,ProviderId.KEETA,meal.cart,reader.VERSION,1301))
        val quote=app.octopus.local.pricing.QuoteExtractor().extract(SafeSnapshot(listOf(
            SafeToken(PriceField.SUBTOTAL,2000,10,30,0,200,"subtotal"),
            SafeToken(PriceField.ORDER_TOTAL,2550,40,60,0,200,"total")),2,false))
        val total=FinalTotalReading(ProviderId.KEETA,reader.VERSION,meal.cart.fingerprint,quote.fields,1400)
        assertEquals(2550L,total.total)
        assertTrue(total.fresh(meal.cart,1500))
        assertFalse(total.fresh(meal.cart.copy(items=listOf(meal.cart.items.single().copy(quantity=2))),1500))
    }
    @Test fun headerVisibleBeforeRowsDoesNotRejectIncompleteViewport() {
        val full = frame()
        val rows = full.nodes.filter { it.label == "جانب أ" || it.label == "مشروب ب" }.map { it.parent }.toSet()
        val hidden = rows.flatMap { full.descendants(it) }.map { it.index }.toSet()
        val partial = full.copy(nodes = full.nodes.filter { it.index !in hidden })
        val session = KeetaMealChoices(cart)
        assertEquals(KeetaMealChoices.Step.Scroll, session.next(partial, reader))
        assertTrue(session.next(full, reader) is KeetaMealChoices.Step.Select)
        assertNull(session.addTarget(partial, reader))
        val badgeOnly = partial.copy(nodes = partial.nodes.filter { it.label != "اختر 1" })
        val clippedSession = KeetaMealChoices(cart)
        assertEquals(KeetaMealChoices.Step.Scroll, clippedSession.next(badgeOnly, reader))
        clippedSession.endObserved()
        assertFalse(clippedSession.ready)
        assertTrue(clippedSession.next(full, reader) is KeetaMealChoices.Step.Select)
    }
    private val cart = CanonicalCart("مطعم تجريبي", null, listOf(CanonicalItem("وجبة تجريبية", null,
        listOf(CartOption("الجانب", "جانب أ"), CartOption("المشروب", "مشروب ب")), 1)))
    private val reader = KeetaMenuReader(cart.restaurant, cart.items.single().name, "3.13.301", setOf("جانب أ", "مشروب ب"))
    private fun frame(done: Set<String> = emptySet(), quantity: String = "1", unknownGroup: Boolean = false, nestedRadio: Boolean = false): CatalogFrame {
        val nodes = mutableListOf<CatalogNode>()
        fun node(parent: Int, label: String = "", click: Boolean = false, cls: String = "android.view.ViewGroup", scroll: Boolean = false): Int {
            val i = nodes.size
            nodes += CatalogNode(i,parent,"",label,cls,click,false,scroll)
            return i
        }
        val root = node(-1)
        node(root, cart.restaurant, cls="android.widget.TextView")
        val modal = node(root)
        val box = node(modal, cls="android.widget.FrameLayout")
        val scroll = node(box, cls="android.widget.ScrollView", scroll=true)
        node(scroll, cart.items.single().name, cls="android.widget.TextView")
        for (value in listOf("جانب أ", "مشروب ب") + if (unknownGroup) listOf("خيار مجهول") else emptyList()) {
            val group = node(scroll)
            val header = node(group)
            node(header, "مجموعة $value", cls="android.widget.TextView")
            node(header, "مطلوب", cls="android.widget.TextView")
            node(header, if (value in done) "تم" else "اختر 1", cls="android.widget.TextView")
            val rows = node(group)
            val row = node(rows, click=true)
            if (nestedRadio) {
                val nameHandler = node(row, click=true)
                val labelBox = node(nameHandler)
                node(labelBox, value, cls="android.widget.TextView")
                node(nameHandler, click=true) // radio; nameHandler opens modifiers
            } else node(row, value, cls="android.widget.TextView")
        }
        node(modal) // close slot
        val footer = node(modal)
        val action = node(node(node(footer)))
        node(action, click=true)
        val label = node(action)
        node(label, "إضافة إلى السلة", cls="android.widget.TextView")
        node(label, "20.00 ﷼", cls="android.widget.TextView")
        node(footer, quantity, cls="android.widget.TextView")
        return CatalogFrame(nodes,true)
    }
    @Test fun selectsEachExactOptionOnceAndRequiresAcknowledgementAndEndBeforeAdd() {
        val session = KeetaMealChoices(cart)
        var f = frame()
        assertTrue(reader.product(f))
        assertNull(session.addTarget(f,reader))
        val first = session.next(f,reader) as KeetaMealChoices.Step.Select
        assertTrue(session.consume(first,f,reader))
        assertFalse(session.consume(first,f,reader))
        f = frame(setOf(first.value))
        val second = session.next(f,reader) as KeetaMealChoices.Step.Select
        assertNotEquals(first.value,second.value)
        assertTrue(session.consume(second,f,reader))
        f = frame(setOf(first.value,second.value))
        assertEquals(KeetaMealChoices.Step.Scroll,session.next(f,reader))
        session.endObserved()
        assertEquals(KeetaMealChoices.Step.Ready,session.next(f,reader))
        assertNotNull(session.addTarget(f,reader))
        assertNull(session.addTarget(frame(setOf(first.value,second.value),quantity="2"),reader))
    }
    @Test fun nestedChoiceSelectsRadioInsteadOfOpeningModifierDetails() {
        val f = frame(nestedRadio=true)
        val step = KeetaMealChoices(cart).next(f, reader) as KeetaMealChoices.Step.Select
        assertTrue(step.target.clickable)
        assertTrue(f.descendants(step.target.index).all { it.label.isBlank() })
        val sibling = f.nodes.single { it.parent == step.target.parent && it.index != step.target.index }
        assertTrue(f.descendants(sibling.index).any { it.label == step.value })
    }
    @Test fun failedAcknowledgementUnknownRequiredGroupAndIncompleteFrameCannotAdd() {
        val session=KeetaMealChoices(cart)
        val f=frame()
        val step=session.next(f,reader) as KeetaMealChoices.Step.Select
        assertTrue(session.consume(step,f,reader))
        assertEquals(KeetaMealChoices.Step.Blocked,session.next(f,reader))
        session.endObserved()
        assertFalse(session.ready)
        assertNull(session.addTarget(f,reader))
        assertEquals(KeetaMealChoices.Step.Blocked,KeetaMealChoices(cart).next(frame(unknownGroup=true),reader))
        assertEquals(KeetaMealChoices.Step.Blocked,KeetaMealChoices(cart).next(f.copy(complete=false),reader))
        // A preselected group saying Done does not prove a maximum of one.
        assertEquals(KeetaMealChoices.Step.Blocked,KeetaMealChoices(cart).next(frame(setOf("جانب أ", "مشروب ب")),reader))
    }
    @Test fun choiceLabelsAreExactAndUnknownOrDuplicateSummaryTextIsNotRetained() {
        assertEquals("جانب أ",reader.functionalLabel("جانب أ\uFEFF "))
        assertNotNull(reader.configuredChoices("جانب أ، مشروب ب"))
        assertNull(reader.configuredChoices("جانب أ، مشروب آخر"))
        assertNull(reader.configuredChoices("جانب أ، جانب أ"))
        assertNotNull(reader.configuredChoices("وجبة تجريبية، جانب أ، مشروب ب"))
        assertNull(reader.configuredChoices("وجبة مختلفة، جانب أ، مشروب ب"))
        assertNull(reader.configuredChoices("وجبة تجريبية، وجبة تجريبية، جانب أ، مشروب ب"))
    }

    @Test fun followsPartlyVisibleGroupAndScopesScrollingToProductSheet() {
        val full = frame()
        val hiddenChoice = full.copy(nodes = full.nodes.map {
            if (it.label == "مشروب ب") it.copy(label = "", omittedText = true) else it
        })
        val session = KeetaMealChoices(cart)
        val first = session.next(hiddenChoice, reader) as KeetaMealChoices.Step.Select
        assertEquals("جانب أ", first.value)
        assertTrue(session.consume(first, hiddenChoice, reader))
        val acknowledged = frame(setOf(first.value)).let { f -> f.copy(nodes = f.nodes.map {
            if (it.label == "مشروب ب") it.copy(label = "", omittedText = true) else it
        }) }
        assertEquals(KeetaMealChoices.Step.Scroll, session.next(acknowledged, reader))
        assertEquals("مشروب ب", (session.next(frame(setOf(first.value)), reader) as KeetaMealChoices.Step.Select).value)
        val backgroundScroll = CatalogNode(full.nodes.size, 0, "", "", "android.widget.ScrollView", false, false, true)
        val twoScrolls = full.copy(nodes = full.nodes + backgroundScroll)
        val body = reader.configuredProductBody(twoScrolls)
        assertNotNull(body)
        assertNotEquals(backgroundScroll.index, body!!.index)
    }

    @Test fun restoresOnlyTheHeadingInsideEachRequiredGroup() {
        val original = frame()
        val candidates = original.nodes.filter { it.label.startsWith("مجموعة ") }.associate { it.index to it.label }
        val redacted = original.copy(nodes = original.nodes.map {
            if (it.index in candidates) it.copy(label = "", omittedText = true) else it
        })
        assertEquals(original, KeetaMealChoices.restoreHeaders(redacted, reader, candidates))
    }
}
