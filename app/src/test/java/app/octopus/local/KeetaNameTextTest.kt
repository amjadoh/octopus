package app.octopus.local

import app.octopus.local.providers.*
import org.junit.Assert.*
import org.junit.Test

class KeetaNameTextTest {
    @Test fun writingDifferencesMatchTheRequestedItemOnly() {
        val titles = setOf("وجبة دجاج مسحب - ١٠ قطع", "صلصة الثوم")
        assertEquals("وجبة دجاج مسحب - ١٠ قطع", KeetaNameText.requested("وجبة دجاج مسحب – 10 قطع", titles))
        assertEquals("وجبة دجاج مسحب - ١٠ قطع", KeetaNameText.requested("وجبة  دجاج مسحـب -١٠ قطع 🔥", titles))
        assertEquals("صلصة الثوم", KeetaNameText.requested("صلصة الثّوم", titles))
        assertEquals("صلصة الثوم", KeetaNameText.requested("‏صلصة الثوم", titles))
        // A different count, size or extra word is a different item.
        assertNull(KeetaNameText.requested("وجبة دجاج مسحب - ٦ قطع", titles))
        assertNull(KeetaNameText.requested("وجبة دجاج مسحب حرّاق - ١٠ قطع", titles))
        assertNull(KeetaNameText.requested("صلصة الثوم كبير", titles))
        assertNull(KeetaNameText.requested("", titles))
        assertNull(KeetaNameText.requested("صلصة\nالثوم", titles))
    }

    @Test fun hamzaAndYaFormsAreTheSameWriting() {
        assertEquals(KeetaNameText.loose("أرز بخاري"), KeetaNameText.loose("ارز بخاري"))
        assertEquals(KeetaNameText.loose("شاورما على الصاج"), KeetaNameText.loose("شاورما علي الصاج"))
        assertNotEquals(KeetaNameText.loose("برجر"), KeetaNameText.loose("برجر لحم"))
    }

    @Test fun restaurantPromotionAfterTheNameMatchesButNotABranchOrAnotherRestaurant() {
        assertTrue(KeetaRestaurantName.matches("البيك", "البيك - عرض الويكند 🔥"))
        assertTrue(KeetaRestaurantName.matches("البيك", "🔥 البيك | خصم 20%"))
        assertTrue(KeetaRestaurantName.matches("مطعم الطازج", "مطعم الطازج (توصيل مجاني)"))
        assertFalse(KeetaRestaurantName.matches("البيك", "البيك - العليا"))
        assertFalse(KeetaRestaurantName.matches("البيك", "البيك (عرض)"))
        assertTrue(KeetaRestaurantName.matches("شاورمر", "شاورمر"))
        assertTrue(KeetaRestaurantName.matches("ماما نورة", "ماما نوره".replace('ه', 'ة')))
        assertFalse(KeetaRestaurantName.matches("البيك", "البيك - فرع الحمراء"))
        assertFalse(KeetaRestaurantName.matches("البيك", "البيك اكسبرس"))
        assertFalse(KeetaRestaurantName.matches("البيك", "الطازج - البيك"))
        assertFalse(KeetaRestaurantName.matches("هرفي", "هرفي\nبرجر"))
    }

    @Test fun terraApprovalAddsOnlyThatExactPair() {
        assertFalse(KeetaRestaurantName.matches("ماكدونالدز", "McDonald's"))
        KeetaRestaurantName.approve("ماكدونالدز", "McDonald's")
        assertTrue(KeetaRestaurantName.matches("ماكدونالدز", "McDonald's"))
        assertFalse(KeetaRestaurantName.matches("ماكدونالدز", "Burger King"))
        assertFalse(KeetaRestaurantName.matches("برجر كنج", "McDonald's"))
    }
}
