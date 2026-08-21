package pl.lewicowyt.notifier.network

import org.json.JSONObject
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import pl.lewicowyt.notifier.model.VideoKind

data class YouTubeVideoMetadata(
    val videoId: String,
    val title: String,
    val channelId: String,
    val publishedAtMillis: Long,
    val kind: VideoKind,
    val description: String?,
)

internal sealed interface DescriptionFetchResult {
    data class Available(val description: String) : DescriptionFetchResult
    data object MembersOnly : DescriptionFetchResult
    data object ScheduledStream : DescriptionFetchResult
    data object Invalid : DescriptionFetchResult
}

/**
 * Klasyfikacja bez klucza YouTube Data API na podstawie publicznej strony filmu.
 * Jest to mechanizm best-effort: YouTube może zmieniać wewnętrzny JSON strony.
 */
class YouTubePageClassifier(private val http: HttpTextClient) {
    fun inspect(videoId: String): YouTubeVideoMetadata? {
        if (!YOUTUBE_VIDEO_ID.matches(videoId)) return null
        val responseText = runCatching {
            http.postJson(
                url = PLAYER_ENDPOINT,
                json = playerRequest(videoId),
                maxChars = MAX_PLAYER_RESPONSE_CHARS,
                headers = PLAYER_HEADERS,
            )
        }.getOrNull()
        responseText?.let { inspectPlayerResponse(it, videoId) }?.let { return it }

        // Część publicznych starszych filmów jest błędnie oznaczana przez klienta
        // Android player jako LOGIN_REQUIRED. Strona watch nadal zawiera podpisane
        // przez YouTube metadane bieżącego videoId, kanału i daty.
        val html = runCatching {
            http.getText(
                "https://www.youtube.com/watch?v=$videoId",
                maxChars = MAX_WATCH_HTML_CHARS,
            )
        }.getOrNull() ?: return null
        val embeddedPlayer = extractCurrentPlayerData(html) ?: return null
        return inspectPlayerResponse(
            responseText = embeddedPlayer,
            videoId = videoId,
            fallbackPublishedAtMillis = extractWatchPublishedAtMillis(html),
        )
    }

    internal fun inspectPlayerResponse(
        responseText: String,
        videoId: String,
        fallbackPublishedAtMillis: Long? = null,
    ): YouTubeVideoMetadata? {
        if (!hasSafeJsonNesting(responseText)) return null
        val response = runCatching { JSONObject(responseText) }.getOrNull() ?: return null
        val details = response.optJSONObject("videoDetails") ?: return null
        if (details.optString("videoId") != videoId) return null
        val playability = response.optJSONObject("playabilityStatus")?.optString("status")
        if (playability !in METADATA_PLAYABILITY_STATUSES) return null
        val channelId = details.optString("channelId").takeIf(YOUTUBE_CHANNEL_ID::matches)
            ?: return null
        val title = details.optString("title").trim().takeIf(String::isNotBlank)
            ?.take(MAX_TITLE_CHARS)
            ?: return null
        val microformat = response.optJSONObject("microformat")
            ?.optJSONObject("playerMicroformatRenderer")
        val published = sequenceOf(
            microformat?.optString("publishDate"),
            microformat?.optString("uploadDate"),
        ).filterNotNull().mapNotNull(::parseIsoDateMillis).firstOrNull()
            ?: fallbackPublishedAtMillis
            ?: return null
        val kind = classifyPlayerResponse(responseText, videoId)
            .takeUnless { it == VideoKind.UNKNOWN }
            ?: VideoKind.VIDEO
        return YouTubeVideoMetadata(
            videoId = videoId,
            title = title,
            channelId = channelId,
            publishedAtMillis = published,
            kind = kind,
            description = details.optString("shortDescription")
                .trim()
                .takeIf(String::isNotBlank)
                ?.take(MAX_DESCRIPTION_CHARS),
        )
    }

    fun fetchDescription(
        videoId: String,
        expectedTitle: String,
        expectedChannelIds: Set<String>,
    ): String? = when (
        val result = fetchDescriptionResult(videoId, expectedTitle, expectedChannelIds)
    ) {
        is DescriptionFetchResult.Available -> result.description
        else -> null
    }

    internal fun fetchDescriptionResult(
        videoId: String,
        expectedTitle: String,
        expectedChannelIds: Set<String>,
    ): DescriptionFetchResult {
        if (!YOUTUBE_VIDEO_ID.matches(videoId)) return DescriptionFetchResult.Invalid
        val responseText = runCatching {
            http.postJson(
                url = PLAYER_ENDPOINT,
                json = playerRequest(videoId),
                maxChars = MAX_PLAYER_RESPONSE_CHARS,
                headers = PLAYER_HEADERS,
            )
        }.getOrNull()
        // Pobieranie opisów celowo nie korzysta z /watch. Poprawna odpowiedź
        // playera, także z pustym shortDescription, jest wynikiem ostatecznym.
        return responseText?.let {
            extractDescriptionFetchResult(
                responseText = it,
                videoId = videoId,
                expectedTitle = expectedTitle,
                expectedChannelIds = expectedChannelIds,
            )
        } ?: DescriptionFetchResult.Invalid
    }

