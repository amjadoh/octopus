package app.octopus.local

import app.octopus.local.domain.*
import org.junit.Assert.*
import org.junit.Test

class ProviderCompatibilityTest {
    @Test fun laterPatchRunsOnTheVerifiedProfile() {
        assertEquals("8.0.303", ProviderCompatibility.effective(ProviderId.HUNGERSTATION, "8.0.304"))
        assertEquals("8.0.303", ProviderCompatibility.effective(ProviderId.HUNGERSTATION, "8.0.303"))
        assertEquals("3.13.301", ProviderCompatibility.effective(ProviderId.KEETA, "3.13.305"))
        assertEquals("3.13.203", ProviderCompatibility.effective(ProviderId.KEETA, "3.13.250"))
    }
    @Test fun otherLinesOlderPatchesAndOddVersionsStayAsInstalled() {
        assertEquals("8.1.0", ProviderCompatibility.effective(ProviderId.HUNGERSTATION, "8.1.0"))
        assertEquals("8.0.302", ProviderCompatibility.effective(ProviderId.HUNGERSTATION, "8.0.302"))
        assertEquals("9.0.304", ProviderCompatibility.effective(ProviderId.HUNGERSTATION, "9.0.304"))
        assertEquals("3.14.1", ProviderCompatibility.effective(ProviderId.KEETA, "3.14.1"))
        assertEquals("8.0.304-beta", ProviderCompatibility.effective(ProviderId.HUNGERSTATION, "8.0.304-beta"))
        assertNull(ProviderCompatibility.effective(ProviderId.KEETA, null))
    }
}
