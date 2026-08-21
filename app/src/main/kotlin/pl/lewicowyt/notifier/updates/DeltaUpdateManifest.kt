package pl.lewicowyt.notifier.updates

import java.security.MessageDigest
import org.json.JSONObject

internal data class DeltaUpdateTarget(
    val versionName: String,
    val versionCode: Long,
    val apkName: String,
    val apkSha256: String,
    val apkSize: Long,
)

internal data class DeltaUpdateEntry(
    val algorithm: String,
    val fromVersionName: String,
    val fromVersionCode: Long,
    val fromApkSha256: String,
    val patchName: String,
    val patchSha256: String,
    val patchSize: Long,
)

internal data class DeltaUpdateManifest(
    val schemaVersion: Int,
    val target: DeltaUpdateTarget,
    val deltas: List<DeltaUpdateEntry>,
)

internal data class InstalledApkIdentity(
    val versionName: String,
    val versionCode: Long,
    val apkSha256: String,
)

internal data class SelectedDelta(
    val target: DeltaUpdateTarget,
    val entry: DeltaUpdateEntry,
    val asset: ReleaseAsset,
    val fingerprint: String,
)

internal enum class DeltaFallbackReason(val deterministic: Boolean = false) {
    DELTA_NOT_AVAILABLE,
    DELTA_MANIFEST_INVALID,
    DELTA_NOT_WORTH_IT,
    DELTA_SOURCE_HASH_MISMATCH,
    DELTA_DOWNLOAD_FAILED,
    DELTA_HTTP_ERROR,
    DELTA_PATCH_HASH_MISMATCH(true),
    DELTA_FORMAT_INVALID(true),
    DELTA_APPLY_FAILED(true),
    DELTA_TARGET_HASH_MISMATCH(true),
    DELTA_TARGET_SIGNATURE_INVALID(true),
    DELTA_TARGET_PACKAGE_INVALID(true),
    DELTA_TARGET_VERSION_INVALID(true),
    DELTA_NO_SPACE,
    DELTA_IO_ERROR,
    DELTA_DECODER_UNAVAILABLE,
    DELTA_CANCELLED,
    DELTA_PREVIOUSLY_REJECTED(true),
}

internal sealed interface DeltaSelectionResult {
    data class UseDelta(val selected: SelectedDelta) : DeltaSelectionResult
    data class UseFullApk(val reason: DeltaFallbackReason) : DeltaSelectionResult
}

internal object DeltaUpdateManifestParser {
    fun parse(json: String): DeltaUpdateManifest {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_DELTA_MANIFEST_BYTES)
        val root = JSONObject(json)
        require(root.getInt("schemaVersion") == DELTA_MANIFEST_SCHEMA_VERSION)
        val targetJson = root.getJSONObject("target")
        val target = DeltaUpdateTarget(
            versionName = requiredVersion(targetJson, "versionName"),
            versionCode = requiredPositiveLong(targetJson, "versionCode"),
            apkName = requiredAssetName(targetJson, "apkName", ".apk"),
            apkSha256 = requiredSha256(targetJson, "apkSha256"),
            apkSize = requiredSize(targetJson, "apkSize", MAX_RECONSTRUCTED_APK_BYTES),
        )
        val deltasJson = root.getJSONArray("deltas")
        require(deltasJson.length() <= MAX_DELTA_ENTRIES)
        val deltas = buildList(deltasJson.length()) {
            repeat(deltasJson.length()) { index ->
                val item = deltasJson.getJSONObject(index)
                val algorithm = item.getString("algorithm").trim()
                require(algorithm == XDELTA_ALGORITHM)
                add(
                    DeltaUpdateEntry(
                        algorithm = algorithm,
                        fromVersionName = requiredVersion(item, "fromVersionName"),
                        fromVersionCode = requiredPositiveLong(item, "fromVersionCode"),
                        fromApkSha256 = requiredSha256(item, "fromApkSha256"),
                        patchName = requiredAssetName(item, "patchName", ".xdelta"),
                        patchSha256 = requiredSha256(item, "patchSha256"),
                        patchSize = requiredSize(item, "patchSize", MAX_DELTA_PATCH_BYTES),
                    ),
                )
            }
        }
        return DeltaUpdateManifest(
            schemaVersion = DELTA_MANIFEST_SCHEMA_VERSION,
            target = target,
            deltas = deltas,
        )
    }

    private fun requiredVersion(json: JSONObject, key: String): String =
        json.getString(key).trim().also { value ->
            require(value.length in 1..MAX_VERSION_LENGTH)
            require(value.none(Char::isISOControl))
        }

    private fun requiredPositiveLong(json: JSONObject, key: String): Long =
        json.getLong(key).also { require(it > 0L) }

    private fun requiredSize(json: JSONObject, key: String, maximum: Long): Long =
        json.getLong(key).also { require(it in 1..maximum) }

    private fun requiredSha256(json: JSONObject, key: String): String =
        json.getString(key).trim().lowercase().also { require(SHA256.matches(it)) }

    private fun requiredAssetName(json: JSONObject, key: String, suffix: String): String =
        json.getString(key).trim().also { value ->
            require(isSafeReleaseAssetName(value))
            require(value.endsWith(suffix, ignoreCase = true))
        }
}

