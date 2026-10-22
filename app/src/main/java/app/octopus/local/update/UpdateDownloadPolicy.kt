package app.octopus.local.update

import java.net.URI

/** Only the private Octopus repository and GitHub's release asset hosts may serve an APK. */
object UpdateDownloadPolicy {
    const val RELEASES = "https://github.com/amjadoh/octopus/releases"
    private const val REPOSITORY = "/amjadoh/octopus/releases/"
    private val assetHosts = setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com")
    private fun uri(raw: String): URI? = runCatching { URI(raw) }.getOrNull()?.takeIf {
        it.scheme == "https" && it.rawUserInfo == null && it.port in setOf(-1, 443) && it.fragment == null
    }
    fun browser(raw: String): Boolean = uri(raw)?.host == "github.com"
    fun source(raw: String): Boolean = uri(raw)?.let {
        it.host == "github.com" && it.rawPath.matches(Regex(
            "/amjadoh/octopus/releases/download/v[0-9]+\\.[0-9]+\\.[0-9]+(?:-alpha)?/octopus-[0-9]+\\.[0-9]+\\.[0-9]+\\.apk"))
    } == true
    fun redirect(raw: String): Boolean = source(raw) || uri(raw)?.host in assetHosts
    fun sendsSession(raw: String): Boolean = source(raw)
    fun assetsPage(raw: String): Boolean = uri(raw)?.let {
        it.host == "github.com" && it.rawPath.matches(Regex(
            "${REPOSITORY}expanded_assets/v[0-9]+\\.[0-9]+\\.[0-9]+(?:-alpha)?"))
    } == true
    fun releasesPage(raw: String): Boolean = uri(raw)?.let {
        it.host == "github.com" && (it.path.trimEnd('/') == REPOSITORY.trimEnd('/') || it.path.startsWith("${REPOSITORY}tag/"))
    } == true
    fun newerFile(raw: String, installedName: String): Boolean {
        if (!source(raw)) return false
        fun parts(value: String): List<Long>? {
            return Regex("([0-9]+)\\.([0-9]+)\\.([0-9]+)").find(value)
                ?.groupValues?.drop(1)?.map { it.toLongOrNull() ?: return null }
        }
        val next = parts(URI(raw).path.substringAfterLast('/')) ?: return false
        val current = parts(installedName) ?: return false
        for (i in 0..2) if (next[i] != current[i]) return next[i] > current[i]
        return false
    }
}
