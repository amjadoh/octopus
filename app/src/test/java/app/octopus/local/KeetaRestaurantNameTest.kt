package app.octopus.local

import app.octopus.local.providers.KeetaRestaurantName
import org.junit.Assert.*
import org.junit.Test

class KeetaRestaurantNameTest {
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
