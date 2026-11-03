package app.octopus.local

import app.octopus.local.domain.*
import app.octopus.local.pricing.QuoteExtractor
import org.junit.Assert.*
import org.junit.Test

/** HungerStation 8.0.304 review on the user's phone: free H+ delivery shows "0.00" beside a struck-through "19.00". */
class QuoteStrikethroughTest {
    private fun label(field: PriceField, top: Int) = SafeToken(label = field, top = top, bottom = top + 60, left = 600, right = 1000, parent = "r$top")
    private fun amount(halala: Long, top: Int, left: Int) = SafeToken(amount = halala, top = top, bottom = top + 60, left = left, right = left + 200, parent = "r$top")

    private val review = listOf(
        label(PriceField.SUBTOTAL, 500), amount(4600, 500, 40),
        label(PriceField.DELIVERY, 640), amount(0, 640, 40), amount(1900, 640, 260),
        label(PriceField.SERVICE, 780), amount(490, 780, 40),
        label(PriceField.ORDER_TOTAL, 1300), amount(5320, 1300, 40),
    )

    @Test fun struckThroughFeeLeavesTheSingleOrderTotalReadable() {
        val quote = QuoteExtractor().extract(SafeSnapshot(review, review.size, false))
        assertEquals(5320L, quote.total)
        assertEquals(4600L, quote.fields[PriceField.SUBTOTAL])
        assertNull(quote.fields[PriceField.DELIVERY])
    }

    @Test fun twoAmountsOnTheOrderTotalStillBlock() {
        val crossedTotal = review + amount(7220, 1300, 260)
        assertNull(QuoteExtractor().extract(SafeSnapshot(crossedTotal, crossedTotal.size, false)).total)
    }
}
