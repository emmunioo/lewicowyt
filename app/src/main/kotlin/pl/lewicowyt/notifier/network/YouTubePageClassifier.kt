package pl.lewicowyt.notifier.network

import org.json.JSONArray
import org.json.JSONObject
import pl.lewicowyt.notifier.model.VideoKind

/**
 * Klasyfikacja bez klucza YouTube Data API na podstawie publicznej strony filmu.
 * Jest to mechanizm best-effort: YouTube może zmieniać wewnętrzny JSON strony.
 */
class YouTubePageClassifier(private val http: HttpTextClient) {
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
                status == "LIVE_STREAM_OFFLINE"
        val isLive =
            details.optBoolean("isLive", false) ||
                details.optBoolean("isLiveNow", false)
        return when {
            isUpcoming -> VideoKind.UPCOMING
            isLive -> VideoKind.LIVE
            details.optBoolean("isLiveContent", false) -> VideoKind.STREAM_ARCHIVE
            isPortraitShort(response, details) -> VideoKind.SHORT
            status == "OK" -> VideoKind.VIDEO
            else -> VideoKind.UNKNOWN
        }
    }

    private fun isPortraitShort(response: JSONObject, details: JSONObject): Boolean {
        val durationSeconds = details.optString("lengthSeconds").toLongOrNull()
            ?: details.optLong("lengthSeconds", -1L)
        if (durationSeconds !in 1L..MAX_SHORT_SECONDS) return false

        val streamingData = response.optJSONObject("streamingData") ?: return false
        return sequenceOf(
            streamingData.optJSONArray("formats"),
            streamingData.optJSONArray("adaptiveFormats"),
        ).filterNotNull().any(::containsPortraitVideoFormat)
    }

    private fun containsPortraitVideoFormat(formats: JSONArray): Boolean {
        for (index in 0 until formats.length()) {
            val format = formats.optJSONObject(index) ?: continue
            val width = format.optInt("width", 0)
            val height = format.optInt("height", 0)
            if (width > 0 && height > width) return true
        }
        return false
    }

    internal fun classifyHtml(html: String, videoId: String): VideoKind {
        val playerData = extractCurrentPlayerData(html) ?: return VideoKind.UNKNOWN
        if (!currentVideoIdPattern(videoId).containsMatchIn(playerData)) {
            return VideoKind.UNKNOWN
        }
        return when {
            isShort(playerData, videoId) -> VideoKind.SHORT
            LIVE_NOW.containsMatchIn(playerData) -> VideoKind.LIVE
            UPCOMING.containsMatchIn(playerData) -> VideoKind.UPCOMING
            isStreamArchive(playerData) -> VideoKind.STREAM_ARCHIVE
            else -> VideoKind.VIDEO
        }
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

    private fun isStreamArchive(html: String): Boolean =
        LIVE_CONTENT.containsMatchIn(html) ||
            POST_LIVE_DVR.containsMatchIn(html) ||
            ACTUAL_START_TIME.containsMatchIn(html) ||
            LIVE_BROADCAST_DETAILS.containsMatchIn(html)

    private companion object {
        const val PLAYER_ENDPOINT =
            "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
        const val PLAYER_CLIENT_NAME = "ANDROID"
        const val PLAYER_CLIENT_VERSION = "20.10.38"
        const val MAX_PLAYER_RESPONSE_CHARS = 2_000_000
        const val MAX_SHORT_SECONDS = 180L
        val PLAYER_HEADERS = mapOf(
            "User-Agent" to
                "com.google.android.youtube/$PLAYER_CLIENT_VERSION " +
                "(Linux; U; Android 14) gzip",
            "X-YouTube-Client-Name" to "3",
            "X-YouTube-Client-Version" to PLAYER_CLIENT_VERSION,
        )
        const val MAX_INLINE_PLAYER_JSON_CHARS = 100_000
        val PLAYER_RESPONSE_MARKERS = listOf(
            "var ytInitialPlayerResponse =",
            "ytInitialPlayerResponse =",
            "\"ytInitialPlayerResponse\":",
        )
        val SHORT_FLAG = Regex("""[\"']isShort[\"']\s*:\s*true""")
        val LIVE_NOW = Regex("""[\"']isLiveNow[\"']\s*:\s*true""")
        val UPCOMING = Regex("""[\"']isUpcoming[\"']\s*:\s*true""")

        // Po zakończeniu transmisji isLiveNow/isUpcoming są fałszywe, ale strona
        // nadal zawiera informację, że materiał pochodził z transmisji na żywo.
        val LIVE_CONTENT = Regex("""[\"']isLiveContent[\"']\s*:\s*true""")
        val POST_LIVE_DVR = Regex("""[\"']isPostLiveDvr[\"']\s*:\s*true""")
        val ACTUAL_START_TIME = Regex("""[\"']actualStartTime[\"']\s*:""")
        val LIVE_BROADCAST_DETAILS = Regex("""[\"']liveBroadcastDetails[\"']\s*:""")
    }
}
