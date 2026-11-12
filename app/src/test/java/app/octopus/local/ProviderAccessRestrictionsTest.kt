package app.octopus.local

import app.octopus.local.safety.*
import org.junit.Assert.*
import org.junit.Test

class ProviderAccessRestrictionsTest {
    @Test fun nullAndEmptyInputReturnNull() {
        assertNull(ProviderAccessRestrictions.detect(null))
        assertNull(ProviderAccessRestrictions.detect(""))
    }

    @Test fun blankAndUnrelatedInputReturnNull() {
        listOf(" \t\r\n ", "Orders", "Please try again", "maximum number of attempts").forEach { label ->
            assertNull(ProviderAccessRestrictions.detect(label))
        }
    }

    @Test fun acceptsNonStringCharSequences() {
        assertEquals(ProviderAccessRestriction.ACCESS_ERROR,
            ProviderAccessRestrictions.detect(StringBuilder("Access error")))
    }

    @Test fun lengthLimitIsCheckedBeforeNormalization() {
        val tooLong = "Access error".padEnd(513, ' ')
        assertEquals(513, tooLong.length)
        assertNull(ProviderAccessRestrictions.detect(tooLong))
    }

    @Test fun lengthLimitOf512IsInclusive() {
        val atLimit = " ".repeat(500) + "access error"
        assertEquals(512, atLimit.length)
        assertEquals(ProviderAccessRestriction.ACCESS_ERROR, ProviderAccessRestrictions.detect(atLimit))
    }

    @Test fun accessErrorIgnoresCaseAndCollapsesWhitespace() {
        assertEquals(ProviderAccessRestriction.ACCESS_ERROR, ProviderAccessRestrictions.detect("ACCESS ERROR"))
        assertEquals(ProviderAccessRestriction.ACCESS_ERROR, ProviderAccessRestrictions.detect("  Access\t\n error  "))
    }

    @Test fun accessErrorRequiresExactMatch() {
        assertNull(ProviderAccessRestrictions.detect("Access error."))
        assertNull(ProviderAccessRestrictions.detect("Access error occurred"))
    }

    @Test fun directionMarksAreRemovedBeforeMatching() {
        val lrm = Char(0x200E)
        val alm = Char(0x061C)
        val lre = Char(0x202A)
        val rlo = Char(0x202E)
        val lri = Char(0x2066)
        val pdi = Char(0x2069)
        assertEquals(ProviderAccessRestriction.ACCESS_ERROR, ProviderAccessRestrictions.detect("Access${lrm} error"))
        assertEquals(ProviderAccessRestriction.ACCESS_ERROR, ProviderAccessRestrictions.detect("${alm}Acc${lre}ess error"))
        assertEquals(ProviderAccessRestriction.ACCESS_ERROR, ProviderAccessRestrictions.detect("${lri}Access${rlo} error${pdi}"))
        assertEquals(ProviderAccessRestriction.UNUSUAL_ACTIVITY, ProviderAccessRestrictions.detect(
            "${rlo}${lri}Unusual activity detected. Sign-up is restricted.${pdi}"))
    }

    @Test fun allSupportedDirectionMarksAreRemoved() {
        val marks = listOf('\u200e', '\u200f', '\u061c') +
            ('\u202a'..'\u202e').toList() + ('\u2066'..'\u2069').toList()
        marks.forEach { mark ->
            assertEquals(ProviderAccessRestriction.ACCESS_ERROR,
                ProviderAccessRestrictions.detect("${mark}Acc${mark}ess error${mark}"))
        }
    }

    @Test fun unusualActivityRequiresRestrictionPhrase() {
        assertNull(ProviderAccessRestrictions.detect("Unusual activity detected."))
        assertNull(ProviderAccessRestrictions.detect("Unusual activity detected. Log-in restricted"))
        assertEquals(ProviderAccessRestriction.UNUSUAL_ACTIVITY, ProviderAccessRestrictions.detect(
            " \tUnusual activity\ndetected. Log-in\tis restricted. Contact support. "))
    }

    @Test fun matchingUsesPrefixesAndContainsWithoutExtraWordBoundaries() {
        assertEquals(ProviderAccessRestriction.UNUSUAL_ACTIVITY, ProviderAccessRestrictions.detect(
            "Unusual activity detected.this is restrictedness"))
        assertEquals(ProviderAccessRestriction.TOO_MANY_ATTEMPTS, ProviderAccessRestrictions.detect(
            "You've reached our maximum number of attempts.Access error"))
        assertEquals(ProviderAccessRestriction.UNUSUAL_ACTIVITY, ProviderAccessRestrictions.detect(
            "Unusual activity detected. You've reached our maximum number of attempts. Access is restricted"))
    }

    @Test fun unusualActivityIsCaseInsensitive() {
        assertEquals(ProviderAccessRestriction.UNUSUAL_ACTIVITY, ProviderAccessRestrictions.detect(
            "UNUSUAL ACTIVITY DETECTED. LOG-IN IS RESTRICTED"))
    }

    @Test fun unusualActivityMustBeAtStartWithPeriod() {
        assertNull(ProviderAccessRestrictions.detect("Notice: Unusual activity detected. Log-in is restricted"))
        assertNull(ProviderAccessRestrictions.detect("Unusual activity detected Log-in is restricted"))
    }

    @Test fun tooManyAttemptsAcceptsBothApostrophesAndAnyCase() {
        assertEquals(ProviderAccessRestriction.TOO_MANY_ATTEMPTS, ProviderAccessRestrictions.detect(
            "You’ve reached our maximum number of attempts. Please wait."))
        assertEquals(ProviderAccessRestriction.TOO_MANY_ATTEMPTS, ProviderAccessRestrictions.detect(
            "You've reached our maximum number of attempts."))
        assertEquals(ProviderAccessRestriction.TOO_MANY_ATTEMPTS, ProviderAccessRestrictions.detect(
            "YOU'VE REACHED OUR MAXIMUM NUMBER OF ATTEMPTS. TRY LATER"))
    }

    @Test fun tooManyAttemptsMustBeAtStartWithPeriod() {
        assertNull(ProviderAccessRestrictions.detect("Error: You've reached our maximum number of attempts."))
        assertNull(ProviderAccessRestrictions.detect("You've reached our maximum number of attempts"))
    }

    @Test fun enumEntriesHaveExpectedOrderAndMessages() {
        assertEquals(
            listOf(
                ProviderAccessRestriction.UNUSUAL_ACTIVITY,
                ProviderAccessRestriction.TOO_MANY_ATTEMPTS,
                ProviderAccessRestriction.ACCESS_ERROR,
            ),
            ProviderAccessRestriction.entries.toList(),
        )
        val messages = ProviderAccessRestriction.entries.map { it.userMessage }
        messages.forEach { message ->
            assertTrue(message.isNotBlank())
            assertTrue(message.contains("أوقفت العملية"))
        }
        assertEquals(messages.size, messages.toSet().size)
    }
}
