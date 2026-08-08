package pl.lewicowyt.notifier.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AppUpdateRedirectTest {
    @Test
    fun acceptsInitialGitHubReleaseApkUrl() {
        val url = requireSafeApkDownloadUrl(
            "https://github.com/emmunioo/lewicowyt/releases/download/" +
                "v1.6-beta/lewicowYT-1.6-beta.apk",
            allowGitHubAssetHost = false,
        )

        assertEquals("github.com", url.host)
    }

    @Test
    fun acceptsGitHubReleaseAssetRedirect() {
        val url = requireSafeApkDownloadUrl(
            "https://release-assets.githubusercontent.com/github-production-release-asset/file",
            allowGitHubAssetHost = true,
        )

        assertEquals("release-assets.githubusercontent.com", url.host)
    }

    @Test
    fun rejectsAssetHostAsInitialUrl() {
        assertThrows(IllegalArgumentException::class.java) {
            requireSafeApkDownloadUrl(
                "https://release-assets.githubusercontent.com/file",
                allowGitHubAssetHost = false,
            )
        }
    }

    @Test
    fun rejectsHttpCredentialsPortsAndUntrustedRedirects() {
        listOf(
            "http://github.com/a/b/releases/download/v1/app.apk",
            "https://user@github.com/a/b/releases/download/v1/app.apk",
            "https://github.com:444/a/b/releases/download/v1/app.apk",
            "https://example.org/app.apk",
            "https://githubusercontent.com.evil.example/app.apk",
        ).forEach { value ->
            assertThrows(value, IllegalArgumentException::class.java) {
                requireSafeApkDownloadUrl(value, allowGitHubAssetHost = true)
            }
        }
    }
}
