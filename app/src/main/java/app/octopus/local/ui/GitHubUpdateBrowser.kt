package app.octopus.local.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceResponse
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.octopus.local.BuildConfig
import app.octopus.local.update.UpdateDownloadPolicy
import org.json.JSONArray
import app.octopus.local.update.ReleaseLinkResolver

/** Normal GitHub sign-in. No JavaScript bridge or inspection of login/form contents. */
@SuppressLint("SetJavaScriptEnabled")
@Composable fun GitHubUpdateBrowser(onDismiss: () -> Unit, onMessage: (String) -> Unit,
    onDownload: (String, String?, String?) -> Unit) {
    var browser by remember { mutableStateOf<WebView?>(null) }
    var status by remember { mutableStateOf("سجّل دخولك إلى GitHub بالحساب المخوّل إذا طُلب منك. يبدأ تنزيل الإصدار الأحدث داخل الأخطبوط.") }
    var dispatched by remember { mutableStateOf(false) }
    var showAccount by remember { mutableStateOf(false) }
    var pageGeneration by remember { mutableIntStateOf(0) }
    var loginRequested by remember { mutableStateOf(false) }
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
    fun resolve(view: WebView, url: String, generation: Int, attempt: Int = 0) {
        if (dispatched || generation != pageGeneration || view.url != url) return
        if (attempt >= 40) {
            onMessage("تعذر التحقق من التحديث تلقائيًا. تحقق من اتصال GitHub وصلاحية الحساب ثم أعد المحاولة.")
            onDismiss()
            return
        }
        val script = """JSON.stringify(Array.from(document.querySelectorAll('include-fragment[src],a[href]')).map(e=>e.getAttribute('src')||e.getAttribute('href')).filter(h=>h && h.includes('/amjadoh/octopus/releases/')).slice(0,200))"""
        view.evaluateJavascript(script) { raw ->
            if (dispatched || generation != pageGeneration || view.url != url) return@evaluateJavascript
            val links = runCatching {
                val encoded = org.json.JSONTokener(raw).nextValue() as? String ?: "[]"
                val array = JSONArray(encoded)
                (0 until array.length()).map { array.getString(it) }
            }.getOrDefault(emptyList())
            val selected = ReleaseLinkResolver.choose(url, links)
            when {
                selected == null -> view.postDelayed({ resolve(view, url, generation, attempt + 1) }, 500)
                UpdateDownloadPolicy.source(selected) -> download(view, selected)
                else -> view.loadUrl(selected)
            }
        }
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
                if (!showAccount) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        CircularProgressIndicator(color = OctopusPalette.Coral)
                        Text("جارٍ التحقق من الإصدار الأحدث…")
                        Text("سيبدأ التنزيل تلقائيًا", style = MaterialTheme.typography.bodySmall)
                    }
                }
                AndroidView(modifier = if (showAccount) Modifier.fillMaxWidth().weight(1f)
                    else Modifier.fillMaxWidth().height(1.dp), factory = { context ->
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
                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                pageGeneration++
                                showAccount = !UpdateDownloadPolicy.releasesPage(url) && !UpdateDownloadPolicy.assetsPage(url)
                            }
                            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                                if (request.isForMainFrame && response.statusCode in setOf(401, 403, 404)) {
                                    pageGeneration++
                                    showAccount = true
                                    status = "سجّل الدخول إلى حساب GitHub الذي يملك صلاحية المستودع الخاص."
                                    if (!loginRequested) {
                                        loginRequested = true
                                        view.post { view.loadUrl("https://github.com/login?return_to=%2Famjadoh%2Foctopus%2Freleases") }
                                    }
                                }
                            }
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
                                showAccount = false
                                resolve(view, url, pageGeneration)
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
