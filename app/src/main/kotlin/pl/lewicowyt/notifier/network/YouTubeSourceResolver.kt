package pl.lewicowyt.notifier.network

import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import pl.lewicowyt.notifier.data.LocalDatabase
import pl.lewicowyt.notifier.images.BundledAvatarStore
import pl.lewicowyt.notifier.model.Creator
import pl.lewicowyt.notifier.model.CreatorSource
import pl.lewicowyt.notifier.model.SourceType

data class ResolvedSource(
    val sourceKey: String,
    val type: SourceType,
    val externalId: String,
    val feedUrl: String,
)

/**
 * Reads only metadata that identifies the channel whose page is open.
 *
 * A YouTube channel page also contains IDs of recommended channels. Generic
 * `channelId` fields and `/channel/...` links must not be used here.
 */
internal fun extractCanonicalYouTubeChannelId(html: String): String? =
    CANONICAL_CHANNEL_ID_PATTERNS.firstNotNullOfOrNull { pattern ->
        pattern.find(html)?.groupValues?.getOrNull(1)
    }

/**
 * Prosi CDN YouTube o kwadratowy avatar 176 px. Modyfikujemy wyłącznie
 * zweryfikowane adresy yt3; zwykła miniatura filmu nigdy nie może zostać
 * przypadkowo potraktowana jak zdjęcie twórcy.
 */
internal fun normalizeYouTubeAvatarUrl(value: String): String? = runCatching {
    if (value.length > MAX_IMAGE_URL_CHARS) return@runCatching null
    val url = value.toHttpUrlOrNull() ?: return@runCatching null
    val host = url.host.lowercase(Locale.ROOT).trimEnd('.')
    if (
        !url.isHttps ||
        host !in YOUTUBE_AVATAR_HOSTS ||
        url.username.isNotEmpty() ||
        url.password.isNotEmpty() ||
        url.port != 443
    ) {
        return@runCatching null
    }
    val rawPath = url.encodedPath
    if (rawPath.isBlank() || rawPath.length > MAX_IMAGE_URL_CHARS) {
        return@runCatching null
    }
    val normalizedPath = if (AVATAR_SIZE_SUFFIX.containsMatchIn(rawPath)) {
        rawPath.replace(AVATAR_SIZE_SUFFIX, AVATAR_176_SUFFIX)
    } else {
        "$rawPath$AVATAR_176_SUFFIX"
    }
    url.newBuilder()
        .host(host)
        .encodedPath(normalizedPath)
        .build()
        .toString()
        .takeIf { it.length <= MAX_IMAGE_URL_CHARS }
}.getOrNull()

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
        database.getCreatorAvatar(creator.id)
            ?.takeIf { it.isNotBlank() }
            ?.let { cached ->
                if (BundledAvatarStore.isBundledAvatarUrl(cached)) return cached
                normalizeYouTubeAvatarUrl(cached)?.let { normalized ->
                    if (normalized != cached) {
                        database.saveCreatorAvatar(creator.id, normalized)
                    }
                    return normalized
                }
            }

        return resolveFreshCreatorAvatar(creator)?.also { avatar ->
            database.saveCreatorAvatar(creator.id, avatar)
        }
    }

    /** Pomija zapisany URL; używane przez tygodniową kontrolę zawartości. */
    fun resolveFreshCreatorAvatar(creator: Creator): String? {
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
        val id = extractCanonicalYouTubeChannelId(html)
            ?: throw IOException("Nie udało się rozpoznać ID kanału z $pageUrl")

        database.saveResolvedId(sourceKey, id)
        extractAvatarUrl(html)?.let { database.saveCreatorAvatar(creator.id, it) }
        return id
    }

    private fun extractAvatarUrl(html: String): String? {
        return AVATAR_PATTERNS.firstNotNullOfOrNull { pattern ->
            pattern.find(html)
                ?.groupValues
                ?.getOrNull(1)
                ?.replace("\\u0026", "&")
                ?.replace("\\u003d", "=", ignoreCase = true)
                ?.replace("\\/", "/")
                ?.replace("&amp;", "&")
                ?.take(MAX_IMAGE_URL_CHARS)
                ?.let(::normalizeYouTubeAvatarUrl)
        }
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

    private companion object {
        val CHANNEL_IN_URL = Regex("""youtube\.com/channel/(UC[\w-]{18,})""")
        val CHANNEL_ID = Regex("""UC[A-Za-z0-9_-]{22}""")
        val PLAYLIST_ID = Regex("""[A-Za-z0-9_-]{10,100}""")
        val YOUTUBE_HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com")
        const val MAX_YOUTUBE_PATH_CHARS = 500
        val AVATAR_PATTERNS = listOf(
            Regex("""[\"']avatar[\"']\s*:\s*\{[\s\S]{0,800}?[\"']url[\"']\s*:\s*[\"']([^\"']+)[\"']"""),
            Regex("""<meta[^>]+property=[\"']og:image[\"'][^>]+content=[\"']([^\"']+)[\"']"""),
            Regex("""<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+property=[\"']og:image[\"']"""),
        )
    }
}

private const val MAX_IMAGE_URL_CHARS = 2_048
private const val AVATAR_176_SUFFIX = "=s176-c-k-c0x00ffffff-no-rj"
private val AVATAR_SIZE_SUFFIX = Regex("""=s\d{1,4}(?:-[A-Za-z0-9]+)*$""")
private val YOUTUBE_AVATAR_HOSTS = setOf(
    "yt3.ggpht.com",
    "yt3.googleusercontent.com",
)

private val CANONICAL_CHANNEL_ID_PATTERNS = listOf(
    Regex(""""externalId"\s*:\s*"(UC[A-Za-z0-9_-]{22})""""),
    Regex("""<meta[^>]+itemprop=["']channelId["'][^>]+content=["'](UC[A-Za-z0-9_-]{22})["']"""),
    Regex("""<meta[^>]+content=["'](UC[A-Za-z0-9_-]{22})["'][^>]+itemprop=["']channelId["']"""),
)
