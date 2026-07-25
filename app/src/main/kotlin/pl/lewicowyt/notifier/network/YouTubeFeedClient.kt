package pl.lewicowyt.notifier.network

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import pl.lewicowyt.notifier.model.VideoEntry
import java.io.StringReader
import java.time.Instant

class YouTubeFeedClient(private val http: HttpTextClient) {
    fun fetch(source: ResolvedSource): List<VideoEntry> {
        val xml = http.getText(source.feedUrl, maxChars = 1_000_000)
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

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name.localName()) {
                    "entry" -> {
                        inEntry = true
                        videoId = ""
                        title = ""
                        published = ""
                        author = ""
                    }
                    "author" -> if (inEntry) inAuthor = true
                    "videoId" -> if (inEntry) videoId = parser.safeNextText()
                    "title" -> if (inEntry) title = parser.safeNextText()
                    "published" -> if (inEntry) published = parser.safeNextText()
                    "name" -> if (inEntry && inAuthor) author = parser.safeNextText()
                }

                XmlPullParser.END_TAG -> when (parser.name.localName()) {
                    "author" -> inAuthor = false
                    "entry" -> {
                        inEntry = false
                        val publishedAtMillis = parseInstant(published)
                        if (
                            YOUTUBE_VIDEO_ID.matches(videoId) &&
                            publishedAtMillis != null
                        ) {
                            result += VideoEntry(
                                id = videoId,
                                title = title.ifBlank { "Materiał bez tytułu" }
                                    .take(MAX_TITLE_CHARS),
                                url = "https://www.youtube.com/watch?v=$videoId",
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

    private fun parseInstant(value: String): Long? {
        val publishedAtMillis = runCatching {
            Instant.parse(value).toEpochMilli()
        }.getOrNull() ?: return null
        val now = System.currentTimeMillis()
        return publishedAtMillis.takeIf {
            it > 0L && it <= now + MAX_FUTURE_SKEW_MILLIS
        }
    }

    private fun String.localName(): String = substringAfter(':')

    private fun XmlPullParser.safeNextText(): String = runCatching { nextText() }
        .getOrDefault("")
        .trim()

    private companion object {
        val YOUTUBE_VIDEO_ID = Regex("""[A-Za-z0-9_-]{11}""")
        const val MAX_FUTURE_SKEW_MILLIS = 10L * 60L * 1_000L
        const val MAX_TITLE_CHARS = 300
        const val MAX_AUTHOR_CHARS = 200
    }
}
