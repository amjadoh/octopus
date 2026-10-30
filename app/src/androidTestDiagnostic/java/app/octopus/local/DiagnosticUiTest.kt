package app.octopus.local

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Before

/** Requires an emulator or device; source alone is not a passing device test. */
class DiagnosticUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun resetVisibleComparisonState() {
        compose.waitUntil(10000) { (compose.activity.application as OctopusApplication).graph.draftReady.value }
        compose.runOnIdle {
            val graph=(compose.activity.application as OctopusApplication).graph
            graph.catalog.value=app.octopus.local.providers.LiveCatalogState()
            graph.draft.value=null
        }
    }
    private fun scrollToText(text: String) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
    }
    @Test fun homeStartsWithOnlyNativeActionsAndNoRestaurantCatalog() {
        compose.onNodeWithText("ابدأ في هنقرستيشن").assertIsDisplayed()
        compose.onNodeWithText("قارن").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithText("تحديث قائمة المطاعم").assertDoesNotExist()
        compose.onNodeWithText("التطبيقات").assertDoesNotExist()
        compose.onNodeWithText("0.00 ر.س").assertDoesNotExist()
    }
    @Test fun savedOrderDoesNotAutomaticallyBecomeANewNativeComparison() {
        compose.waitUntil(5000) { (compose.activity.application as OctopusApplication).graph.draftReady.value }
        compose.runOnIdle {
            val graph=(compose.activity.application as OctopusApplication).graph
            graph.draft.value=app.octopus.local.domain.CanonicalCart("مطعم محفوظ",null,
                listOf(app.octopus.local.domain.CanonicalItem("وجبة",null,emptyList(),1)))
            org.junit.Assert.assertFalse(graph.catalog.value.comparisonRunning)
        }
        compose.onNodeWithText("مطعم محفوظ").assertDoesNotExist()
        compose.onNodeWithText("قارن").assertIsDisplayed()
    }
    @Test fun nativeHomeShowsDifferenceOnlyForFreshTotalsOfTheSameRequest() {
        compose.waitUntil(5000) { (compose.activity.application as OctopusApplication).graph.draftReady.value }
        compose.runOnIdle {
            val graph=(compose.activity.application as OctopusApplication).graph
            val cart=app.octopus.local.domain.CanonicalCart("مطعم تجريبي",null,
                listOf(app.octopus.local.domain.CanonicalItem("وجبة",null,emptyList(),1)))
            graph.draft.value=cart
            graph.catalog.value=graph.catalog.value.copy(finalTotals=mapOf(
                app.octopus.local.domain.ProviderId.HUNGERSTATION to app.octopus.local.providers.FinalTotalReading(
                    app.octopus.local.domain.ProviderId.HUNGERSTATION,"test",cart.fingerprint,
                    mapOf(app.octopus.local.domain.PriceField.ORDER_TOTAL to 2500L),System.currentTimeMillis()),
                app.octopus.local.domain.ProviderId.KEETA to app.octopus.local.providers.FinalTotalReading(
                    app.octopus.local.domain.ProviderId.KEETA,"test",cart.fingerprint,
                    mapOf(app.octopus.local.domain.PriceField.ORDER_TOTAL to 2000L),System.currentTimeMillis())))
        }
        compose.onNodeWithText("كيتا أوفر بـ 5.00 ر.س").assertIsDisplayed()
        compose.runOnIdle {
            val graph=(compose.activity.application as OctopusApplication).graph
            graph.draft.value=graph.draft.value!!.copy(items=listOf(graph.draft.value!!.items.single().copy(quantity=2)))
        }
        compose.onNodeWithText("كيتا أوفر بـ 5.00 ر.س").assertDoesNotExist()
        compose.onNodeWithText("25.00 ر.س").assertDoesNotExist()
    }
    @Test fun nativeHomeShowsFailureWithoutLosingTheRequest() {
        val message="لم تثبت سلة المطعم؛ لم أكرر الإضافة."
        compose.runOnIdle {
            val graph=(compose.activity.application as OctopusApplication).graph
            graph.catalog.value=graph.catalog.value.copy(error=message)
        }
        compose.onNodeWithText(message).assertIsDisplayed()
        compose.onNodeWithText("ابدأ في هنقرستيشن").assertIsDisplayed()
        compose.onNodeWithText("قارن").assertIsDisplayed()
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
