package app.octopus.local.update

import java.net.URI

/** Release order and lazy loading must not force the user to select an APK. */
object ReleaseLinkResolver {
    fun choose(page: String, links: List<String>): String? {
        if (!UpdateDownloadPolicy.releasesPage(page) && !UpdateDownloadPolicy.assetsPage(page)) return null
        val candidates = links.take(200).mapNotNull { runCatching { URI(page).resolve(it).toString() }.getOrNull() }
        fun version(link: String): List<Long> = Regex("v?([0-9]+)\\.([0-9]+)\\.([0-9]+)").find(URI(link).path)
            ?.groupValues?.drop(1)?.map { it.toLongOrNull() ?: 0L } ?: listOf(0, 0, 0)
        val order = compareBy<String>({ version(it)[0] }, { version(it)[1] }, { version(it)[2] })
        return candidates.filter(UpdateDownloadPolicy::source).maxWithOrNull(order)
            ?: candidates.filter(UpdateDownloadPolicy::assetsPage).maxWithOrNull(order)
    }
}
