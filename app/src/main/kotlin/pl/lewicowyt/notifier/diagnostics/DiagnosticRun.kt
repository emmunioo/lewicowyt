package pl.lewicowyt.notifier.diagnostics

import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal enum class DiagnosticSyncTrigger {
    EXACT_ALARM,
    RETRY,
    MANUAL,
    FIRST_SYNC,
    HISTORY_BACKFILL,
    DND_CATCHUP,
    WATCHDOG,
    REDELIVERED_FGS,
}

internal enum class DiagnosticReasonCode {
    DND_ACTIVE,
    NO_NETWORK,
    CELLULAR_DISABLED,
    EXACT_ALARM_PERMISSION_MISSING,
    NOTIFICATION_PERMISSION_MISSING,
    NOTIFICATION_POLICY_ACCESS_MISSING,
    NO_SELECTED_CREATORS,
    NO_ENABLED_CONTENT_TYPES,
    SYNC_ALREADY_RUNNING,
    FGS_START_FAILED,
    WATCHDOG_TIMEOUT,
    WAKELOCK_FAILURE,
    RETRY_SCHEDULED,
    TYPE_DISABLED,
    CREATOR_DISABLED,
    NOTIFICATION_DISABLED_FOR_TYPE,
    NOTIFICATION_DISABLED_FOR_CREATOR,
    NOT_YET_NOTIFICATION_TIME,
    ALREADY_DELIVERED,
    NOT_NEWER_THAN_CURSOR,
    INVALID_SOURCE_RESULT,
    DATABASE_ERROR,
    NETWORK_TIMEOUT,
    HTTP_ERROR,
    DND_DEFERRED,
    APP_UPDATE_REDIRECT_REJECTED,
    HOST_NOT_ALLOWED,
    SHA256_MISMATCH,
    PACKAGE_ID_MISMATCH,
    VERSION_CODE_INVALID,
    SIGNATURE_MISMATCH,
    ALARM_QUEUE_INCOMPLETE,
    SCHEDULE_ERROR,
    DELIVERY_FAILED,
    SOURCE_RESOLUTION_FAILED,
    RSS_SOURCE_FAILED,
    DATA_API_SOURCE_FAILED,
    WEB_SOURCE_FAILED,
    CHANNEL_TABS_UNAVAILABLE,
    API_PLAYLIST_ITEMS_NOT_FOUND,
    HISTORY_PAGE_LIMIT_REACHED,
    HISTORY_RANGE_INCOMPLETE,
    APP_NOT_AVAILABLE,
    BROWSER_NOT_AVAILABLE,
    NO_LINK_HANDLER,
    INVALID_LINK,
    LINK_LAUNCH_FAILED,
    DESCRIPTION_EMPTY,
    DESCRIPTION_STAGE_FAILED,
    DESCRIPTION_FETCH_FAILED,
    DESCRIPTION_SAVE_FAILED,
    OLDER_SEARCH_FAILED,
    OLDER_MATERIAL_UNAVAILABLE,
    OLDER_MATERIAL_CHANNEL_MISMATCH,
    OLDER_FAVORITE_SAVE_FAILED,
    FTS_QUERY_FAILED,
    DELTA_NOT_AVAILABLE,
    DELTA_MANIFEST_INVALID,
    DELTA_NOT_WORTH_IT,
    DELTA_SOURCE_HASH_MISMATCH,
    DELTA_DOWNLOAD_FAILED,
    DELTA_HTTP_ERROR,
    DELTA_PATCH_HASH_MISMATCH,
    DELTA_FORMAT_INVALID,
    DELTA_APPLY_FAILED,
    DELTA_TARGET_HASH_MISMATCH,
    DELTA_TARGET_SIGNATURE_INVALID,
    DELTA_TARGET_PACKAGE_INVALID,
    DELTA_TARGET_VERSION_INVALID,
    DELTA_NO_SPACE,
    DELTA_IO_ERROR,
    DELTA_DECODER_UNAVAILABLE,
    DELTA_CANCELLED,
    DELTA_PREVIOUSLY_REJECTED,
}

internal enum class DiagnosticNotificationResult {
    SENT,
    INBOX_ONLY,
    DEFERRED,
    SKIPPED,
}

internal enum class DiagnosticSyncStage {
    PREPARE,
    RSS,
    API,
    WEB,
    CLASSIFICATION,
    HISTORY,
    DATABASE,
    IMAGES,
    INBOX,
    NOTIFICATIONS,
    UPDATE,
    DESCRIPTION,
    FINISHED,
}

