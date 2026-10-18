package app.octopus.local

import app.octopus.local.domain.*
import app.octopus.local.pricing.*
import app.octopus.local.safety.*
import org.junit.Assert.*
import org.junit.Test

class MoneyParserTest {
    @Test fun arabicAndPersianDigits() {
        assertEquals(12345L, MoneyParser.parse("١٢٣٫٤٥ ر.س"))
        assertEquals(12345L, MoneyParser.parse("۱۲۳٫۴۵ SAR"))
    }
    @Test fun decimalAndThousands() {
        assertEquals(123456L, MoneyParser.parse("1,234.56 SAR"))
        assertEquals(1250L, MoneyParser.parse("12,50"))
        assertEquals(123400L, MoneyParser.parse("١٬٢٣٤"))
    }
    @Test fun unknownIsNotZero() {
        assertNull(MoneyParser.parse("غير متاح"))
        assertNull(MoneyParser.parse(""))
        assertNull(MoneyParser.parse("12.345"))
        assertNull(MoneyParser.parse("50%"))
        assertNull(MoneyParser.parse("0501234567"))
        assertNull(MoneyParser.parse("50 60"))
        assertNull(MoneyParser.parse("1,23,4"))
    }
    @Test fun knownZeroAndNegativeDiscountArePreserved() {
        assertEquals(0L, MoneyParser.parse("0.00"))
        assertEquals(-1050L, MoneyParser.parse("−١٠٫٥٠"))
    }
}

class QuoteExtractorTest {
    private fun node(field: PriceField, amount: Long) = SafeToken(field, amount, 10, 30, 0, 200, "row")
    @Test fun doesNotInventMissingFields() {
        val result = QuoteExtractor().extract(SafeSnapshot(listOf(node(PriceField.ORDER_TOTAL, 6000)), 1, false))
        assertEquals(6000L, result.total)
        assertNull(result.fields[PriceField.TAX])
    }
    @Test fun walletAndAmountDueAreNotOrderCost() {
        val result = QuoteExtractor().extract(SafeSnapshot(listOf(node(PriceField.WALLET, 2000), node(PriceField.AMOUNT_DUE, 4000)), 2, false))
        assertNull(result.total)
        assertEquals(4000L, result.fields[PriceField.AMOUNT_DUE])
    }
    @Test fun conflictingTotalsAndSensitiveScreensFailClosed() {
        assertNull(QuoteExtractor().extract(SafeSnapshot(listOf(node(PriceField.ORDER_TOTAL, 4000), node(PriceField.ORDER_TOTAL, 5000)), 2, false)).total)
        assertNull(QuoteExtractor().extract(SafeSnapshot(listOf(node(PriceField.ORDER_TOTAL, 4000)), 1, true)).total)
        assertNull(QuoteExtractor().extract(SafeSnapshot(listOf(node(PriceField.ORDER_TOTAL, 4000)), 1, false, truncated = true)).total)
    }
    @Test fun spatialRowsRequireUniqueAmount() {
        val label = SafeToken(label = PriceField.ORDER_TOTAL, top = 10, bottom = 30, left = 200, right = 300, parent = "row")
        val amount = SafeToken(amount = 5000, top = 10, bottom = 30, left = 10, right = 100, parent = "row")
        assertEquals(5000L, QuoteExtractor().extract(SafeSnapshot(listOf(label, amount), 2, false)).total)
        assertNull(QuoteExtractor().extract(SafeSnapshot(listOf(label, amount, amount.copy(amount = 6000)), 3, false)).total)
    }
}

class SafetyGuardTest {
    @Test fun allActionsDeniedInDiagnosticBuild() {
        ProposedAction.entries.forEach { assertFalse(SafetyGuard().allowsAction(it)) }
    }
    @Test fun readsRequireEveryPermissionAndExactForegroundPackage() {
        val provider = ProviderId.KEETA
        val guard = SafetyGuard()
        assertTrue(guard.allowsRead(provider, provider.packageName, true, true, true))
        assertFalse(guard.allowsRead(provider, provider.packageName, false, true, true))
        assertFalse(guard.allowsRead(provider, provider.packageName, true, false, true))
        assertFalse(guard.allowsRead(provider, provider.packageName, true, true, false))
        assertFalse(guard.allowsRead(provider, ProviderId.HUNGERSTATION.packageName, true, true, true))
        assertFalse(guard.allowsRead(provider, null, true, true, true))
    }
    @Test fun unstablePriceResetsObservationWindow() {
        val gate = StabilityGate(1000)
        assertFalse(gate.observe("50", 0))
        assertFalse(gate.observe("50", 900))
        assertFalse(gate.observe("51", 1000))
        assertFalse(gate.observe("51", 1900))
        assertTrue(gate.observe("51", 2000))
        gate.reset()
        assertFalse(gate.observe("51", 3000))
    }
}

class PriceComparisonTest {
    private fun quote(provider: ProviderId, total: Long?, at: Long = 1000) = VerifiedQuote(provider, total,
        walletApplied = null, amountDueNow = null, cartFingerprint = "same-cart", addressReference = "same-address",
        cartVerified = true, addressVerified = true, branchVerified = true, allMandatoryFeesIncluded = true, capturedAtMillis = at)
    @Test fun incompleteQuotesAreExcludedInsteadOfBecomingZero() {
        val result = PriceComparisonEngine().rank(listOf(quote(ProviderId.KEETA, null), quote(ProviderId.HUNGERSTATION, 6000)), 2000)
        assertEquals(1, result.size)
        assertEquals(ProviderId.HUNGERSTATION, result.single().provider)
    }
    @Test fun rankUsesOrderCostNotWalletAmountDue() {
        val costly = quote(ProviderId.KEETA, 6000).copy(walletApplied = 5000, amountDueNow = 1000)
        val cheaper = quote(ProviderId.HUNGERSTATION, 5000).copy(amountDueNow = 5000)
        assertEquals(ProviderId.HUNGERSTATION, PriceComparisonEngine().rank(listOf(costly, cheaper), 2000).first().provider)
    }
    @Test fun mismatchedContextAndUnverifiedQuotesAreExcluded() {
        val base = quote(ProviderId.KEETA, 6000)
        assertTrue(PriceComparisonEngine().rank(listOf(base, quote(ProviderId.HUNGERSTATION, 5000).copy(addressReference = "other")), 2000).isEmpty())
        assertTrue(PriceComparisonEngine().rank(listOf(base.copy(cartVerified = false)), 2000).isEmpty())
        assertTrue(PriceComparisonEngine().rank(listOf(base.copy(branchVerified = false)), 2000).isEmpty())
        assertTrue(PriceComparisonEngine().rank(listOf(base.copy(allMandatoryFeesIncluded = false)), 2000).isEmpty())
    }
    @Test fun staleAndFutureReadingsAreExcluded() {
        assertTrue(PriceComparisonEngine(1000).rank(listOf(quote(ProviderId.KEETA, 4000, 0)), 2000).isEmpty())
        assertTrue(PriceComparisonEngine().rank(listOf(quote(ProviderId.KEETA, 4000, 3000)), 2000).isEmpty())
    }
}
