package app.octopus.local

import app.octopus.local.providers.KeetaRestaurantName
import org.junit.Assert.*
import org.junit.Test

class KeetaRestaurantNameTest {
    @org.junit.Test fun observedCampaignSpacingPreservesRestaurantIdentity() {
        assertTrue(KeetaRestaurantName.matches("كنتاكي", "كنتاكي - مخلل كل شي🥒"))
        assertTrue(KeetaRestaurantName.matches("كنتاكي", "كنتاكي - مخلل كل شي 🥒"))
        assertFalse(KeetaRestaurantName.matches("كنتاكي", "كنتاكي النرجس - مخلل كل شي🥒"))
        assertFalse(KeetaRestaurantName.matches("مطعم آخر", "كنتاكي - مخلل كل شي🥒"))
    }
    @Test fun observedCouponDecorationPreservesRestaurantAndBranchIdentity() {
        assertTrue(KeetaRestaurantName.matches("كودو", "🔥 كودو -كوبونات 🔥"))
        assertFalse(KeetaRestaurantName.matches("كودو", "🔥 كودو النرجس -كوبونات 🔥"))
        assertFalse(KeetaRestaurantName.matches("كودو", "🔥 مطعم آخر -كوبونات 🔥"))
        assertFalse(KeetaRestaurantName.matches("كودو", "🔥 كودو -كوبونات 🔥 فرع آخر"))
    }
    @Test fun observedDeliveryPromotionDoesNotChangeTheRestaurantName() {
        assertTrue(KeetaRestaurantName.matches("مدور", "مدور ( توصيل مجاني 🔥🏍️)"))
        assertTrue(KeetaRestaurantName.matches("مدور", "  مدور  "))
    }
    @Test fun branchAndOtherNamesAreNeverRemovedToForceAMatch() {
        assertFalse(KeetaRestaurantName.matches("مدور", "مدور (العليا)"))
        assertFalse(KeetaRestaurantName.matches("مدور", "مدور الثاني ( توصيل مجاني 🔥🏍️)"))
        assertFalse(KeetaRestaurantName.matches("مدور", "مدور ( توصيل مجاني 🔥🏍️) فرع آخر"))
        assertFalse(KeetaRestaurantName.matches("مدور", "مدور (خصم 20%)"))
    }
    @Test fun exactHerfyCampaignDoesNotAcceptOtherChainsOrBranchSuffixes() {
        assertTrue(KeetaRestaurantName.matches("هرفي", "هرفي🔥السوبر كامل ومكمل🔥"))
        assertFalse(KeetaRestaurantName.matches("كودو", "هرفي🔥السوبر كامل ومكمل🔥"))
        assertFalse(KeetaRestaurantName.matches("هرفي", "هرفي النرجس🔥السوبر كامل ومكمل🔥"))
        assertFalse(KeetaRestaurantName.matches("هرفي", "هرفي🔥السوبر كامل ومكمل🔥 فرع آخر"))
        assertFalse(KeetaRestaurantName.matches("هرفي", "هرفي🔥حملة أخرى🔥"))
    }
    @Test fun observedBrownBrandTranslationDoesNotRemoveBranchOrOtherBrandNames() {
        assertTrue(KeetaRestaurantName.matches("براون كوفي","براون كافيه"))
        assertFalse(KeetaRestaurantName.matches("براون كوفي","براون كافيه فرع آخر"))
        assertFalse(KeetaRestaurantName.matches("براون كوفي","براون هارت كافيه"))
        assertFalse(KeetaRestaurantName.matches("بون كافية","براون كافيه"))
    }
}
