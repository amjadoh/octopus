package app.octopus.local

import app.octopus.local.automation.HungerStationBasketControls
import app.octopus.local.providers.BasketEvidenceReader
import app.octopus.local.providers.CatalogFrame
import app.octopus.local.providers.CatalogNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HungerStationDiscountedCartLineTest {
    private fun cart(line: String) = CatalogFrame(listOf(
        CatalogNode(0, -1, "FlutterCart_title", "السلة\nبوبايز", "android.view.View", false, false, false),
        CatalogNode(1, -1, "FlutterCart_cartItem", line, "android.widget.ImageView", true, false, false),
        CatalogNode(2, 1, "menuItemActionDualButtonValue", "1", "android.view.View", false, false, false)), true)
    private val options = "ساندويتش ديلوكس\n1x بطاطا, بيبسي, حجم, ساندوتش تشيكن ديلوكس, لا تقم بإجراء أي تغيير\nتعديل\n"

    @Test fun discountedLineKeepsTheCurrentPrice() {
        val read = BasketEvidenceReader.hungerStation(cart(options + "31.50\n§\n42\n§"), true)
        assertTrue(read.complete)
        assertEquals(3150L, read.lines.single().lineAmount)
        assertEquals(5, read.lines.single().choices.size)
    }

    @Test fun aSecondAmountThatIsNotHigherIsRejected() {
        assertFalse(BasketEvidenceReader.hungerStation(cart(options + "31.50\n§\n20\n§"), true).complete)
        assertFalse(BasketEvidenceReader.hungerStation(cart(options + "31.50\n§\n31.50\n§"), true).complete)
    }

    @Test fun cartBarTakesTheLowerCurrentAmountAfterTheStruckOne() {
        assertEquals(3150L, HungerStationBasketControls.footerAmount(listOf("عرض السلة", "42", "§", "31.50", "§")))
        assertEquals(3600L, HungerStationBasketControls.footerAmount(listOf("عرض السلة", "36", "§")))
        assertEquals(null, HungerStationBasketControls.footerAmount(listOf("عرض السلة", "31.50", "§", "42", "§")))
        assertEquals(null, HungerStationBasketControls.footerAmount(listOf("السلة", "36", "§")))
    }
}
