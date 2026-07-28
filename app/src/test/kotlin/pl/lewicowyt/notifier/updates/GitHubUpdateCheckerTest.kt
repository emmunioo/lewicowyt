package pl.lewicowyt.notifier.updates

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubUpdateCheckerTest {
    @Test
    fun stableReleaseIsNewerThanBetaWithTheSameCore() {
        assertTrue(compareReleaseVersions("1.0", "1.0-beta") > 0)
    }

    @Test
    fun comparesSuccessiveBetaIdentifiers() {
        assertTrue(compareReleaseVersions("1.0-beta.2", "1.0-beta.1") > 0)
        assertTrue(compareReleaseVersions("1.0-rc.1", "1.0-beta.99") > 0)
    }

    @Test
    fun ignoresBuildMetadataAndPadsNumericCore() {
        assertEquals(0, compareReleaseVersions("1.0.0+build.7", "1.0"))
    }

    @Test
    fun acceptsOnlyHttpsUrlsFromTheConfiguredGitHubRepository() {
        assertEquals(
            "https://github.com/emmunioo/lewicowyt/releases/download/v1.0-beta/app.apk",
            requireSafeGitHubReleaseUrl(
                "https://github.com/emmunioo/lewicowyt/releases/download/v1.0-beta/app.apk",
                "emmunioo/lewicowyt",
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            requireSafeGitHubReleaseUrl(
                "https://example.org/emmunioo/lewicowyt/app.apk",
                "emmunioo/lewicowyt",
            )
        }
    }

    @Test
    fun skipsNewerSourceOnlyReleaseAndSelectsNewestInstallableApk() {
        val releases = JSONArray()
            .put(release(version = "1.1", hasApk = false))
            .put(release(version = "1.0.1", hasApk = true))

        val selected = selectNewestInstallableRelease(
            releases = releases,
            currentVersion = "1.0-beta",
            repository = "emmunioo/lewicowyt",
        )

        assertEquals("1.0.1", selected?.version)
    }

    @Test
    fun stableInstallationIgnoresPrereleases() {
        val releases = JSONArray()
            .put(release(version = "1.1-beta", hasApk = true, prerelease = true))
            .put(release(version = "1.0.1", hasApk = true))

        val selected = selectNewestInstallableRelease(
            releases = releases,
            currentVersion = "1.0",
            repository = "emmunioo/lewicowyt",
        )

        assertEquals("1.0.1", selected?.version)
    }

    @Test
    fun betaInstallationCanSeeNewerPrerelease() {
        val releases = JSONArray()
            .put(release(version = "1.1-beta", hasApk = true, prerelease = true))
            .put(release(version = "1.0.1", hasApk = true))

        val selected = selectNewestInstallableRelease(
            releases = releases,
            currentVersion = "1.0-beta",
            repository = "emmunioo/lewicowyt",
        )

        assertEquals("1.1-beta", selected?.version)
    }

    @Test
    fun existingCurrentReleaseKeepsNewerUpdateOptional() {
        val releases = JSONArray()
            .put(release(version = "1.3-beta", hasApk = true, prerelease = true))
            .put(release(version = "1.4-beta", hasApk = true, prerelease = true))

        val result = selectUpdateResultFromReleases(
            releases = releases,
            currentVersion = "1.3-beta",
            repository = "emmunioo/lewicowyt",
        ) as UpdateCheckResult.Available

        assertEquals("1.4-beta", result.update.version)
        assertEquals(UpdatePolicy.OPTIONAL, result.update.policy)
    }

    @Test
    fun removedCurrentReleaseMakesNewerReplacementMandatory() {
        val releases = JSONArray()
            .put(release(version = "1.4-beta", hasApk = true, prerelease = true))
            .put(release(version = "1.2-beta", hasApk = true, prerelease = true))

        val result = selectUpdateResultFromReleases(
            releases = releases,
            currentVersion = "1.3-beta",
            repository = "emmunioo/lewicowyt",
        ) as UpdateCheckResult.Available

        assertEquals("1.4-beta", result.update.version)
        assertEquals(UpdatePolicy.MANDATORY_SECURITY_UPDATE, result.update.policy)
    }

    @Test
    fun removedCurrentReleaseSelectsNewestOlderSecurityRollback() {
        val releases = JSONArray()
            .put(release(version = "1.2-beta", hasApk = true, prerelease = true))
            .put(release(version = "1.1-beta", hasApk = true, prerelease = true))

        val result = selectUpdateResultFromReleases(
            releases = releases,
            currentVersion = "1.3-beta",
            repository = "emmunioo/lewicowyt",
        ) as UpdateCheckResult.Available

        assertEquals("1.2-beta", result.update.version)
        assertEquals(UpdatePolicy.SECURITY_ROLLBACK, result.update.policy)
    }

    private fun release(
        version: String,
        hasApk: Boolean,
        prerelease: Boolean = false,
    ): JSONObject = JSONObject()
        .put("tag_name", "v$version")
        .put("draft", false)
        .put("prerelease", prerelease)
        .put(
            "html_url",
            "https://github.com/emmunioo/lewicowyt/releases/tag/v$version",
        )
        .put("body", "Informacje o wydaniu")
        .put(
            "assets",
            JSONArray().apply {
                if (hasApk) {
                    put(
                        JSONObject()
                            .put("name", "lewicowYT-$version.apk")
                            .put(
                                "browser_download_url",
                                "https://github.com/emmunioo/lewicowyt/releases/" +
                                    "download/v$version/lewicowYT-$version.apk",
                            ),
                    )
                }
            },
        )
}
