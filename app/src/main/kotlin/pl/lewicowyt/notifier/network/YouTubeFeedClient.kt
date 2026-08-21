package pl.lewicowyt.notifier.network

import pl.lewicowyt.notifier.model.SourceType
import pl.lewicowyt.notifier.model.VideoEntry
import pl.lewicowyt.notifier.model.VideoKind
import pl.lewicowyt.notifier.model.VideoKindDecision
import pl.lewicowyt.notifier.model.VideoKindEvidence
import java.io.StringReader
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

private val YOUTUBE_VIDEO_ID = Regex("""[A-Za-z0-9_-]{11}""")
private val YOUTUBE_CHANNEL_ID = Regex("""UC[A-Za-z0-9_-]{22}""")
private val YOUTUBE_COMPACT_CHANNEL_ID = Regex("""[A-Za-z0-9_-]{22}""")
private val YOUTUBE_PLAYLIST_ID = Regex("""[A-Za-z0-9_-]{10,100}""")

internal data class YouTubeFeedIdentity(
    val channelId: String?,
    val playlistId: String?,
)

internal data class ParsedYouTubeFeed(
    val identity: YouTubeFeedIdentity,
    val entries: List<VideoEntry>,
)

internal class InvalidYouTubeFeedIdentityException(message: String) : IOException(message)

class YouTubeFeedClient(private val http: HttpTextClient) {
    fun fetch(source: ResolvedSource): List<VideoEntry> {
        val xml = http.getText(source.feedUrl, maxChars = 1_000_000)
        val parsed = parseFeed(xml)
        requireExpectedFeedIdentity(source, parsed.identity)
        return parsed.entries
    }

    internal fun parseFeed(
        xml: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): ParsedYouTubeFeed {
        if (
            xml.length > MAX_FEED_XML_CHARS ||
            xml.contains("<!DOCTYPE", ignoreCase = true) ||
            xml.contains("<!ENTITY", ignoreCase = true)
        ) {
            throw IOException("YouTube RSS ma niedozwoloną strukturę XML.")
        }
        val result = mutableListOf<VideoEntry>()
        var inEntry = false
        var inAuthor = false
        var videoId = ""
        var title = ""
        var published = ""
        var author = ""
        var alternateUrl = ""
        var feedChannelId = ""
        var feedPlaylistId = ""
        var feedRootId = ""
        var feedSelfUrl = ""
        val text = StringBuilder()
        val handler = object : DefaultHandler() {
            override fun startElement(
                uri: String?,
                localName: String?,
                qName: String?,
                attributes: Attributes,
            ) {
                text.setLength(0)
                when (elementName(localName, qName)) {
                    "entry" -> {
                        inEntry = true
                        videoId = ""
                        title = ""
                        published = ""
                        author = ""
                        alternateUrl = ""
                    }
                    "author" -> if (inEntry) inAuthor = true
                    "link" -> if (
                        inEntry &&
                        attributes.getValue("rel") == "alternate"
                    ) {
                        alternateUrl = attributes.getValue("href")
                            .orEmpty()
                            .trim()
                            .take(MAX_URL_CHARS)
                    } else if (
                        !inEntry &&
                        attributes.getValue("rel") == "self"
                    ) {
                        feedSelfUrl = attributes.getValue("href")
                            .orEmpty()
                            .trim()
                            .take(MAX_URL_CHARS)
                    }
                }
            }

            override fun characters(characters: CharArray, start: Int, length: Int) {
                val remaining = MAX_XML_TEXT_CHARS - text.length
                if (remaining > 0) text.append(characters, start, minOf(length, remaining))
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                val value = text.toString().trim()
                when (elementName(localName, qName)) {
                    "videoId" -> if (inEntry) videoId = value
                    "channelId" -> if (!inEntry) feedChannelId = value
                    "playlistId" -> if (!inEntry) feedPlaylistId = value
                    "id" -> if (!inEntry) feedRootId = value
                    "title" -> if (inEntry) title = value
                    "published" -> if (inEntry) published = value
                    "name" -> if (inEntry && inAuthor) author = value
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
                text.setLength(0)
            }
        }
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            isValidating = false
            XML_SECURITY_FEATURES.forEach { (feature, enabled) ->
                runCatching { setFeature(feature, enabled) }
            }
        }
        val reader = factory.newSAXParser().xmlReader.apply {
            contentHandler = handler
            entityResolver = org.xml.sax.EntityResolver { _, _ -> InputSource(StringReader("")) }
        }
        reader.parse(InputSource(StringReader(xml)))
        val channelIdentity = resolveFeedIdentity(
            values = listOf(
                feedChannelId,
                feedRootId.removePrefix(FEED_CHANNEL_ID_PREFIX)
                    .takeIf { feedRootId.startsWith(FEED_CHANNEL_ID_PREFIX) }.orEmpty(),
                feedSelfUrl.feedQueryValue("channel_id").orEmpty(),
            ),
            normalize = ::normalizeYouTubeChannelFeedId,
            label = "channelId",
        )
        val playlistIdentity = resolveFeedIdentity(
            values = listOf(
                feedPlaylistId,
                feedRootId.removePrefix(FEED_PLAYLIST_ID_PREFIX)
                    .takeIf { feedRootId.startsWith(FEED_PLAYLIST_ID_PREFIX) }.orEmpty(),
                feedSelfUrl.feedQueryValue("playlist_id").orEmpty(),
            ),
            normalize = { value -> value.takeIf(YOUTUBE_PLAYLIST_ID::matches) },
            label = "playlistId",
        )
        return ParsedYouTubeFeed(
            identity = YouTubeFeedIdentity(channelIdentity, playlistIdentity),
            entries = result.distinctBy { it.id }.sortedBy { it.publishedAtMillis },
        )
    }

