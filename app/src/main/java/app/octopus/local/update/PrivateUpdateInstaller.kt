package app.octopus.local.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

object UpdatePackagePolicy {
    fun accepts(installedPackage: String, candidatePackage: String, installedVersion: Long, candidateVersion: Long,
        installedSigners: Set<String>, candidateSigners: Set<String>): Boolean =
        installedPackage == candidatePackage && candidateVersion > installedVersion &&
            installedSigners.isNotEmpty() && installedSigners == candidateSigners
}

/** Downloads into app-private cache and accepts only a newer APK with the installed signing identity. */
class PrivateUpdateInstaller(private val context: Context) {
    private val directory get() = File(context.cacheDir, "verified-updates").apply { mkdirs() }
    private val target get() = File(directory, "update.apk")
    suspend fun downloadApk(source: String, githubSession: String?, agent: String?,
        progress: suspend (Long, Long?) -> Unit): String = withContext(Dispatchers.IO) {
        val temporary = File(directory, "download.part")
        target.delete()
        try {
            PrivateApkDownloader().download(source, githubSession, agent, temporary, progress)
            val version = verify(temporary)
            check(temporary.renameTo(target)) { "تعذر حفظ ملف التحديث." }
            version
        } finally { temporary.delete() }
    }
    private fun verify(file: File): String {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        @Suppress("DEPRECATION")
        val candidate = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: error("الملف ليس تحديث Android صالحًا.")
        fun signers(info: android.content.pm.PackageInfo) = info.signingInfo?.apkContentsSigners?.map {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) }
        }?.toSet().orEmpty()
        check(UpdatePackagePolicy.accepts(context.packageName, candidate.packageName, installed.longVersionCode,
            candidate.longVersionCode, signers(installed), signers(candidate))) {
            "الملف ليس إصدارًا أحدث من الأخطبوط بالتوقيع نفسه؛ لم يبدأ التثبيت."
        }
        return candidate.versionName.orEmpty()
    }
    /** Returns false when Android needs the user to grant this app installation permission first. */
    fun install(): Boolean {
        check(target.isFile) { "نزّل التحديث أولًا." }
        verify(target)
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", target)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
    fun clear() { target.delete() }
}
