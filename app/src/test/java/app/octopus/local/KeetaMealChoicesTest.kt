package app.octopus.local

import app.octopus.local.domain.*
import app.octopus.local.providers.*
import org.junit.Assert.*
import org.junit.Test

class KeetaMealChoicesTest {
    private val cart = CanonicalCart("مطعم تجريبي", null, listOf(CanonicalItem("وجبة تجريبية", null,
        listOf(CartOption("الجانب", "جانب أ"), CartOption("المشروب", "مشروب ب")), 1)))
    private val reader = KeetaMenuReader(cart.restaurant, cart.items.single().name, "3.13.301", setOf("جانب أ", "مشروب ب"))
    private fun frame(done: Set<String> = emptySet(), quantity: String = "1", unknownGroup: Boolean = false): CatalogFrame {
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
            node(row, value, cls="android.widget.TextView")
        }
        node(modal) // close slot
        val footer = node(modal)
        val action = node(footer)
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
