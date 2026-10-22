package app.octopus.local

import app.octopus.local.update.PrivateApkDownloader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class PrivateApkDownloaderTest {
    private val source = "https://github.com/amjadoh/octopus/releases/download/v1.3.6-alpha/octopus-1.3.6.apk"
    private class Response(url: URL, val status: Int, val location: String? = null,
        val bytes: ByteArray = byteArrayOf(1, 2, 3), val kind: String = "application/octet-stream",
        val size: Long = bytes.size.toLong()) : HttpURLConnection(url) {
        var closed = false
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun connect() = Unit
        override fun getResponseCode() = status
        override fun getHeaderField(name: String?) = if (name == "Location") location else null
        override fun getContentLengthLong() = size
        override fun getContentType() = kind
        override fun getInputStream() = bytes.inputStream()
    }
    private suspend fun withFile(block: suspend (File) -> Unit) {
        val file = File.createTempFile("octopus-update-test", ".part")
        try { block(file) } finally { file.delete() }
    }
    @Test fun authenticatedDownloadFollowsStorageRedirectWithoutLeakingSession() = runBlocking {
        val responses = mutableListOf<Response>()
        val downloader = PrivateApkDownloader { url ->
            Response(url, if (responses.isEmpty()) 302 else 200,
                "https://release-assets.githubusercontent.com/example").also { responses += it }
        }
        withFile { file ->
            downloader.download(source, "private-browser-session", "test", file) { _, _ -> }
            assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
            assertEquals("private-browser-session", responses[0].getRequestProperty("Cookie"))
            assertNull(responses[1].getRequestProperty("Cookie"))
            assertTrue(responses.all { it.closed })
            assertTrue(responses.none { it.instanceFollowRedirects })
        }
    }
    @Test fun redirectOutsideAllowedHostsIsRejectedBeforeAnyRequest() = runBlocking {
        var requests = 0
        val downloader = PrivateApkDownloader { url -> requests++; Response(url, 302, "https://evil.test/file") }
        withFile { file ->
            try { downloader.download(source, "session", null, file) { _, _ -> }; fail("Must reject redirect") }
            catch (_: IllegalStateException) { assertEquals(1, requests) }
        }
    }
    @Test fun loginHtmlCannotBeMistakenForAnApk() = runBlocking {
        val downloader = PrivateApkDownloader { Response(it, 200, kind = "text/html; charset=utf-8") }
        withFile { file ->
            try { downloader.download(source, null, null, file) { _, _ -> }; fail("Must reject login HTML") }
            catch (_: IllegalStateException) { assertEquals(0, file.length()) }
        }
    }
    @Test fun incompleteBodyAndOversizedDeclaredLengthAreRejected() = runBlocking {
        for (size in listOf(10L, PrivateApkDownloader.MAX_BYTES + 1)) {
            val downloader = PrivateApkDownloader { Response(it, 200, size = size) }
            withFile { file ->
                try { downloader.download(source, null, null, file) { _, _ -> }; fail("Must reject incomplete/oversized body") }
                catch (_: IllegalStateException) { /* The installer's finally block removes its private temporary file. */ }
            }
        }
    }
}
