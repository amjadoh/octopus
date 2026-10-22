package app.octopus.local
import app.octopus.local.domain.*
import app.octopus.local.providers.*
import org.junit.Assert.*
import org.junit.Test
class FinalTotalReadingTest {
    private val cart = CanonicalCart("مطعم",null,listOf(CanonicalItem("وجبة",null,emptyList(),1)))
    @Test fun totalsExpireAndCannotFollowAChangedCart() {
        val read = FinalTotalReading(ProviderId.KEETA,"3.13.203",cart.fingerprint,mapOf(PriceField.ORDER_TOTAL to 1234L),1000)
        assertTrue(read.fresh(cart,2000)); assertFalse(read.fresh(cart,999)); assertFalse(read.fresh(cart,121001))
        assertFalse(read.fresh(cart.copy(restaurant="آخر"),2000))
        assertFalse(read.fields.containsKey(PriceField.DELIVERY))
    }
    @Test fun subtotalIsNeverPromotedToAFinalTotal() {
        try { FinalTotalReading(ProviderId.KEETA,"3.13.203",cart.fingerprint,mapOf(PriceField.SUBTOTAL to 1234L),1000); fail() }
        catch (_: IllegalArgumentException) { }
    }
}
