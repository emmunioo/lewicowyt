package pl.lewicowyt.notifier.images

import android.content.Context
import android.graphics.Bitmap
import com.awxkee.jxlcoder.JxlCoder
import java.io.ByteArrayOutputStream
import java.io.InputStream
import org.json.JSONObject
import pl.lewicowyt.notifier.AppLog
import pl.lewicowyt.notifier.data.LocalDatabase

/** Awatary dołączone do APK. Baza przechowuje ich SHA-256 i wskazanie zasobu. */
object BundledAvatarStore {
    fun seedDatabase(context: Context, database: LocalDatabase) {
        val manifest = runCatching { readManifest(context) }
            .onFailure { error ->
                AppLog.warning("BundledAvatars", "Nie udało się odczytać manifestu awatarów", error)
            }
            .getOrNull() ?: return
        val repairPreferences = context.getSharedPreferences(
            AVATAR_REPAIR_PREFERENCES,
            Context.MODE_PRIVATE,
        )
        val needsProfileChannelRepair =
            repairPreferences.getInt(AVATAR_REPAIR_REVISION_KEY, 0) <
                AVATAR_REPAIR_REVISION
        manifest.entries.forEach { entry ->
            database.seedBundledCreatorAvatar(
                creatorId = entry.creatorId,
                assetUrl = assetUrl(entry.fileName),
                sha256 = entry.sha256,
                checkedAtMillis = manifest.generatedAtMillis,
                force = needsProfileChannelRepair &&
                    entry.creatorId == PROFILE_CHANNEL_REPAIR_CREATOR_ID,
            )
        }
        if (needsProfileChannelRepair) {
            repairPreferences.edit()
                .putInt(AVATAR_REPAIR_REVISION_KEY, AVATAR_REPAIR_REVISION)
                .commit()
        }
    }

    fun isBundledAvatarUrl(value: String): Boolean =
        value.startsWith(ASSET_URL_PREFIX) &&
            value.removePrefix(ASSET_URL_PREFIX).matches(SAFE_FILE_NAME)

    fun load(context: Context, assetUrl: String): Bitmap? {
        if (!isBundledAvatarUrl(assetUrl)) return null
        val fileName = assetUrl.removePrefix(ASSET_URL_PREFIX)
        val bytes = runCatching {
            context.applicationContext.assets.open("$ASSET_DIRECTORY/$fileName").use { input ->
                readLimited(input)
            }
        }.getOrNull() ?: return null
        if (bytes.isEmpty() || !JxlCoder.isJXL(bytes)) {
            return null
        }
        val bitmap = runCatching { JxlCoder.decode(bytes) }.getOrNull() ?: return null
        if (bitmap.width != AVATAR_SIZE_PX || bitmap.height != AVATAR_SIZE_PX) {
            bitmap.recycle()
            return null
        }
        return bitmap
    }

    private fun readManifest(context: Context): Manifest {
        val root = context.applicationContext.assets.open(MANIFEST_PATH)
            .bufferedReader(Charsets.UTF_8)
            .use { JSONObject(it.readText()) }
        require(root.getInt("schemaVersion") == MANIFEST_SCHEMA_VERSION)
        val generatedAt = root.getLong("generatedAtMillis")
        require(generatedAt > 0L)
        val avatars = root.getJSONArray("avatars")
        val entries = buildList {
            for (index in 0 until avatars.length()) {
                val item = avatars.getJSONObject(index)
                val creatorId = item.getString("creatorId")
                val fileName = item.getString("fileName")
                val sha256 = item.getString("sha256").lowercase()
                if (
                    creatorId.matches(SAFE_CREATOR_ID) &&
                    fileName.matches(SAFE_FILE_NAME) &&
                    sha256.matches(SHA_256)
                ) {
                    add(Entry(creatorId, fileName, sha256))
                }
            }
        }
        return Manifest(generatedAt, entries)
    }

    private fun assetUrl(fileName: String): String = "$ASSET_URL_PREFIX$fileName"

    private fun readLimited(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= MAX_BUNDLED_AVATAR_BYTES)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private data class Manifest(
        val generatedAtMillis: Long,
        val entries: List<Entry>,
    )

    private data class Entry(
        val creatorId: String,
        val fileName: String,
        val sha256: String,
    )

    private const val MANIFEST_SCHEMA_VERSION = 1
    private const val ASSET_DIRECTORY = "bundled_avatars"
    private const val MANIFEST_PATH = "$ASSET_DIRECTORY/manifest.json"
    private const val ASSET_URL_PREFIX = "asset://bundled-avatars/"
    private const val AVATAR_SIZE_PX = 176
    private const val MAX_BUNDLED_AVATAR_BYTES = 512 * 1024
    private const val AVATAR_REPAIR_PREFERENCES = "bundled_avatar_repair"
    private const val AVATAR_REPAIR_REVISION_KEY = "revision"
    private const val AVATAR_REPAIR_REVISION = 1
    private const val PROFILE_CHANNEL_REPAIR_CREATOR_ID = "myslec-glebiej"
    private val SAFE_CREATOR_ID = Regex("[a-z0-9][a-z0-9-]{0,79}")
    private val SAFE_FILE_NAME = Regex("[a-z0-9][a-z0-9-]{0,79}\\.jxl")
    private val SHA_256 = Regex("[0-9a-f]{64}")
}
