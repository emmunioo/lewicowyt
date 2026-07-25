package pl.lewicowyt.notifier.network

import pl.lewicowyt.notifier.model.VideoKind

/**
 * Klasyfikacja bez klucza YouTube Data API na podstawie publicznej strony filmu.
 * Jest to mechanizm best-effort: YouTube może zmieniać wewnętrzny JSON strony.
 */
class YouTubePageClassifier(private val http: HttpTextClient) {
    fun classify(videoId: String): VideoKind = runCatching {
        val html = http.getText(
            "https://www.youtube.com/watch?v=$videoId",
            maxChars = 4_000_000,
        )
        classifyHtml(html, videoId)
    }.getOrDefault(VideoKind.UNKNOWN)

    internal fun classifyHtml(html: String, videoId: String): VideoKind = when {
        isShort(html, videoId) -> VideoKind.SHORT
        LIVE_NOW.containsMatchIn(html) -> VideoKind.LIVE
        UPCOMING.containsMatchIn(html) -> VideoKind.UPCOMING
        isStreamArchive(html) -> VideoKind.STREAM_ARCHIVE
        else -> VideoKind.VIDEO
    }

    private fun isShort(html: String, videoId: String): Boolean {
        if (SHORT_FLAG.containsMatchIn(html)) return true
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
