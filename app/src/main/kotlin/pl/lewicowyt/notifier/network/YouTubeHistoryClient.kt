package pl.lewicowyt.notifier.network

import java.io.IOException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject
import pl.lewicowyt.notifier.model.SourceType
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
    val kindVerified: Boolean = true,
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
 * Czyta publiczny endpoint używany przez webowy interfejs YouTube.
 * Nie wymaga klucza YouTube Data API. Pobiera od razu żądaną kartę, a następnie
 * sprawdza, którą kartę YouTube rzeczywiście zaznaczył. Dzięki temu brak
 * `/streams` nie zmienia zwykłych filmów w archiwalne transmisje i nie wymaga
 * dodatkowego żądania listy kart dla każdego kanału.
 */
class YouTubeHistoryClient(private val http: HttpTextClient) {
    private val channelTabsCache = ConcurrentHashMap<String, CachedYouTubeTabs>()

    fun firstPage(
        source: ResolvedSource,
        tab: YouTubeHistoryTab,
        nowMillis: Long = System.currentTimeMillis(),
    ): YouTubeHistoryPage {
        if (source.type == SourceType.CHANNEL && tab != YouTubeHistoryTab.PLAYLIST) {
            return try {
                firstChannelPageFromBrowse(source, tab, nowMillis)
            } catch (browseError: Exception) {
                try {
                    firstPageFromHtml(source, tab, nowMillis, validateChannelTab = true)
                } catch (htmlError: Exception) {
                    htmlError.addSuppressed(browseError)
                    throw htmlError
                }
            }
        }
        return firstPageFromHtml(source, tab, nowMillis, validateChannelTab = false)
    }

    private fun firstChannelPageFromBrowse(
        source: ResolvedSource,
        tab: YouTubeHistoryTab,
        nowMillis: Long,
    ): YouTubeHistoryPage {
        knownTabs(source.externalId)?.let { tabs ->
            if (tab !in tabs) return emptyPage()
        }
        val json = browse(
            browseId = source.externalId,
            params = tab.requestParams(),
            apiKey = PUBLIC_WEB_API_KEY,
            clientVersion = DEFAULT_CLIENT_VERSION,
        )
        rememberAvailableTabs(source.externalId, json)
        val selectedTab = findSelectedYouTubeTab(json)
            ?: throw IOException("YouTube nie potwierdził wybranej karty kanału")
        if (selectedTab.type != tab) return emptyPage()
        return parsePage(
            json = json,
            tab = tab,
            apiKey = PUBLIC_WEB_API_KEY,
            clientVersion = DEFAULT_CLIENT_VERSION,
            nowMillis = nowMillis,
            previousCursorToken = null,
        )
    }

    private fun knownTabs(channelId: String): Set<YouTubeHistoryTab>? {
        val now = System.currentTimeMillis()
        return channelTabsCache[channelId]
            ?.takeIf { now - it.discoveredAtMillis < TAB_CACHE_MILLIS }
            ?.tabs
    }

    private fun rememberAvailableTabs(channelId: String, json: JSONObject) {
        val tabs = extractAvailableYouTubeTabs(json).keys
        if (tabs.isNotEmpty()) {
            channelTabsCache[channelId] = CachedYouTubeTabs(
                tabs = tabs,
                discoveredAtMillis = System.currentTimeMillis(),
            )
        }
    }

    private fun firstPageFromHtml(
        source: ResolvedSource,
        tab: YouTubeHistoryTab,
        nowMillis: Long,
        validateChannelTab: Boolean,
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
        val json = JSONObject(initialData)
        if (validateChannelTab) {
            rememberAvailableTabs(source.externalId, json)
            val selectedTab = findSelectedYouTubeTab(json)
                ?: throw IOException("YouTube nie potwierdził wybranej karty kanału")
            if (selectedTab.type != tab) return emptyPage()
        }
        return parsePage(
            json = json,
            tab = tab,
            apiKey = apiKey,
            clientVersion = clientVersion,
            nowMillis = nowMillis,
            previousCursorToken = null,
        )
    }