    internal fun extractDescriptionFromPlayerResponse(
        responseText: String,
        videoId: String,
        expectedTitle: String? = null,
        expectedChannelIds: Set<String> = emptySet(),
    ): String? = when (
        val result = extractDescriptionFetchResult(
            responseText = responseText,
            videoId = videoId,
            expectedTitle = expectedTitle,
            expectedChannelIds = expectedChannelIds,
        )
    ) {
        is DescriptionFetchResult.Available -> result.description
        else -> null
    }

    internal fun extractDescriptionFetchResult(
        responseText: String,
        videoId: String,
        expectedTitle: String? = null,
        expectedChannelIds: Set<String> = emptySet(),
    ): DescriptionFetchResult {
        if (!YOUTUBE_VIDEO_ID.matches(videoId) || !hasSafeJsonNesting(responseText)) {
            return DescriptionFetchResult.Invalid
        }
        val response = runCatching { JSONObject(responseText) }.getOrNull()
            ?: return DescriptionFetchResult.Invalid
        val playability = response.optJSONObject("playabilityStatus")
        val playabilityStatus = playability?.optString("status").orEmpty()
        val reason = playability?.optString("reason").orEmpty()
        if (
            playabilityStatus == "LIVE_STREAM_OFFLINE" &&
            playability?.optJSONObject("liveStreamability")
                ?.optJSONObject("liveStreamabilityRenderer")
                ?.optString("videoId") == videoId
        ) {
            return DescriptionFetchResult.ScheduledStream
        }
        if (
            playabilityStatus == "UNPLAYABLE" &&
            MEMBERS_ONLY_REASON.containsMatchIn(reason)
        ) {
            return DescriptionFetchResult.MembersOnly
        }
        val details = response.optJSONObject("videoDetails")
            ?: return DescriptionFetchResult.Invalid
        if (details.optString("videoId") != videoId) return DescriptionFetchResult.Invalid
        if (playabilityStatus !in METADATA_PLAYABILITY_STATUSES) {
            return DescriptionFetchResult.Invalid
        }
        val actualChannelId = details.optString("channelId")
            .takeIf(YOUTUBE_CHANNEL_ID::matches)
            ?: return DescriptionFetchResult.Invalid
        val knownExpectedChannels = expectedChannelIds.filter(YOUTUBE_CHANNEL_ID::matches).toSet()
        if (knownExpectedChannels.isNotEmpty()) {
            if (actualChannelId !in knownExpectedChannels) return DescriptionFetchResult.Invalid
        } else if (expectedTitle != null) {
            val actualTitle = details.optString("title").take(MAX_TITLE_CHARS)
            if (
                normalizeMetadataTitle(actualTitle) !=
                normalizeMetadataTitle(expectedTitle.take(MAX_TITLE_CHARS))
            ) {
                return DescriptionFetchResult.Invalid
            }
        }
        // Brak pola albo sam biały tekst oznacza prawidłowy film bez opisu.
        // Pusty String różni się od null, które pozostaje błędem walidacji/sieci.
        return DescriptionFetchResult.Available(
            details.optString("shortDescription")
                .trim()
                .take(MAX_DESCRIPTION_CHARS),
        )
    }

    private fun normalizeMetadataTitle(value: String): String = value
        .trim()
        .replace(WHITESPACE, " ")

    private fun parseIsoDateMillis(value: String?): Long? {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching {
                LocalDate.parse(raw)
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            }.getOrNull()
    }

    internal fun extractWatchPublishedAtMillis(html: String): Long? = META_TAG
        .findAll(html)
        .map { match -> match.value }
        .firstNotNullOfOrNull { tag ->
            val itemProp = ITEMPROP_ATTRIBUTE.find(tag)?.groupValues?.getOrNull(2)
                ?.lowercase()
            if (itemProp !in WATCH_DATE_ITEMPROPS) return@firstNotNullOfOrNull null
            val content = CONTENT_ATTRIBUTE.find(tag)?.groupValues?.getOrNull(2)
            parseIsoDateMillis(content)
        }

