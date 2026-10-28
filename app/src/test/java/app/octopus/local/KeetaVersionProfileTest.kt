package app.octopus.local

import app.octopus.local.domain.CanonicalCart
import app.octopus.local.domain.CanonicalItem
import app.octopus.local.domain.ProviderId
import app.octopus.local.providers.KeetaMenuReader
import app.octopus.local.providers.KeetaVersionProfile
import app.octopus.local.providers.PlainMenuReadPlan
import org.junit.Assert.*
import org.junit.Test

class KeetaVersionProfileTest {
    @Test fun legacyRetainsSingleItemCapabilitiesWithoutOwnedMultiItemAdmission() {
        val profile = checkNotNull(KeetaVersionProfile.resolve("3.12.501"))
        assertEquals(KeetaVersionProfile.Schema.LEGACY_312, profile.schema)
        assertFalse(profile.usesCurrentLayout)
        assertTrue(profile.allowsReading())
        assertTrue(profile.allowsPreparation())
        assertTrue(profile.allowsReview())
        assertFalse(profile.allowsReading(true))
        assertFalse(profile.allowsPreparation(true))
        assertFalse(profile.allowsReview(true))
        assertFalse(profile.allowsOwnedPlainPreparation)
    }

    @Test fun verifiedCurrentVersionRetainsOwnedPlainAndReviewCapabilities() {
        val profile = checkNotNull(KeetaVersionProfile.resolve("3.13.203"))
        assertEquals(KeetaVersionProfile.Schema.CURRENT_313, profile.schema)
        assertTrue(profile.usesCurrentLayout)
        assertTrue(profile.allowsReading(true))
        assertTrue(profile.allowsPreparation(true))
        assertTrue(profile.allowsReview(true))
        assertTrue(profile.allowsOwnedPlainPreparation)
    }

    @Test fun unverifiedVersionsDoNotInheritSupportFromTheirPrefixOrRecordedShape() {
        listOf(null, "", "3.13", "3.13.302", "3.13.204", "3.13.203-beta", " 3.13.203 ").forEach {
            assertNull(KeetaVersionProfile.resolve(it))
        }
        assertTrue(runCatching { KeetaMenuReader("كودو", "بطاطس وسط", "3.13.302") }.isFailure)
    }

    @Test fun currentVersionAdmitsPlainMultiItemExecutorAndOwnedDrawerReturn() {
        val profile = checkNotNull(KeetaVersionProfile.resolve("3.13.301"))
        assertTrue(profile.usesCurrentLayout)
        assertTrue(profile.allowsReading())
        assertTrue(profile.allowsPreparation())
        assertTrue(profile.allowsOwnedPlainPreparation)
        assertTrue(profile.allowsReview())
        assertTrue(profile.allowsOwnedDrawerReturn)
        assertTrue(profile.allowsDirectDrawerReview)
        assertTrue(profile.allowsReading(true))
        assertTrue(profile.allowsPreparation(true))
        assertTrue(profile.allowsReview(true))
    }

    @Test fun readerAndReadPlanKeepActualVersionAndRejectAnotherVersionAtRecordTime() {
        val cart = CanonicalCart("كودو", null, listOf(CanonicalItem("بطاطس وسط", null, emptyList(), 3)))
        for (version in listOf("3.12.501", "3.13.203", "3.13.301")) {
            val profile = checkNotNull(KeetaVersionProfile.resolve(version))
            val reader = KeetaMenuReader(cart.restaurant, cart.items.single().name, profile.version)
            assertEquals(version, reader.VERSION)
            assertEquals(version, reader.profile.version)
            val plan = checkNotNull(PlainMenuReadPlan.create(cart, ProviderId.KEETA, profile.version))
            assertTrue(plan.record(cart, plan.lines.single(), 900, 1000, version))
            assertEquals(version, plan.preview(cart, 1100)?.version)
            val stale = checkNotNull(PlainMenuReadPlan.create(cart, ProviderId.KEETA, profile.version))
            assertFalse(stale.record(cart, stale.lines.single(), 900, 1000,
                if (version == "3.12.501") "3.13.203" else "3.12.501"))
            assertNull(stale.preview(cart, 1100))
        }
    }
}
