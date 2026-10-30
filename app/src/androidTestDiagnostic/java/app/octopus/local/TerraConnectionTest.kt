package app.octopus.local

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.octopus.local.automation.TerraConnectionStore
import app.octopus.local.ui.OctopusTheme
import app.octopus.local.ui.TerraControls
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File

class TerraConnectionTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var file: File
    private lateinit var store: TerraConnectionStore
    @Before fun prepare() {
        file = File.createTempFile("terra-connection", ".json", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        store = TerraConnectionStore(file)
    }
    @After fun clean() { store.disconnect() }
    private fun provision(endpoint: String = TerraConnectionStore.CLOUD_ENDPOINT, token: String = "a".repeat(64)) {
        file.writeText(JSONObject().put("endpoint", endpoint).put("token", token).put("enabled", true).toString())
    }

    @Test fun provisioningAloneDoesNotGrantConsentAndDisconnectRevokesAdvice() {
        provision()
        assertTrue(store.isConfigured())
        assertNull(store.activeConnection())
        store.setEnabled(true)
        val previous = requireNotNull(store.activeConnection())
        assertTrue(store.isStillActive(previous))
        store.setEnabled(false)
        assertNull(store.activeConnection())
        assertFalse(store.isStillActive(previous))
        assertTrue(store.isConfigured())
        store.setEnabled(true)
        store.disconnect()
        assertFalse(store.isConfigured())
        assertFalse(store.isStillActive(previous))
        assertFalse(file.exists())
    }

    @Test fun foreignEndpointsOpenAiKeysAndMalformedProfilesFailClosed() {
        for (endpoint in listOf("http://example.com/v1/recover", "https://example.com/v1/recover",
            TerraConnectionStore.CLOUD_ENDPOINT + "?redirect=other", TerraConnectionStore.CLOUD_ENDPOINT + "#x")) {
            provision(endpoint)
            assertFalse(store.isConfigured())
            assertNull(store.activeConnection())
        }
        provision(token = "sk-" + "a".repeat(64))
        assertFalse(store.isConfigured())
        file.writeText("invalid json")
        assertNull(store.activeConnection())
        file.writeText("a".repeat(4097))
        assertFalse(store.isConfigured())
    }

    @Test fun replacingDeviceCredentialInvalidatesAnOutstandingResponse() {
        provision()
        store.setEnabled(true)
        val previous = requireNotNull(store.activeConnection())
        provision(token = "b".repeat(64))
        store.setEnabled(true)
        assertFalse(store.isStillActive(previous))
    }

    @Test fun userMustConfirmSharingAndCanStopOrDeleteTheConnection() {
        provision()
        compose.setContent { OctopusTheme { Column { TerraControls(store) } } }
        compose.onNodeWithText("تفعيل مساعد تيرا").performClick()
        compose.onNodeWithText("ليس الآن").performClick()
        compose.runOnIdle { assertNull(store.activeConnection()) }
        compose.onNodeWithText("تفعيل مساعد تيرا").performClick()
        compose.onNodeWithText("أوافق وأفعّل المساعد").performClick()
        compose.runOnIdle { assertNotNull(store.activeConnection()) }
        compose.onNodeWithText("إيقاف مساعد تيرا").performClick()
        compose.runOnIdle { assertNull(store.activeConnection()) }
        compose.onNodeWithText("قطع الربط وحذف رمز الجهاز").performClick()
        compose.runOnIdle { assertFalse(file.exists()) }
        compose.onNodeWithText("تفعيل مساعد تيرا").assertDoesNotExist()
    }
}
