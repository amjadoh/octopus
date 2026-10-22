package app.octopus.local.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.octopus.local.BuildConfig
import app.octopus.local.update.PrivateUpdateInstaller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable fun PrivateUpdateDialog(onDismiss: () -> Unit, openGitHub: () -> Unit) {
    val context = LocalContext.current
    val installer = remember { PrivateUpdateInstaller(context) }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var verified by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true; verified = false
            try { val version = installer.importApk(uri); verified = true; message = "تحقق التوقيع والإصدار: $version. اضغط تثبيت التحديث." }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { message = failure.message ?: "تعذر فحص التحديث." }
            finally { busy = false }
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("تحديث التطبيق") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("الإصدار الحالي: ${BuildConfig.VERSION_NAME}")
            Text("نزّل APK من إصدار GitHub الخاص بحسابك، ثم اختر الملف هنا. يفحص الأخطبوط التوقيع والإصدار قبل فتح تثبيت Android.")
            TextButton(onClick = { runCatching(openGitHub).onFailure { message = "تعذر فتح GitHub." } }, enabled = !busy) { Text("تنزيل التحديث من GitHub") }
            TextButton(onClick = { picker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream")) }, enabled = !busy) { Text("اختيار ملف التحديث وفحصه") }
            if (busy) LinearProgressIndicator()
            message?.let { Text(it) }
        } },
        confirmButton = { TextButton(enabled = verified && !busy, onClick = {
            runCatching { installer.install() }.onSuccess { opened ->
                message = if (opened) "أكمل تأكيد التثبيت في Android." else "اسمح للأخطبوط بالتثبيت، ثم ارجع واضغط تثبيت التحديث."
            }.onFailure { verified = false; installer.clear(); message = it.message ?: "تعذر فتح التثبيت." }
        }) { Text("تثبيت التحديث") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("إغلاق") } })
}