/**
 * Lekki kontekst jednego rzeczywistego przebiegu. Nie zawiera identyfikatora
 * urządzenia ani danych użytkownika. Czasy etapów są wall-clock i mogą się
 * nakładać, gdy źródła działają równolegle.
 */
internal class DiagnosticSyncRun private constructor(
    val syncId: String,
    val trigger: DiagnosticSyncTrigger,
    val retryOf: String?,
    private val startedAtNanos: Long,
    private val stageSink: (String) -> Unit,
) {
    private val currentStage = AtomicReference(DiagnosticSyncStage.PREPARE.name)
    private val stageNanos = ConcurrentHashMap<DiagnosticSyncStage, AtomicLong>()
    private val rssRequests = AtomicInteger()
    private val apiRequests = AtomicInteger()
    private val webRequests = AtomicInteger()
    private val rssErrors = AtomicInteger()
    private val apiErrors = AtomicInteger()
    private val webErrors = AtomicInteger()
    private val started = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)
    private val networkStarted = DiagnosticNetworkUsage.snapshot()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        event(
            name = "START",
            fields = mapOf(
                "trigger" to trigger.name,
                "retryOf" to retryOf,
            ),
        )
        stageSink(currentStage.get())
    }

    fun stage(stage: DiagnosticSyncStage) {
        currentStage.set(stage.name)
        stageSink(stage.name)
    }

    fun lastStage(): String = currentStage.get()

    fun addStageDuration(stage: DiagnosticSyncStage, durationNanos: Long) {
        if (durationNanos <= 0L) return
        stageNanos.getOrPut(stage) { AtomicLong() }.addAndGet(durationNanos)
        val millis = durationNanos / 1_000_000L
        if (millis >= slowOperationThresholdMillis(stage)) {
            event(
                name = "SLOW_OPERATION",
                level = DiagnosticLevel.WARNING,
                fields = mapOf("stage" to stage.name, "durationMs" to millis),
            )
        }
    }

    suspend fun <T> measure(stage: DiagnosticSyncStage, block: suspend () -> T): T {
        stage(stage)
        val started = System.nanoTime()
        return try {
            block()
        } finally {
            addStageDuration(stage, System.nanoTime() - started)
        }
    }

    fun sourceRequest(source: DiagnosticYouTubeSource) {
        when (source) {
            DiagnosticYouTubeSource.RSS -> rssRequests.incrementAndGet()
            DiagnosticYouTubeSource.DATA_API -> apiRequests.incrementAndGet()
            DiagnosticYouTubeSource.WEB -> webRequests.incrementAndGet()
        }
    }

    fun sourceError(source: DiagnosticYouTubeSource) {
        when (source) {
            DiagnosticYouTubeSource.RSS -> rssErrors.incrementAndGet()
            DiagnosticYouTubeSource.DATA_API -> apiErrors.incrementAndGet()
            DiagnosticYouTubeSource.WEB -> webErrors.incrementAndGet()
        }
    }

    fun event(
        name: String,
        category: DiagnosticCategory = DiagnosticCategory.SYNC,
        level: DiagnosticLevel = DiagnosticLevel.INFO,
        reason: DiagnosticReasonCode? = null,
        fields: Map<String, Any?> = emptyMap(),
        text: String? = null,
    ) {
        DiagnosticLogStore.event(
            category = category,
            level = level,
            name = name,
            syncId = syncId,
            reason = reason,
            fields = fields,
            text = text,
        )
    }

    fun notificationDecision(
        videoId: String,
        result: DiagnosticNotificationResult,
        reason: DiagnosticReasonCode? = null,
    ) {
        event(
            name = "NOTIFICATION_DECISION",
            category = DiagnosticCategory.SYNC,
            reason = reason,
            fields = mapOf(
                "video" to diagnosticYouTubeVideoUrl(videoId),
                "result" to result.name,
            ),
        )
    }

    fun finish(fields: Map<String, Any?> = emptyMap()) {
        if (!finished.compareAndSet(false, true)) return
        stage(DiagnosticSyncStage.FINISHED)
        val totalNanos = (System.nanoTime() - startedAtNanos).coerceAtLeast(0L)
        val timingFields = linkedMapOf<String, Any?>(
            "totalMs" to totalNanos / 1_000_000L,
        )
        DiagnosticSyncStage.entries.forEach { stage ->
            stageNanos[stage]?.get()?.let { nanos ->
                timingFields[stage.name.lowercase(Locale.ROOT) + "Ms"] = nanos / 1_000_000L
            }
        }
        event("SYNC_TIMING", fields = timingFields)
        event(
            "SOURCES",
            category = DiagnosticCategory.NETWORK,
            fields = mapOf(
                "rssRequests" to rssRequests.get(),
                "apiRequests" to apiRequests.get(),
                "webRequests" to webRequests.get(),
                "rssErrors" to rssErrors.get(),
                "apiErrors" to apiErrors.get(),
                "webErrors" to webErrors.get(),
            ),
        )
        val network = DiagnosticNetworkUsage.snapshot().deltaSince(networkStarted)
        event(
            "NETWORK_USAGE",
            category = DiagnosticCategory.NETWORK,
            fields = mapOf(
                "uploadedHttpBodyBytes" to network.uploadedBytes,
                "downloadedHttpBodyBytes" to network.downloadedBytes,
                "totalHttpBodyBytes" to network.totalBytes,
                "scope" to "HTTP_BODY_ONLY",
            ),
        )
        event("END", fields = fields + ("durationMs" to totalNanos / 1_000_000L))
    }

    companion object {
        fun create(
            trigger: DiagnosticSyncTrigger,
            retryOf: String? = null,
            stageSink: (String) -> Unit = {},
        ): DiagnosticSyncRun = DiagnosticSyncRun(
            syncId = DiagnosticSyncIdGenerator.next(),
            trigger = trigger,
            retryOf = retryOf?.takeIf(DIAGNOSTIC_SYNC_ID::matches),
            startedAtNanos = System.nanoTime(),
            stageSink = stageSink,
        )

        fun resume(
            syncId: String,
            trigger: DiagnosticSyncTrigger,
            retryOf: String? = null,
            stageSink: (String) -> Unit = {},
        ): DiagnosticSyncRun = DiagnosticSyncRun(
            syncId = syncId.takeIf(DIAGNOSTIC_SYNC_ID::matches)
                ?: DiagnosticSyncIdGenerator.next(),
            trigger = trigger,
            retryOf = retryOf?.takeIf(DIAGNOSTIC_SYNC_ID::matches),
            startedAtNanos = System.nanoTime(),
            stageSink = stageSink,
        )
    }
}