    private fun browse(
        browseId: String,
        params: String?,
        apiKey: String,
        clientVersion: String,
    ): JSONObject {
        val body = JSONObject()
            .put("context", webContext(clientVersion))
            .put("browseId", browseId)
            .apply {
                if (!params.isNullOrBlank()) put("params", params)
            }
            .toString()
        val response = http.postJson(
            url = "$BROWSE_ENDPOINT?prettyPrint=false&key=$apiKey",
            json = body,
            maxChars = MAX_PAGE_CHARS,
            headers = webHeaders(clientVersion),
        )
        if (!hasSafeJsonNesting(response)) {
            throw IOException("Dane kanału YouTube mają zbyt głęboką strukturę JSON")
        }
        return JSONObject(response)
    }

    private fun webContext(clientVersion: String): JSONObject = JSONObject().put(
        "client",
        JSONObject()
            .put("clientName", "WEB")
            .put("clientVersion", clientVersion)
            .put("hl", "pl")
            .put("gl", "PL"),
    )

    private fun webHeaders(clientVersion: String): Map<String, String> = mapOf(
        "X-YouTube-Client-Name" to "1",
        "X-YouTube-Client-Version" to clientVersion,
    )

    private fun emptyPage(): YouTubeHistoryPage =
        YouTubeHistoryPage(items = emptyList(), nextCursor = null)

    fun nextPage(
        cursor: YouTubeHistoryCursor,
        tab: YouTubeHistoryTab,
        nowMillis: Long = System.currentTimeMillis(),
    ): YouTubeHistoryPage {
        val body = JSONObject()
            .put(
                "context",
                webContext(cursor.clientVersion),
            )
            .put("continuation", cursor.token)
            .toString()
        val response = http.postJson(
            url = "$BROWSE_ENDPOINT?prettyPrint=false&key=${cursor.apiKey}",
            json = body,
            maxChars = MAX_PAGE_CHARS,
            headers = webHeaders(cursor.clientVersion),
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
            previousCursorToken = cursor.token,
        )
    }

