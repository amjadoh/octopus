package app.octopus.local

import app.octopus.local.providers.*
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

/** Keeta 3.13.301 shows a single-SKU meal with an empty special-request field (recorded on the user's phone). */
class KeetaSingleSkuNotesTest {
    private val title = "وجبة دجاج مسحب – ١٠ قطع"
    private val actual = Gson().fromJson(javaClass.getResource("/recorded-keeta/keeta-3.13.301-albaik-single-sku-notes-redacted.json")!!.readText(),
        CatalogFrame::class.java)
    private val reader = KeetaMenuReader("البيك", title, "3.13.301")
    private fun changed(change: (CatalogNode) -> CatalogNode?) = actual.copy(nodes = actual.nodes.mapNotNull(change))
    private val editor = actual.nodes.single { it.editable }

    @Test fun soleSkuWithEmptyNotesCanBeAddedOnce() {
        assertNotNull(KeetaSingleSkuProduct.body(actual, "البيك", title))
        assertTrue(reader.product(actual))
        assertNotNull(reader.productBody(actual))
        assertEquals(2200L, reader.unitPrice(actual))
        assertNotNull(KeetaBasketReader(reader).addTarget(actual, 2200))
        assertNull(KeetaBasketReader(reader).addTarget(actual, 2300))
    }

    @Test fun typedNoteOrChangedNotesSectionIsRejected() {
        val typed = changed { if (it.index == editor.index) it.copy(label = "بدون صوص", enteredText = true) else it }
        assertNull(reader.productBody(typed))
        assertNull(KeetaBasketReader(reader).addTarget(typed, 2200))
        val extraLabel = changed { if (it.label == "0/70") it.copy(label = "3/70") else it }
        assertNull(KeetaSingleSkuProduct.body(extraLabel, "البيك", title))
        val second = editor.copy(index = 5000)
        assertNull(KeetaSingleSkuProduct.body(actual.copy(nodes = actual.nodes + second), "البيك", title))
    }

    @Test fun secondSkuOrPriceMismatchIsRejected() {
        // Recorded indices: 205 is the sole SKU row label and 209 its price.
        val renamed = changed { if (it.index == 205) it.copy(label = "وجبة دجاج مسحب – ٦ قطع") else it }
        assertNull(KeetaSingleSkuProduct.body(renamed, "البيك", title))
        val dearer = changed { if (it.index == 209) it.copy(label = "‏25.00 ﷼‏") else it }
        assertNull(KeetaSingleSkuProduct.body(dearer, "البيك", title))
        assertNull(KeetaSingleSkuProduct.body(actual.copy(complete = false), "البيك", title))
        assertNull(KeetaSingleSkuProduct.body(actual, "البيك", "وجبة دجاج البيك – ٤ قطع"))
    }
}
