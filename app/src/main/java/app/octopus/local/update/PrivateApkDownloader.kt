package app.octopus.local.update

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** Uses the user's own in-app GitHub browser session for this one download only. No token is shipped. */
class PrivateApkDownloader(private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {
    suspend fun download(source: String, githubSession: String?, agent: String?, destination: File,
        progress: suspend (Long, Long?) -> Unit) {
        check(UpdateDownloadPolicy.source(source)) { "رابط التحديث غير معتمد." }
        var address = source
        repeat(6) {
            currentCoroutineContext().ensureActive()
            check(UpdateDownloadPolicy.redirect(address)) { "توقف التنزيل عند رابط غير معتمد." }
            val connection = open(URI(address).toURL())
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.useCaches = false
                connection.setRequestProperty("Accept", "application/octet-stream")
                agent?.let { connection.setRequestProperty("User-Agent", it) }
                // Never forward GitHub cookies to a release storage host or arbitrary redirect.
                if (UpdateDownloadPolicy.sendsSession(address) && !githubSession.isNullOrBlank())
                    connection.setRequestProperty("Cookie", githubSession)
                val status = connection.responseCode
                if (status in setOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location") ?: error("تعذر متابعة رابط التحديث.")
                    address = URI(address).resolve(location).toString()
                } else {
                    check(status == 200) { "تعذر تنزيل التحديث الخاص؛ سجّل دخول GitHub بالحساب المخوّل ثم أعد المحاولة." }
                    val length = connection.contentLengthLong.takeIf { it > 0 }
                    check(length == null || length <= MAX_BYTES) { "ملف التحديث أكبر من الحد المسموح." }
                    check(!connection.contentType.orEmpty().contains("text/html", ignoreCase = true)) {
                        "يتطلب التحديث تسجيل الدخول إلى GitHub أولًا."
                    }
                    var received = 0L
                    var lastProgress = 0L
                    connection.inputStream.use { input -> destination.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            received += read
                            check(received <= MAX_BYTES) { "ملف التحديث أكبر من الحد المسموح." }
                            output.write(buffer, 0, read)
                            val now = System.nanoTime()
                            if (now - lastProgress >= 150_000_000) { progress(received, length); lastProgress = now }
                        }
                    } }
                    check(received > 0 && (length == null || received == length)) { "لم يكتمل تنزيل التحديث." }
                    progress(received, length)
                    return
                }
            } finally { connection.disconnect() }
        }
        error("تعذر الوصول إلى ملف التحديث بعد إعادة التوجيه.")
    }
    companion object { const val MAX_BYTES = 100L * 1024 * 1024 }
}
