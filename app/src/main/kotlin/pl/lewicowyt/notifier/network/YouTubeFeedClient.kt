package pl.lewicowyt.notifier.network

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import pl.lewicowyt.notifier.model.VideoEntry
import pl.lewicowyt.notifier.model.VideoKind
import pl.lewicowyt.notifier.model.VideoKindDecision
import pl.lewicowyt.notifier.model.VideoKindEvidence
import java.io.StringReader
import java.net.URI
import java.time.Instant

class YouTubeFeedClient(private val http: HttpTextClient) {
    fun fetch(source: ResolvedSource): List<VideoEntry> {
        val xml = http.getText(source.feedUrl, maxChars = 1_000_000)
        return parseFeed(xml)
    }

    internal fun parseFeed(
        xml: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<VideoEntry> {
        val parser = XmlPullParserFactory.newInstance().apply {
            isNamespaceAware = false
        }.newPullParser().apply {
            setInput(StringReader(xml))
        }

        val result = mutableListOf<VideoEntry>()
        var inEntry = false
        var inAuthor = false
        var videoId = ""
        var title = ""
        var published = ""
        var author = ""
        var alternateUrl = ""

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name.localName()) {
                    "entry" -> {
                        inEntry = true
                        videoId = ""
                        title = ""
                        published = ""
                        author = ""
                        alternateUrl = ""
                    }
                    "author" -> if (inEntry) inAuthor = true
                    "videoId" -> if (inEntry) videoId = parser.safeNextText()
                    "title" -> if (inEntry) title = parser.safeNextText()
                    "published" -> if (inEntry) published = parser.safeNextText()
                    "name" -> if (inEntry && inAuthor) author = parser.safeNextText()
                    "link" -> if (
                        inEntry &&
                        parser.getAttributeValue(null, "rel") == "alternate"
                    ) {
                        alternateUrl = parser.getAttributeValue(null, "href")
                            .orEmpty()
                            .trim()
                            .take(MAX_URL_CHARS)
                    }
                }

                XmlPullParser.END_TAG -> when (parser.name.localName()) {
                    "author" -> inAuthor = false
                    "entry" -> {
                        inEntry = false
                        val publishedAtMillis = parseInstant(published, nowMillis)
                        if (
                            YOUTUBE_VIDEO_ID.matches(videoId) &&
                            publishedAtMillis != null
                        ) {
                            result += VideoEntry(
                                id = videoId,
                                title = title.ifBlank { "Materiał bez tytułu" }
                                    .take(MAX_TITLE_CHARS),
                                url = canonicalYouTubeEntryUrl(videoId, alternateUrl),
                                publishedAtMillis = publishedAtMillis,
                                author = author.take(MAX_AUTHOR_CHARS),
                            )
                        }
                    }
                }
            }
            parser.next()
        }
        return result.distinctBy { it.id }.sortedBy { it.publishedAtMillis }
    }

    private fun parseInstant(value: String, nowMillis: Long): Long? {
        return parseYouTubePublishedInstant(value, nowMillis)
    }

    private fun String.localName(): String = substringAfter(':')

    private fun XmlPullParser.safeNextText(): String = runCatching { nextText() }
        .getOrDefault("")
        .trim()

    private companion object {
        val YOUTUBE_VIDEO_ID = Regex("""[A-Za-z0-9_-]{11}""")
        const val MAX_TITLE_CHARS = 300
        const val MAX_AUTHOR_CHARS = 200
        const val MAX_URL_CHARS = 2_048
    }
}

internal fun parseYouTubePublishedInstant(
    value: String,
    nowMillis: Long,
): Long? {
    val publishedAtMillis = runCatching {
        Instant.parse(value).toEpochMilli()
    }.getOrNull() ?: return null
    return publishedAtMillis.takeIf {
        it > 0L && it <= nowMillis + FEED_MAX_FUTURE_SKEW_MILLIS
    }
}

private const val FEED_MAX_FUTURE_SKEW_MILLIS = 10L * 60L * 1_000L

/**
 * Atomowy RSS YouTube czasem publikuje dla Shorta kanoniczny adres `/shorts/`.
 * Zachowujemy wyłącznie ten jednoznaczny sygnał; każdy inny lub obcy adres
 * zastępujemy bezpiecznym adresem `watch` z identyfikatorem z pola yt:videoId.
 */
internal fun canonicalYouTubeEntryUrl(videoId: String, alternateUrl: String): String {
    val shortUrl = runCatching { URI(alternateUrl) }.getOrNull()
        ?.takeIf {
            it.scheme.equals("https", ignoreCase = true) &&
                it.host?.lowercase()?.let(TRUSTED_YOUTUBE_HOSTS::contains) == true &&
                it.path == "/shorts/$videoId"
        }
        ?.let { "https://www.youtube.com/shorts/$videoId" }
    return shortUrl ?: "https://www.youtube.com/watch?v=$videoId"
}

internal fun rssVideoKindDecision(entry: VideoEntry): VideoKindDecision =
    if (entry.url == "https://www.youtube.com/shorts/${entry.id}") {
        VideoKindDecision(
            kind = VideoKind.SHORT,
            evidence = VideoKindEvidence.RSS_SHORT_URL,
        )
    } else {
        VideoKindDecision(
            kind = VideoKind.VIDEO,
            evidence = VideoKindEvidence.DEFAULT_VIDEO_FALLBACK,
        )
    }

private val TRUSTED_YOUTUBE_HOSTS = setOf(
    "youtube.com",
    "www.youtube.com",
    "m.youtube.com",
)
