package app.octopus.local.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.octopus.local.BuildConfig
import app.octopus.local.update.PrivateUpdateInstaller
import kotlinx.coroutines.*

@Composable fun PrivateUpdateDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val installer = remember { PrivateUpdateInstaller(context) }
    val scope = rememberCoroutineScope()
    var browser by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var verified by remember { mutableStateOf(false) }
    var task by remember { mutableStateOf<Job?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("تحديث التطبيق") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("الإصدار الحالي: ${BuildConfig.VERSION_NAME}")
            Text("ينزّل الأخطبوط التحديث الخاص من GitHub ويفحص توقيعه ثم يفتح تأكيد التثبيت. قد تحتاج تسجيل دخول GitHub داخل التطبيق؛ لا تحتاج اختيار ملف من التنزيلات.")
            TextButton(onClick = { browser = true; message = null }, enabled = !busy) { Text("التحقق من التحديث وتنزيله") }
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = { task?.cancel() }) { Text("إلغاء التنزيل") }
            }
            message?.let { Text(it) }
        } },
        confirmButton = { TextButton(enabled = verified && !busy, onClick = {
            runCatching { installer.install() }.onSuccess { opened ->
                message = if (opened) "أكمل تأكيد التثبيت في Android." else "اسمح للأخطبوط بالتثبيت، ثم ارجع واضغط تثبيت التحديث."
            }.onFailure { verified = false; installer.clear(); message = it.message ?: "تعذر فتح التثبيت." }
        }) { Text("تثبيت التحديث") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("إغلاق") } })
    if (browser) GitHubUpdateBrowser(onDismiss = { browser = false }, onMessage = { message = it },
        onDownload = { url, session, agent ->
            browser = false
            busy = true; verified = false
            task = scope.launch {
                try {
                    val version = installer.downloadApk(url, session, agent) { received, total ->
                        withContext(Dispatchers.Main) { message = if (total != null)
                            "جارٍ تنزيل التحديث: ${received * 100 / total}٪" else "جارٍ تنزيل التحديث: ${received / (1024 * 1024)} ميجابايت" }
                    }
                    verified = true
                    val opened = installer.install()
                    message = if (opened) "تحقق الإصدار $version والتوقيع. أكمل تأكيد التثبيت في Android."
                        else "تحقق الإصدار $version. اسمح للأخطبوط بالتثبيت، ثم ارجع واضغط تثبيت التحديث."
                } catch (cancelled: CancellationException) {
                    message = "أُلغي التنزيل."; throw cancelled
                } catch (failure: Exception) { message = failure.message ?: "تعذر تنزيل التحديث وفحصه." }
                finally { busy = false }
            }
        })
}
