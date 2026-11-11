package pl.lewicowyt.notifier.images

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Zapis obrazu do galerii urządzenia (folder Pictures/lewicowYT). Na Androidzie 10+ przez MediaStore
 * bez żadnych uprawnień (scoped storage); poniżej — bezpośredni plik (wymaga WRITE_EXTERNAL_STORAGE,
 * o które prosi warstwa UI przed wywołaniem). Nic nie wysyła na zewnątrz — plik ląduje lokalnie.
 */
object MediaImageSaver {

    private const val SUBDIR = "lewicowYT"

    sealed interface Result {
        data class Saved(val displayName: String) : Result
        data class Failure(val reason: String) : Result
    }

    suspend fun save(
        context: Context,
        bytes: ByteArray,
        baseName: String,
        extension: String,
        mime: String,
    ): Result = withContext(Dispatchers.IO) {
        val displayName = "${sanitize(baseName)}.$extension"
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveViaMediaStore(context, bytes, displayName, mime)
            else saveLegacy(context, bytes, displayName)
        }.getOrElse { Result.Failure(it.message ?: it.javaClass.simpleName) }
    }

    private fun saveViaMediaStore(context: Context, bytes: ByteArray, displayName: String, mime: String): Result {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$SUBDIR")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: return Result.Failure("MediaStore odmówił zapisu")
        // L-8: każdy błąd w trakcie zapisu kasuje wpis — inaczej zostaje wiszący rekord z IS_PENDING=1.
        return try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IOException("Nie udało się otworzyć strumienia zapisu")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            Result.Saved(displayName)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            Result.Failure(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun saveLegacy(context: Context, bytes: ByteArray, displayName: String): Result {
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), SUBDIR)
        if (!dir.exists() && !dir.mkdirs()) return Result.Failure("Brak dostępu do Pictures (uprawnienie?)")
        // L-8: nie nadpisuj istniejącego pliku — dobierz unikalną nazwę (…-1, …-2).
        val file = uniqueFile(dir, displayName)
        file.writeBytes(bytes)
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
        return Result.Saved(file.name)
    }

    private fun uniqueFile(dir: File, displayName: String): File {
        var candidate = File(dir, displayName)
        if (!candidate.exists()) return candidate
        val dot = displayName.lastIndexOf('.')
        val base = if (dot > 0) displayName.substring(0, dot) else displayName
        val ext = if (dot > 0) displayName.substring(dot) else ""
        var i = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base-$i$ext")
            i++
        }
        return candidate
    }

    private fun sanitize(name: String): String =
        name.trim().replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifEmpty { "mem" }.take(80)
}