    fun classify(videoId: String): VideoKind {
        val playerKind = runCatching {
            val response = http.postJson(
                url = PLAYER_ENDPOINT,
                json = playerRequest(videoId),
                maxChars = MAX_PLAYER_RESPONSE_CHARS,
                headers = PLAYER_HEADERS,
            )
            classifyPlayerResponse(response, videoId)
        }.getOrDefault(VideoKind.UNKNOWN)
        if (playerKind != VideoKind.UNKNOWN) return playerKind

        return runCatching {
            val html = http.getText(
                "https://www.youtube.com/watch?v=$videoId",
                maxChars = 4_000_000,
            )
            classifyHtml(html, videoId)
        }.getOrDefault(VideoKind.UNKNOWN)
    }

    private fun playerRequest(videoId: String): String = JSONObject()
        .put(
            "context",
            JSONObject().put(
                "client",
                JSONObject()
                    .put("clientName", PLAYER_CLIENT_NAME)
                    .put("clientVersion", PLAYER_CLIENT_VERSION)
                    .put("androidSdkVersion", 35)
                    .put("hl", "pl")
                    .put("gl", "PL"),
            ),
        )
        .put("videoId", videoId)
        .put("contentCheckOk", true)
        .put("racyCheckOk", true)
        .toString()

    internal fun classifyPlayerResponse(json: String, videoId: String): VideoKind {
        if (!hasSafeJsonNesting(json)) return VideoKind.UNKNOWN
        val response = runCatching { JSONObject(json) }.getOrNull()
            ?: return VideoKind.UNKNOWN
        val details = response.optJSONObject("videoDetails")
            ?: return VideoKind.UNKNOWN
        if (details.optString("videoId") != videoId) return VideoKind.UNKNOWN

        val playability = response.optJSONObject("playabilityStatus")
        val status = playability?.optString("status").orEmpty()
        val isUpcoming =
            details.optBoolean("isUpcoming", false) ||
                response.optJSONObject("microformat")
                    ?.optJSONObject("playerMicroformatRenderer")
                    ?.optJSONObject("liveBroadcastDetails")
                    ?.optBoolean("isUpcoming", false) == true
        val isLive =
            details.optBoolean("isLive", false) ||
                details.optBoolean("isLiveNow", false)
        val durationSeconds = details.optString("lengthSeconds").toLongOrNull()
            ?: details.optLong("lengthSeconds", -1L)
        val shape = videoShape(response)
        return when {
            isUpcoming -> VideoKind.UPCOMING
            isLive -> VideoKind.LIVE
            status != "OK" -> VideoKind.UNKNOWN
            // To pole obejmuje również zakończone Premiery. Bez karty kanału
            // nie jest dowodem, że materiał należy do sekcji „Streamy”.
            details.optBoolean("isLiveContent", false) -> VideoKind.UNKNOWN
            durationSeconds !in 1L..MAX_SHORT_SECONDS ->
                if (durationSeconds > MAX_SHORT_SECONDS) {
                    VideoKind.VIDEO
                } else {
                    VideoKind.UNKNOWN
                }
            shape == VideoShape.PORTRAIT_OR_SQUARE -> VideoKind.SHORT
            shape == VideoShape.LANDSCAPE -> VideoKind.VIDEO
            else -> VideoKind.UNKNOWN
        }
    }

    private fun videoShape(response: JSONObject): VideoShape {
        val streamingData = response.optJSONObject("streamingData")
            ?: return VideoShape.MISSING
        var foundDimensions = false
        sequenceOf(
            streamingData.optJSONArray("formats"),
            streamingData.optJSONArray("adaptiveFormats"),
        ).filterNotNull().forEach { formats ->
            for (index in 0 until formats.length()) {
                val format = formats.optJSONObject(index) ?: continue
                val width = format.optInt("width", 0)
                val height = format.optInt("height", 0)
                if (width <= 0 || height <= 0) continue
                foundDimensions = true
                if (height >= width) return VideoShape.PORTRAIT_OR_SQUARE
            }
        }
        return if (foundDimensions) VideoShape.LANDSCAPE else VideoShape.MISSING
    }

    internal fun classifyHtml(html: String, videoId: String): VideoKind {
        val playerData = extractCurrentPlayerData(html) ?: return VideoKind.UNKNOWN
        if (!currentVideoIdPattern(videoId).containsMatchIn(playerData)) {
            return VideoKind.UNKNOWN
        }
        if (isShort(html, videoId) || isShort(playerData, videoId)) {
            return VideoKind.SHORT
        }
        return classifyPlayerResponse(playerData, videoId)
    }

    private fun extractCurrentPlayerData(html: String): String? {
        PLAYER_RESPONSE_MARKERS.forEach { marker ->
            extractJsonObjectAfterMarker(html, marker)?.let { return it }
        }
        // Ułatwia bezpośrednie testowanie parsera małym fragmentem odpowiedzi,
        // lecz nigdy nie uruchamia globalnego skanowania pełnej strony HTML.
        return html.trim().takeIf {
            it.length <= MAX_INLINE_PLAYER_JSON_CHARS &&
                it.startsWith('{') &&
                it.endsWith('}')
        }
    }

