package app.octopus.local

import app.octopus.local.providers.KeetaNameText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeetaChoiceNameTest {
    private val popeyes = setOf("ساندوتش تشيكن ديلوكس", "بيبسي", "بطاطا", "حجم", "لا تقم بإجراء أي تغيير")

    @Test fun keetaSpellingWithoutTheFillerWordNamesTheRequestedChoice() {
        assertEquals("ساندوتش تشيكن ديلوكس", KeetaNameText.requestedChoice("ساندويتش ديلوكس", popeyes))
        assertEquals("ساندوتش تشيكن ديلوكس", KeetaNameText.requestedChoice("ساندويتش تشيكن ديلوكس", popeyes))
        assertEquals("بيبسي", KeetaNameText.requestedChoice("بيبسي", popeyes))
    }

    @Test fun aDroppedQualifierOrAnAmbiguousMatchStaysDifferent() {
        assertNull(KeetaNameText.requestedChoice("ساندويتش ديلوكس", setOf("ساندوتش تشيكن ديلوكس - حار")))
        assertNull(KeetaNameText.requestedChoice("ساندوتش تشيكن ديلوكس - حار", popeyes))
        assertNull(KeetaNameText.requestedChoice("ساندويتش ديلوكس", setOf("ساندوتش تشيكن ديلوكس", "ساندوتش دجاج ديلوكس")))
        assertNull(KeetaNameText.requestedChoice("بطاطا", setOf("بطاطا كبير")))
        assertNull(KeetaNameText.requestedChoice("ديلوكس", popeyes))
        assertNull(KeetaNameText.requestedChoice("ساندويتش ديلوكس كبير", popeyes))
    }

    @Test fun aComboWrittenAsAMealWithTheSameWordsIsTheSameTitle() {
        val hs = setOf("كومبو بيج ديلوكس برجر - وسط")
        assertEquals("كومبو بيج ديلوكس برجر - وسط", KeetaNameText.requested("بيج ديلوكس برجر وجبة - وسط", hs))
        assertNull(KeetaNameText.requested("بيج ديلوكس برجر وجبة - كبير", hs))
        assertNull(KeetaNameText.requested("بيج ديلوكس برجر", hs))
        assertNull(KeetaNameText.requested("سوبر ستار برجر وجبة - وسط", hs))
    }
}