    internal fun parsePage(
        json: JSONObject,
        tab: YouTubeHistoryTab,
        apiKey: String,
        clientVersion: String,
        nowMillis: Long,
        previousCursorToken: String?,
    ): YouTubeHistoryPage {
        val items = linkedMapOf<String, YouTubeHistoryItem>()
        // Pierwsza odpowiedź zawiera także nieaktywne karty, nawigację i
        // polecane materiały. Typ karty wolno przypisać wyłącznie filmom
        // znajdującym się w zawartości aktualnie wybranej karty.
        val content = if (previousCursorToken == null) {
            selectedYouTubeTabContent(json) ?: json
        } else {
            // Odpowiedź kontynuacji zawiera appendContinuationItemsAction poza
            // strukturą kart. Ograniczenie jej do tabRenderer może ponownie
            // wybrać stary kursor z dołączonych danych nawigacyjnych.
            json
        }
        val recognizedRenderers = collectVideos(content, tab, nowMillis, items)
        val token = if (
            tab == YouTubeHistoryTab.SHORTS &&
            recognizedRenderers > 0 &&
            items.isEmpty()
        ) {
            // Aktualne kafelki Shorts nie zawierają dat publikacji. RSS zapewnia
            // najnowsze wpisy; bez daty nie wolno udawać czasu ani przewijać
            // bez końca całej karty.
            null
        } else {
            findYouTubeContinuationToken(
                value = content,
                previousToken = previousCursorToken,
            )
        }
        if (recognizedRenderers > 0 && items.isEmpty()) {
            if (tab == YouTubeHistoryTab.SHORTS) {
                return YouTubeHistoryPage(items = emptyList(), nextCursor = null)
            }
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
                value.optJSONObject("lockupViewModel")?.let { renderer ->
                    if (renderer.optString("contentType") == LOCKUP_VIDEO_CONTENT_TYPE) {
                        recognized += 1
                        val videoId = renderer.optString("contentId")
                            .takeIf(YOUTUBE_VIDEO_ID::matches)
                            ?: findStringByKey(renderer, "videoId")
                                ?.takeIf(YOUTUBE_VIDEO_ID::matches)
                        if (videoId != null && videoId !in result) {
                            parseLockupVideo(renderer, videoId, tab, nowMillis)
                                ?.let { result[videoId] = it }
                        }
                    }
                }
                value.optJSONObject("shortsLockupViewModel")?.let { renderer ->
                    recognized += 1
                    val videoId = findStringByKey(renderer, "videoId")
                        ?.takeIf(YOUTUBE_VIDEO_ID::matches)
                    if (videoId != null && videoId !in result) {
                        parseShortsLockup(renderer, videoId, nowMillis)
                            ?.let { result[videoId] = it }
                    }
                }
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

    private fun parseLockupVideo(
        renderer: JSONObject,
        videoId: String,
        tab: YouTubeHistoryTab,
        nowMillis: Long,
    ): YouTubeHistoryItem? {
        val metadata = renderer.optJSONObject("metadata")
            ?.optJSONObject("lockupMetadataViewModel")
            ?: return null
        val title = metadata.optJSONObject("title")
            ?.optString("content")
            ?.takeIf { it.isNotBlank() }
            ?.take(MAX_TITLE_CHARS)
            ?: return null
        val contentMetadata = metadata.optJSONObject("metadata")
            ?.optJSONObject("contentMetadataViewModel")
        val publishedMillis = findStringValues(contentMetadata)
            .asSequence()
            .map { it.take(MAX_PUBLISHED_TEXT_CHARS) }
            .mapNotNull { parsePublishedTime(it, nowMillis) }
            .firstOrNull()
            ?: return null
        if (publishedMillis <= 0L || publishedMillis > nowMillis + MAX_FUTURE_MILLIS) {
            return null
        }
        val serialized = renderer.toString()
        val kind = when {
            serialized.contains("upcomingEventData") ||
                serialized.contains("BADGE_STYLE_TYPE_UPCOMING") -> VideoKind.UPCOMING
            serialized.contains("BADGE_STYLE_TYPE_LIVE_NOW") -> VideoKind.LIVE
            tab == YouTubeHistoryTab.STREAMS -> VideoKind.STREAM_ARCHIVE
            tab == YouTubeHistoryTab.SHORTS -> VideoKind.SHORT
            else -> VideoKind.VIDEO
        }
        return historyItem(
            videoId = videoId,
            title = title,
            publishedMillis = publishedMillis,
            kind = kind,
            kindVerified = tab != YouTubeHistoryTab.PLAYLIST,
        )
    }

    private fun parseShortsLockup(
        renderer: JSONObject,
        videoId: String,
        nowMillis: Long,
    ): YouTubeHistoryItem? {
        val title = renderer.optJSONObject("overlayMetadata")
            ?.optJSONObject("primaryText")
            ?.optString("content")
            ?.takeIf { it.isNotBlank() }
            ?.take(MAX_TITLE_CHARS)
            ?: return null
        val publishedMillis = findStringValues(renderer)
            .asSequence()
            .map { it.take(MAX_PUBLISHED_TEXT_CHARS) }
            .mapNotNull { parsePublishedTime(it, nowMillis) }
            .firstOrNull()
            ?: return null
        return historyItem(videoId, title, publishedMillis, VideoKind.SHORT)
    }

    private fun historyItem(
        videoId: String,
        title: String,
        publishedMillis: Long,
        kind: VideoKind,
        kindVerified: Boolean = true,
    ): YouTubeHistoryItem = YouTubeHistoryItem(
        entry = VideoEntry(
            id = videoId,
            title = title,
            url = "https://www.youtube.com/watch?v=$videoId",
            publishedAtMillis = publishedMillis,
            author = "",
        ),
        kind = kind,
        kindVerified = kindVerified,
    )

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
        return historyItem(
            videoId = videoId,
            title = title,
            publishedMillis = publishedMillis,
            kind = kind,
            // Playlista potwierdza obecność filmu, lecz sama nie rozstrzyga,
            // czy był on Shortem albo transmisją.
            kindVerified = tab != YouTubeHistoryTab.PLAYLIST,
        )
    }

    private fun readText(value: Any?): String? = when (value) {
        is String -> value
        is JSONObject -> {
            value.optString("content").takeIf { it.isNotBlank() }
                ?: value.optString("simpleText").takeIf { it.isNotBlank() }
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

    private fun findStringByKey(value: Any?, wantedKey: String): String? {
        when (value) {
            is JSONObject -> {
                value.optString(wantedKey).takeIf { it.isNotBlank() }?.let { return it }
                value.keys().forEach { key ->
                    findStringByKey(value.opt(key), wantedKey)?.let { return it }
                }
            }

            is JSONArray -> for (index in 0 until value.length()) {
                findStringByKey(value.opt(index), wantedKey)?.let { return it }
            }
        }
        return null
    }

    private fun findStringValues(value: Any?): List<String> = buildList {
        fun collect(current: Any?) {
            when (current) {
                is String -> if (current.isNotBlank()) add(current)
                is JSONObject -> current.keys().forEach { key -> collect(current.opt(key)) }
                is JSONArray -> for (index in 0 until current.length()) {
                    collect(current.opt(index))
                }
            }
        }
        collect(value)
    }

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
        const val TAB_CACHE_MILLIS = 15L * 60L * 1_000L
        const val BROWSE_ENDPOINT = "https://www.youtube.com/youtubei/v1/browse"
        const val PUBLIC_WEB_API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
        const val DEFAULT_CLIENT_VERSION = "2.20260727.10.00"
        const val LOCKUP_VIDEO_CONTENT_TYPE = "LOCKUP_CONTENT_TYPE_VIDEO"
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

private data class CachedYouTubeTabs(
    val tabs: Set<YouTubeHistoryTab>,
    val discoveredAtMillis: Long,
)

internal data class SelectedYouTubeTab(
    val type: YouTubeHistoryTab?,
)

private fun YouTubeHistoryTab.requestParams(): String = when (this) {
    YouTubeHistoryTab.VIDEOS -> VIDEOS_PARAMS
    YouTubeHistoryTab.STREAMS -> STREAMS_PARAMS
    YouTubeHistoryTab.SHORTS -> SHORTS_PARAMS
    YouTubeHistoryTab.PLAYLIST ->
        throw IllegalArgumentException("Playlista nie jest kartą kanału")
}

/**
 * Rozpoznaje karty po identyfikatorze/parametrach endpointu, nigdy po
 * lokalizowanym tytule. Brak STREAMS jest prawidłowym wynikiem.
 */
internal fun extractAvailableYouTubeTabs(value: Any?): Map<YouTubeHistoryTab, String> {
    val result = linkedMapOf<YouTubeHistoryTab, String>()

    fun collect(current: Any?) {
        when (current) {
            is JSONObject -> {
                current.optJSONObject("tabRenderer")?.let { tab ->
                    val endpointContainer = tabEndpointContainer(tab)
                    val endpoint = endpointContainer?.optJSONObject("browseEndpoint")
                    val rawParams = endpoint?.optString("params")
                        ?.takeIf { it.isNotBlank() }
                    if (rawParams != null) {
                        val decoded = decodeTabParams(rawParams)
                        val type = identifyYouTubeTab(tab, endpointContainer, endpoint)
                        if (type != null) result.putIfAbsent(type, decoded)
                    }
                }
                current.keys().forEach { key -> collect(current.opt(key)) }
            }

            is JSONArray -> for (index in 0 until current.length()) {
                collect(current.opt(index))
            }
        }
    }

    collect(value)
    return result
}

internal fun findSelectedYouTubeTab(value: Any?): SelectedYouTubeTab? {
    when (value) {
        is JSONObject -> {
            value.optJSONObject("tabRenderer")?.let { tab ->
                if (tab.optBoolean("selected", false)) {
                    val endpointContainer = tabEndpointContainer(tab)
                    return SelectedYouTubeTab(
                        identifyYouTubeTab(
                            tab = tab,
                            endpointContainer = endpointContainer,
                            browseEndpoint = endpointContainer?.optJSONObject("browseEndpoint"),
                        ),
                    )
                }
            }
            value.keys().forEach { key ->
                findSelectedYouTubeTab(value.opt(key))?.let { return it }
            }
        }

        is JSONArray -> for (index in 0 until value.length()) {
            findSelectedYouTubeTab(value.opt(index))?.let { return it }
        }
    }
    return null
}

private fun tabEndpointContainer(tab: JSONObject): JSONObject? =
    tab.optJSONObject("endpoint")
        ?: tab.optJSONObject("navigationEndpoint")

private fun identifyYouTubeTab(
    tab: JSONObject,
    endpointContainer: JSONObject?,
    browseEndpoint: JSONObject?,
): YouTubeHistoryTab? {
    val identifier = tab.optString("tabIdentifier").lowercase(Locale.ROOT)
    val params = browseEndpoint?.optString("params")
        ?.takeIf { it.isNotBlank() }
        ?.let(::decodeTabParams)
        .orEmpty()
    val endpointPath = endpointContainer
        ?.optJSONObject("commandMetadata")
        ?.optJSONObject("webCommandMetadata")
        ?.optString("url")
        .orEmpty()
        .substringBefore('?')
        .trimEnd('/')
        .substringAfterLast('/')
        .lowercase(Locale.ROOT)
    return when {
        identifier == "videos" ||
            endpointPath == "videos" ||
            params.startsWith(VIDEOS_PARAMS_PREFIX) -> YouTubeHistoryTab.VIDEOS
        identifier == "streams" ||
            endpointPath == "streams" ||
            params.startsWith(STREAMS_PARAMS_PREFIX) -> YouTubeHistoryTab.STREAMS
        identifier == "shorts" ||
            endpointPath == "shorts" ||
            params.startsWith(SHORTS_PARAMS_PREFIX) -> YouTubeHistoryTab.SHORTS
        else -> null
    }
}

private fun decodeTabParams(value: String): String = runCatching {
    URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}.getOrDefault(value)

private const val VIDEOS_PARAMS = "EgZ2aWRlb3PyBgQKAjoA"
private const val STREAMS_PARAMS = "EgdzdHJlYW1z8gYECgJ6AA=="
private const val SHORTS_PARAMS = "EgZzaG9ydHPyBgUKA5oBAA=="
private const val VIDEOS_PARAMS_PREFIX = "EgZ2aWRlb3M"
private const val STREAMS_PARAMS_PREFIX = "EgdzdHJlYW1z"
private const val SHORTS_PARAMS_PREFIX = "EgZzaG9ydHM"

internal fun selectedYouTubeTabContent(value: Any?): Any? {
    when (value) {
        is JSONObject -> {
            value.optJSONObject("tabRenderer")?.let { tab ->
                if (tab.optBoolean("selected", false)) {
                    return tab.opt("content").takeUnless {
                        it == null || it === JSONObject.NULL
                    }
                }
            }
            value.keys().forEach { key ->
                selectedYouTubeTabContent(value.opt(key))?.let { return it }
            }
        }

        is JSONArray -> for (index in 0 until value.length()) {
            selectedYouTubeTabContent(value.opt(index))?.let { return it }
        }
    }
    return null
}

/**
 * Zwraca wyłącznie token faktycznego elementu „załaduj następną stronę”.
 * Odpowiedzi YouTube zawierają też inne continuationCommand (nawigacja,
 * ponowienie i echo żądania), których nie wolno używać do stronicowania.
 */
internal fun findYouTubeContinuationToken(
    value: Any?,
    previousToken: String? = null,
): String? {
    val candidates = linkedSetOf<String>()

    fun addToken(token: String?) {
        token
            ?.takeIf {
                it.isNotBlank() &&
                    it.length <= MAX_YOUTUBE_CONTINUATION_TOKEN_CHARS
            }
            ?.let(candidates::add)
    }

    fun collectCommandsInsideRenderer(rendererValue: Any?) {
        when (rendererValue) {
            is JSONObject -> {
                rendererValue.optJSONObject("continuationCommand")
                    ?.optString("token")
                    ?.let(::addToken)
                rendererValue.keys().forEach { key ->
                    collectCommandsInsideRenderer(rendererValue.opt(key))
                }
            }

            is JSONArray -> for (index in 0 until rendererValue.length()) {
                collectCommandsInsideRenderer(rendererValue.opt(index))
            }
        }
    }

    fun collect(current: Any?) {
        when (current) {
            is JSONObject -> {
                current.optJSONObject("continuationItemRenderer")
                    ?.let(::collectCommandsInsideRenderer)
                current.optJSONObject("nextContinuationData")
                    ?.optString("continuation")
                    ?.let(::addToken)
                current.keys().forEach { key -> collect(current.opt(key)) }
            }

            is JSONArray -> for (index in 0 until current.length()) {
                collect(current.opt(index))
            }
        }
    }

    collect(value)
    return if (previousToken == null) {
        candidates.lastOrNull()
    } else {
        candidates.lastOrNull { it != previousToken }
    }
}

private const val MAX_YOUTUBE_CONTINUATION_TOKEN_CHARS = 16_384
