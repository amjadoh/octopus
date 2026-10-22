package app.octopus.local

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

/** Requires an emulator or device; source alone is not a passing device test. */
class DiagnosticUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun scrollToText(text: String) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
    }
    @Test fun startsWithRestaurantSearchAndNoInventedPrices() {
        compose.onNodeWithText("المطاعم").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performScrollTo().assertIsDisplayed().performTextInput("مدور")
        compose.onNode(hasSetTextAction()).assertTextContains("مدور")
        compose.onNodeWithText("0.00 ر.س").assertDoesNotExist()
    }
    @Test fun providerControlsAndNoFakePrice() {
        compose.onNodeWithText("التطبيقات", useUnmergedTree = true).performClick()
        scrollToText("كيتا")
        compose.onNodeWithText("كيتا").assertIsDisplayed()
        scrollToText("هنقرستيشن")
        compose.onNodeWithText("هنقرستيشن").assertIsDisplayed()
        compose.onNodeWithText("0.00 ر.س").assertDoesNotExist()
    }
    @Test fun inspectionPageIsExplicitAndDoesNotStartCaptureOnOpen() {
        compose.onNodeWithContentDescription("الإعدادات").performClick()
        scrollToText("أدوات فحص القراءة")
        compose.onNodeWithText("أدوات فحص القراءة").performClick()
        scrollToText("فحص شاشة مراجعة الطلب")
        compose.onNodeWithText("فحص شاشة مراجعة الطلب").performClick()
        compose.onNodeWithText("فحص عناصر الشاشة").assertIsDisplayed()
        scrollToText("اسم المطعم كما يظهر في التطبيق")
        compose.onNodeWithText("اسم المطعم كما يظهر في التطبيق").assertExists()
        compose.onNodeWithText("السماح بفحص هذه الشاشة؟").assertDoesNotExist()
    }
    @Test fun updateStartsFromSettingsWithExplicitBrowserHandoff() {
        compose.onNodeWithContentDescription("الإعدادات").performClick()
        compose.onNode(hasText("تحديث التطبيق") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText("تنزيل التحديث من GitHub").assertIsDisplayed()
        compose.onNodeWithText("اختيار ملف التحديث وفحصه").assertIsDisplayed()
        compose.onNodeWithText("تثبيت التحديث").assertIsNotEnabled()
        compose.onNodeWithText("الإصدار الحالي: ${BuildConfig.VERSION_NAME}").assertIsDisplayed()
    }
}
