package pl.lewicowyt.notifier.network

import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.Locale
import org.json.JSONObject
import pl.lewicowyt.notifier.model.SourceType
import pl.lewicowyt.notifier.model.VideoEntry
import pl.lewicowyt.notifier.model.VideoKind

data class YouTubeDataApiPage(
    val items: List<YouTubeHistoryItem>,
    val nextPageToken: String?,
)

sealed interface YouTubeApiKeyValidation {
    data object Valid : YouTubeApiKeyValidation
    data class Rejected(val message: String) : YouTubeApiKeyValidation
    data class TemporarilyUnavailable(val message: String) : YouTubeApiKeyValidation
}

/**
 * Szybka, oficjalna ścieżka historii. Jedna strona zawiera do 50 pozycji i ma
 * wielokrotnie mniejszą odpowiedź niż webowy interfejs YouTube.
 */
class YouTubeDataApiHistoryClient(
    private val http: HttpTextClient,
    private val apiRequestHeaders: Map<String, String> = emptyMap(),
) {
    /**
     * Wykonuje najtańsze żądanie kontrolne (1 jednostka limitu). Pusta lista
     * kanałów jest prawidłowym wynikiem — o ważności klucza świadczy przyjęcie
     * żądania przez YouTube Data API, nie istnienie testowego kanału.
     */
    fun validateApiKey(apiKey: String): YouTubeApiKeyValidation {
        val normalized = apiKey.trim()
        if (normalized.isBlank()) {
            return YouTubeApiKeyValidation.Rejected("Klucz API jest pusty.")
        }
        if (normalized.length > MAX_API_KEY_CHARS) {
            return YouTubeApiKeyValidation.Rejected("Klucz API jest zbyt długi.")
        }
        val url = "$API_BASE/channels?part=id&maxResults=1" +
            "&id=$VALIDATION_CHANNEL_ID&key=${normalized.urlEncode()}"
        val responseBody = try {
            http.getText(
                url = url,
                maxChars = 100_000,
                headers = apiRequestHeaders,
            )
        } catch (error: HttpStatusException) {
            return interpretApiKeyValidationResponse(
                statusCode = error.statusCode,
                responseBody = error.responseBody,
            )
        }
        return interpretApiKeyValidationResponse(
            statusCode = 200,
            responseBody = responseBody,
        )
    }

    fun resolveChannelId(channelUrl: String, apiKey: String): String {
        val path = runCatching { URI(channelUrl).path.trim('/') }.getOrDefault("")
        CHANNEL_ID_IN_PATH.find(path)?.groupValues?.getOrNull(1)?.let { return it }

        val lastSegment = path.substringAfterLast('/').takeIf { it.isNotBlank() }
            ?: throw IOException("Nieprawidłowy adres kanału: $channelUrl")
        val lookup = if (lastSegment.startsWith("@")) {
            "forHandle" to lastSegment.removePrefix("@")
        } else {
            "forUsername" to lastSegment
        }
        val url = "$API_BASE/channels?part=id&maxResults=1&" +
            "${lookup.first}=${lookup.second.urlEncode()}&key=${apiKey.urlEncode()}"
        val json = JSONObject(
            http.getText(url, maxChars = 200_000, headers = apiRequestHeaders),
        )
        throwApiErrorIfPresent(json)
        val channelId = json.optJSONArray("items")
            ?.optJSONObject(0)
            ?.optString("id")
            ?.takeIf { it.startsWith("UC") }
        return channelId
            ?: throw IOException("YouTube Data API nie rozpoznało kanału: $channelUrl")
    }

    fun fetchPage(
        source: ResolvedSource,
        apiKey: String,
        pageToken: String?,
        classifyAfterMillis: Long = Long.MIN_VALUE,
    ): YouTubeDataApiPage {
        val playlistId = when (source.type) {
            SourceType.CHANNEL -> source.externalId.toUploadsPlaylistId()
            SourceType.PLAYLIST -> source.externalId
        }
        val playlistUrl = buildString {
            append("$API_BASE/playlistItems")
            append("?part=snippet,contentDetails&maxResults=50")
            append("&playlistId=${playlistId.urlEncode()}")
            append("&key=${apiKey.urlEncode()}")
            if (!pageToken.isNullOrBlank()) append("&pageToken=${pageToken.urlEncode()}")
        }
        val playlistJson = JSONObject(
            http.getText(
                playlistUrl,
                maxChars = 1_500_000,
                headers = apiRequestHeaders,
            ),
        )
        throwApiErrorIfPresent(playlistJson)

        val rawItems = buildList {
            val items = playlistJson.optJSONArray("items") ?: return@buildList
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val snippet = item.optJSONObject("snippet") ?: continue
                val details = item.optJSONObject("contentDetails")
                val videoId = details?.optString("videoId")
                    ?.takeIf(YOUTUBE_VIDEO_ID::matches)
                    ?: snippet.optJSONObject("resourceId")
                        ?.optString("videoId")
                        ?.takeIf(YOUTUBE_VIDEO_ID::matches)
                    ?: continue
                val published = details?.optString("videoPublishedAt")
                    ?.takeIf { it.isNotBlank() }
                    ?: snippet.optString("publishedAt")
                val publishedAtMillis = runCatching {
                    Instant.parse(published).toEpochMilli()
                }.getOrNull()
                    ?.takeIf {
                        it > 0L &&
                            it <= System.currentTimeMillis() + MAX_FUTURE_SKEW_MILLIS
                    }
                    ?: continue
                add(
                    RawItem(
                        videoId = videoId,
                        title = snippet.optString("title")
                            .ifBlank { "Materiał bez tytułu" }
                            .take(MAX_TITLE_CHARS),
                        publishedAtMillis = publishedAtMillis,
                        author = snippet.optString("videoOwnerChannelTitle")
                            .take(MAX_AUTHOR_CHARS),
                    ),
                )
            }
        }
        val kinds = fetchVideoKinds(
            rawItems
                .filter { it.publishedAtMillis >= classifyAfterMillis }
                .map(RawItem::videoId),
            apiKey,
        )
        return YouTubeDataApiPage(
            items = rawItems.map { item ->
                YouTubeHistoryItem(
                    entry = VideoEntry(
                        id = item.videoId,
                        title = item.title,
                        url = "https://www.youtube.com/watch?v=${item.videoId}",
                        publishedAtMillis = item.publishedAtMillis,
                        author = item.author,
                    ),
                    kind = kinds[item.videoId] ?: VideoKind.UNKNOWN,
                )
            },
            nextPageToken = playlistJson.optString("nextPageToken")
                .takeIf { it.isNotBlank() },
        )
    }

    fun fetchVideoKinds(videoIds: Collection<String>, apiKey: String): Map<String, VideoKind> =
        videoIds
            .asSequence()
            .filter(YOUTUBE_VIDEO_ID::matches)
            .distinct()
            .chunked(MAX_VIDEO_IDS_PER_REQUEST)
            .fold(emptyMap<String, VideoKind>()) { result, chunk ->
                result + fetchVideoKindsPage(chunk, apiKey)
            }

    private fun fetchVideoKindsPage(
        videoIds: List<String>,
        apiKey: String,
    ): Map<String, VideoKind> {
        if (videoIds.isEmpty()) return emptyMap()
        val url = buildString {
            append("$API_BASE/videos")
            append("?part=snippet,contentDetails,liveStreamingDetails")
            append("&id=${videoIds.joinToString(",").urlEncode()}")
            append("&key=${apiKey.urlEncode()}")
        }
        val json = JSONObject(
            http.getText(url, maxChars = 1_500_000, headers = apiRequestHeaders),
        )
        throwApiErrorIfPresent(json)
        return buildMap {
            val items = json.optJSONArray("items") ?: return@buildMap
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val id = item.optString("id").takeIf(YOUTUBE_VIDEO_ID::matches) ?: continue
                val liveDetails = item.optJSONObject("liveStreamingDetails")
                val broadcastState = item.optJSONObject("snippet")
                    ?.optString("liveBroadcastContent")
                    .orEmpty()
                val kind = when {
                    broadcastState == "live" -> VideoKind.LIVE
                    broadcastState == "upcoming" -> VideoKind.UPCOMING
                    liveDetails?.optString("actualEndTime")?.isNotBlank() == true ->
                        VideoKind.STREAM_ARCHIVE
                    liveDetails?.optString("actualStartTime")?.isNotBlank() == true ->
                        VideoKind.LIVE
                    liveDetails?.optString("scheduledStartTime")?.isNotBlank() == true ->
                        VideoKind.UPCOMING
                    parseDurationSeconds(
                        item.optJSONObject("contentDetails")?.optString("duration").orEmpty(),
                    ) in 1..MAX_SHORT_SECONDS -> VideoKind.SHORT
                    else -> VideoKind.VIDEO
                }
                put(id, kind)
            }
        }
    }

    private fun throwApiErrorIfPresent(json: JSONObject) {
        val error = json.optJSONObject("error") ?: return
        val message = error.optString("message")
            .ifBlank { "Błąd YouTube Data API" }
            .take(MAX_API_ERROR_CHARS)
        throw IOException(message)
    }

    private fun String.toUploadsPlaylistId(): String {
        if (!startsWith("UC") || length < 3) {
            throw IOException("Nieprawidłowy identyfikator kanału: $this")
        }
        return "UU${drop(2)}"
    }

    private fun String.urlEncode(): String =
        URLEncoder.encode(this, StandardCharsets.UTF_8.name())

    private fun parseDurationSeconds(value: String): Long =
        runCatching { Duration.parse(value).seconds }.getOrDefault(0L)

    private data class RawItem(
        val videoId: String,
        val title: String,
        val publishedAtMillis: Long,
        val author: String,
    )

    private companion object {
        const val API_BASE = "https://www.googleapis.com/youtube/v3"
        const val VALIDATION_CHANNEL_ID = "UC0000000000000000000000"
        const val MAX_API_KEY_CHARS = 256
        const val MAX_SHORT_SECONDS = 180L
        const val MAX_VIDEO_IDS_PER_REQUEST = 50
        const val MAX_FUTURE_SKEW_MILLIS = 10L * 60L * 1_000L
        const val MAX_API_ERROR_CHARS = 500
        const val MAX_TITLE_CHARS = 300
        const val MAX_AUTHOR_CHARS = 200
        val CHANNEL_ID_IN_PATH = Regex("""(?:^|/)channel/(UC[\w-]{18,})""")
        val YOUTUBE_VIDEO_ID = Regex("""[A-Za-z0-9_-]{11}""")
    }
}

