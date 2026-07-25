package pl.lewicowyt.notifier.network

import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import pl.lewicowyt.notifier.model.VideoEntry
import pl.lewicowyt.notifier.model.VideoKind

enum class YouTubeHistoryTab(val path: String) {
    VIDEOS("videos"),
    STREAMS("streams"),
    SHORTS("shorts"),
    PLAYLIST("playlist"),
}

data class YouTubeHistoryItem(
    val entry: VideoEntry,
    val kind: VideoKind,
)

data class YouTubeHistoryCursor(
    val token: String,
    val apiKey: String,
    val clientVersion: String,
)

data class YouTubeHistoryPage(
    val items: List<YouTubeHistoryItem>,
    val nextCursor: YouTubeHistoryCursor?,
)

/**
 * Czyta publiczne strony kanału i endpoint kontynuacji używany przez webowy interfejs YouTube.
 * Nie wymaga klucza YouTube Data API; klucz klienta webowego jest pobierany z tej samej strony.
 */
class YouTubeHistoryClient(private val http: HttpTextClient) {
    fun firstPage(
        source: ResolvedSource,
        tab: YouTubeHistoryTab,
        nowMillis: Long = System.currentTimeMillis(),
    ): YouTubeHistoryPage {
        val url = if (tab == YouTubeHistoryTab.PLAYLIST) {
            "https://www.youtube.com/playlist?list=${source.externalId}"
        } else {
            "https://www.youtube.com/channel/${source.externalId}/${tab.path}"
        }
        val html = http.getText(url, maxChars = MAX_PAGE_CHARS)
        val apiKey = API_KEY.find(html)?.groupValues?.getOrNull(1)
            ?.takeIf(WEB_API_KEY::matches)
            ?: throw IOException("YouTube nie udostępnił klucza klienta webowego")
        val clientVersion = CLIENT_VERSION.find(html)?.groupValues?.getOrNull(1)
            ?.takeIf(WEB_CLIENT_VERSION::matches)
            ?: DEFAULT_CLIENT_VERSION
        val initialData = extractJsonObject(html, INITIAL_DATA_MARKERS)
            ?: throw IOException("Nie znaleziono danych historii na stronie YouTube")
        if (!hasSafeJsonNesting(initialData)) {
            throw IOException("Dane historii YouTube mają zbyt głęboką strukturę JSON")
        }
        return parsePage(
            json = JSONObject(initialData),
            tab = tab,
            apiKey = apiKey,
            clientVersion = clientVersion,
            nowMillis = nowMillis,
        )
    }

    fun nextPage(
        cursor: YouTubeHistoryCursor,
        tab: YouTubeHistoryTab,
        nowMillis: Long = System.currentTimeMillis(),
    ): YouTubeHistoryPage {
        val body = JSONObject()
            .put(
                "context",
                JSONObject().put(
                    "client",
                    JSONObject()
                        .put("clientName", "WEB")
                        .put("clientVersion", cursor.clientVersion)
                        .put("hl", "pl")
                        .put("gl", "PL"),
                ),
            )
            .put("continuation", cursor.token)
            .toString()
        val response = http.postJson(
            url = "https://www.youtube.com/youtubei/v1/browse?key=${cursor.apiKey}",
            json = body,
            maxChars = MAX_PAGE_CHARS,
        )
        if (!hasSafeJsonNesting(response)) {
            throw IOException("Kontynuacja YouTube ma zbyt głęboką strukturę JSON")
        }
        return parsePage(
            json = JSONObject(response),
            tab = tab,
            apiKey = cursor.apiKey,
            clientVersion = cursor.clientVersion,
            nowMillis = nowMillis,
        )
    }

    private fun parsePage(
        json: JSONObject,
        tab: YouTubeHistoryTab,
        apiKey: String,
        clientVersion: String,
        nowMillis: Long,
    ): YouTubeHistoryPage {
        val items = linkedMapOf<String, YouTubeHistoryItem>()
        val recognizedRenderers = collectVideos(json, tab, nowMillis, items)
        val token = findContinuationToken(json)
        if (recognizedRenderers > 0 && items.isEmpty()) {
            throw IOException(
                "YouTube nie udostępnił dat publikacji dla materiałów na tej stronie",
            )
        }
        return YouTubeHistoryPage(
            items = items.values.toList(),
            nextCursor = token?.let { YouTubeHistoryCursor(it, apiKey, clientVersion) },
        )
    }

