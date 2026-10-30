package app.octopus.local.automation

import android.content.Context
import app.octopus.local.domain.ProviderId
import app.octopus.local.providers.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.util.UUID

/** Fixed native recovery proposals. No account, basket, address or arbitrary screen text is sent. */
enum class TerraRecoveryReason(val proposal: String) {
    REOPEN_SOURCE_RESTAURANT("Source cart navigation landed on the known home screen instead of its restaurant menu. Resume the existing verified exact-restaurant search, then re-read the unchanged complete basket. No cart edit or checkout."),
    RETRY_SCREEN_READ("A verified comparison step is waiting for its next screen. Retry reading the screen once, without repeating Add, quantity changes, payment or order submission."),
}

/** Optional advice; every action still passes the native executor's independent guards. */
class TerraRecoveryClient(private val context: Context,
    private val openConnection: (java.net.URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection }) {
    private fun payload(frame: CatalogFrame, provider: ProviderId, text: String, candidates: JSONArray): JSONObject =
        JSONObject().put("request_id", UUID.randomUUID().toString())
            .put("frame_fingerprint", MessageDigest.getInstance("SHA-256").digest(frame.toString().toByteArray())
                .joinToString("") { "%02x".format(it) })
            .put("provider", provider.name).put("public_text", text).put("candidates", candidates)
            .put("redacted_image", JSONObject.NULL).put("redaction_version", 1)

    suspend fun approveContinuation(frame: CatalogFrame, provider: ProviderId, reason: TerraRecoveryReason): Boolean? = withContext(Dispatchers.IO) {
        val store = TerraConnectionStore(context)
        val config = store.activeConnection() ?: return@withContext null
        val reply = exchange(store, config, payload(frame, provider,
            "Native food comparison recovery. ${reason.proposal} Continue only this native-validated plan or stop.", JSONArray()))
            ?: return@withContext null
        if (reply.optString("action") == "stop") return@withContext false
        if (reply.optString("screen_type") == "catalog" && reply.optString("action") == "continue" &&
            reply.isNull("target_id") && reply.optDouble("confidence", 0.0) in 0.8..1.0) true else null
    }

    suspend fun approveAdvertClose(frame: CatalogFrame, close: CatalogNode): Boolean? = withContext(Dispatchers.IO) {
        val store=TerraConnectionStore(context)
        val config=store.activeConnection() ?: return@withContext null
        val container=frame.nodes.singleOrNull {it.id.endsWith(":id/com_braze_inappmessage_html")} ?: return@withContext null
        val dialog=frame.descendants(container.index).singleOrNull {it.className=="android.app.AlertDialog"} ?: return@withContext null
        val body=frame.descendants(dialog.index)
        if(close !in body || body.any {it.editable || it.enteredText || it.omittedText}) return@withContext null
        val text=body.map{it.label}.filter{it.isNotBlank()}.distinct().joinToString("\n")
        if(text.length>2000 || Regex("password|captcha|verification|address|phone|email|دفع|عنوان|هاتف|تحقق|[\\w.+-]+@[\\w.-]+|(?:\\+966|05)\\d{8,9}",RegexOption.IGNORE_CASE).containsMatchIn(text)) return@withContext null
        val reply = exchange(store, config, payload(frame, ProviderId.HUNGERSTATION, text,
            JSONArray().put(JSONObject().put("id",close.index).put("label",close.label).put("class_name",close.className))))
            ?: return@withContext null
        reply.optString("screen_type")=="advert" && reply.optString("action")=="dismiss_overlay" &&
            reply.optInt("target_id",-1)==close.index && reply.optDouble("confidence",0.0) in 0.9..1.0
    }

    private suspend fun exchange(store: TerraConnectionStore, config: TerraConnectionStore.Connection, payload: JSONObject): JSONObject? {
        if (!store.isStillActive(config)) return null
        val connection=openConnection(config.endpoint)
        try {
            connection.instanceFollowRedirects=false
            connection.connectTimeout=2000;connection.readTimeout=6000
            connection.requestMethod="POST";connection.doOutput=true
            connection.setRequestProperty("Authorization","Bearer ${config.token}")
            connection.setRequestProperty("Content-Type","application/json")
            connection.outputStream.use{it.write(payload.toString().toByteArray(Charsets.UTF_8))}
            if(connection.responseCode!=200) return null
            val raw=connection.inputStream.use { input ->
                val bytes=java.io.ByteArrayOutputStream(); val chunk=ByteArray(1024)
                while(bytes.size()<=16384) { val count=input.read(chunk); if(count<0)break;bytes.write(chunk,0,count) }
                bytes.toByteArray()
            }
            if(raw.size>16384) return null
            val result=JSONObject(String(raw,Charsets.UTF_8))
            currentCoroutineContext().ensureActive()
            if(!store.isStillActive(config) || result.optString("request_id")!=payload.getString("request_id") ||
                result.optString("frame_fingerprint")!=payload.getString("frame_fingerprint")) return JSONObject().put("action","stop")
            return result.getJSONObject("advice")
        } finally {connection.disconnect()}
    }
}
