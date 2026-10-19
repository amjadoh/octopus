package app.octopus.local

import app.octopus.local.domain.*
import app.octopus.local.pricing.QuoteExtractor
import app.octopus.local.safety.*
import org.junit.Assert.*
import org.junit.Test

class ProviderAccessRestrictionTest {
    @Test fun keetaRestrictionSurvivesLineWrappingAndDirectionMarks() {
        assertEquals(ProviderAccessRestriction.UNUSUAL_ACTIVITY, ProviderAccessRestrictions.detect(
            "\u200fUnusual activity detected. Log-in or\nsign-up is restricted."))
    }
    @Test fun hungerStationRestrictionDoesNotRetainReferenceCode() {
        val error = ProviderAccessRestrictions.detect("You've reached our maximum number of attempts. Please wait and try again later.To report, use the reference code: private-test-reference")
        assertEquals(ProviderAccessRestriction.TOO_MANY_ATTEMPTS, error)
        assertFalse(error!!.userMessage.contains("private-test-reference"))
    }
    @Test fun accessErrorHasNoAssumedSecurityCauseOrRetryTime() {
        assertEquals(ProviderAccessRestriction.ACCESS_ERROR, ProviderAccessRestrictions.detect("Access error"))
        assertNull(ProviderAccessRestrictions.detect("Please try again"))
        assertNull(ProviderAccessRestrictions.detect("Orders"))
        assertNull(ProviderAccessRestrictions.detect("maximum number of extras"))
        assertNull(ProviderAccessRestrictions.detect("Unusual activity detected. Our restaurant has a new menu"))
    }
    @Test fun restrictionCannotBecomeAQuoteEvenWithValidTotalTokens() {
        ProviderAccessRestriction.entries.forEach { reason ->
            val snapshot = SafeSnapshot(listOf(SafeToken(PriceField.ORDER_TOTAL, 3600, 0, 40)), 8, false, accessRestriction = reason)
            val quote = QuoteExtractor().extract(snapshot)
            assertTrue(quote.ambiguous)
            assertTrue(quote.fields.isEmpty())
            assertNull(quote.total)
        }
    }
    @Test fun ordinaryPriceReadingStillWorks() {
        val snapshot = SafeSnapshot(listOf(SafeToken(PriceField.ORDER_TOTAL, 3600, 0, 40)), 8, false)
        assertEquals(3600L, QuoteExtractor().extract(snapshot).total)
    }
}