    private fun collectVideos(
        value: Any?,
        tab: YouTubeHistoryTab,
        nowMillis: Long,
        result: MutableMap<String, YouTubeHistoryItem>,
    ): Int {
        return when (value) {
            is JSONObject -> {
                var recognized = 0
                VIDEO_RENDERER_KEYS.forEach { key ->
                    val renderer = value.optJSONObject(key) ?: return@forEach
                    recognized += 1
                    val videoId = renderer.optString("videoId")
                        .takeIf(YOUTUBE_VIDEO_ID::matches)
                        ?: return@forEach
                    if (videoId !in result) {
                        parseVideo(renderer, videoId, tab, key, nowMillis)
                            ?.let { result[videoId] = it }
                    }
                }
                value.keys().forEach { key ->
                    recognized += collectVideos(value.opt(key), tab, nowMillis, result)
                }
                recognized
            }

            is JSONArray -> {
                var recognized = 0
                for (index in 0 until value.length()) {
                    recognized += collectVideos(value.opt(index), tab, nowMillis, result)
                }
                recognized
            }

            else -> 0
        }
    }

    private fun parseVideo(
        renderer: JSONObject,
        videoId: String,
        tab: YouTubeHistoryTab,
        rendererKey: String,
        nowMillis: Long,
    ): YouTubeHistoryItem? {
        val title = sequenceOf("title", "headline")
            .mapNotNull { key -> readText(renderer.opt(key)) }
            .firstOrNull { it.isNotBlank() }
            ?.take(MAX_TITLE_CHARS)
            ?: return null
        val publishedText = readText(renderer.opt("publishedTimeText"))
            .orEmpty()
            .take(MAX_PUBLISHED_TEXT_CHARS)
        val publishedMillis = renderer.optJSONObject("upcomingEventData")
            ?.optString("startTime")
            ?.toLongOrNull()
            ?.times(1_000L)
            ?: parsePublishedTime(publishedText, nowMillis)
            ?: return null
        if (publishedMillis <= 0L || publishedMillis > nowMillis + MAX_FUTURE_MILLIS) {
            return null
        }
        val serialized = renderer.toString()
        val kind = when {
            renderer.has("upcomingEventData") -> VideoKind.UPCOMING
            serialized.contains("BADGE_STYLE_TYPE_LIVE_NOW") -> VideoKind.LIVE
            tab == YouTubeHistoryTab.STREAMS -> VideoKind.STREAM_ARCHIVE
            tab == YouTubeHistoryTab.SHORTS ||
                rendererKey == "reelItemRenderer" -> VideoKind.SHORT
            else -> VideoKind.VIDEO
        }
        return YouTubeHistoryItem(
            entry = VideoEntry(
                id = videoId,
                title = title,
                url = "https://www.youtube.com/watch?v=$videoId",
                publishedAtMillis = publishedMillis,
                author = "",
            ),
            kind = kind,
        )
    }

    private fun readText(value: Any?): String? = when (value) {
        is String -> value
        is JSONObject -> {
            value.optString("simpleText").takeIf { it.isNotBlank() }
                ?: value.optJSONArray("runs")?.let { runs ->
                    buildString {
                        for (index in 0 until runs.length()) {
                            append(runs.optJSONObject(index)?.optString("text").orEmpty())
                        }
                    }.takeIf { it.isNotBlank() }
                }
        }
        else -> null
    }

    private fun findContinuationToken(value: Any?): String? {
        when (value) {
            is JSONObject -> {
                value.optJSONObject("continuationCommand")
                    ?.optString("token")
                    ?.takeIf(::isSafeContinuationToken)
                    ?.let { return it }
                value.optJSONObject("nextContinuationData")
                    ?.optString("continuation")
                    ?.takeIf(::isSafeContinuationToken)
                    ?.let { return it }
                value.keys().forEach { key ->
                    findContinuationToken(value.opt(key))?.let { return it }
                }
            }
            is JSONArray -> for (index in 0 until value.length()) {
                findContinuationToken(value.opt(index))?.let { return it }
            }
        }
        return null
    }

