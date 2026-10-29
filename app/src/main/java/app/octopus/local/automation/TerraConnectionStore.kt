package app.octopus.local.automation

import android.content.Context
import android.util.AtomicFile
import app.octopus.local.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.URI

/** The independently provisioned device token is never bundled, backed up or exported. */
class TerraConnectionStore(file: File) {
    constructor(context: Context) : this(context.filesDir.resolve("ai-recovery-config.json"))
    private val storage = AtomicFile(file)

    class Connection(val endpoint: URI, val token: String)

    fun isConfigured(): Boolean = connection(read()) != null
    fun activeConnection(): Connection? = read()?.takeIf {
        it.optBoolean("enabled") && it.optInt("consent_version") == 1
    }?.let(::connection)

    fun isStillActive(previous: Connection): Boolean = activeConnection()?.let {
        it.endpoint == previous.endpoint && it.token == previous.token
    } == true

    fun setEnabled(enabled: Boolean) = synchronized(lock) {
        val config = read() ?: error("Missing connection")
        check(connection(config) != null) { "Invalid connection" }
        config.put("enabled", enabled).put("consent_version", if (enabled) 1 else 0)
        val stream = storage.startWrite()
        try {
            stream.write(config.toString().toByteArray(Charsets.UTF_8))
            storage.finishWrite(stream)
        } catch (error: Exception) {
            storage.failWrite(stream)
            throw error
        }
    }

    fun disconnect() = synchronized(lock) { storage.delete() }

    private fun read(): JSONObject? = synchronized(lock) {
        runCatching {
            require(storage.baseFile.length() in 1..4096)
            val bytes = storage.readFully()
            require(bytes.size <= 4096)
            JSONObject(String(bytes, Charsets.UTF_8))
        }.getOrNull()
    }

    private fun connection(config: JSONObject?): Connection? = runCatching {
        requireNotNull(config)
        val endpoint = URI(config.getString("endpoint"))
        val localTest = BuildConfig.VERSION_NAME.endsWith("-device-tools") &&
            endpoint.scheme == "http" && endpoint.host == "127.0.0.1" && endpoint.path == "/v1/recover" &&
            endpoint.userInfo == null && endpoint.query == null && endpoint.fragment == null
        require(endpoint.toString() == CLOUD_ENDPOINT || localTest)
        val token = config.getString("token")
        require(token.matches(Regex("[A-Za-z0-9_-]{32,256}")) && !token.startsWith("sk-"))
        Connection(endpoint, token)
    }.getOrNull()

    companion object {
        const val CLOUD_ENDPOINT = "https://octopus-ai-32027385946.us-central1.run.app/v1/recover"
        private val lock = Any()
    }
}
