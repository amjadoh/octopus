package app.octopus.local.automation

import android.content.Context
import app.octopus.local.providers.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.util.UUID

/** Optional gateway advice. The API key never belongs on Android; native action guards remain authoritative. */
class TerraRecoveryClient(private val context: Context,
    private val openConnection: (java.net.URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection }) {
    suspend fun approveAdvertClose(frame: CatalogFrame, close: CatalogNode): Boolean? = withContext(Dispatchers.IO) {
        val store=TerraConnectionStore(context)
        val config=store.activeConnection() ?: return@withContext null
        // Only the observed advert subtree can leave the phone. Never include the background catalog/account.
        val container=frame.nodes.singleOrNull {it.id.endsWith(":id/com_braze_inappmessage_html")} ?: return@withContext null
        val dialog=frame.descendants(container.index).singleOrNull {it.className=="android.app.AlertDialog"} ?: return@withContext null
        val body=frame.descendants(dialog.index)
        if(close !in body || body.any {it.editable || it.enteredText || it.omittedText}) return@withContext null
        val text=body.map{it.label}.filter{it.isNotBlank()}.distinct().joinToString("\n")
        if(text.length>2000 || Regex("password|captcha|verification|address|phone|email|دفع|عنوان|هاتف|تحقق|[\\w.+-]+@[\\w.-]+|(?:\\+966|05)\\d{8,9}",RegexOption.IGNORE_CASE).containsMatchIn(text)) return@withContext null
        val fingerprint=MessageDigest.getInstance("SHA-256").digest(frame.toString().toByteArray()).joinToString(""){"%02x".format(it)}
        val id=UUID.randomUUID().toString()
        val payload=JSONObject().put("request_id",id).put("frame_fingerprint",fingerprint)
            .put("provider","HUNGERSTATION").put("public_text",text).put("redacted_image",JSONObject.NULL).put("redaction_version",1)
            .put("candidates",JSONArray().put(JSONObject().put("id",close.index).put("label",close.label).put("class_name",close.className)))
        if(!store.isStillActive(config)) return@withContext null
        val connection=openConnection(config.endpoint)
        try {
            connection.instanceFollowRedirects=false
            connection.connectTimeout=2000;connection.readTimeout=6000
            connection.requestMethod="POST";connection.doOutput=true
            connection.setRequestProperty("Authorization","Bearer ${config.token}")
            connection.setRequestProperty("Content-Type","application/json")
            connection.outputStream.use{it.write(payload.toString().toByteArray(Charsets.UTF_8))}
            if(connection.responseCode!=200) return@withContext null
            val raw=connection.inputStream.use { input ->
                val bytes=java.io.ByteArrayOutputStream()
                val chunk=ByteArray(1024)
                while(bytes.size()<=16384) { val count=input.read(chunk); if(count<0)break;bytes.write(chunk,0,count) }
                bytes.toByteArray()
            }
            if(raw.size>16384) return@withContext null
            val result=JSONObject(String(raw,Charsets.UTF_8))
            currentCoroutineContext().ensureActive()
            if(!store.isStillActive(config)) return@withContext false
            if(result.getString("request_id")!=id || result.getString("frame_fingerprint")!=fingerprint) return@withContext false
            val advice=result.getJSONObject("advice")
            advice.getString("screen_type")=="advert" && advice.getString("action")=="dismiss_overlay" &&
                advice.getInt("target_id")==close.index && advice.getDouble("confidence") in 0.9..1.0
        } finally {connection.disconnect()}
    }
}
