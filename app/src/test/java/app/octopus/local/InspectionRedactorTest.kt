package app.octopus.local

import app.octopus.local.diagnostics.*
import org.junit.Assert.*
import org.junit.Test

class InspectionRedactorTest {
    private val request = InspectionRequest(InspectionStage.REVIEW, "مطعم اختبار", "وجبة اختبار")
    @Test fun arbitraryIdentityAddressAndPaymentValuesAreNotExported() {
        listOf("اسم المستخدم", "0501234567", "customer@example.com", "عنوان المنزل شارع 15",
            "4111 1111 1111 1111", "123456", "رمز التحقق 1234").forEach { assertNull(InspectionRedactor.tag(it, request)) }
    }
    @Test fun exactTargetsBecomeTagsNotRawNames() {
        assertEquals("TARGET_RESTAURANT", InspectionRedactor.tag("مطعم اختبار", request))
        assertEquals("TARGET_ITEM", InspectionRedactor.tag("وجبة اختبار", request))
        assertNull(InspectionRedactor.tag("وجبة اختبار مع عنوان المنزل", request))
    }
    @Test fun dangerousButtonsAreDocumentedWithoutAuthorizingClicks() {
        assertEquals("FORBIDDEN_ORDER", InspectionRedactor.tag("تأكيد الطلب", request))
        assertEquals("FORBIDDEN_PAYMENT", InspectionRedactor.tag("pay now", request))
    }
    @Test fun invisibleFormattingAndArabicMarksAreNormalized() {
        assertEquals("TOTAL", InspectionRedactor.tag("  \u200fالإجمالي  ", request))
        assertNull(InspectionRedactor.tag("x".repeat(121), request))
    }
    @Test fun onlyStaticResourceIdsAndClassNamesSurvive() {
        assertEquals("com.example:id/cart_total", InspectionRedactor.resourceId("com.example:id/cart_total"))
        assertNull(InspectionRedactor.resourceId("com.example:id/customer@example.com"))
        assertNull(InspectionRedactor.resourceId("/data/data/com.example/files/account"))
        assertEquals("android.widget.TextView", InspectionRedactor.className("android.widget.TextView"))
        assertNull(InspectionRedactor.className("private data"))
    }
    @Test fun mergedPriceLabelProducesOnlyDictionaryHints() {
        assertEquals(listOf("TOTAL", "INCLUDED", "TAX"), InspectionRedactor.priceHints("الإجمالي شامل الضريبة ١٢٣٫٤٥"))
        assertTrue(InspectionRedactor.priceHints("customer@example.com 0501234567 شارع 12").isEmpty())
    }
}
