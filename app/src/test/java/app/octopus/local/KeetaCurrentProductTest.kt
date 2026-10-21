package app.octopus.local

import app.octopus.local.providers.*
import app.octopus.local.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

/** Actual Honor 3.13.203 read-only capture, with only whitelisted food/control labels. */
class KeetaCurrentProductTest {
    private val reader = KeetaMenuReader("مدور", "فطيرة جبن فيتا", "3.13.203")
    private fun recorded(): CatalogFrame {
        val stream = checkNotNull(javaClass.getResourceAsStream("/recorded-keeta/keeta-3.13.203-feta-product.tsv"))
        val nodes = stream.bufferedReader(Charsets.UTF_8).use { input -> input.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map {
            val p = it.split('\t')
            CatalogNode(p[0].toInt(), p[1].toInt(), p[2], String(Base64.getDecoder().decode(p[9]), Charsets.UTF_8),
                p[3], p[4] == "1", p[5] == "1", p[6] == "1", p[7] == "1", p[8] == "1")
        } }
        return CatalogFrame(nodes, true)
    }
    @Test fun recordedCurrentProductHasAnUnambiguousUnitPriceAndBoundedCloseAndAddTargets() {
        val frame = recorded()
        assertTrue(reader.product(frame))
        assertFalse(reader.menu(frame))
        assertEquals(700L, reader.unitPrice(frame))
        assertNotNull(reader.closeProduct(frame))
        val add = KeetaBasketReader(reader).addTarget(frame, 700)
        assertNotNull(add)
        assertNotEquals(reader.closeProduct(frame), add)
        assertNull(KeetaBasketReader(reader).addTarget(frame, 800))
    }
    @Test fun nativeMenuCardCanBeOpenedReadOnlyWithoutInventingItsHiddenDeliveryContext() {
        val frame = recorded()
        val modal = reader.productBody(frame)!!.let { body ->
            val wrapper = frame.nodes.single { it.index == body.parent }
            frame.nodes.single { it.index == wrapper.parent }
        }
        val omitted = frame.descendants(modal.index).map { it.index }.toSet()
        val menu = frame.copy(nodes = frame.nodes.filterNot { it.index in omitted })
        assertTrue(reader.menu(menu))
        assertNotNull(reader.productTarget(menu))
    }
    @Test fun extraChoiceContentOrProtectedIncompleteFramesAreRejected() {
        val frame = recorded()
        assertFalse(reader.product(frame.copy(complete = false)))
        val body = reader.productBody(frame)!!
        val changed = frame.copy(nodes = frame.nodes + CatalogNode(1000, body.index, "", "اختيار إلزامي", "android.widget.TextView", false, false, false))
        assertFalse(reader.product(changed))
        assertFalse(KeetaMenuReader("مطعم آخر", reader.title, reader.VERSION).product(frame))
    }
    @Test fun currentRecordedProductProducesOnlyItsBasePriceWithoutInventingDeliveryOrDiscounts() {
        val cart = CanonicalCart(reader.restaurant, null, listOf(CanonicalItem(reader.title, null, emptyList(), 2)), null)
        val preview = reader.itemPreview(recorded(), cart, 1000)!!
        assertEquals(1400L, preview.itemSubtotal)
        assertNull(preview.finalTotal)
        assertNull(preview.deliveryBeforeOffers)
        assertTrue(preview.rules.isEmpty())
        assertTrue(preview.offers.isEmpty())
        assertEquals(cart.fingerprint, preview.cartFingerprint)
        assertNull(reader.itemPreview(recorded(), cart.copy(restaurant = "مطعم آخر"), 1000))
        assertNull(reader.itemPreview(recorded().copy(complete = false), cart, 1000))
    }
}
