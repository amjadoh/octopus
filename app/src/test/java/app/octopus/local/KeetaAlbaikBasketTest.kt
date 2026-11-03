package app.octopus.local

import app.octopus.local.providers.*
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class KeetaAlbaikBasketTest {
    private val titles = setOf("بيج بيك", "برجر فيليه دجاج حرّاق")
    private fun recorded(): CatalogFrame = Gson().fromJson(javaClass.getResource(
        "/recorded-keeta/keeta-3.13.301-albaik-two-cart-redacted.json")!!.readText(), CatalogFrame::class.java)
    private fun reader() = KeetaBasketReader(KeetaMenuReader("البيك", "بيج بيك", "3.13.301"), titles)
    @Test fun twoRecordedSkuRowsRetainNamesQuantitiesAndAmounts() {
        val actual = checkNotNull(reader().evidence(recorded()))
        assertEquals(titles, actual.lines.map { it.name }.toSet())
        assertTrue(actual.lines.all { it.quantity == 1 && it.choices.isEmpty() })
        assertEquals(2450L, actual.subtotal)
    }
    @Test fun omittedSecondSkuNameIsRestoredOnlyInsideItsOwnObservedRow() {
        val original = recorded()
        val candidates = original.nodes.filter { it.label == "برجر فيليه دجاج حرّاق" }.associate { it.index to it.label }
        val hidden = original.copy(nodes = original.nodes.map { if(it.index in candidates) it.copy(label="",omittedText=true) else it })
        val restored = reader().restoreRequestedRowTitles(hidden,candidates)
        assertEquals(reader().evidence(original), reader().evidence(restored))
    }
    @Test fun incompleteOrUnknownChoiceStillRejectsTheWholeBasket() {
        val original=recorded()
        assertNull(reader().evidence(original.copy(complete=false)))
        val variant=original.nodes.last { it.label=="برجر فيليه دجاج حرّاق" }
        assertNull(reader().evidence(original.copy(nodes=original.nodes.map {
            if(it.index==variant.index)it.copy(label="",omittedText=true) else it
        })))
    }

    /** S22 (3.13.301): a cart over 30 ﷼ closes the drawer list with a delivery-savings section. */
    private fun withSavings(saved: String = "\u200f2\u00a0﷼\u200f", discount: String = "-\u200f2\u00a0﷼\u200f",
        clickableNote: Boolean = false): CatalogFrame {
        val f = recorded()
        val scroll = f.nodes.single { it.className == "android.widget.ScrollView" && f.descendants(it.index).any { n -> n.label in titles } }
        val content = f.nodes.single { it.parent == scroll.index }
        var next = f.nodes.maxOf { it.index } + 1
        val added = mutableListOf<CatalogNode>()
        fun node(parent: Int, label: String = "", cls: String = "android.view.ViewGroup", clickable: Boolean = false) =
            CatalogNode(next++, parent, "", label, cls, clickable, false, false).also { added += it }.index
        val section = node(content.index); node(section)
        val inner = node(node(section))
        val total = node(inner); node(node(total), "إجمالي التوفير", "android.widget.TextView"); node(node(total), saved, "android.widget.TextView")
        val row = node(node(inner)); node(node(row, clickable = true), "خصم التوصيل", "android.widget.TextView")
        node(node(row), discount, "android.widget.TextView")
        node(inner); node(inner, "يتم تأكيد المبلغ النهائي عند صفحة إتمام الدفع", "android.widget.TextView", clickableNote)
        return f.copy(nodes = f.nodes + added)
    }
    @Test fun deliverySavingsSectionUnderTheRowsIsNotAnItem() {
        assertEquals(reader().evidence(recorded()), reader().evidence(withSavings()))
    }
    @Test fun changedOrMismatchedSavingsSectionStillRejectsTheBasket() {
        assertNull(reader().evidence(withSavings(discount = "-\u200f3\u00a0﷼\u200f")))
        assertNull(reader().evidence(withSavings(clickableNote = true)))
        val extra = withSavings().let { f -> f.copy(nodes = f.nodes.map { if (it.label == "إجمالي التوفير") it.copy(label = "", omittedText = true) else it }) }
        assertNull(reader().evidence(extra))
    }
}
