package app.octopus.local

import app.octopus.local.domain.ProviderId
import app.octopus.local.providers.*
import app.octopus.local.safety.CatalogFinancialBoundary
import app.octopus.local.safety.CatalogFinancialBoundary.Decision.*
import org.junit.Assert.*
import org.junit.Test

class CatalogFinancialBoundaryTest {
    private fun classify(id: String, clickable: Boolean = true, editable: Boolean = false,
        provider: ProviderId = ProviderId.HUNGERSTATION, className: String = "android.widget.Button") =
        CatalogFinancialBoundary.classify(id, className, clickable, editable, provider)
    private fun frame(vararg ids: String, complete: Boolean = true) = CatalogFrame(
        ids.mapIndexed { i, id -> CatalogNode(i, -1, id, "", "android.view.View", false, false, false) }, complete)

    @Test fun checkoutActionIsDisabledRatherThanMistakenForItsEntireScreen() {
        listOf("FlutterCart_checkoutButton", "com.example:id/payment_button", "checkout_btn", "btn_payment")
            .forEach { assertEquals(DISABLED_CONTROL, classify(it)) }
    }
    @Test fun financialScreensAndUnknownFinancialElementsStillStop() {
        listOf("checkout", "checkoutScreen", "payment_container", "checkout_total", "paymentMethods")
            .forEach { assertEquals(STOP, classify(it)) }
    }
    @Test fun financialInputsOrUnknownWidgetsNeverBecomeDisabledButtonExceptions() {
        assertEquals(STOP, classify("checkoutButton", editable = true))
        assertEquals(STOP, classify("checkoutButton", clickable = false))
        assertEquals(STOP, classify("checkoutButton", className = "android.widget.EditText"))
        assertEquals(STOP, classify("checkoutButton", provider = ProviderId.KEETA))
    }
    @Test fun cardFieldsStayBlockedEvenWithButtonSuffix() {
        listOf("credit_card", "bankCard", "cvv", "card_number", "card_expiry", "creditCardPaymentButton")
            .forEach { assertEquals(STOP, classify(it)) }
    }
    @Test fun exceptionRequiresCompleteKnownMenuOrCart() {
        assertTrue(CatalogFinancialBoundary.permitsDisabledControls(frame("FlutterCart_title"), ProviderId.HUNGERSTATION))
        assertTrue(CatalogFinancialBoundary.permitsDisabledControls(frame("menuBackButton", "menuSearchButton"), ProviderId.HUNGERSTATION))
        assertFalse(CatalogFinancialBoundary.permitsDisabledControls(frame("FlutterCart_title", complete = false), ProviderId.HUNGERSTATION))
        assertFalse(CatalogFinancialBoundary.permitsDisabledControls(frame("checkoutButton"), ProviderId.HUNGERSTATION))
        assertFalse(CatalogFinancialBoundary.permitsDisabledControls(frame("FlutterCart_title"), ProviderId.KEETA))
    }
    @Test fun ordinaryCatalogNodesAreUnaffected() {
        assertEquals(NORMAL, classify("menuBackButton"))
        assertEquals(NORMAL, classify("FlutterCart_cartItem"))
    }
}
