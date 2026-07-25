package pl.lewicowyt.notifier.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.Request
import pl.lewicowyt.notifier.MainActivity
import pl.lewicowyt.notifier.R
import pl.lewicowyt.notifier.data.DataRetentionPolicy
import pl.lewicowyt.notifier.data.LocalDatabase
import pl.lewicowyt.notifier.model.Creator
import pl.lewicowyt.notifier.model.HistoryItem
import pl.lewicowyt.notifier.model.VideoEntry
import pl.lewicowyt.notifier.model.VideoKind
import pl.lewicowyt.notifier.network.PrivacyHttpClient

data class NotificationCandidate(
    val creator: Creator,
    val entry: VideoEntry,
    val kind: VideoKind,
)

data class NotificationDeliveryResult(
    val systemNotificationsSent: Int = 0,
    val deliveredVideoIds: Set<String> = emptySet(),
)

class NotificationHelper(
    private val context: Context,
    private val database: LocalDatabase,
) {
    private val thumbnailClient by lazy {
        PrivacyHttpClient.get(context).newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(7, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Nowe materiały YouTube",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description =
                "Powiadomienia o nowych filmach, Shortach i transmisjach wybranych twórców"
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    suspend fun notifyBatch(
        candidates: List<NotificationCandidate>,
        canDeliver: suspend (NotificationCandidate) -> Boolean = { true },
    ): NotificationDeliveryResult {
        if (candidates.isEmpty() || !hasNotificationPermission()) {
            return NotificationDeliveryResult()
        }
        val unique = candidates.distinctBy { it.entry.id }
            .sortedByDescending { it.entry.publishedAtMillis }
            .filter { canDeliver(it) }
        // Wybór kanałów może zmienić się podczas przygotowywania paczki.
        // Ponowny odczyt tuż przed wyborem typu powiadomienia zapobiega
        // zbiorczemu oznaczeniu odznaczonych twórców jako dostarczonych.
        val deliverable = unique.filter { canDeliver(it) }
        if (deliverable.isEmpty()) return NotificationDeliveryResult()
        return if (!usesSummaryNotification(deliverable.size)) {
            val deliveredIds = coroutineScope {
                deliverable.map { candidate ->
                    async(Dispatchers.IO) {
                        try {
                            candidate.entry.id.takeIf {
                                notifyDirect(candidate) { canDeliver(candidate) }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        }
                    }
                }.awaitAll().filterNotNull().toSet()
            }
            NotificationDeliveryResult(
                systemNotificationsSent = deliveredIds.size,
                deliveredVideoIds = deliveredIds,
            )
        } else {
            if (notifySummary(deliverable.size)) {
                NotificationDeliveryResult(
                    systemNotificationsSent = 1,
                    deliveredVideoIds = deliverable.mapTo(mutableSetOf()) { it.entry.id },
                )
            } else {
                NotificationDeliveryResult()
            }
        }
    }

    private suspend fun notifyDirect(
        candidate: NotificationCandidate,
        canDeliver: suspend () -> Boolean,
    ): Boolean {
        if (!YOUTUBE_VIDEO_ID.matches(candidate.entry.id)) return false
        val notificationId = database.getOrCreateNotificationId(candidate.entry.id)
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            Intent(
                Intent.ACTION_VIEW,
                "https://www.youtube.com/watch?v=${candidate.entry.id}".toUri(),
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = when (candidate.kind) {
            VideoKind.LIVE -> "Transmisja na żywo: ${candidate.creator.name}"
            VideoKind.UPCOMING -> "Zaplanowana transmisja: ${candidate.creator.name}"
            VideoKind.STREAM_ARCHIVE -> "Nowy zapis transmisji: ${candidate.creator.name}"
            VideoKind.SHORT -> "Nowy Short: ${candidate.creator.name}"
            VideoKind.VIDEO, VideoKind.UNKNOWN -> "Nowy film: ${candidate.creator.name}"
        }
        val thumbnail = try {
            loadThumbnail(candidate.entry.id)
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
        if (!canDeliver() || !hasNotificationPermission()) return false

        if (thumbnail != null) {
            val notificationWithImage = directNotificationBuilder(
                title = title,
                text = candidate.entry.title,
                pendingIntent = pendingIntent,
            )
                .setLargeIcon(thumbnail)
                .setStyle(
                    NotificationCompat.BigPictureStyle()
                        .bigPicture(thumbnail)
                        .bigLargeIcon(null as Bitmap?),
                )
                .build()
            if (show(notificationId, notificationWithImage)) return true
        }

        val notificationWithoutImage = directNotificationBuilder(
            title = title,
            text = candidate.entry.title,
            pendingIntent = pendingIntent,
        )
            .setStyle(NotificationCompat.BigTextStyle().bigText(candidate.entry.title))
            .build()
        return show(notificationId, notificationWithoutImage)
    }

    fun cancelAll() {
        NotificationManagerCompat.from(context).cancelAll()
    }

    private fun directNotificationBuilder(
        title: String,
        text: String,
        pendingIntent: PendingIntent,
    ): NotificationCompat.Builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setGroup(GROUP_KEY)
            .setTimeoutAfter(NOTIFICATION_TIMEOUT_MILLIS)

    private fun notifySummary(count: Int): Boolean {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_NOTIFICATIONS, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            SUMMARY_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Nowe materiały ($count)")
            .setContentText("Dotknij, aby otworzyć sekcję Powiadomienia")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    polishNewMaterialsSentence(count) +
                        " Wszystkie znajdziesz osobno w sekcji Powiadomienia.",
                ),
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setTimeoutAfter(NOTIFICATION_TIMEOUT_MILLIS)
            .build()
        return show(SUMMARY_NOTIFICATION_ID, notification)
    }

    private fun loadThumbnail(videoId: String): Bitmap? {
        val urls = listOf(
            "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg",
            "https://i.ytimg.com/vi/$videoId/hq720.jpg",
        )
        for (url in urls) {
            loadJpegFromNetwork(url)?.let { return it }
        }
        return null
    }

    private fun loadJpegFromNetwork(url: String): Bitmap? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "image/jpeg,image/*;q=0.8")
            .get()
            .build()
        val bytes = thumbnailClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body
            val contentLength = body.contentLength()
            if (contentLength > MAX_THUMBNAIL_BYTES) return null
            readLimited(body.byteStream()) ?: return null
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (
            bounds.outWidth <= 0 ||
            bounds.outHeight <= 0 ||
            bounds.outWidth < MIN_SOURCE_WIDTH ||
            bounds.outHeight < MIN_SOURCE_HEIGHT ||
            bounds.outWidth > MAX_SOURCE_DIMENSION ||
            bounds.outHeight > MAX_SOURCE_DIMENSION
        ) {
            return null
        }

        var sampleSize = 1
        while (
            bounds.outWidth / sampleSize > MAX_SOURCE_WIDTH ||
            bounds.outHeight / sampleSize > MAX_SOURCE_HEIGHT
        ) {
            sampleSize *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: return null
        return scaleForNotification(decoded)
    }

    private fun readLimited(input: InputStream): ByteArray? = input.use {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        var total = 0
        while (true) {
            val count = it.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_THUMBNAIL_BYTES) return null
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }

    private fun scaleForNotification(source: Bitmap): Bitmap {
        val scale = minOf(
            1f,
            NOTIFICATION_IMAGE_WIDTH.toFloat() / source.width,
            NOTIFICATION_IMAGE_HEIGHT.toFloat() / source.height,
        )
        if (scale >= 1f) return source
        val scaled = Bitmap.createScaledBitmap(
            source,
            (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== source) source.recycle()
        return scaled
    }

    private fun show(id: Int, notification: android.app.Notification): Boolean {
        if (!hasNotificationPermission()) return false
        return try {
            NotificationManagerCompat.from(context).notify(id, notification)
            true
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun hasNotificationPermission(): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(CHANNEL_ID)
        if (channel?.importance == NotificationManager.IMPORTANCE_NONE) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        const val MAX_DIRECT_NOTIFICATIONS = 3
        private const val CHANNEL_ID = "youtube_updates"
        private const val GROUP_KEY = "pl.lewicowyt.app.YOUTUBE_UPDATES"
        private const val SUMMARY_NOTIFICATION_ID = Int.MIN_VALUE
        private const val NOTIFICATION_TIMEOUT_MILLIS =
            DataRetentionPolicy.NOTIFICATION_DAYS * DataRetentionPolicy.DAY_MILLIS
        private const val MAX_THUMBNAIL_BYTES = 5 * 1024 * 1024
        private const val MAX_SOURCE_DIMENSION = 4_096
        private const val MIN_SOURCE_WIDTH = 640
        private const val MIN_SOURCE_HEIGHT = 360
        private const val MAX_SOURCE_WIDTH = 1_280
        private const val MAX_SOURCE_HEIGHT = 720
        private const val NOTIFICATION_IMAGE_WIDTH = 512
        private const val NOTIFICATION_IMAGE_HEIGHT = 288
        private val YOUTUBE_VIDEO_ID = Regex("""[A-Za-z0-9_-]{11}""")
    }
}

internal fun usesSummaryNotification(count: Int): Boolean =
    count > NotificationHelper.MAX_DIRECT_NOTIFICATIONS

internal fun polishNewMaterialsSentence(count: Int): String {
    if (count == 0) return "Nie pojawiły się nowe materiały."
    if (count == 1) return "Pojawił się 1 nowy materiał."
    val lastTwoDigits = count % 100
    val lastDigit = count % 10
    return if (lastTwoDigits !in 12..14 && lastDigit in 2..4) {
        "Pojawiły się $count nowe materiały."
    } else {
        "Pojawiło się $count nowych materiałów."
    }
}

fun HistoryItem.toNotificationCandidate(kind: VideoKind = this.kind): NotificationCandidate =
    NotificationCandidate(
        creator = Creator(creatorId, creatorName, emptyList()),
        entry = VideoEntry(
            id = videoId,
            title = title,
            url = url,
            publishedAtMillis = publishedAtMillis,
            author = creatorName,
            origin = origin,
        ),
        kind = kind,
    )
