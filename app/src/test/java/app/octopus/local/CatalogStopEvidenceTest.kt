package app.octopus.local

import app.octopus.local.diagnostics.CatalogOperation
import app.octopus.local.diagnostics.CatalogStopEvidence
import app.octopus.local.diagnostics.CatalogStopReason
import app.octopus.local.diagnostics.CatalogStopState
import app.octopus.local.diagnostics.CatalogStoppedException
import app.octopus.local.domain.ProviderId
import app.octopus.local.providers.CatalogScreen
import org.junit.Assert.*
import org.junit.Test

class CatalogStopEvidenceTest {
    private fun evidence(
        id: String? = "com.hungerstation.android.web:id/payment_method",
        className: String? = "android.widget.Button",
        provider: ProviderId = ProviderId.HUNGERSTATION,
        providerVersion: String? = "8.0.303",
        appVersion: String = "1.3.2-diagnostic",
        fingerprint: String? = null,
        prior: CatalogScreen? = CatalogScreen.MENU,
        reason: CatalogStopReason = CatalogStopReason.FINANCIAL_ELEMENT,
        operation: CatalogOperation = CatalogOperation.BROWSE,
        password: Boolean = false,
        dataSensitive: Boolean = false,
    ) = CatalogStopEvidence.create(provider, providerVersion, 1_700_000_000_000, prior, reason, id,
        className, clickable = true, editable = false, password, dataSensitive, appVersion,
        appVersionCode = 10, operation, fingerprint)

    @Test fun knownFinancialControlStructureSurvivesWithoutReadingItsContent() {
        listOf("com.hungerstation.android.web:id/payment_method", "checkoutButton", "FlutterCart_checkoutButton",
            "android:id/button").forEach { id -> assertEquals(id, evidence(id = id).resourceId) }
        val keeta = evidence(id = "com.sankuai.sailor.afooddelivery:id/payment_method", provider = ProviderId.KEETA)
        assertNotNull(keeta.resourceId)
        assertNull(evidence(id = "com.sankuai.sailor.afooddelivery:id/payment_method").resourceId)
    }

    @Test fun identifiersThatCouldCarryPersonalOrDynamicValuesAreOmittedRatherThanTrimmed() {
        listOf("payment_person@example.com", "payment_0501234567", "payment_1234", "payment value",
            "checkout\nButton", "checkout\u200fButton", "customer: id/payment", "com.example:id/payment",
            "com.hungerstation.android.web:id/payment_4111111111111111",
            "com.hungerstation.android.web:id/checkout/person@example.com", "payment_" + "a".repeat(180),
            "", " checkoutButton").forEach { assertNull(it, evidence(id = it).resourceId) }
    }

    @Test fun classAndVersionMetadataCannotSmuggleArbitraryScreenValues() {
        listOf("person@example.com", "my.app.CustomerName", "android.widget.Button 0501234567",
            "android.widget.Phone0501234567", "android.widget." + "A".repeat(160))
            .forEach { assertNull(it, evidence(className = it).className) }
        listOf("android.widget.EditText", "android.view.View", "androidx.compose.ui.platform.ComposeView")
            .forEach { assertEquals(it, evidence(className = it).className) }
        listOf("0501234567", "8.0.303 person@example.com", "8.0.303\n", "8.0.1234567890", "8.0-customer_0501234567")
            .forEach {
                assertNull(evidence(providerVersion = it).providerVersion)
                assertNull(evidence(appVersion = it).appVersion)
            }
        assertEquals("1.3.2-diagnostic", evidence().appVersion)
        assertEquals("8.0.303", evidence().providerVersion)
    }

    @Test fun stopContextKeepsPriorScreenSeparateAndDoesNotInferScreenFromFinancialElement() {
        val blocked = evidence(prior = CatalogScreen.MENU,
            reason = CatalogStopReason.FINANCIAL_CONTROL_ON_UNKNOWN_SCREEN, operation = CatalogOperation.BUILD_CART)
        assertEquals(CatalogScreen.MENU, blocked.priorRecognizedScreen)
        assertEquals(CatalogStopReason.FINANCIAL_CONTROL_ON_UNKNOWN_SCREEN, blocked.reason)
        assertEquals(CatalogOperation.BUILD_CART, blocked.operation)
        assertNull(evidence(prior = CatalogScreen.UNKNOWN).priorRecognizedScreen)
        assertNull(evidence(prior = null).priorRecognizedScreen)
    }

    @Test fun sensitiveNodeFlagsRemainAvailableEvenWhenIdentifiersAreOmitted() {
        val blocked = evidence(id = "card_4111111111111111", password = true, dataSensitive = true)
        assertNull(blocked.resourceId)
        assertTrue(blocked.password)
        assertTrue(blocked.dataSensitive)
        assertEquals(1, blocked.schemaVersion)
        assertEquals(ProviderId.HUNGERSTATION, blocked.provider)
    }

    @Test fun requestLinkAcceptsOnlyOpaqueSha256AndNeverPlainRequestOrAddress() {
        val hash = "ABCDEF0123456789".repeat(4)
        assertEquals(hash.lowercase(), evidence(fingerprint = hash).requestFingerprint)
        listOf("restaurant / item / address", "0501234567", "a".repeat(63), "a".repeat(65), "g".repeat(64), " $hash")
            .forEach { assertNull(it, evidence(fingerprint = it).requestFingerprint) }
    }

    @Test fun caughtStopCannotBeFollowedByFallbackActionsAndDoesNotRequireStableFrame() {
        val state = CatalogStopState()
        val first = evidence(prior = null, id = "payment_0501234567")
        try { state.stop(first) } catch (_: Exception) { /* Simulates an existing broad fallback catch. */ }
        var fallbackExecuted = false
        try {
            state.throwIfStopped()
            fallbackExecuted = true
        } catch (stopped: CatalogStoppedException) {
            assertSame(first, stopped.evidence)
            assertFalse(stopped.message!!.contains("0501234567"))
        }
        assertFalse(fallbackExecuted)
        assertSame(first, state.evidence)
    }

    @Test fun laterStopCannotOverwriteFirstCauseAndOnlyNewSessionMayProceed() {
        val state = CatalogStopState()
        val first = evidence(id = "payment_method", prior = CatalogScreen.MENU)
        try { state.stop(first) } catch (_: CatalogStoppedException) { }
        val second = evidence(id = "checkoutButton", reason = CatalogStopReason.FINANCIAL_CONTROL_ON_UNKNOWN_SCREEN)
        try {
            state.stop(second)
        } catch (stopped: CatalogStoppedException) {
            assertSame(first, stopped.evidence)
            assertEquals(CatalogStopReason.FINANCIAL_ELEMENT, stopped.evidence.reason)
        }
        val newSession = CatalogStopState()
        newSession.throwIfStopped()
        assertNull(newSession.evidence)
        assertSame(first, state.evidence)
    }
}
