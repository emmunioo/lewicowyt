package pl.lewicowyt.notifier.images

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.awxkee.jxlcoder.JxlCoder
import com.awxkee.jxlcoder.JxlChannelsConfiguration
import com.awxkee.jxlcoder.JxlCompressionOption
import com.awxkee.jxlcoder.JxlEffort
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import pl.lewicowyt.notifier.BuildConfig
import pl.lewicowyt.notifier.data.DataRetentionPolicy
import pl.lewicowyt.notifier.network.PrivacyHttpClient

/**
 * Obraz jest dostępny od razu z pobranego JPG, a jego zamiana na JXL odbywa się
 * w tle. Plik źródłowy jest usuwany dopiero po zapisaniu i zweryfikowaniu JXL.
 * Pliki są adresowane SHA-256 zawartości, dlatego historia, powiadomienia i
 * awatary współdzielą jedną kopię identycznego obrazu niezależnie od URL.
 */
object JxlImageCache {
    private val memory = object : LruCache<String, Bitmap>(MEMORY_CACHE_KIB) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val conversionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val conversionSemaphore = Semaphore(1)
    private val conversionsInProgress = ConcurrentHashMap.newKeySet<String>()
    private val cacheGeneration = AtomicLong()
    private val cacheMutationLock = Any()

    suspend fun load(context: Context, url: String): Bitmap? = withContext(Dispatchers.IO) {
        if (BundledAvatarStore.isBundledAvatarUrl(url)) {
            synchronized(memory) { memory.get(url) }?.let { return@withContext it }
            return@withContext BundledAvatarStore.load(context, url)?.also { bitmap ->
                remember(url, bitmap)
            }
        }
        val generation = cacheGeneration.get()
        val directories = cacheDirectories(context)
        val urlKey = ImageContentAddressing.urlKey(url)
        val referenceFile = ImageContentAddressing.referenceFile(directories.references, urlKey)
        loadReferenced(referenceFile, directories.content)?.let {
            return@withContext it
        }
        loadUrlAddressedCache(
            context = context,
            urlKey = urlKey,
            directories = directories,
            referenceFile = referenceFile,
            generation = generation,
        )?.let { return@withContext it }

        val downloaded = download(context, url) ?: return@withContext null
        val bitmap = decodeOriginal(downloaded)
            ?: return@withContext null
        val contentKey = ImageContentAddressing.contentKey(downloaded)
        val jpgFile = ImageContentAddressing.contentFile(
            directories.content,
            contentKey,
            ORIGINAL_EXTENSION,
        )
        val jxlFile = ImageContentAddressing.contentFile(
            directories.content,
            contentKey,
            JXL_EXTENSION,
        )
        val stored = synchronized(cacheMutationLock) {
            if (generation != cacheGeneration.get()) return@synchronized false
            if (!jxlFile.isFile && !jpgFile.isFile) {
                writeAtomically(jpgFile, downloaded)
            }
            val contentExists = jxlFile.isFile || jpgFile.isFile
            contentExists &&
                writeAtomically(referenceFile, contentKey.toByteArray(Charsets.US_ASCII))
        }
        if (stored) {
            remember(contentKey, bitmap)
            if (!jxlFile.isFile) {
                scheduleConversion(contentKey, jpgFile, jxlFile)
            }
        }
        bitmap
    }

    data class DownloadedAvatar(
        val bytes: ByteArray,
        val sha256: String,
    )

    /** Pobiera dokładnie jeden obraz i sprawdza, czy CDN zwrócił avatar 176x176. */
    suspend fun downloadValidatedAvatar(context: Context, url: String): DownloadedAvatar? =
        withContext(Dispatchers.IO) {
            val bytes = download(context, url) ?: return@withContext null
            val bitmap = decodeOriginal(bytes) ?: return@withContext null
            val valid = bitmap.width == AVATAR_SIZE_PX && bitmap.height == AVATAR_SIZE_PX
            bitmap.recycle()
            if (!valid) return@withContext null
            DownloadedAvatar(bytes, ImageContentAddressing.contentKey(bytes))
        }

