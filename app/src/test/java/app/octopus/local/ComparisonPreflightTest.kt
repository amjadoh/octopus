package app.octopus.local

import app.octopus.local.automation.ComparisonPreflight
import app.octopus.local.domain.*
import org.junit.Assert.*
import org.junit.Test

class ComparisonPreflightTest {
    private val one = CanonicalCart("مطعم", null, listOf(CanonicalItem("فطيرة", null, emptyList(), 3)))
    private val two = one.copy(items = one.items + CanonicalItem("ماء", null, emptyList(), 1))
    private val installed = listOf(ProviderInstallation(ProviderId.HUNGERSTATION, true, "8.0.303"),
        ProviderInstallation(ProviderId.KEETA, true, "3.13.203"))
    private fun failure(cart: CanonicalCart = one, rows: List<ProviderInstallation> = installed,
        allowed: Set<ProviderId> = ProviderId.entries.toSet(), consent: Boolean = true) =
        ComparisonPreflight.failure(cart, consent, allowed, rows)

    @Test fun bothKnownAdaptersAdmitPlainOneAndTwoRowRequests() {
        assertNull(failure())
        assertNull(failure(two))
    }
    @Test fun unsupportedSecondAdapterBlocksBeforeFirstProviderCanStart() {
        for (version in listOf(null, "", "3.13.204")) {
            val error = failure(rows = installed.map { if (it.provider == ProviderId.KEETA) it.copy(version = version) else it })!!
            assertTrue(error.contains("كيتا"))
            assertTrue(error.contains("لم تبدأ المقارنة"))
        }
    }
    @Test fun legacyKeetaAdmitsOneRowButNeverRoutesTwoRowsIntoSingleExecutor() {
        val legacy = installed.map { if (it.provider == ProviderId.KEETA) it.copy(version = "3.12.501") else it }
        assertNull(failure(rows = legacy))
        assertNotNull(failure(two, legacy))
    }
    @Test fun unknownMissingOrAmbiguousInstallationNeverStartsAFullComparison() {
        assertNotNull(failure(rows = installed.dropLast(1)))
        assertNotNull(failure(rows = installed + installed.last()))
        assertNotNull(failure(rows = installed.map { it.copy(installed = false) }))
        assertNotNull(failure(rows = installed.map { if (it.provider == ProviderId.HUNGERSTATION) it.copy(version = "8.0.304") else it }))
    }
    @Test fun consentAndBothProviderPermissionsAreRequiredBeforeMovement() {
        assertNotNull(failure(consent = false))
        for (id in ProviderId.entries) assertTrue(failure(allowed = setOf(id))!!.contains(ProviderId.entries.first { it != id }.title))
    }
    @Test fun unsupportedOptionsAndThirdLineKeepFullRequestWithoutAdmission() {
        assertNotNull(failure(one.copy(items = one.items.map { it.copy(size = "كبير") })))
        assertNotNull(failure(one.copy(items = one.items.map { it.copy(note = "بدون ملح") })))
        assertNotNull(failure(one.copy(items = one.items.map { it.copy(options = listOf(CartOption("إضافة", "جبن"))) })))
        assertNotNull(failure(two.copy(items = two.items + CanonicalItem("عصير", null, emptyList(), 1))))
    }
}
