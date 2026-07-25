package pl.lewicowyt.notifier.network

import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import pl.lewicowyt.notifier.data.LocalDatabase
import pl.lewicowyt.notifier.model.Creator
import pl.lewicowyt.notifier.model.CreatorSource
import pl.lewicowyt.notifier.model.SourceType

data class ResolvedSource(
    val sourceKey: String,
    val type: SourceType,
    val externalId: String,
    val feedUrl: String,
)

class YouTubeSourceResolver(
    private val http: HttpTextClient,
    private val database: LocalDatabase,
) {
    fun sourceKey(creator: Creator, source: CreatorSource): String =
        "${creator.id}|${source.type.name}|${source.url}"

    fun resolve(creator: Creator, source: CreatorSource): ResolvedSource {
        val key = sourceKey(creator, source)
        val id = when (source.type) {
            SourceType.PLAYLIST -> source.externalId
                ?: throw IOException("Brak identyfikatora playlisty: ${source.url}")

            SourceType.CHANNEL -> resolveChannelId(creator, key, source)
        }
        if (
            (source.type == SourceType.CHANNEL && !looksLikeChannelId(id)) ||
            (source.type == SourceType.PLAYLIST && !looksLikePlaylistId(id))
        ) {
            throw IOException("Nieprawidłowy identyfikator źródła YouTube")
        }
        val encoded = URLEncoder.encode(id, StandardCharsets.UTF_8.name())
        val feedUrl = when (source.type) {
            SourceType.CHANNEL ->
                "https://www.youtube.com/feeds/videos.xml?channel_id=$encoded"
            SourceType.PLAYLIST ->
                "https://www.youtube.com/feeds/videos.xml?playlist_id=$encoded"
        }
        return ResolvedSource(key, source.type, id, feedUrl)
    }

    /**
     * Pobiera i zapamiętuje zdjęcie twórcy. Działa bez YouTube Data API.
     * W przypadku playlisty próbuje odczytać avatar właściciela z jej strony.
     */
    fun resolveCreatorAvatar(creator: Creator): String? {
        database.getCreatorAvatar(creator.id)?.takeIf { it.isNotBlank() }?.let { return it }

        for (source in creator.sources) {
            val pageUrl = when (source.type) {
                SourceType.CHANNEL -> normalizeChannelPageUrl(source.url)
                SourceType.PLAYLIST -> {
                    val playlistId = source.externalId
                        ?.takeIf(::looksLikePlaylistId)
                        ?: continue
                    "https://www.youtube.com/playlist?list=${
                        URLEncoder.encode(playlistId, StandardCharsets.UTF_8.name())
                    }"
                }
            }
            val avatar = runCatching {
                val html = http.getText(pageUrl)
                extractAvatarUrl(html)
            }.getOrNull()
            if (!avatar.isNullOrBlank()) {
                database.saveCreatorAvatar(creator.id, avatar)
                return avatar
            }
        }
        return null
    }

    private fun resolveChannelId(
        creator: Creator,
        sourceKey: String,
        source: CreatorSource,
    ): String {
        source.externalId?.takeIf(::looksLikeChannelId)?.let { return it }
        extractChannelId(source.url)?.let { return it }
        database.getResolvedId(sourceKey)?.takeIf(::looksLikeChannelId)?.let { return it }

        val pageUrl = normalizeChannelPageUrl(source.url)
        val html = http.getText(pageUrl)
        val id = CHANNEL_ID_PATTERNS.firstNotNullOfOrNull { pattern ->
            pattern.find(html)?.groupValues?.getOrNull(1)
        } ?: throw IOException("Nie udało się rozpoznać ID kanału z $pageUrl")

        database.saveResolvedId(sourceKey, id)
        extractAvatarUrl(html)?.let { database.saveCreatorAvatar(creator.id, it) }
        return id
    }

    private fun extractAvatarUrl(html: String): String? {
        val raw = AVATAR_PATTERNS.firstNotNullOfOrNull { pattern ->
            pattern.find(html)?.groupValues?.getOrNull(1)
        } ?: return null
        return raw
            .replace("\\u0026", "&")
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .take(MAX_IMAGE_URL_CHARS)
            .takeIf(::isSafeImageUrl)
    }

    private fun extractChannelId(url: String): String? = CHANNEL_IN_URL
        .find(url)
        ?.groupValues
        ?.getOrNull(1)
        ?.takeIf(::looksLikeChannelId)

    private fun normalizeChannelPageUrl(rawUrl: String): String {
        val secure = rawUrl.replaceFirst("http://", "https://")
        val uri = runCatching { URI(secure) }.getOrNull()
            ?: throw IOException("Nieprawidłowy adres kanału YouTube")
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        if (
            !uri.scheme.equals("https", ignoreCase = true) ||
            host !in YOUTUBE_HOSTS ||
            uri.userInfo != null ||
            uri.port !in setOf(-1, 443)
        ) {
            throw IOException("Adres kanału nie należy do YouTube")
        }
        val cleanedPath = uri.path
            .removeSuffix("/featured")
            .removeSuffix("/videos")
            .removeSuffix("/streams")
            .removeSuffix("/shorts")
            .trimEnd('/')
        if (cleanedPath.isBlank() || cleanedPath.length > MAX_YOUTUBE_PATH_CHARS) {
            throw IOException("Nieprawidłowa ścieżka kanału YouTube")
        }
        return URI("https", "www.youtube.com", cleanedPath, null, null).toString()
    }

    private fun looksLikeChannelId(value: String): Boolean =
        CHANNEL_ID.matches(value)

    private fun looksLikePlaylistId(value: String): Boolean =
        PLAYLIST_ID.matches(value)

    private fun isSafeImageUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty().trimEnd('.')
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.userInfo == null &&
            uri.port in setOf(-1, 443) &&
            IMAGE_HOST_SUFFIXES.any { suffix ->
                host == suffix || host.endsWith(".$suffix")
            }
    }.getOrDefault(false)

    private companion object {
        val CHANNEL_IN_URL = Regex("""youtube\.com/channel/(UC[\w-]{18,})""")
        val CHANNEL_ID = Regex("""UC[A-Za-z0-9_-]{22}""")
        val PLAYLIST_ID = Regex("""[A-Za-z0-9_-]{10,100}""")
        val YOUTUBE_HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com")
        val IMAGE_HOST_SUFFIXES = setOf(
            "ytimg.com",
            "ggpht.com",
            "googleusercontent.com",
        )
        const val MAX_YOUTUBE_PATH_CHARS = 500
        const val MAX_IMAGE_URL_CHARS = 2_048
        val CHANNEL_ID_PATTERNS = listOf(
            Regex("""<meta[^>]+itemprop=[\"']channelId[\"'][^>]+content=[\"'](UC[\w-]{18,})[\"']"""),
            Regex("""<meta[^>]+content=[\"'](UC[\w-]{18,})[\"'][^>]+itemprop=[\"']channelId[\"']"""),
            Regex("""[\"']channelId[\"']\s*:\s*[\"'](UC[\w-]{18,})[\"']"""),
            Regex("""youtube\.com/channel/(UC[\w-]{18,})"""),
        )
        val AVATAR_PATTERNS = listOf(
            Regex("""[\"']avatar[\"']\s*:\s*\{[\s\S]{0,800}?[\"']url[\"']\s*:\s*[\"']([^\"']+)[\"']"""),
            Regex("""<meta[^>]+property=[\"']og:image[\"'][^>]+content=[\"']([^\"']+)[\"']"""),
            Regex("""<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+property=[\"']og:image[\"']"""),
        )
    }
}
