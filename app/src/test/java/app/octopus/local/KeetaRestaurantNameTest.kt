package app.octopus.local

import app.octopus.local.providers.KeetaRestaurantName
import org.junit.Assert.*
import org.junit.Test

class KeetaRestaurantNameTest {
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
}