internal object DiagnosticSyncIdGenerator {
    private val random = SecureRandom()
    private val sequence = AtomicInteger()

    fun next(): String {
        val randomPart = random.nextInt() xor sequence.incrementAndGet()
        return "%08X".format(Locale.ROOT, randomPart)
    }
}

internal fun formatDiagnosticEvent(
    name: String,
    syncId: String? = null,
    reason: DiagnosticReasonCode? = null,
    fields: Map<String, Any?> = emptyMap(),
    text: String? = null,
): String = buildString {
    append(name.trim().uppercase(Locale.ROOT).take(MAX_EVENT_NAME_CHARS))
    syncId?.takeIf(DIAGNOSTIC_SYNC_ID::matches)?.let {
        append(" | sync=")
        append(it)
    }
    reason?.let {
        append(" | reason=")
        append(it.name)
    }
    fields.forEach { (rawKey, rawValue) ->
        val key = rawKey.takeIf(DIAGNOSTIC_FIELD_NAME::matches) ?: return@forEach
        val value = rawValue?.toString()?.take(MAX_FIELD_VALUE_CHARS) ?: return@forEach
        append(" | ")
        append(key)
        append('=')
        append(value)
    }
    text?.takeIf(String::isNotBlank)?.let {
        append(" | ")
        append(it.take(MAX_TEXT_CHARS))
    }
}

private fun slowOperationThresholdMillis(stage: DiagnosticSyncStage): Long = when (stage) {
    DiagnosticSyncStage.WEB,
    DiagnosticSyncStage.HISTORY,
    DiagnosticSyncStage.DESCRIPTION,
    -> 15_000L
    DiagnosticSyncStage.IMAGES -> 5_000L
    else -> 10_000L
}

private val DIAGNOSTIC_SYNC_ID = Regex("[0-9A-F]{8}")
private val DIAGNOSTIC_FIELD_NAME = Regex("[A-Za-z][A-Za-z0-9_]{0,31}")
private const val MAX_EVENT_NAME_CHARS = 48
private const val MAX_FIELD_VALUE_CHARS = 180
private const val MAX_TEXT_CHARS = 300