internal fun selectDeltaUpdate(
    manifest: DeltaUpdateManifest,
    update: AvailableUpdate,
    installed: InstalledApkIdentity,
    rejectedFingerprint: String?,
): DeltaSelectionResult {
    val target = manifest.target
    val officialTargetHash = update.sha256Digest?.lowercase()
        ?: return DeltaSelectionResult.UseFullApk(DeltaFallbackReason.DELTA_NOT_AVAILABLE)
    if (
        target.versionName != update.version ||
        target.apkName != update.apkName ||
        target.apkSize != update.apkSizeBytes ||
        target.apkSha256 != officialTargetHash
    ) {
        return DeltaSelectionResult.UseFullApk(DeltaFallbackReason.DELTA_MANIFEST_INVALID)
    }

    val entry = manifest.deltas.firstOrNull {
        it.fromVersionName == installed.versionName &&
            it.fromVersionCode == installed.versionCode
    } ?: return DeltaSelectionResult.UseFullApk(DeltaFallbackReason.DELTA_NOT_AVAILABLE)
    if (entry.fromApkSha256 != installed.apkSha256.lowercase()) {
        return DeltaSelectionResult.UseFullApk(DeltaFallbackReason.DELTA_SOURCE_HASH_MISMATCH)
    }
    if (!deltaHasMinimumSavings(entry.patchSize, target.apkSize)) {
        return DeltaSelectionResult.UseFullApk(DeltaFallbackReason.DELTA_NOT_WORTH_IT)
    }
    val asset = update.releaseAssets[entry.patchName]
        ?: return DeltaSelectionResult.UseFullApk(DeltaFallbackReason.DELTA_NOT_AVAILABLE)
    if (
        asset.sizeBytes != entry.patchSize ||
        (asset.sha256Digest != null && asset.sha256Digest.lowercase() != entry.patchSha256)
    ) {
        return DeltaSelectionResult.UseFullApk(DeltaFallbackReason.DELTA_MANIFEST_INVALID)
    }
    val fingerprint = deltaFingerprint(
        sourceSha256 = entry.fromApkSha256,
        patchSha256 = entry.patchSha256,
        targetSha256 = target.apkSha256,
        targetVersion = target.versionName,
    )
    if (fingerprint == rejectedFingerprint) {
        return DeltaSelectionResult.UseFullApk(DeltaFallbackReason.DELTA_PREVIOUSLY_REJECTED)
    }
    return DeltaSelectionResult.UseDelta(
        SelectedDelta(target, entry, asset, fingerprint),
    )
}

internal fun deltaHasMinimumSavings(patchSize: Long, apkSize: Long): Boolean {
    if (patchSize <= 0L || apkSize <= 0L || patchSize >= apkSize) return false
    return patchSize * 100L <= apkSize * (100L - MIN_DELTA_SAVINGS_PERCENT)
}

internal fun deltaFingerprint(
    sourceSha256: String,
    patchSha256: String,
    targetSha256: String,
    targetVersion: String,
): String {
    val input = listOf(sourceSha256, patchSha256, targetSha256, targetVersion).joinToString("|")
    return MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

internal const val DELTA_MANIFEST_ASSET_NAME = "lewicowYT-update.json"
internal const val XDELTA_ALGORITHM = "xdelta3-vcdiff"
internal const val DELTA_MANIFEST_SCHEMA_VERSION = 1
internal const val MIN_DELTA_SAVINGS_PERCENT = 20L
internal const val MAX_DELTA_MANIFEST_BYTES = 128 * 1024
internal const val MAX_DELTA_PATCH_BYTES = 100L * 1024L * 1024L
internal const val MAX_RECONSTRUCTED_APK_BYTES = 200L * 1024L * 1024L
internal const val MAX_DELTA_ENTRIES = 32
private const val MAX_VERSION_LENGTH = 100
private val SHA256 = Regex("""[a-f0-9]{64}""")