internal fun interpretApiKeyValidationResponse(
    statusCode: Int,
    responseBody: String,
): YouTubeApiKeyValidation {
    val responseJson = runCatching { JSONObject(responseBody) }.getOrNull()
    val error = responseJson?.optJSONObject("error")
    val expectedSuccessDocument =
        responseJson != null &&
            responseJson.optString("kind") == "youtube#channelListResponse" &&
            responseJson.optJSONObject("pageInfo") != null &&
            responseJson.optJSONArray("items") != null
    if (statusCode in 200..299 && error == null && expectedSuccessDocument) {
        return YouTubeApiKeyValidation.Valid
    }

    val legacyReason = error
        ?.optJSONArray("errors")
        ?.optJSONObject(0)
        ?.optString("reason")
        .orEmpty()
        .lowercase(Locale.ROOT)
    val detailedReason = error
        ?.optJSONArray("details")
        ?.let { details ->
            (0 until details.length())
                .asSequence()
                .mapNotNull(details::optJSONObject)
                .map { it.optString("reason").lowercase(Locale.ROOT) }
                .firstOrNull { it.isNotBlank() }
        }
        .orEmpty()
    val reasons = setOf(legacyReason, detailedReason).filterTo(mutableSetOf()) {
        it.isNotBlank()
    }
    return when {
        statusCode >= 500 || statusCode == 408 || statusCode == 429 ->
            YouTubeApiKeyValidation.TemporarilyUnavailable(
                "YouTube chwilowo nie może zweryfikować klucza. Spróbuj ponownie później.",
            )
        reasons.any(QUOTA_ERROR_REASONS::contains) ->
            YouTubeApiKeyValidation.TemporarilyUnavailable(
                "Google rozpoznał żądanie, ale limit API jest obecnie wyczerpany. " +
                    "Klucz nie został jeszcze aktywowany.",
            )
        reasons.any(API_DISABLED_ERROR_REASONS::contains) ->
            YouTubeApiKeyValidation.Rejected(
                "Klucz istnieje, ale YouTube Data API v3 nie jest włączone dla jego projektu.",
            )
        reasons.any(INVALID_KEY_REASONS::contains) ->
            YouTubeApiKeyValidation.Rejected(
                "Klucz API jest nieprawidłowy albo jego ograniczenia nie zezwalają tej aplikacji.",
            )
        statusCode in 400..499 ->
            YouTubeApiKeyValidation.Rejected(
                "YouTube odrzucił klucz API. Sprawdź klucz, włączenie API i jego ograniczenia.",
            )
        else ->
            YouTubeApiKeyValidation.TemporarilyUnavailable(
                "Nie udało się jednoznacznie zweryfikować odpowiedzi YouTube. " +
                    "Klucz nie został zapisany.",
            )
    }
}

private val QUOTA_ERROR_REASONS = setOf(
    "dailylimitexceeded",
    "dailylimitexceededunreg",
    "quotaexceeded",
    "ratelimitexceeded",
    "ratelimitexceededunreg",
    "resource_exhausted",
    "userratelimitexceeded",
    "userratelimitexceededunreg",
)

private val API_DISABLED_ERROR_REASONS = setOf(
    "accessnotconfigured",
    "service_disabled",
)

private val INVALID_KEY_REASONS = setOf(
    "api_key_invalid",
    "apikeyexpired",
    "apikeynotvalid",
    "forbidden",
    "iprefererblocked",
    "keyinvalid",
    "requestsfromrefererblocked",
)
