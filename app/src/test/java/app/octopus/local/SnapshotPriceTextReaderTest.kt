package app.octopus.local

import app.octopus.local.domain.*
import app.octopus.local.pricing.*
import org.junit.Assert.*
import org.junit.Test

class SnapshotPriceTextReaderTest {
    private val hunger = ProviderId.HUNGERSTATION.packageName
    private fun extract(vararg texts: String, provider: String? = hunger): QuoteCandidate {
        val tokens = texts.flatMap { text -> SnapshotPriceTextReader.read(text, provider).map {
            SafeToken(it.field, it.amount, 10, 30, 0, 200, "row")
        } }
        return QuoteExtractor().extract(SafeSnapshot(tokens, texts.size, false))
    }

    @Test fun savedHungerFeesStayPartial() {
        // Exact public pricing-only values from hs-review-grouped-pricing.json.
        val quote = extract("رسوم التوصيل\n§\n20.00\nرسوم الخدمة\n§\n1.90")
        assertEquals(2000L, quote.fields[PriceField.DELIVERY])
        assertEquals(190L, quote.fields[PriceField.SERVICE])
        assertNull(quote.total)
    }

    @Test fun observedGlyphSupportsBothSpatialAmountAndMergedLabel() {
        assertEquals(listOf(SnapshotPriceTextReader.Value(null, 4290L)), SnapshotPriceTextReader.read("§ 42.90", hunger))
        assertEquals(listOf(SnapshotPriceTextReader.Value(PriceField.ORDER_TOTAL, 4290L)),
            SnapshotPriceTextReader.read("الإجمالي § ٤٢٫٩٠", hunger))
        assertEquals(4290L, extract("الإجمالي § 42.90").total)
    }

    @Test fun glyphExceptionIsNotAddedToGenericMoneyOrOtherProviders() {
        assertNull(MoneyParser.parse("§ 42.90"))
        for (provider in listOf(null, ProviderId.KEETA.packageName, "another.app")) {
            assertTrue(SnapshotPriceTextReader.read("§ 42.90", provider).isEmpty())
            assertTrue(SnapshotPriceTextReader.read("الإجمالي § 42.90", provider).isEmpty())
        }
    }

    @Test fun existingSupportedCurrenciesAndDigitsStillUseSameProductionReader() {
        assertEquals(4290L, extract("الإجمالي ٤٢٫٩٠ ر.س").total)
        assertEquals(4290L, extract("Order total 42.90 SAR", provider = ProviderId.KEETA.packageName).total)
        assertEquals(4290L, extract("الإجمالي ۴۲٫۹۰ ﷼", provider = ProviderId.KEETA.packageName).total)
    }

    @Test fun noSubtotalWalletOrUnlabelledAmountBecomesFinalTotal() {
        assertNull(extract("المجموع\n§ 21.00").total)
        assertNull(extract("رصيد المحفظة المستخدم § 20.00", "المتبقي للدفع § 22.90").total)
        assertNull(extract("§ 42.90").total)
        assertNull(extract("الإجمالي غير متاح").total)
        assertNull(extract("الإجمالي § 42.90 أو § 43.90").total)
        assertNull(extract("الإجمالي § 42.90", "الإجمالي § 43.90").total)
        assertEquals(0L, extract("الإجمالي § 0.00").total)
    }
}