    private fun extractJsonObjectAfterMarker(source: String, marker: String): String? {
        var markerIndex = source.indexOf(marker)
        while (markerIndex >= 0) {
            val start = source.indexOf('{', markerIndex + marker.length)
            if (start < 0) return null
            var depth = 0
            var inString = false
            var escaped = false
            for (index in start until source.length) {
                val char = source[index]
                if (inString) {
                    when {
                        escaped -> escaped = false
                        char == '\\' -> escaped = true
                        char == '"' -> inString = false
                    }
                } else {
                    when (char) {
                        '"' -> inString = true
                        '{' -> depth += 1
                        '}' -> {
                            depth -= 1
                            if (depth == 0) return source.substring(start, index + 1)
                        }
                    }
                }
            }
            markerIndex = source.indexOf(marker, markerIndex + marker.length)
        }
        return null
    }

    private fun currentVideoIdPattern(videoId: String): Regex =
        Regex(""""videoId"\s*:\s*"${Regex.escape(videoId)}"""")

    private fun isShort(html: String, videoId: String): Boolean {
        if (SHORT_FLAG.containsMatchIn(html)) return true
        // YouTube nie zawsze publikuje już pole `isShort`. Aktualna strona
        // odtwarzania umieszcza jednak ścieżkę bieżącego Shorta w wewnętrznych
        // komendach nawigacji (ze zwykłymi albo escapowanymi slashami).
        val shortPath = "/shorts/$videoId"
        if (html.contains(shortPath) || html.contains(shortPath.replace("/", "\\/"))) {
            return true
        }
        val escapedId = Regex.escape(videoId)
        return Regex(
            """<(?:link|meta)[^>]+(?:href|content)=[\"']https://www\.youtube\.com/shorts/$escapedId(?:[?\"'][^>]*)?>""",
            RegexOption.IGNORE_CASE,
        ).containsMatchIn(html)
    }

    private companion object {
        const val PLAYER_ENDPOINT =
            "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
        const val PLAYER_CLIENT_NAME = "ANDROID"
        const val PLAYER_CLIENT_VERSION = "20.10.38"
        const val MAX_PLAYER_RESPONSE_CHARS = 2_000_000
        const val MAX_WATCH_HTML_CHARS = 4_000_000
        const val MAX_TITLE_CHARS = 300
        const val MAX_DESCRIPTION_CHARS = 200_000
        const val MAX_SHORT_SECONDS = 180L
        val METADATA_PLAYABILITY_STATUSES = setOf(
            "OK",
            "LOGIN_REQUIRED",
            "AGE_CHECK_REQUIRED",
            "CONTENT_CHECK_REQUIRED",
        )
        val PLAYER_HEADERS = mapOf(
            "User-Agent" to
                "com.google.android.youtube/$PLAYER_CLIENT_VERSION " +
                "(Linux; U; Android 14) gzip",
            "X-YouTube-Client-Name" to "3",
            "X-YouTube-Client-Version" to PLAYER_CLIENT_VERSION,
        )
        val MEMBERS_ONLY_REASON = Regex(
            "(?:wspieraj|wspierających|members?[- ]only|channel membership|join this channel)",
            RegexOption.IGNORE_CASE,
        )
        const val MAX_INLINE_PLAYER_JSON_CHARS = 100_000
        val PLAYER_RESPONSE_MARKERS = listOf(
            "var ytInitialPlayerResponse =",
            "ytInitialPlayerResponse =",
            "\"ytInitialPlayerResponse\":",
        )
        val SHORT_FLAG = Regex("""[\"']isShort[\"']\s*:\s*true""")
        val META_TAG = Regex("""<meta\b[^>]{0,2000}>""", RegexOption.IGNORE_CASE)
        val ITEMPROP_ATTRIBUTE = Regex(
            """\bitemprop\s*=\s*([\"'])([^\"']+)\1""",
            RegexOption.IGNORE_CASE,
        )
        val CONTENT_ATTRIBUTE = Regex(
            """\bcontent\s*=\s*([\"'])([^\"']+)\1""",
            RegexOption.IGNORE_CASE,
        )
        val WATCH_DATE_ITEMPROPS = setOf("uploaddate", "datepublished")
        val WHITESPACE = Regex("\\s+")
        val YOUTUBE_VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
        val YOUTUBE_CHANNEL_ID = Regex("UC[A-Za-z0-9_-]{22}")
    }

    private enum class VideoShape {
        PORTRAIT_OR_SQUARE,
        LANDSCAPE,
        MISSING,
    }
}
