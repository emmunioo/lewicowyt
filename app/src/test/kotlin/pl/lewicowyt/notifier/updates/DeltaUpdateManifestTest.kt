package pl.lewicowyt.notifier.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DeltaUpdateManifestTest {
    private val sourceHash = "a".repeat(64)
    private val patchHash = "b".repeat(64)
    private val targetHash = "c".repeat(64)

    @Test fun parsesValidManifest() {
        val parsed = DeltaUpdateManifestParser.parse(validJson())
        assertEquals(1, parsed.schemaVersion)
        assertEquals("1.7-beta", parsed.target.versionName)
        assertEquals(1, parsed.deltas.size)
    }

    @Test fun rejectsUnknownSchema() {
        assertThrows(IllegalArgumentException::class.java) {
            DeltaUpdateManifestParser.parse(validJson().replace("\"schemaVersion\":1", "\"schemaVersion\":2"))
        }
    }

    @Test fun rejectsPathTraversalInPatchName() {
        assertThrows(IllegalArgumentException::class.java) {
            DeltaUpdateManifestParser.parse(validJson().replace("update.xdelta", "../update.xdelta"))
        }
    }

    @Test fun rejectsPathSeparatorInApkName() {
        assertThrows(IllegalArgumentException::class.java) {
            DeltaUpdateManifestParser.parse(validJson().replace("app.apk", "folder/app.apk"))
        }
    }

    @Test fun rejectsUnsupportedAlgorithm() {
        assertThrows(IllegalArgumentException::class.java) {
            DeltaUpdateManifestParser.parse(validJson().replace(XDELTA_ALGORITHM, "bsdiff"))
        }
    }

    @Test fun rejectsInvalidSha256() {
        assertThrows(IllegalArgumentException::class.java) {
            DeltaUpdateManifestParser.parse(validJson().replace(sourceHash, "1234"))
        }
    }

    @Test fun rejectsZeroPatchSize() {
        assertThrows(IllegalArgumentException::class.java) {
            DeltaUpdateManifestParser.parse(validJson().replace("\"patchSize\":700", "\"patchSize\":0"))
        }
    }

    @Test fun selectsExactInstalledApk() {
        val result = selectDeltaUpdate(manifest(), update(), installed(), null)
        assertTrue(result is DeltaSelectionResult.UseDelta)
    }

    @Test fun rejectsDifferentSourceHash() {
        val result = selectDeltaUpdate(
            manifest(),
            update(),
            installed().copy(apkSha256 = "d".repeat(64)),
            null,
        )
        assertEquals(
            DeltaFallbackReason.DELTA_SOURCE_HASH_MISMATCH,
            (result as DeltaSelectionResult.UseFullApk).reason,
        )
    }

    @Test fun skipsPatchWithoutTwentyPercentSavings() {
        val entry = manifest().deltas.single().copy(patchSize = 801)
        val changed = manifest().copy(deltas = listOf(entry))
        val changedUpdate = update(patchSize = 801)
        val result = selectDeltaUpdate(changed, changedUpdate, installed(), null)
        assertEquals(
            DeltaFallbackReason.DELTA_NOT_WORTH_IT,
            (result as DeltaSelectionResult.UseFullApk).reason,
        )
    }

    @Test fun acceptsExactlyTwentyPercentSavings() {
        assertTrue(deltaHasMinimumSavings(800, 1000))
    }

    @Test fun rejectsMissingPatchAsset() {
        val result = selectDeltaUpdate(
            manifest(),
            update().copy(releaseAssets = emptyMap()),
            installed(),
            null,
        )
        assertEquals(
            DeltaFallbackReason.DELTA_NOT_AVAILABLE,
            (result as DeltaSelectionResult.UseFullApk).reason,
        )
    }

    @Test fun rejectsManifestTargetDifferentFromOfficialApk() {
        val changed = manifest().copy(target = manifest().target.copy(apkSha256 = "d".repeat(64)))
        val result = selectDeltaUpdate(changed, update(), installed(), null)
        assertEquals(
            DeltaFallbackReason.DELTA_MANIFEST_INVALID,
            (result as DeltaSelectionResult.UseFullApk).reason,
        )
    }

    @Test fun rejectsPatchMetadataDifferentFromGitHubAsset() {
        val asset = update().releaseAssets.getValue("update.xdelta").copy(sizeBytes = 699)
        val result = selectDeltaUpdate(
            manifest(),
            update().copy(releaseAssets = mapOf(asset.name to asset)),
            installed(),
            null,
        )
        assertEquals(
            DeltaFallbackReason.DELTA_MANIFEST_INVALID,
            (result as DeltaSelectionResult.UseFullApk).reason,
        )
    }

    @Test fun acceptsPatchWhenGitHubDoesNotExposeOptionalAssetDigest() {
        val asset = update().releaseAssets.getValue("update.xdelta").copy(sha256Digest = null)
        val result = selectDeltaUpdate(
            manifest(),
            update().copy(releaseAssets = mapOf(asset.name to asset)),
            installed(),
            null,
        )
        assertTrue(result is DeltaSelectionResult.UseDelta)
    }

    @Test fun skipsPreviouslyRejectedDeterministicCandidate() {
        val selected = selectDeltaUpdate(manifest(), update(), installed(), null)
            as DeltaSelectionResult.UseDelta
        val repeated = selectDeltaUpdate(manifest(), update(), installed(), selected.selected.fingerprint)
        assertEquals(
            DeltaFallbackReason.DELTA_PREVIOUSLY_REJECTED,
            (repeated as DeltaSelectionResult.UseFullApk).reason,
        )
    }

    @Test fun fingerprintIsStableAndIncludesTargetVersion() {
        val first = deltaFingerprint(sourceHash, patchHash, targetHash, "1.7-beta")
        assertEquals(first, deltaFingerprint(sourceHash, patchHash, targetHash, "1.7-beta"))
        assertNotEquals(first, deltaFingerprint(sourceHash, patchHash, targetHash, "1.7.1-beta"))
    }

    @Test fun safeAssetNameRejectsTraversalAndControlCharacters() {
        assertTrue(isSafeReleaseAssetName("lewicowYT-1.6.1-to-1.7.xdelta"))
        assertFalse(isSafeReleaseAssetName("../patch.xdelta"))
        assertFalse(isSafeReleaseAssetName("patch\n.xdelta"))
    }

    private fun manifest(): DeltaUpdateManifest = DeltaUpdateManifestParser.parse(validJson())

    private fun installed() = InstalledApkIdentity("1.6.1-beta", 17, sourceHash)

    private fun update(patchSize: Long = 700): AvailableUpdate {
        val patch = ReleaseAsset(
            name = "update.xdelta",
            downloadUrl = "https://github.com/emmunioo/lewicowyt/releases/download/v1.7-beta/update.xdelta",
            sizeBytes = patchSize,
            sha256Digest = patchHash,
        )
        return AvailableUpdate(
            version = "1.7-beta",
            releasePageUrl = "https://github.com/emmunioo/lewicowyt/releases/tag/v1.7-beta",
            apkDownloadUrl = "https://github.com/emmunioo/lewicowyt/releases/download/v1.7-beta/app.apk",
            apkName = "app.apk",
            apkSizeBytes = 1000,
            sha256Digest = targetHash,
            releaseNotes = "",
            releaseAssets = mapOf(patch.name to patch),
        )
    }

    private fun validJson(): String = """
        {
          "schemaVersion":1,
          "target":{
            "versionName":"1.7-beta",
            "versionCode":18,
            "apkName":"app.apk",
            "apkSha256":"$targetHash",
            "apkSize":1000
          },
          "deltas":[{
            "algorithm":"$XDELTA_ALGORITHM",
            "fromVersionName":"1.6.1-beta",
            "fromVersionCode":17,
            "fromApkSha256":"$sourceHash",
            "patchName":"update.xdelta",
            "patchSha256":"$patchHash",
            "patchSize":700
          }]
        }
    """.trimIndent()
}
