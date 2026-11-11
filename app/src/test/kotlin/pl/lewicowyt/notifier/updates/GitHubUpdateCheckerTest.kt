package pl.lewicowyt.notifier.updates

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(13_000_000L, selected?.update?.apkSizeBytes)
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
    fun `M-11 missing current release offers newer as optional (no false mandatory)`() {
        // bieżąca wersja nie jest na liście (np. repo >100 wydań), ale jest nowsza — bez markera to
        // aktualizacja OPCJONALNA, nie wymuszana (dawniej błędnie MANDATORY_SECURITY_UPDATE).
        val releases = JSONArray()
            .put(release(version = "1.4-beta", hasApk = true, prerelease = true))
            .put(release(version = "1.2-beta", hasApk = true, prerelease = true))

        val result = selectUpdateResultFromReleases(
            releases = releases,
            currentVersion = "1.3-beta",
            repository = "emmunioo/lewicowyt",
        ) as UpdateCheckResult.Available

        assertEquals("1.4-beta", result.update.version)
        assertEquals(UpdatePolicy.OPTIONAL, result.update.policy)
    }

    @Test
    fun `M-11 build ahead of all releases is up to date (no false rollback)`() {
        // lokalna beta wyprzedza wszystkie wydania — nic nowszego → UpToDate (dawniej błędnie SECURITY_ROLLBACK).
        val releases = JSONArray()
            .put(release(version = "1.2-beta", hasApk = true, prerelease = true))
            .put(release(version = "1.1-beta", hasApk = true, prerelease = true))

        val result = selectUpdateResultFromReleases(
            releases = releases,
            currentVersion = "1.3-beta",
            repository = "emmunioo/lewicowyt",
        )

        assertEquals(UpdateCheckResult.UpToDate("1.3-beta"), result)
    }

    @Test
    fun `M-11 explicit marker in release body makes update mandatory`() {
        val releases = JSONArray()
            .put(release(version = "1.3-beta", hasApk = true, prerelease = true))
            .put(
                release(
                    version = "1.4-beta",
                    hasApk = true,
                    prerelease = true,
                    body = "Krytyczna łatka. [security-mandatory]",
                ),
            )

        val result = selectUpdateResultFromReleases(
            releases = releases,
            currentVersion = "1.3-beta",
            repository = "emmunioo/lewicowyt",
        ) as UpdateCheckResult.Available

        assertEquals("1.4-beta", result.update.version)
        assertEquals(UpdatePolicy.MANDATORY_SECURITY_UPDATE, result.update.policy)
    }

    @Test
    fun `single apk for a foreign abi is not offered`() {
        val armv7 = listOf("armeabi-v7a", "armeabi")
        val arm64 = listOf("arm64-v8a", "armeabi-v7a", "armeabi")
        assertTrue(isApkCompatibleWithAbis("lewicowYT-1.9.apk", armv7)) // bez tokenu = uniwersalny
        assertFalse(isApkCompatibleWithAbis("lewicowYT-1.9-arm64.apk", armv7))
        assertTrue(isApkCompatibleWithAbis("lewicowYT-1.9-arm64.apk", arm64))
        assertTrue(isApkCompatibleWithAbis("lewicowYT-1.9-armv7.apk", arm64))
        assertFalse(isApkCompatibleWithAbis("lewicowYT-1.9-x86_64.apk", arm64))
        assertTrue(isApkCompatibleWithAbis("lewicowYT-1.9-arm64.apk", emptyList()))
    }

    private fun release(
        version: String,
        hasApk: Boolean,
        prerelease: Boolean = false,
        body: String = "Informacje o wydaniu",
    ): JSONObject = JSONObject()
        .put("tag_name", "v$version")
        .put("draft", false)
        .put("prerelease", prerelease)
        .put(
            "html_url",
            "https://github.com/emmunioo/lewicowyt/releases/tag/v$version",
        )
        .put("body", body)
        .put(
            "assets",
            JSONArray().apply {
                if (hasApk) {
                    put(
                        JSONObject()
                            .put("name", "lewicowYT-$version.apk")
                            .put("size", 13_000_000L)
                            .put(
                                "browser_download_url",
                                "https://github.com/emmunioo/lewicowyt/releases/" +
                                    "download/v$version/lewicowYT-$version.apk",
                            ),
                    )
                }
            },
        )

    @Test
    fun rejectsReleaseUrlsEscapingTheRepositoryWithDotSegments() {
        listOf(
            "https://github.com/emmunioo/lewicowyt/../inny/releases/download/v1/app.apk",
            "https://github.com/emmunioo/lewicowyt/%2e%2e/inny/app.apk",
            "https://github.com/emmunioo/lewicowyt/%2E%2E/inny/app.apk",
        ).forEach { url ->
            assertThrows(IllegalArgumentException::class.java) { requireSafeGitHubReleaseUrl(url, "emmunioo/lewicowyt") }
        }
    }

    @Test
    fun universalApkNamedForOldUpdaterIsFirstAndFitsEveryAbi() {
        // K-01: aktualizator 1.8-beta bierze pierwszy alfabetycznie .apk z wydania (bez ABI)
        val names = listOf(
            "lewicowYT-1.9-beta-arm64.apk",
            "lewicowYT-1.9-beta-armv7.apk",
            "lewicowYT-1.9-beta-x64.apk",
            "lewicowYT-1.9-beta-all-universal.apk",
        )
        assertEquals("lewicowYT-1.9-beta-all-universal.apk", names.sorted().first())
        listOf(listOf("arm64-v8a"), listOf("armeabi-v7a", "armeabi"), listOf("x86_64")).forEach { abis ->
            assertTrue(isApkCompatibleWithAbis("lewicowYT-1.9-beta-all-universal.apk", abis))
        }
        assertFalse(isApkCompatibleWithAbis("lewicowYT-1.9-beta-arm64.apk", listOf("armeabi-v7a")))
    }
}
