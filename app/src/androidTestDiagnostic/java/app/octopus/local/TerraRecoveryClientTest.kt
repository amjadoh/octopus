package app.octopus.local

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import app.octopus.local.automation.TerraConnectionStore
import app.octopus.local.automation.TerraRecoveryClient
import app.octopus.local.automation.TerraRecoveryReason
import app.octopus.local.domain.ProviderId
import app.octopus.local.providers.CatalogFrame
import app.octopus.local.providers.CatalogNode
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Transport-boundary checks; no real account, token or network request is used. */
class TerraRecoveryClientTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory: File
    private lateinit var context: ContextWrapper
    private lateinit var store: TerraConnectionStore
    private val close = node(3, 2, "Close dialog")
    private val frame = CatalogFrame(listOf(
        node(0, -1, "private background must stay local"),
        node(1, 0, "").copy(id = "test:id/com_braze_inappmessage_html"),
        node(2, 1, "").copy(className = "android.app.AlertDialog"), close,
        node(4, 2, "Public offer")
    ), true)

    @Before fun setup() {
        directory = File.createTempFile("terra-client", ".tmp", base.cacheDir)
        check(directory.delete() && directory.mkdir())
        context = object : ContextWrapper(base) { override fun getFilesDir() = directory }
        File(directory, "ai-recovery-config.json").writeText(JSONObject()
            .put("endpoint", TerraConnectionStore.CLOUD_ENDPOINT).put("token", "a".repeat(64)).toString())
        store = TerraConnectionStore(context)
        store.setEnabled(true)
    }

    @After fun cleanup() { directory.deleteRecursively() }

    @Test fun validAdviceSendsOnlyAdvertAndClosesConnection() = runBlocking {
        val connection = Reply()
        assertEquals(true, TerraRecoveryClient(context) { connection }.approveAdvertClose(frame, close))
        val sent = JSONObject(connection.request.toString("UTF-8"))
        assertFalse(sent.toString().contains("private background"))
        assertTrue(sent.getString("public_text").contains("Public offer"))
        assertTrue(sent.isNull("redacted_image"))
        assertTrue(connection.closed)
    }

    @Test fun disabledOrPrivateFramesNeverOpenNetwork() = runBlocking {
        val client = TerraRecoveryClient(context) { error("Network must not open") }
        store.setEnabled(false)
        assertNull(client.approveAdvertClose(frame, close))
        store.setEnabled(true)
        val privateFrame = frame.copy(nodes = frame.nodes.map {
            if (it.index == 4) it.copy(label = "test@example.com") else it
        })
        assertNull(client.approveAdvertClose(privateFrame, close))
    }

    @Test fun pauseWhileResponseIsPendingRejectsAdvice() = runBlocking {
        val connection = Reply(beforeReply = { store.setEnabled(false) })
        assertEquals(false, TerraRecoveryClient(context) { connection }.approveAdvertClose(frame, close))
        assertTrue(connection.closed)
    }

    @Test fun responseForAnotherFrameIsRejected() = runBlocking {
        val connection = Reply(wrongFrame = true)
        assertEquals(false, TerraRecoveryClient(context) { connection }.approveAdvertClose(frame, close))
        assertTrue(connection.closed)
    }

    @Test fun unavailableGatewayReturnsNoAdviceAndClosesConnection() = runBlocking {
        val connection = Reply(status = 503)
        assertNull(TerraRecoveryClient(context) { connection }.approveAdvertClose(frame, close))
        assertTrue(connection.closed)
    }
    @Test fun continuationSendsOnlyFixedRecoveryPlanAndNoScreenOrBasketContents() = runBlocking {
        val connection = Reply(continuation = true, confidence = 0.84)
        assertEquals(true, TerraRecoveryClient(context) { connection }.approveContinuation(frame,
            ProviderId.KEETA, TerraRecoveryReason.RETRY_SCREEN_READ))
        val sent = JSONObject(connection.request.toString("UTF-8"))
        assertEquals("KEETA", sent.getString("provider"))
        assertFalse(sent.getString("public_text").contains("private background"))
        assertFalse(sent.getString("public_text").contains("Public offer"))
        assertEquals(0, sent.getJSONArray("candidates").length())
        assertTrue(sent.isNull("redacted_image"))
        assertTrue(connection.closed)
    }
    @Test fun continuationRejectsLowConfidenceAndResponseForOtherFrame() = runBlocking {
        assertNull(TerraRecoveryClient(context) { Reply(continuation=true,confidence=0.4) }
            .approveContinuation(frame,ProviderId.HUNGERSTATION,TerraRecoveryReason.REOPEN_SOURCE_RESTAURANT))
        assertEquals(false, TerraRecoveryClient(context) { Reply(continuation=true,wrongFrame=true) }
            .approveContinuation(frame,ProviderId.HUNGERSTATION,TerraRecoveryReason.REOPEN_SOURCE_RESTAURANT))
    }
    @Test fun continuationDisabledOrRevokedNeverGrantsRecovery() = runBlocking {
        store.setEnabled(false)
        assertNull(TerraRecoveryClient(context) { error("No network") }
            .approveContinuation(frame,ProviderId.HUNGERSTATION,TerraRecoveryReason.RETRY_SCREEN_READ))
        store.setEnabled(true)
        assertEquals(false, TerraRecoveryClient(context) { Reply(continuation=true,beforeReply={store.setEnabled(false)}) }
            .approveContinuation(frame,ProviderId.HUNGERSTATION,TerraRecoveryReason.RETRY_SCREEN_READ))
    }

    private class Reply(val status: Int = 200, val wrongFrame: Boolean = false,
        val beforeReply: () -> Unit = {}, val continuation: Boolean = false, val confidence: Double = 0.99) : HttpURLConnection(URL(TerraConnectionStore.CLOUD_ENDPOINT)) {
        val request = ByteArrayOutputStream()
        var closed = false
        override fun connect() = Unit
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getOutputStream() = request
        override fun getResponseCode() = status
        override fun getInputStream(): java.io.InputStream {
            beforeReply()
            val sent = JSONObject(request.toString("UTF-8"))
            return JSONObject().put("request_id", sent.getString("request_id"))
                .put("frame_fingerprint", if (wrongFrame) "other-frame" else sent.getString("frame_fingerprint"))
                .put("advice", JSONObject().put("screen_type", if(continuation) "catalog" else "advert")
                    .put("action", if(continuation) "continue" else "dismiss_overlay")
                    .put("target_id", if(continuation) JSONObject.NULL else 3).put("confidence", confidence)).toString().byteInputStream()
        }
    }

    private fun node(index: Int, parent: Int, label: String) =
        CatalogNode(index, parent, "", label, "android.view.View", true, false, false)
}
