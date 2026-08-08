package pl.lewicowyt.notifier.diagnostics

import java.io.IOException
import java.net.SocketTimeoutException
import pl.lewicowyt.notifier.model.CreatorSource
import pl.lewicowyt.notifier.model.SourceType
import pl.lewicowyt.notifier.network.HttpStatusException
import pl.lewicowyt.notifier.network.ResolvedSource

internal enum class DiagnosticYouTubeOperation {
    SOURCE_RESOLVE,
    SOURCE_SYNC,
    RSS_FEED,
    API_PLAYLIST_ITEMS,
    WEB_HISTORY,
    CHANNEL_TABS,
    VIDEO_CLASSIFICATION,
}

internal fun diagnosticYouTubeSourceSummary(
    creatorId: String,
    source: CreatorSource,
    resolved: ResolvedSource? = null,
): String {
    val fields = diagnosticYouTubeSourceFields(
        creatorId = creatorId,
        source = source,
        operation = DiagnosticYouTubeOperation.SOURCE_SYNC,
        resolved = resolved,
    )
    return listOf("creatorId", "sourceType", "channelId", "playlistId")
        .mapNotNull { key -> fields[key]?.let { "$key=$it" } }
        .joinToString(", ")
}

/**
 * Buduje bezpieczny kontekst publicznego źródła YouTube. Celowo nie zapisuje
 * URL-a, nazwy twórcy, treści odpowiedzi ani komunikatu wyjątku, ponieważ URL
 * Data API może zawierać klucz, a odpowiedź może być nieprzewidywalna.
 */
internal fun diagnosticYouTubeSourceFields(
    creatorId: String,
    source: CreatorSource,
    operation: DiagnosticYouTubeOperation,
    resolved: ResolvedSource? = null,
    error: Throwable? = null,
    extra: Map<String, Any?> = emptyMap(),
): Map<String, Any?> = buildMap {
    val sourceType = resolved?.type ?: source.type
    val publicSourceId = resolved?.externalId ?: source.externalId
    put("creatorId", creatorId)
    put("sourceType", sourceType.name)
    put("provider", operation.provider)
    put("operation", operation.name)
    when (sourceType) {
        SourceType.CHANNEL -> publicSourceId
            ?.takeIf(YOUTUBE_CHANNEL_ID::matches)
            ?.let { put("channelId", it) }
        SourceType.PLAYLIST -> publicSourceId
            ?.takeIf(YOUTUBE_PLAYLIST_ID::matches)
            ?.let { put("playlistId", it) }
    }
    error?.let {
        put("errorType", it.javaClass.simpleName.take(64))
        diagnosticHttpStatus(it)?.let { status -> put("httpStatus", status) }
    }
    extra.filterKeys { it !in RESERVED_CONTEXT_FIELDS }.forEach { (key, value) ->
        put(key, value)
    }
}

internal fun diagnosticYouTubeFailureReason(
    error: Throwable,
    operation: DiagnosticYouTubeOperation,
    fallback: DiagnosticReasonCode,
): DiagnosticReasonCode {
    val status = diagnosticHttpStatus(error)
    return when {
        operation == DiagnosticYouTubeOperation.API_PLAYLIST_ITEMS && status == 404 ->
            DiagnosticReasonCode.API_PLAYLIST_ITEMS_NOT_FOUND
        operation == DiagnosticYouTubeOperation.CHANNEL_TABS ||
            error.message.orEmpty().contains("listy kart", ignoreCase = true) ||
            error.message.orEmpty().contains("channel tabs", ignoreCase = true) ->
            DiagnosticReasonCode.CHANNEL_TABS_UNAVAILABLE
        error is SocketTimeoutException ||
            error.message.orEmpty().contains("timeout", ignoreCase = true) ->
            DiagnosticReasonCode.NETWORK_TIMEOUT
        status != null || error is IOException -> DiagnosticReasonCode.HTTP_ERROR
        else -> fallback
    }
}

internal fun DiagnosticSyncRun.youtubeIssue(
    name: String,
    level: DiagnosticLevel,
    creatorId: String,
    source: CreatorSource,
    operation: DiagnosticYouTubeOperation,
    fallbackReason: DiagnosticReasonCode,
    resolved: ResolvedSource? = null,
    error: Throwable? = null,
    extra: Map<String, Any?> = emptyMap(),
    text: String,
) {
    event(
        name = name,
        category = DiagnosticCategory.NETWORK,
        level = level,
        reason = error?.let {
            diagnosticYouTubeFailureReason(it, operation, fallbackReason)
        } ?: fallbackReason,
        fields = diagnosticYouTubeSourceFields(
            creatorId = creatorId,
            source = source,
            operation = operation,
            resolved = resolved,
            error = error,
            extra = extra,
        ),
        text = text,
    )
}

private fun diagnosticHttpStatus(error: Throwable): Int? = when (error) {
    is HttpStatusException -> error.statusCode
    else -> HTTP_STATUS.find(error.message.orEmpty())
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
}

private val DiagnosticYouTubeOperation.provider: String
    get() = when (this) {
        DiagnosticYouTubeOperation.RSS_FEED -> "RSS"
        DiagnosticYouTubeOperation.API_PLAYLIST_ITEMS -> "DATA_API"
        DiagnosticYouTubeOperation.WEB_HISTORY,
        DiagnosticYouTubeOperation.CHANNEL_TABS,
        DiagnosticYouTubeOperation.VIDEO_CLASSIFICATION,
        -> "WEB"
        DiagnosticYouTubeOperation.SOURCE_RESOLVE -> "RESOLVER"
        DiagnosticYouTubeOperation.SOURCE_SYNC -> "YOUTUBE"
    }

private val HTTP_STATUS = Regex("(?:HTTP|status)[ =:]*(\\d{3})", RegexOption.IGNORE_CASE)
private val YOUTUBE_CHANNEL_ID = Regex("UC[A-Za-z0-9_-]{22}")
private val YOUTUBE_PLAYLIST_ID = Regex("[A-Za-z0-9_-]{10,80}")
private val RESERVED_CONTEXT_FIELDS = setOf(
    "creatorId",
    "sourceType",
    "provider",
    "operation",
    "channelId",
    "playlistId",
    "errorType",
    "httpStatus",
)
