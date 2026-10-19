package app.octopus.local

import app.octopus.local.providers.*
import org.junit.Assert.*
import org.junit.Test

class KeetaRidanBasketTest {
    private fun fixture(quantity: Int = 3, unknown: Boolean = false, scrollable: Boolean = false): CatalogFrame {
        val nodes = mutableListOf<CatalogNode>()
        fun n(parent: Int, label: String = "", cls: String = "android.view.ViewGroup", click: Boolean = false, scroll: Boolean = false, omitted: Boolean = false): Int {
            val index = nodes.size; nodes += CatalogNode(index, parent, "", label, cls, click, false, scroll, omittedText = omitted); return index
        }
        val root = n(-1); n(root, "ريدان", "android.widget.TextView")
        n(root, "إتمام الدفع", "android.widget.TextView")
        val pane = n(root); val header = n(pane)
        n(header, "الأصناف المضافة", "android.widget.TextView"); n(header, "تفريغ السلة", "android.widget.TextView")
        val scroll = n(pane, cls = "android.widget.ScrollView", scroll = scrollable)
        val content = n(scroll); val rows = n(content); val row = n(rows, click = true)
        n(row, "نصف دجاج مضغوط", "android.widget.TextView")
        n(row, "${quantity * 30}.00 ﷼", "android.widget.TextView")
        if (unknown) n(row, omitted = true)
        val controls = n(row)
        val minus = n(controls); n(minus, click = true); n(minus, cls = "android.widget.ImageView")
        n(controls, "$quantity", "android.widget.TextView", click = true)
        val plus = n(controls); n(plus, click = true); n(plus, cls = "android.widget.ImageView")
        return CatalogFrame(nodes, true)
    }
    @Test fun readsCompleteFixedMealAndOnlyItsQuantityControls() {
        val f = fixture()
        val evidence = KeetaRidanBasket.evidence(f)!!
        assertTrue(evidence.complete); assertEquals(3, evidence.lines.single().quantity)
        assertEquals(9000L, evidence.subtotal)
        val plus = KeetaRidanBasket.quantityTarget(f, true)!!
        val minus = KeetaRidanBasket.quantityTarget(f, false)!!
        assertNotEquals(plus.index, minus.index)
        assertTrue(listOf(plus, minus).all { it.clickable && it.label.isEmpty() })
        assertFalse(evidence.recognizedOrderReview)
    }
    @Test fun rejectsUnknownOptionsAndIncompleteRows() {
        assertNull(KeetaRidanBasket.evidence(fixture(unknown = true)))
        assertNull(KeetaRidanBasket.evidence(fixture(scrollable = true)))
        val f = fixture(); assertNull(KeetaRidanBasket.evidence(f.copy(complete = false)))
        val title = f.nodes.single { it.label == "نصف دجاج مضغوط" }
        assertNull(KeetaRidanBasket.evidence(f.copy(nodes = f.nodes + title.copy(index = 100))))
    }
    @Test fun neverSelectsRemoveLastItemOrExceedsQuantityLimit() {
        assertNull(KeetaRidanBasket.quantityTarget(fixture(1), false))
        assertNull(KeetaRidanBasket.quantityTarget(fixture(99), true))
    }
}