    private fun isSafeContinuationToken(value: String): Boolean =
        value.isNotBlank() && value.length <= MAX_CONTINUATION_TOKEN_CHARS

    private fun parsePublishedTime(text: String, nowMillis: Long): Long? {
        val normalized = text
            .lowercase(Locale.forLanguageTag("pl"))
            .replace('\u00a0', ' ')
            .trim()
        if (normalized.isBlank()) return null
        if ("wczoraj" in normalized || "yesterday" in normalized) {
            return nowMillis - DAY_MILLIS
        }
        if ("dzisiaj" in normalized || "today" in normalized) return nowMillis

        val amount = NUMBER.find(normalized)?.groupValues?.getOrNull(1)?.toLongOrNull()
        if (amount != null) {
            val unitMillis = when {
                containsAny(normalized, "sekund", "second") -> 1_000L
                containsAny(normalized, "minut", "minute") -> 60_000L
                containsAny(normalized, "godzin", "hour") -> 3_600_000L
                containsAny(normalized, "dzień", "dni", "day") -> DAY_MILLIS
                containsAny(normalized, "tydzień", "tygod", "week") -> 7L * DAY_MILLIS
                containsAny(normalized, "miesią", "miesi", "month") -> 30L * DAY_MILLIS
                containsAny(normalized, "rok", "lata", "lat", "year") -> 365L * DAY_MILLIS
                else -> null
            }
            if (unitMillis != null) {
                val ageMillis = runCatching { Math.multiplyExact(amount, unitMillis) }
                    .getOrNull()
                    ?.takeIf { it <= MAX_RELATIVE_AGE_MILLIS }
                    ?: return null
                return nowMillis - ageMillis
            }
        }

        for (formatter in ABSOLUTE_DATE_FORMATTERS) {
            try {
                val date = LocalDate.parse(text.trim(), formatter)
                return date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
                // Spróbuj następnego formatu.
            }
        }
        return runCatching { Instant.parse(text.trim()).toEpochMilli() }.getOrNull()
    }

    private fun containsAny(text: String, vararg values: String): Boolean =
        values.any(text::contains)

    private fun extractJsonObject(source: String, markers: List<String>): String? {
        for (marker in markers) {
            val markerIndex = source.indexOf(marker)
            if (markerIndex < 0) continue
            val start = source.indexOf('{', markerIndex + marker.length)
            if (start < 0) continue
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
        }
        return null
    }

    private companion object {
        const val MAX_PAGE_CHARS = 8_000_000
        const val MAX_TITLE_CHARS = 300
        const val MAX_PUBLISHED_TEXT_CHARS = 200
        const val MAX_CONTINUATION_TOKEN_CHARS = 16_384
        const val DEFAULT_CLIENT_VERSION = "2.20260701.00.00"
        const val DAY_MILLIS = 24L * 60L * 60L * 1_000L
        const val MAX_FUTURE_MILLIS = 2L * 365L * DAY_MILLIS
        const val MAX_RELATIVE_AGE_MILLIS = 10L * 365L * DAY_MILLIS
        val NUMBER = Regex("""(\d+)""")
        val API_KEY = Regex(""""INNERTUBE_API_KEY"\s*:\s*"([^"]{1,256})"""")
        val CLIENT_VERSION =
            Regex(""""INNERTUBE_CLIENT_VERSION"\s*:\s*"([^"]{1,100})"""")
        val WEB_API_KEY = Regex("""[A-Za-z0-9_-]{20,256}""")
        val WEB_CLIENT_VERSION = Regex("""[A-Za-z0-9._-]{1,100}""")
        val INITIAL_DATA_MARKERS = listOf(
            "var ytInitialData =",
            "window[\"ytInitialData\"] =",
            "ytInitialData =",
            "\"ytInitialData\":",
        )
        val YOUTUBE_VIDEO_ID = Regex("""[A-Za-z0-9_-]{11}""")
        val VIDEO_RENDERER_KEYS = listOf(
            "videoRenderer",
            "gridVideoRenderer",
            "playlistVideoRenderer",
            "reelItemRenderer",
        )
        val ABSOLUTE_DATE_FORMATTERS = listOf(
            DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("pl")),
            DateTimeFormatter.ISO_LOCAL_DATE,
        )
    }
}
