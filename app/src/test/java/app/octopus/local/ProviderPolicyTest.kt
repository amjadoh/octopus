package app.octopus.local

import app.octopus.local.domain.ProviderId
import app.octopus.local.providers.IntegrationStatus
import app.octopus.local.providers.ProviderPolicies
import app.octopus.local.safety.SafetyGuard
import org.junit.Assert.*
import org.junit.Test

class ProviderPolicyTest {
    @Test fun localReadNeedsUserConsentAndTermsStayDocumented() {
        val guard = SafetyGuard()
        assertTrue(guard.allowsRead(ProviderId.KEETA, ProviderId.KEETA.packageName, true, true, true))
        assertFalse(guard.allowsRead(ProviderId.KEETA, ProviderId.KEETA.packageName, true, false, true))
        val policy = ProviderPolicies.forProvider(ProviderId.KEETA)
        assertEquals(IntegrationStatus.TERMS_RESTRICTION_DOCUMENTED, policy.status)
        assertTrue(policy.permitsDiagnosticRead)
        assertTrue(policy.reason.contains("4.1.6"))
        assertEquals("https://www.mykeeta.com/contents/user.html?locale=en&region=SA", policy.source)
    }

    @Test fun hungerStationIsOnlyUnverifiedReadSupport() {
        val policy = ProviderPolicies.forProvider(ProviderId.HUNGERSTATION)
        assertEquals(IntegrationStatus.READ_ONLY_UNVERIFIED, policy.status)
        assertTrue(policy.permitsDiagnosticRead)
        assertTrue(SafetyGuard().allowsRead(ProviderId.HUNGERSTATION, ProviderId.HUNGERSTATION.packageName, true, true, true))
        assertFalse(SafetyGuard().allowsRead(ProviderId.HUNGERSTATION, ProviderId.KEETA.packageName, true, true, true))
    }
}
