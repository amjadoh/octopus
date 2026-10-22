package app.octopus.local.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.octopus.local.BuildConfig
import app.octopus.local.update.UpdateDownloadPolicy
import org.json.JSONTokener
import java.net.URI

/** Normal GitHub sign-in. No JavaScript bridge or inspection of login/form contents. */
@SuppressLint("SetJavaScriptEnabled")
@Composable fun GitHubUpdateBrowser(onDismiss: () -> Unit, onMessage: (String) -> Unit,
    onDownload: (String, String?, String?) -> Unit) {
    var browser by remember { mutableStateOf<WebView?>(null) }
    var status by remember { mutableStateOf("سجّل دخولك إلى GitHub بالحساب المخوّل إذا طُلب منك. يبدأ تنزيل الإصدار الأحدث داخل الأخطبوط.") }
    var dispatched by remember { mutableStateOf(false) }
    fun download(view: WebView, url: String) {
        if (dispatched) return
        if (!UpdateDownloadPolicy.source(url)) { status = "هذا الرابط ليس تحديث الأخطبوط المعتمد."; return }
        if (!UpdateDownloadPolicy.newerFile(url, BuildConfig.VERSION_NAME)) {
            dispatched = true
            onMessage("الإصدار المحدد ليس أحدث من نسختك الحالية.")
            onDismiss()
            return
        }
        dispatched = true
        // Used in memory for its own browser download only; never persisted by the downloader or logged.
        onDownload(url, CookieManager.getInstance().getCookie(url), view.settings.userAgentString)
    }
    DisposableEffect(Unit) { onDispose { browser?.apply { stopLoading(); destroy() }; browser = null } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onDismiss) { Text("إغلاق") }
                    TextButton(onClick = { browser?.loadUrl(UpdateDownloadPolicy.RELEASES) }) { Text("إعادة المحاولة") }
                    TextButton(onClick = { CookieManager.getInstance().removeAllCookies {
                        browser?.loadUrl(UpdateDownloadPolicy.RELEASES)
                    } }) { Text("تسجيل الخروج") }
                }
                Text(status, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                AndroidView(modifier = Modifier.fillMaxWidth().weight(1f), factory = { context ->
                    WebView(context).apply {
                        browser = this
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        settings.setSupportMultipleWindows(false)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                if (!request.isForMainFrame) return false
                                val url = request.url.toString()
                                if (UpdateDownloadPolicy.source(url)) { download(view, url); return true }
                                if (UpdateDownloadPolicy.browser(url)) return false
                                status = "توقف فتح رابط خارج GitHub."
                                return true
                            }
                            override fun onPageFinished(view: WebView, url: String) {
                                if (dispatched) return
                                val assets = UpdateDownloadPolicy.assetsPage(url)
                                if (!assets && !UpdateDownloadPolicy.releasesPage(url)) return
                                // Only this repository's release pages; never a sign-in page.
                                val script = if (assets) """(()=>Array.from(document.querySelectorAll('a[href]')).map(a=>a.getAttribute('href')).find(h=>h && /\/octopus-[0-9]+\.[0-9]+\.[0-9]+\.apk$/.test(h)) || null)()"""
                                    else """(()=>{const e=document.querySelector('include-fragment[src*="/amjadoh/octopus/releases/expanded_assets/"]');return e?e.getAttribute('src'):null;})()"""
                                view.evaluateJavascript(script) { result ->
                                    val path = runCatching { JSONTokener(result).nextValue() as? String }.getOrNull()
                                        ?: return@evaluateJavascript
                                    val link = runCatching { URI(url).resolve(path).toString() }.getOrNull()
                                        ?: return@evaluateJavascript
                                    if (assets) download(view, link)
                                    else if (UpdateDownloadPolicy.assetsPage(link)) view.loadUrl(link)
                                }
                            }
                        }
                        setDownloadListener { url, _, _, _, _ -> download(this, url) }
                        loadUrl(UpdateDownloadPolicy.RELEASES)
                    }
                })
            }
        }
    }
}
