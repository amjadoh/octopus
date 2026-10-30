package app.octopus.local

import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import app.octopus.local.automation.SnapshotReader
import app.octopus.local.safety.ProviderAccessRestriction
import org.junit.Assert.*
import org.junit.Test

/** Synthetic nodes test the reader on Android; they do not prove provider integration. */
class RestrictedSnapshotTest {
    @Suppress("DEPRECATION")
    private fun withNode(block: (AccessibilityNodeInfo) -> Unit) {
        val node = AccessibilityNodeInfo.obtain()
        try { node.isVisibleToUser = true; block(node) } finally { node.recycle() }
    }
    @Test fun longVisibleRestrictionStopsPriceReading() = withNode { node ->
        node.text = "You've reached our maximum number of attempts. Please wait and try again later.To report, use the reference code: test-reference"
        val result = SnapshotReader().read(node)
        assertEquals(ProviderAccessRestriction.TOO_MANY_ATTEMPTS, result.accessRestriction)
        assertTrue(result.tokens.isEmpty())
    }
    @Test fun editableContentsAreNeverClassifiedOrExtracted() = withNode { node ->
        node.isEditable = true
        node.text = "Unusual activity detected. Log-in or sign-up is restricted."
        val result = SnapshotReader().read(node)
        assertNull(result.accessRestriction)
        assertTrue(result.tokens.isEmpty())
    }
    @Test fun protectedTextIsNeverExtracted() = withNode { node ->
        if (Build.VERSION.SDK_INT < 34) return@withNode
        node.isAccessibilityDataSensitive = true
        node.text = "Total 36.00"
        val result = SnapshotReader().read(node)
        assertTrue(result.sensitiveScreen)
        assertTrue(result.tokens.isEmpty())
    }
    @Test fun readonlyPrivateFieldsCannotBecomePriceTokens() = withNode { node ->
        node.text = "Total 36.00"
        listOf("credit_card", "card_number", "address", "phone", "otp").forEach { name ->
            node.viewIdResourceName = "com.example:id/$name"
            assertTrue(name, SnapshotReader().read(node).tokens.isEmpty())
        }
        node.viewIdResourceName = "com.example:id/order_total"
        assertTrue(SnapshotReader().read(node).tokens.any { it.amount == 3600L })
    }
}