    private fun parseInstant(value: String, nowMillis: Long): Long? {
        return parseYouTubePublishedInstant(value, nowMillis)
    }

    private companion object {
        const val MAX_TITLE_CHARS = 300
        const val MAX_AUTHOR_CHARS = 200
        const val MAX_URL_CHARS = 2_048
        const val MAX_XML_TEXT_CHARS = 8_192
        const val MAX_FEED_XML_CHARS = 1_000_000
        val XML_SECURITY_FEATURES = mapOf(
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
            "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
        )
    }
}

private fun elementName(localName: String?, qName: String?): String =
    localName?.takeIf(String::isNotBlank) ?: qName.orEmpty().substringAfter(':')

internal fun requireExpectedFeedIdentity(
    source: ResolvedSource,
    identity: YouTubeFeedIdentity,
) {
    val actual = when (source.type) {
        SourceType.CHANNEL -> identity.channelId
        SourceType.PLAYLIST -> identity.playlistId
    }
    if (actual == null) {
        throw InvalidYouTubeFeedIdentityException(
            "YouTube RSS nie podał tożsamości oczekiwanego źródła.",
        )
    }
    if (actual != source.externalId) {
        throw InvalidYouTubeFeedIdentityException(
            "YouTube RSS zwrócił dane innego źródła.",
        )
    }
}

internal fun requireNoFeedIdentityFailure(result: Result<*>) {
    val error = result.exceptionOrNull()
    if (error is InvalidYouTubeFeedIdentityException) throw error
}

private fun resolveFeedIdentity(
    values: List<String>,
    normalize: (String) -> String?,
    label: String,
): String? {
    val present = values.map(String::trim).filter(String::isNotEmpty)
    val normalized = present.map { value ->
        normalize(value) ?: throw InvalidYouTubeFeedIdentityException(
            "YouTube RSS podał nieprawidłowy $label.",
        )
    }
    val distinct = normalized.distinct()
    if (distinct.size > 1) {
        throw InvalidYouTubeFeedIdentityException("YouTube RSS podał sprzeczny $label.")
    }
    return distinct.singleOrNull()
}

/**
 * Oficjalny Atom feed YouTube zapisuje feed-level `yt:channelId` oraz
 * `yt:channel:...` bez stałego prefiksu `UC`, mimo że parametr URL i katalog
 * aplikacji używają pełnego 24-znakowego ID. Normalizujemy wyłącznie ten
 * udokumentowany wariant; inne długości i znaki nadal są odrzucane fail-closed.
 */
private fun normalizeYouTubeChannelFeedId(value: String): String? = when {
    YOUTUBE_CHANNEL_ID.matches(value) -> value
    YOUTUBE_COMPACT_CHANNEL_ID.matches(value) -> "UC$value"
    else -> null
}

private fun String.feedQueryValue(name: String): String? = runCatching {
    val uri = URI(this)
    if (
        uri.scheme?.lowercase() !in setOf("http", "https") ||
        uri.host?.lowercase() !in setOf("youtube.com", "www.youtube.com") ||
        uri.path != "/feeds/videos.xml"
    ) return@runCatching null
    uri.rawQuery.orEmpty().split('&').firstNotNullOfOrNull { parameter ->
        val rawName = parameter.substringBefore('=', missingDelimiterValue = "")
        if (rawName != name) return@firstNotNullOfOrNull null
        URLDecoder.decode(parameter.substringAfter('=', ""), StandardCharsets.UTF_8.name())
    }
}.getOrNull()

private const val FEED_CHANNEL_ID_PREFIX = "yt:channel:"
private const val FEED_PLAYLIST_ID_PREFIX = "yt:playlist:"

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
