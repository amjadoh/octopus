package app.octopus.local.catalog

import java.net.HttpURLConnection
import java.net.URI

fun interface PublicCatalogClient { fun get(url: String): String }

/** Only unauthenticated public HTML. Does not share the update WebView's cookie/session jar. */
class PublicCatalogHttpClient(private val allowed: (String) -> Boolean = PublicCatalogUrls::allowed) : PublicCatalogClient {
    override fun get(url: String): String {
        require(allowed(url))
        var next = url
        repeat(4) {
            require(allowed(next))
            val connection = URI(next).toURL().openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 12_000; connection.readTimeout = 15_000
                connection.setRequestProperty("Accept", "text/html")
                val code = connection.responseCode
                if (code in listOf(301,302,303,307,308)) {
                    next = URI(next).resolve(connection.getHeaderField("Location") ?: error("Redirect missing")).toString()
                    require(allowed(next)) { "Public source redirected outside its supported pages" }
                } else {
                    check(code == 200 && connection.contentType?.substringBefore(';')?.trim() == "text/html") { "Public source unavailable" }
                    require(connection.contentLengthLong <= HungerStationPublicParser.MAX_HTML_BYTES)
                    val bytes = connection.inputStream.use { it.readBytesBounded(HungerStationPublicParser.MAX_HTML_BYTES) }
                    return bytes.toString(Charsets.UTF_8)
                }
            } finally { connection.disconnect() }
        }
        error("Too many redirects")
    }
    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer); if (count < 0) break
            require(out.size().toLong() + count <= limit)
            out.write(buffer,0,count)
        }
        return out.toByteArray()
    }
}
