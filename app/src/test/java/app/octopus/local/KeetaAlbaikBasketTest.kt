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
}
