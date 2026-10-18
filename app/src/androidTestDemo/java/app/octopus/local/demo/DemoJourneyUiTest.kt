package app.octopus.local.demo

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

/** Device execution is recorded separately from compilation of these tests. */
class DemoJourneyUiTest {
    @get:Rule val ui = createAndroidComposeRule<DemoJourneyActivity>()
    private fun clickTag(tag: String) {
        ui.onNodeWithTag("journey-list").performScrollToNode(hasTestTag(tag))
        ui.onNodeWithTag(tag).performClick()
    }
    @Test fun browseCustomiseCompareAndPreviewHandoff() {
        ui.onNodeWithText("وضع عرض • مطاعم وأسعار توضيحية • لا اتصال بالتطبيقات").assertIsDisplayed()
        clickTag("restaurant-garden"); clickTag("add-beef")
        clickTag("size-large"); clickTag("drink-lemon"); clickTag("extra-cheese"); clickTag("draft-add")
        ui.onNodeWithTag("journey-list").performScrollToNode(hasText("مراجعة طلبي (1) • 39.00 ر.س"))
        ui.onNodeWithText("مراجعة طلبي (1) • 39.00 ر.س").performClick()
        clickTag("compare-start")
        ui.mainClock.advanceTimeBy(3000)
        ui.waitForIdle()
        clickTag("pick-KEETA"); clickTag("selected-continue"); clickTag("handoff-preview")
        ui.onNodeWithText("هنا تستلم طلبك في كيتا").assertIsDisplayed()
        ui.onNodeWithTag("journey-list").performScrollToNode(hasText("الدفع داخل التطبيق الأصلي — غير متاح في العرض"))
        ui.onNodeWithText("الدفع داخل التطبيق الأصلي — غير متاح في العرض").assertIsNotEnabled()
    }
}
