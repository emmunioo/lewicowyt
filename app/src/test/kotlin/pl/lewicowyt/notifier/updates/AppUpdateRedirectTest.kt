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

    @Test
    fun acceptsInitialManifestAndPatchFromGitHubRelease() {
        listOf("lewicowYT-update.json", "lewicowYT-1.6.1-to-1.7.xdelta").forEach { name ->
            val url = requireSafeReleaseAssetDownloadUrl(
                "https://github.com/emmunioo/lewicowyt/releases/download/v1.7-beta/$name",
                allowGitHubAssetHost = false,
                expectedName = name,
            )
            assertEquals("github.com", url.host)
        }
    }

    @Test
    fun rejectsDifferentAssetNameAndTraversal() {
        assertThrows(IllegalArgumentException::class.java) {
            requireSafeReleaseAssetDownloadUrl(
                "https://github.com/emmunioo/lewicowyt/releases/download/v1.7-beta/evil.xdelta",
                allowGitHubAssetHost = false,
                expectedName = "expected.xdelta",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireSafeReleaseAssetDownloadUrl(
                "https://github.com/emmunioo/lewicowyt/releases/download/v1.7-beta/patch.xdelta",
                allowGitHubAssetHost = false,
                expectedName = "../patch.xdelta",
            )
        }
    }
}