    /**
     * Zapisuje już pobrane bajty bez drugiego żądania sieciowego. JPG jest
     * podmieniany w tle na JXL 69 / effort 10 tak samo jak miniatury.
     */
    suspend fun cacheDownloaded(
        context: Context,
        url: String,
        bytes: ByteArray,
    ): Boolean = withContext(Dispatchers.IO) {
        if (BundledAvatarStore.isBundledAvatarUrl(url)) return@withContext false
        val parsedUrl = url.toHttpUrlOrNull() ?: return@withContext false
        if (!isAllowedImageUrl(parsedUrl)) return@withContext false
        val bitmap = decodeOriginal(bytes) ?: return@withContext false
        val generation = cacheGeneration.get()
        val directories = cacheDirectories(context)
        val contentKey = ImageContentAddressing.contentKey(bytes)
        val jpgFile = ImageContentAddressing.contentFile(
            directories.content,
            contentKey,
            ORIGINAL_EXTENSION,
        )
        val jxlFile = ImageContentAddressing.contentFile(
            directories.content,
            contentKey,
            JXL_EXTENSION,
        )
        val referenceFile = ImageContentAddressing.referenceFile(
            directories.references,
            ImageContentAddressing.urlKey(url),
        )
        val stored = synchronized(cacheMutationLock) {
            if (generation != cacheGeneration.get()) return@synchronized false
            if (!jxlFile.isFile && !jpgFile.isFile) writeAtomically(jpgFile, bytes)
            (jxlFile.isFile || jpgFile.isFile) && writeAtomically(
                referenceFile,
                contentKey.toByteArray(Charsets.US_ASCII),
            )
        }
        if (stored) {
            remember(contentKey, bitmap)
            if (!jxlFile.isFile) scheduleConversion(contentKey, jpgFile, jxlFile)
        } else {
            bitmap.recycle()
        }
        stored
    }

    /**
     * Najstarszy katalog nie miał jednoznacznego formatu. Cache v2 zachowujemy
     * i migrujemy leniwie podczas pierwszego użycia konkretnego URL-u, dzięki
     * czemu aktualizacja nie pobiera ani nie kompresuje obrazów ponownie.
     */
    fun migrateExisting(context: Context) {
        deleteCacheDirectory(context, LEGACY_CACHE_DIRECTORY)
    }

    /**
     * Cache jest wydzielonym katalogiem, więc można bezpiecznie usuwać zapisane
     * miniatury i awatary starsze niż maksymalny zakres historii.
     */
    fun pruneExpired(
        context: Context,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        val cutoff = DataRetentionPolicy.cutoffs(nowMillis).historyBeforeMillis
        val directories = cacheDirectories(context)
        directories.references.listFiles()
            .orEmpty()
            .filter { it.isFile && it.lastModified() < cutoff }
            .forEach(File::delete)
        val referencedKeys = ImageContentAddressing.referencedContentKeys(directories.references)
        directories.content.listFiles()
            .orEmpty()
            .filter { file ->
                file.isFile &&
                    file.lastModified() < cutoff &&
                    file.nameWithoutExtension !in referencedKeys
            }
            .forEach(File::delete)
        File(context.applicationContext.cacheDir, URL_ADDRESSED_CACHE_DIRECTORY)
            .listFiles()
            .orEmpty()
            .filter { it.isFile && it.lastModified() < cutoff }
            .forEach(File::delete)
    }

