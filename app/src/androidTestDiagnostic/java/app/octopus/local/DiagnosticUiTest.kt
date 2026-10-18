package app.octopus.local

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

/** Requires an emulator or device; source alone is not a passing device test. */
class DiagnosticUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun startsWithDiagnosticDisclosureAndEmptyHistory() {
        compose.onNodeWithText("نسخة تشخيصية • قراءة فقط").assertIsDisplayed()
        compose.onNodeWithText("السجل", useUnmergedTree = true).performClick()
        compose.onNodeWithText("لا توجد قراءات بعد").assertIsDisplayed()
    }
    @Test fun providerControlsAndNoFakePrice() {
        compose.onNodeWithText("التطبيقات", useUnmergedTree = true).performClick()
        compose.onNodeWithText("كيتا").assertIsDisplayed()
        compose.onNodeWithText("هنقرستيشن").assertIsDisplayed()
        compose.onAllNodesWithText("قراءة تشخيصية فقط").assertCountEquals(2)
    }
    @Test fun inspectionPageIsExplicitAndDoesNotStartCaptureOnOpen() {
        compose.onNodeWithText("القراءة", useUnmergedTree = true).performClick()
        compose.onNodeWithText("فحص شاشة مراجعة الطلب").performClick()
        compose.onNodeWithText("فحص عناصر الشاشة").assertIsDisplayed()
        compose.onNodeWithText("اسم المطعم كما يظهر في التطبيق").assertExists()
        compose.onNodeWithText("السماح بفحص هذه الشاشة؟").assertDoesNotExist()
    }
    @Test fun updateStartsFromSettingsWithExplicitBrowserHandoff() {
        compose.onNodeWithContentDescription("الإعدادات").performClick()
        compose.onNode(hasText("تحديث التطبيق") and hasClickAction()).performClick()
        compose.onNodeWithText("فتح صفحة التحديث").assertIsDisplayed()
        compose.onNodeWithText("الإصدار الحالي: ${BuildConfig.VERSION_NAME}").assertIsDisplayed()
    }
}
