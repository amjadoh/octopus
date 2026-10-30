package app.octopus.local

import app.octopus.local.update.ReleaseLinkResolver
import app.octopus.local.update.UpdateDownloadPolicy
import org.junit.Assert.*
import org.junit.Test

class ReleaseLinkResolverTest {
    @Test fun choosesNewestReleaseRegardlessOfPageOrdering() {
        val prefix = "/amjadoh/octopus/releases/expanded_assets/"
        assertEquals("https://github.com"+prefix+"v1.3.7-alpha",
            ReleaseLinkResolver.choose(UpdateDownloadPolicy.RELEASES,
                listOf(prefix+"v1.1.0-alpha", prefix+"v1.3.7-alpha", prefix+"v1.3.6-alpha")))
    }
    @Test fun missingLazyLinksCanBeResolvedOnALaterRead() {
        assertNull(ReleaseLinkResolver.choose(UpdateDownloadPolicy.RELEASES, emptyList()))
        val link = "/amjadoh/octopus/releases/expanded_assets/v1.3.7-alpha"
        assertEquals("https://github.com$link", ReleaseLinkResolver.choose(UpdateDownloadPolicy.RELEASES, listOf(link)))
    }
    @Test fun resolvesApkFromExpandedAssetsWithoutManualSelection() {
        val page = "https://github.com/amjadoh/octopus/releases/expanded_assets/v1.3.7-alpha"
        val apk = "/amjadoh/octopus/releases/download/v1.3.7-alpha/octopus-1.3.7.apk"
        assertEquals("https://github.com$apk", ReleaseLinkResolver.choose(page, listOf(apk, "/archive/source.zip")))
    }
    @Test fun newerLazyDoubleDigitPatchOutranksAnAlreadyExpandedOlderApk() {
        val oldApk = "/amjadoh/octopus/releases/download/v1.3.9-alpha/octopus-1.3.9.apk"
        val newFragment = "/amjadoh/octopus/releases/expanded_assets/v1.3.10-alpha"
        for (links in listOf(listOf(oldApk, newFragment), listOf(newFragment, oldApk))) {
            val selected = ReleaseLinkResolver.choose(UpdateDownloadPolicy.RELEASES, links)
            assertEquals("https://github.com$newFragment", selected)
            val newApk = "/amjadoh/octopus/releases/download/v1.3.10-alpha/octopus-1.3.10.apk"
            val download = ReleaseLinkResolver.choose(selected!!, listOf(oldApk, newApk, newFragment))
            assertEquals("https://github.com$newApk", download)
            assertTrue(UpdateDownloadPolicy.newerFile(download!!, "1.3.9-diagnostic"))
            assertFalse(UpdateDownloadPolicy.newerFile(download, "1.3.10-diagnostic"))
        }
    }
    @Test fun sameVersionDirectApkIsPreferredAndNewerDirectApkWins() {
        val fragment = "/amjadoh/octopus/releases/expanded_assets/v1.3.10-alpha"
        val apk = "/amjadoh/octopus/releases/download/v1.3.10-alpha/octopus-1.3.10.apk"
        val older = "/amjadoh/octopus/releases/expanded_assets/v1.3.9-alpha"
        for (links in listOf(listOf(fragment, apk, older), listOf(older, apk, fragment))) {
            assertEquals("https://github.com$apk", ReleaseLinkResolver.choose(UpdateDownloadPolicy.RELEASES, links))
        }
    }
    @Test fun neverReadsLoginOrAcceptsOtherRepositories() {
        val link = "/someone/other/releases/download/v1.3.7-alpha/octopus-1.3.7.apk"
        assertNull(ReleaseLinkResolver.choose(UpdateDownloadPolicy.RELEASES, listOf(link)))
        assertNull(ReleaseLinkResolver.choose("https://github.com/login",
            listOf("/amjadoh/octopus/releases/expanded_assets/v1.3.7-alpha")))
    }
}