    /**
     * Usuwa wyłącznie odtwarzalną pamięć obrazów aplikacji. Semafor pozwala
     * dokończyć bieżący zapis przed skasowaniem katalogu.
     */
    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        synchronized(cacheMutationLock) {
            cacheGeneration.incrementAndGet()
            synchronized(memory) { memory.evictAll() }
            deleteCacheDirectory(context, CACHE_DIRECTORY)
            deleteCacheDirectory(context, URL_ADDRESSED_CACHE_DIRECTORY)
            deleteCacheDirectory(context, LEGACY_CACHE_DIRECTORY)
        }
    }

    private fun loadReferenced(
        referenceFile: File,
        contentDirectory: File,
    ): Bitmap? {
        val contentKey = ImageContentAddressing.readReference(referenceFile)
        if (contentKey == null) {
            if (referenceFile.exists()) referenceFile.delete()
            return null
        }
        synchronized(memory) { memory.get(contentKey) }?.let { bitmap ->
            touch(referenceFile)
            return bitmap
        }

        val jxlFile = ImageContentAddressing.contentFile(
            contentDirectory,
            contentKey,
            JXL_EXTENSION,
        )
        val jpgFile = ImageContentAddressing.contentFile(
            contentDirectory,
            contentKey,
            ORIGINAL_EXTENSION,
        )
        decodeJxl(jxlFile)?.let { bitmap ->
            jpgFile.delete()
            touch(referenceFile)
            touch(jxlFile)
            remember(contentKey, bitmap)
            return bitmap
        }

        if (jxlFile.exists()) jxlFile.delete()
        decodeOriginal(jpgFile)?.let { bitmap ->
            touch(referenceFile)
            touch(jpgFile)
            remember(contentKey, bitmap)
            scheduleConversion(contentKey, jpgFile, jxlFile)
            return bitmap
        }

        if (jpgFile.exists()) jpgFile.delete()
        referenceFile.delete()
        return null
    }

    private fun loadUrlAddressedCache(
        context: Context,
        urlKey: String,
        directories: CacheDirectories,
        referenceFile: File,
        generation: Long,
    ): Bitmap? {
        val legacyDirectory = File(
            context.applicationContext.cacheDir,
            URL_ADDRESSED_CACHE_DIRECTORY,
        )
        if (!legacyDirectory.isDirectory) return null
        val candidates = listOf(
            LegacyImage(File(legacyDirectory, "$urlKey.$JXL_EXTENSION"), JXL_EXTENSION),
            LegacyImage(File(legacyDirectory, "$urlKey.$ORIGINAL_EXTENSION"), ORIGINAL_EXTENSION),
            LegacyImage(File(legacyDirectory, urlKey), ORIGINAL_EXTENSION),
        )
        for (candidate in candidates) {
            val source = candidate.file
            if (!source.isFile || source.length() !in 1..MAX_IMAGE_BYTES.toLong()) continue
            val bytes = runCatching { source.readBytes() }.getOrNull() ?: continue
            val bitmap = if (candidate.extension == JXL_EXTENSION) {
                if (!JxlCoder.isJXL(bytes)) continue
                runCatching { JxlCoder.decode(bytes) }.getOrNull()
                    ?.takeIf { isSafeDecodedSize(it.width, it.height) }
            } else {
                decodeOriginal(bytes)
            } ?: continue
            val contentKey = ImageContentAddressing.contentKey(bytes)
            val target = ImageContentAddressing.contentFile(
                directories.content,
                contentKey,
                candidate.extension,
            )
            val migrated = synchronized(cacheMutationLock) {
                if (generation != cacheGeneration.get()) return@synchronized false
                val contentStored = if (target.isFile && target.length() == bytes.size.toLong()) {
                    true
                } else {
                    if (target.exists()) target.delete()
                    writeAtomically(target, bytes)
                }
                contentStored && writeAtomically(
                    referenceFile,
                    contentKey.toByteArray(Charsets.US_ASCII),
                )
            }
            if (!migrated) {
                bitmap.recycle()
                continue
            }
            source.delete()
            remember(contentKey, bitmap)
            if (candidate.extension == ORIGINAL_EXTENSION) {
                val jxlFile = ImageContentAddressing.contentFile(
                    directories.content,
                    contentKey,
                    JXL_EXTENSION,
                )
                scheduleConversion(contentKey, target, jxlFile)
            }
            return bitmap
        }
        return null
    }

    private fun scheduleConversion(
        key: String,
        sourceFile: File,
        jxlFile: File,
    ) {
        val generation = cacheGeneration.get()
        synchronized(cacheMutationLock) {
            if (generation != cacheGeneration.get()) return
            if (jxlFile.isFile) {
                sourceFile.delete()
                return
            }
        }
        val conversionKey = "$generation:$key"
        if (!conversionsInProgress.add(conversionKey)) return
        conversionScope.launch {
            try {
                conversionSemaphore.withPermit {
                    if (generation != cacheGeneration.get()) return@withPermit
                    synchronized(cacheMutationLock) {
                        if (generation != cacheGeneration.get()) return@withPermit
                        if (jxlFile.isFile) {
                            sourceFile.delete()
                            return@withPermit
                        }
                    }
                    val bitmap = decodeOriginal(sourceFile) ?: return@withPermit
                    val encoded = try {
                        runCatching {
                            JxlCoder.encode(
                                bitmap,
                                JxlChannelsConfiguration.RGB,
                                JxlCompressionOption.LOSSY,
                                JxlEffort.GLACIER,
                                JXL_QUALITY,
                            )
                        }.getOrNull()
                    } finally {
                        bitmap.recycle()
                    } ?: return@withPermit
                    if (!JxlCoder.isJXL(encoded) || JxlCoder.getSize(encoded) == null) {
                        return@withPermit
                    }
                    synchronized(cacheMutationLock) {
                        if (generation != cacheGeneration.get()) return@withPermit
                        if (!writeAtomically(jxlFile, encoded)) return@withPermit
                        sourceFile.delete()
                    }
                }
            } finally {
                conversionsInProgress.remove(conversionKey)
            }
        }
    }

    private fun decodeJxl(file: File): Bitmap? {
        if (!file.isFile || file.length() !in 1..MAX_IMAGE_BYTES.toLong()) return null
        return runCatching { JxlCoder.decode(file.readBytes()) }
            .getOrNull()
            ?.takeIf { isSafeDecodedSize(it.width, it.height) }
    }

    private fun decodeOriginal(file: File): Bitmap? {
        if (!file.isFile || file.length() !in 1..MAX_IMAGE_BYTES.toLong()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (!isSafeImageSize(bounds.outWidth, bounds.outHeight)) return null
        val options = decodeOptions(bounds.outWidth, bounds.outHeight)
        return BitmapFactory.decodeFile(file.absolutePath, options)
            ?.takeIf { isSafeDecodedSize(it.width, it.height) }
    }

    private fun decodeOriginal(bytes: ByteArray): Bitmap? {
        if (bytes.size !in 1..MAX_IMAGE_BYTES) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (!isSafeImageSize(bounds.outWidth, bounds.outHeight)) return null
        val options = decodeOptions(bounds.outWidth, bounds.outHeight)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?.takeIf { isSafeDecodedSize(it.width, it.height) }
    }

    private fun decodeOptions(width: Int, height: Int): BitmapFactory.Options {
        var sampleSize = 1
        while (
            width / sampleSize > MAX_DECODED_DIMENSION ||
            height / sampleSize > MAX_DECODED_DIMENSION ||
            (width / sampleSize).toLong() * (height / sampleSize).toLong() >
            MAX_DECODED_PIXELS
        ) {
            sampleSize *= 2
        }
        return BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
    }

    private fun remember(contentKey: String, bitmap: Bitmap) {
        synchronized(memory) { memory.put(contentKey, bitmap) }
    }

    private fun cacheDirectories(context: Context): CacheDirectories {
        val root = File(context.applicationContext.cacheDir, CACHE_DIRECTORY).apply { mkdirs() }
        return CacheDirectories(
            references = File(root, REFERENCES_DIRECTORY).apply { mkdirs() },
            content = File(root, CONTENT_DIRECTORY).apply { mkdirs() },
        )
    }

    private fun touch(file: File) {
        if (file.isFile) {
            file.setLastModified(System.currentTimeMillis())
        }
    }

    private fun deleteCacheDirectory(context: Context, directoryName: String) {
        val appCache = context.applicationContext.cacheDir
        val target = File(appCache, directoryName)
        if (target.parentFile == appCache) {
            target.deleteRecursively()
        }
    }

    private fun download(context: Context, initialUrl: String): ByteArray? {
        var currentUrl = initialUrl.toHttpUrlOrNull() ?: return null
        val client = PrivacyHttpClient.get(context).newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        repeat(MAX_REDIRECTS + 1) {
            if (!isAllowedImageUrl(currentUrl)) return null
            val request = Request.Builder()
                .url(currentUrl)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 " +
                        "Chrome/136 Mobile Safari/537.36 " +
                        "lewicowYT/${BuildConfig.VERSION_NAME}",
                )
                .get()
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    if (response.code in REDIRECT_CODES) {
                        val location = response.header("Location") ?: return null
                        currentUrl = response.request.url.resolve(location) ?: return null
                        return@repeat
                    }
                    if (!response.isSuccessful) return null
                    val body = response.body
                    val contentLength = body.contentLength()
                    if (contentLength > MAX_IMAGE_BYTES) return null
                    return body.byteStream().use(::readLimited)
                }
            } catch (_: Exception) {
                return null
            }
        }
        return null
    }

    private fun readLimited(input: InputStream): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_IMAGE_BYTES) return null
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun isAllowedImageUrl(url: HttpUrl): Boolean {
        if (!url.isHttps) return false
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.port != 443) return false
        val host = url.host.trimEnd('.')
        return IMAGE_HOST_SUFFIXES.any { suffix ->
            host == suffix || host.endsWith(".$suffix")
        }
    }

    private fun writeAtomically(target: File, bytes: ByteArray): Boolean {
        val temporary = File(target.parentFile, "${target.name}.tmp")
        return runCatching {
            temporary.writeBytes(bytes)
            if (target.exists() && !target.delete()) return@runCatching false
            if (!temporary.renameTo(target)) {
                target.writeBytes(bytes)
                temporary.delete()
            }
            target.isFile && target.length() == bytes.size.toLong()
        }.getOrDefault(false).also { success ->
            if (!success) temporary.delete()
        }
    }

    private fun isSafeImageSize(width: Int, height: Int): Boolean =
        width in 1..MAX_IMAGE_DIMENSION &&
            height in 1..MAX_IMAGE_DIMENSION &&
            width.toLong() * height.toLong() <= MAX_IMAGE_PIXELS

    private fun isSafeDecodedSize(width: Int, height: Int): Boolean =
        width in 1..MAX_DECODED_DIMENSION &&
            height in 1..MAX_DECODED_DIMENSION &&
            width.toLong() * height.toLong() <= MAX_DECODED_PIXELS

    const val JXL_QUALITY = 69
    const val JXL_EFFORT = 10
    private const val AVATAR_SIZE_PX = 176
    private const val CACHE_DIRECTORY = "remote_images_v3"
    private const val URL_ADDRESSED_CACHE_DIRECTORY = "remote_images_v2"
    private const val LEGACY_CACHE_DIRECTORY = "remote_images"
    private const val REFERENCES_DIRECTORY = "references"
    private const val CONTENT_DIRECTORY = "content"
    private const val ORIGINAL_EXTENSION = "jpg"
    private const val JXL_EXTENSION = "jxl"
    private const val MEMORY_CACHE_KIB = 12 * 1024
    private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
    private const val MAX_IMAGE_DIMENSION = 4_096
    private const val MAX_IMAGE_PIXELS = 16_777_216L
    private const val MAX_DECODED_DIMENSION = 1_280
    private const val MAX_DECODED_PIXELS = 2_097_152L
    private const val MAX_REDIRECTS = 4
    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    private val IMAGE_HOST_SUFFIXES = setOf(
        "ytimg.com",
        "ggpht.com",
        "googleusercontent.com",
    )

    private data class CacheDirectories(
        val references: File,
        val content: File,
    )

    private data class LegacyImage(
        val file: File,
        val extension: String,
    )
}
