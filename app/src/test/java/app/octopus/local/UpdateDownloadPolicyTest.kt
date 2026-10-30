package app.octopus.local

import app.octopus.local.update.UpdateDownloadPolicy as Policy
import org.junit.Assert.*
import org.junit.Test

class UpdateDownloadPolicyTest {
    private val apk = "https://github.com/amjadoh/octopus/releases/download/v1.3.6-alpha/octopus-1.3.6.apk"
    @Test fun acceptsOnlyTheExactPrivateRepositoryApkSource() {
        assertTrue(Policy.source(apk))
        listOf(apk.replace("https:", "http:"), apk.replace("github.com", "github.com.evil.test"),
            apk.replace("github.com", "github.com@evil.test"), apk.replace("amjadoh/octopus", "other/octopus"),
            apk.replace(".apk", ".apk.exe"), "$apk#fragment", apk.replace("github.com", "github.com:444"))
            .forEach { assertFalse(it, Policy.source(it)) }
    }
    @Test fun releaseStorageRedirectsNeverReceiveGitHubSessionCookies() {
        assertTrue(Policy.sendsSession(apk))
        val storage = "https://release-assets.githubusercontent.com/github-production-release-asset/test?signature=example"
        assertTrue(Policy.redirect(storage))
        assertFalse(Policy.sendsSession(storage))
        assertFalse(Policy.redirect("https://release-assets.githubusercontent.com.evil.test/file"))
        assertFalse(Policy.redirect("http://objects.githubusercontent.com/file"))
        assertFalse(Policy.redirect("file:///data/update.apk"))
    }
    @Test fun releasePageScriptsNeverApplyToLoginOrOtherRepositories() {
        assertTrue(Policy.assetsPage("https://github.com/amjadoh/octopus/releases/expanded_assets/v1.3.6-alpha"))
        assertTrue(Policy.releasesPage(Policy.RELEASES))
        assertFalse(Policy.releasesPage("https://github.com/login"))
        assertFalse(Policy.assetsPage("https://github.com/amjadoh/other/releases/expanded_assets/v1.3.6-alpha"))
        assertFalse(Policy.browser("javascript:alert(1)"))
        assertFalse(Policy.browser("https://evil.test/github.com"))
    }
    @Test fun preDownloadVersionCheckAvoidsEqualOlderAndMalformedFiles() {
        assertTrue(Policy.newerFile(apk, "1.3.5-diagnostic"))
        assertFalse(Policy.newerFile(apk, "1.3.6-diagnostic"))
        assertFalse(Policy.newerFile(apk, "2.0.0"))
        assertFalse(Policy.newerFile(apk, "unknown"))
        assertFalse(Policy.newerFile(apk.replace("octopus-1.3.6", "octopus-999999999999999999999999.0.0"), "1.0.0"))
    }
}
