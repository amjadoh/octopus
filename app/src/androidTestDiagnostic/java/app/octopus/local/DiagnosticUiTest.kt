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
    @Test fun savedOrderDoesNotBecomeANewSelectionOnStartup() {
        compose.waitUntil(5_000) { (compose.activity.application as OctopusApplication).graph.draftReady.value }
        compose.runOnIdle {
            val graph = (compose.activity.application as OctopusApplication).graph
            graph.draft.value = app.octopus.local.domain.CanonicalCart("مدور", null,
                listOf(app.octopus.local.domain.CanonicalItem("وجبة محفوظة", null, emptyList(), 1)))
            graph.catalog.value = graph.catalog.value.copy(selectedRestaurant = null)
        }
        compose.onNodeWithText("مدور").assertDoesNotExist()
        compose.onNodeWithText("راجع الطلب وقارن").assertDoesNotExist()
        compose.onNodeWithText("المقارنة").performClick()
        scrollToText("استئناف الطلب المحفوظ")
        compose.onNodeWithText("قارن أسعار الوجبات").assertDoesNotExist()
        compose.onNodeWithText("استئناف الطلب المحفوظ").performClick()
        scrollToText("طلبك • مدور")
        compose.onNodeWithText("طلبك • مدور").assertIsDisplayed()
    }
    @Test fun choosingDifferentRestaurantHidesOldDraftComparison() {
        compose.waitUntil(5_000) { (compose.activity.application as OctopusApplication).graph.draftReady.value }
        compose.runOnIdle {
            val graph = (compose.activity.application as OctopusApplication).graph
            graph.draft.value = app.octopus.local.domain.CanonicalCart("مدور", null,
                listOf(app.octopus.local.domain.CanonicalItem("فطيرة جبن فيتا", null, emptyList(), 3)))
            graph.catalog.value = graph.catalog.value.copy(selectedRestaurant = "مطعم آخر")
        }
        compose.onNodeWithText("راجع الطلب وقارن").assertDoesNotExist()
        compose.onNodeWithText("المقارنة").performClick()
        scrollToText("المطعم المختار: مطعم آخر")
        compose.onNodeWithText("المطعم المختار: مطعم آخر").assertIsDisplayed()
        compose.onNodeWithText("قارن أسعار الوجبات").assertDoesNotExist()
        compose.onNodeWithText("جهّز السلتين لمقارنة الإجمالي").assertDoesNotExist()
    }
    @Test fun restaurantMenuKeepsTheSelectedOrderInAFooterAndOpensItsComparison() {
        compose.waitUntil(5_000) { (compose.activity.application as OctopusApplication).graph.draftReady.value }
        compose.runOnIdle {
            val graph = (compose.activity.application as OctopusApplication).graph
            val cart = app.octopus.local.domain.CanonicalCart("مطعم جديد", null,
                listOf(app.octopus.local.domain.CanonicalItem("وجبة جديدة", null, emptyList(), 2)))
            graph.draft.value = cart
            graph.catalog.value = graph.catalog.value.copy(selectedRestaurant = cart.restaurant)
        }
        compose.onNodeWithText("راجع الطلب وقارن").assertIsDisplayed().performClick()
        scrollToText("طلبك • مطعم جديد")
        compose.onNodeWithText("طلبك • مطعم جديد").assertIsDisplayed()
        scrollToText("قارن أسعار الوجبات")
        compose.onNodeWithText("قارن أسعار الوجبات").assertIsDisplayed()
    }
    @Test fun startsWithRestaurantSearchAndNoInventedPrices() {
        compose.onNodeWithText("المطاعم").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertIsDisplayed().performTextInput("مدور")
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
        compose.onNodeWithText("التحقق من التحديث وتنزيله").assertIsDisplayed()
        compose.onNodeWithText("اختيار ملف التحديث وفحصه").assertDoesNotExist()
        compose.onNodeWithText("تثبيت التحديث").assertIsNotEnabled()
        compose.onNodeWithText("الإصدار الحالي: ${BuildConfig.VERSION_NAME}").assertIsDisplayed()
    }
    @Test fun privateUpdateBrowserOpensInsideTheAppAndCanBeClosed() {
        compose.onNodeWithContentDescription("الإعدادات").performClick()
        compose.onNode(hasText("تحديث التطبيق") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText("التحقق من التحديث وتنزيله").performClick()
        compose.onNodeWithText("تسجيل الخروج").assertIsDisplayed()
        compose.onNodeWithText("إعادة المحاولة").assertIsDisplayed()
        compose.onAllNodesWithText("إغلاق").onLast().performClick()
        compose.onNodeWithText("التحقق من التحديث وتنزيله").assertIsDisplayed()
    }
}
