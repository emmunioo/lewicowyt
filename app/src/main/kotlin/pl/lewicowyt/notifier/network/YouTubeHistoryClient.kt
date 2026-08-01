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
import pl.lewicowyt.notifier.data.CHANNEL_TAB_SHORTS
import pl.lewicowyt.notifier.data.CHANNEL_TAB_STREAMS
import pl.lewicowyt.notifier.data.CHANNEL_TAB_VIDEOS
import pl.lewicowyt.notifier.data.LocalDatabase
import pl.lewicowyt.notifier.data.YouTubeChannelTabState
import pl.lewicowyt.notifier.model.PublishedAtDecision
import pl.lewicowyt.notifier.model.PublishedAtEvidence
import pl.lewicowyt.notifier.model.SourceType
import pl.lewicowyt.notifier.model.VideoEntry
import pl.lewicowyt.notifier.model.VideoKind
import pl.lewicowyt.notifier.model.VideoKindEvidence

enum class YouTubeHistoryTab(val path: String) {
    VIDEOS("videos"),
    STREAMS("streams"),
    SHORTS("shorts"),
    PLAYLIST("playlist"),
}

data class YouTubeHistoryItem(
    val entry: VideoEntry,
    val kind: VideoKind,
    val evidence: VideoKindEvidence,
    val publishedAtEvidence: PublishedAtEvidence = PublishedAtEvidence.UNKNOWN,
)

data class YouTubeHistoryCursor(
    val token: String,
    val apiKey: String,
    val clientVersion: String,
)

data class YouTubeHistoryPage(
    val items: List<YouTubeHistoryItem>,
    val nextCursor: YouTubeHistoryCursor?,
    val membershipKinds: Map<String, VideoKind> = emptyMap(),
)

/**
 * Czyta publiczny endpoint używany przez webowy interfejs YouTube.
 * Nie wymaga klucza YouTube Data API. Pobiera od razu żądaną kartę, a następnie
 * sprawdza, którą kartę YouTube rzeczywiście zaznaczył. Dzięki temu brak
 * `/streams` nie zmienia zwykłych filmów w archiwalne transmisje i nie wymaga
 * dodatkowego żądania listy kart dla każdego kanału.
 */
class YouTubeHistoryClient(
    private val http: HttpTextClient,
    private val database: LocalDatabase? = null,
) {
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
        val cachedTabs = knownTabs(source, nowMillis)
        if (cachedTabs?.isFreshlyAbsent(tab, nowMillis) == true) {
            return emptyPage()
        }
        var requestedParams = cachedTabs?.tabs?.get(tab) ?: tab.requestParams()
        var json = browse(
            browseId = source.externalId,
            params = requestedParams,
            apiKey = PUBLIC_WEB_API_KEY,
            clientVersion = DEFAULT_CLIENT_VERSION,
        )
        val firstDiscoveredTabs = rememberAvailableTabs(
            source = source,
            json = json,
            nowMillis = nowMillis,
            complete = false,
        )
        var selectedTab = findSelectedYouTubeTab(json)
            ?: throw IOException("YouTube nie potwierdził wybranej karty kanału")

        if (selectedTab.type != tab) {
            // Stałe parametry wewnętrznego API mogą się zmienić. Odkrywamy
            // aktualne endpointy z odpowiedzi kanału i ponawiamy najwyżej raz.
            val discovery = browse(
                browseId = source.externalId,
                params = null,
                apiKey = PUBLIC_WEB_API_KEY,
                clientVersion = DEFAULT_CLIENT_VERSION,
            )
            val discoveredTabs = rememberAvailableTabs(
                source = source,
                json = discovery,
                nowMillis = nowMillis,
                complete = true,
            )
                .ifEmpty { firstDiscoveredTabs }
            requestedParams = discoveredTabs[tab] ?: return emptyPage()
            json = browse(
                browseId = source.externalId,
                params = requestedParams,
                apiKey = PUBLIC_WEB_API_KEY,
                clientVersion = DEFAULT_CLIENT_VERSION,
            )
            rememberAvailableTabs(source, json, nowMillis, complete = false)
            selectedTab = findSelectedYouTubeTab(json)
                ?: throw IOException("YouTube nie potwierdził wybranej karty kanału")
        }
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

    private fun knownTabs(
        source: ResolvedSource,
        nowMillis: Long,
    ): CachedYouTubeTabs? {
        channelTabsCache[source.externalId]
            ?.takeIf {
                nowMillis >= it.cachedAtMillis &&
                    nowMillis - it.cachedAtMillis < TAB_MEMORY_CACHE_MILLIS
            }
            ?.let { return it }

        val stored = database?.getYouTubeChannelTabs(source.sourceKey, source.externalId)
            ?: return null
        val cached = CachedYouTubeTabs(
            tabs = buildMap {
                channelTabs().forEach { tab ->
                    stored.presentParams(tab.storageName())?.let { put(tab, it) }
                }
            },
            absentCheckedAt = buildMap {
                channelTabs().forEach { tab ->
                    stored.tabs[tab.storageName()]
                        ?.takeIf { it.state == YouTubeChannelTabState.ABSENT }
                        ?.let { put(tab, it.checkedAtMillis) }
                }
            },
            cachedAtMillis = nowMillis,
        )
        channelTabsCache[source.externalId] = cached
        return cached
    }

    private fun rememberAvailableTabs(
        source: ResolvedSource,
        json: JSONObject,
        nowMillis: Long,
        complete: Boolean,
    ): Map<YouTubeHistoryTab, String> {
        val tabs = if (complete) {
            extractCompleteYouTubeChannelTabs(json, source.externalId).orEmpty()
        } else {
            extractAvailableYouTubeTabs(json)
        }
        if (tabs.isNotEmpty()) {
            if (complete) {
                channelTabsCache[source.externalId] = CachedYouTubeTabs(
                    tabs = tabs,
                    absentCheckedAt = channelTabs()
                        .filterNot(tabs::containsKey)
                        .associateWith { nowMillis },
                    cachedAtMillis = nowMillis,
                )
                database?.saveYouTubeChannelTabs(
                    sourceKey = source.sourceKey,
                    channelId = source.externalId,
                    presentParams = tabs.mapKeys { (tab) -> tab.storageName() },
                    checkedAtMillis = nowMillis,
                )
            } else {
                val previous = knownTabs(source, nowMillis)
                channelTabsCache[source.externalId] = CachedYouTubeTabs(
                    tabs = previous?.tabs.orEmpty() + tabs,
                    absentCheckedAt = previous?.absentCheckedAt.orEmpty() - tabs.keys,
                    cachedAtMillis = nowMillis,
                )
                database?.markYouTubeChannelTabsPresent(
                    sourceKey = source.sourceKey,
                    channelId = source.externalId,
                    presentParams = tabs.mapKeys { (tab) -> tab.storageName() },
                    checkedAtMillis = nowMillis,
                )
            }
        }
        return tabs
    }

    /** Odświeża listę kart jednym żądaniem strony kanału. */
    fun refreshChannelTabs(
        source: ResolvedSource,
        nowMillis: Long = System.currentTimeMillis(),
    ): Set<YouTubeHistoryTab> {
        if (source.type != SourceType.CHANNEL) return emptySet()
        val json = browse(
            browseId = source.externalId,
            params = null,
            apiKey = PUBLIC_WEB_API_KEY,
            clientVersion = DEFAULT_CLIENT_VERSION,
        )
        val tabs = rememberAvailableTabs(source, json, nowMillis, complete = true)
        if (tabs.isEmpty()) {
            throw IOException("YouTube nie udostępnił listy kart kanału")
        }
        return tabs.keys
    }

    fun cachedAvailableChannelTabs(
        source: ResolvedSource,
        nowMillis: Long = System.currentTimeMillis(),
    ): Set<YouTubeHistoryTab>? = if (source.type == SourceType.CHANNEL) {
        knownTabs(source, nowMillis)?.let { cached ->
            channelTabs().filterTo(linkedSetOf()) { tab ->
                !cached.isFreshlyAbsent(tab, nowMillis)
            }
        }
    } else {
        null
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
            rememberAvailableTabs(source, json, nowMillis, complete = false)
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
        YouTubeHistoryPage(
            items = emptyList(),
            nextCursor = null,
            membershipKinds = emptyMap(),
        )

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
        val membershipKinds = linkedMapOf<String, VideoKind>()
        // Pierwsza odpowiedź zawiera także nieaktywne karty, nawigację i
        // polecane materiały. Typ karty wolno przypisać wyłącznie filmom
        // znajdującym się w zawartości aktualnie wybranej karty.
        val content = when {
            previousCursorToken == null && tab == YouTubeHistoryTab.PLAYLIST ->
                selectedYouTubeTabContent(json) ?: json
            previousCursorToken == null ->
                selectedYouTubeTabContent(json) ?: return emptyPage()
            else ->
                youtubeContinuationContent(json)
                    ?: throw IOException("YouTube zwrócił nieznany format kontynuacji")
        }
        val recognizedRenderers = collectVideos(
            value = content,
            tab = tab,
            nowMillis = nowMillis,
            result = items,
            membershipKinds = membershipKinds,
        )
        val token = findYouTubeContinuationToken(
            value = content,
            previousToken = previousCursorToken,
        )
        if (recognizedRenderers > 0 && items.isEmpty()) {
            if (
                tab != YouTubeHistoryTab.PLAYLIST &&
                membershipKinds.isNotEmpty()
            ) {
                // Niektóre warianty kafelków nie zawierają daty. Członkostwo
                // i kursor zachowujemy dla ograniczonej weryfikacji kandydatów;
                // zwykły backfill sam zatrzymuje się po tej stronie.
                return YouTubeHistoryPage(
                    items = emptyList(),
                    nextCursor = token?.let {
                        YouTubeHistoryCursor(it, apiKey, clientVersion)
                    },
                    membershipKinds = membershipKinds,
                )
            }
            throw IOException(
                "YouTube nie udostępnił dat publikacji dla materiałów na tej stronie",
            )
        }
        return YouTubeHistoryPage(
            items = items.values.toList(),
            nextCursor = token?.let { YouTubeHistoryCursor(it, apiKey, clientVersion) },
            membershipKinds = membershipKinds,
        )
    }

    private fun collectVideos(
        value: Any?,
        tab: YouTubeHistoryTab,
        nowMillis: Long,
        result: MutableMap<String, YouTubeHistoryItem>,
        membershipKinds: MutableMap<String, VideoKind>,
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
                            val kind = kindForConfirmedTab(renderer, tab)
                            recordMembership(membershipKinds, videoId, kind)
                            parseLockupVideo(renderer, videoId, tab, nowMillis, kind)
                                ?.let { result[videoId] = it }
                        }
                    }
                }
                value.optJSONObject("shortsLockupViewModel")?.let { renderer ->
                    recognized += 1
                    val videoId = findStringByKey(renderer, "videoId")
                        ?.takeIf(YOUTUBE_VIDEO_ID::matches)
                    if (videoId != null && videoId !in result) {
                        if (tab != YouTubeHistoryTab.PLAYLIST) {
                            recordMembership(membershipKinds, videoId, VideoKind.SHORT)
                        }
                        parseShortsLockup(renderer, videoId, tab, nowMillis)
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
                        val kind = kindForConfirmedTab(
                            renderer = renderer,
                            tab = tab,
                            reelRenderer = key == "reelItemRenderer",
                        )
                        recordMembership(membershipKinds, videoId, kind)
                        parseVideo(renderer, videoId, tab, key, nowMillis, kind)
                            ?.let { result[videoId] = it }
                    }
                }
                value.keys().forEach { key ->
                    recognized += collectVideos(
                        value.opt(key),
                        tab,
                        nowMillis,
                        result,
                        membershipKinds,
                    )
                }
                recognized
            }

            is JSONArray -> {
                var recognized = 0
                for (index in 0 until value.length()) {
                    recognized += collectVideos(
                        value.opt(index),
                        tab,
                        nowMillis,
                        result,
                        membershipKinds,
                    )
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
        confirmedKind: VideoKind,
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
        val published = findStringValues(contentMetadata)
            .asSequence()
            .map { it.take(MAX_PUBLISHED_TEXT_CHARS) }
            .mapNotNull { parsePublishedTime(it, nowMillis) }
            .firstOrNull()
            ?: return null
        if (published.millis <= 0L || published.millis > nowMillis + MAX_FUTURE_MILLIS) {
            return null
        }
        return historyItem(
            videoId = videoId,
            title = title,
            published = published,
            kind = confirmedKind,
            evidence = tab.kindEvidence(confirmedKind),
        )
    }

    private fun parseShortsLockup(
        renderer: JSONObject,
        videoId: String,
        tab: YouTubeHistoryTab,
        nowMillis: Long,
    ): YouTubeHistoryItem? {
        val title = renderer.optJSONObject("overlayMetadata")
            ?.optJSONObject("primaryText")
            ?.optString("content")
            ?.takeIf { it.isNotBlank() }
            ?.take(MAX_TITLE_CHARS)
            ?: return null
        val published = findStringValues(renderer)
            .asSequence()
            .map { it.take(MAX_PUBLISHED_TEXT_CHARS) }
            .mapNotNull { parsePublishedTime(it, nowMillis) }
            .firstOrNull()
            ?: return null
        return historyItem(
            videoId = videoId,
            title = title,
            published = published,
            kind = VideoKind.SHORT,
            evidence = tab.kindEvidence(VideoKind.SHORT),
        )
    }

    private fun historyItem(
        videoId: String,
        title: String,
        published: PublishedAtDecision,
        kind: VideoKind,
        evidence: VideoKindEvidence,
    ): YouTubeHistoryItem = YouTubeHistoryItem(
        entry = VideoEntry(
            id = videoId,
            title = title,
            url = "https://www.youtube.com/watch?v=$videoId",
            publishedAtMillis = published.millis,
            author = "",
        ),
        kind = kind,
        evidence = evidence,
        publishedAtEvidence = published.evidence,
    )

    private fun parseVideo(
        renderer: JSONObject,
        videoId: String,
        tab: YouTubeHistoryTab,
        rendererKey: String,
        nowMillis: Long,
        confirmedKind: VideoKind,
    ): YouTubeHistoryItem? {
        val title = sequenceOf("title", "headline")
            .mapNotNull { key -> readText(renderer.opt(key)) }
            .firstOrNull { it.isNotBlank() }
            ?.take(MAX_TITLE_CHARS)
            ?: return null
        val publishedText = readText(renderer.opt("publishedTimeText"))
            .orEmpty()
            .take(MAX_PUBLISHED_TEXT_CHARS)
        val published = renderer.optJSONObject("upcomingEventData")
            ?.optString("startTime")
            ?.toLongOrNull()
            ?.times(1_000L)
            ?.let {
                PublishedAtDecision(
                    millis = it,
                    evidence = PublishedAtEvidence.WEB_TIMESTAMP,
                )
            }
            ?: parsePublishedTime(publishedText, nowMillis)
            ?: return null
        if (published.millis <= 0L || published.millis > nowMillis + MAX_FUTURE_MILLIS) {
            return null
        }
        return historyItem(
            videoId = videoId,
            title = title,
            published = published,
            kind = confirmedKind,
            evidence = tab.kindEvidence(confirmedKind),
        )
    }

    private fun kindForConfirmedTab(
        renderer: JSONObject,
        tab: YouTubeHistoryTab,
        reelRenderer: Boolean = false,
    ): VideoKind {
        val serialized = renderer.toString()
        return when {
            renderer.has("upcomingEventData") ||
                serialized.contains("upcomingEventData") ||
                serialized.contains("BADGE_STYLE_TYPE_UPCOMING") -> VideoKind.UPCOMING
            serialized.contains("BADGE_STYLE_TYPE_LIVE_NOW") -> VideoKind.LIVE
            tab == YouTubeHistoryTab.STREAMS -> VideoKind.STREAM_ARCHIVE
            tab == YouTubeHistoryTab.SHORTS || reelRenderer -> VideoKind.SHORT
            tab == YouTubeHistoryTab.VIDEOS -> VideoKind.VIDEO
            else -> VideoKind.UNKNOWN
        }
    }

    private fun recordMembership(
        membershipKinds: MutableMap<String, VideoKind>,
        videoId: String,
        kind: VideoKind,
    ) {
        if (kind != VideoKind.UNKNOWN) membershipKinds.putIfAbsent(videoId, kind)
    }

    private fun YouTubeHistoryTab.kindEvidence(kind: VideoKind): VideoKindEvidence = when {
        this != YouTubeHistoryTab.PLAYLIST -> VideoKindEvidence.CHANNEL_TAB
        kind == VideoKind.UNKNOWN -> VideoKindEvidence.NONE
        else -> VideoKindEvidence.PLAYER_METADATA
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

    private fun parsePublishedTime(
        text: String,
        nowMillis: Long,
    ): PublishedAtDecision? {
        val normalized = text
            .lowercase(Locale.forLanguageTag("pl"))
            .replace('\u00a0', ' ')
            .trim()
        if (normalized.isBlank()) return null
        if ("wczoraj" in normalized || "yesterday" in normalized) {
            return PublishedAtDecision(
                nowMillis - DAY_MILLIS,
                PublishedAtEvidence.WEB_RELATIVE,
            )
        }
        if ("dzisiaj" in normalized || "today" in normalized) {
            return PublishedAtDecision(nowMillis, PublishedAtEvidence.WEB_RELATIVE)
        }

        RELATIVE_TIME.find(normalized)?.let { match ->
            val amount = match.groupValues[1].toLongOrNull() ?: return null
            val unit = match.groupValues[2]
            val unitMillis = when {
                unit.startsWith("sekund") || unit.startsWith("second") -> 1_000L
                unit.startsWith("minut") || unit.startsWith("minute") -> 60_000L
                unit.startsWith("godzin") || unit.startsWith("hour") -> 3_600_000L
                unit == "dzień" || unit == "dni" || unit.startsWith("day") -> DAY_MILLIS
                unit == "tydzień" || unit.startsWith("tygodni") ||
                    unit.startsWith("week") -> 7L * DAY_MILLIS
                unit.startsWith("miesiąc") || unit == "miesięcy" ||
                    unit.startsWith("month") -> 30L * DAY_MILLIS
                unit == "rok" || unit == "roku" || unit == "lata" ||
                    unit == "lat" || unit.startsWith("year") -> 365L * DAY_MILLIS
                else -> return null
            }
            val ageMillis = runCatching { Math.multiplyExact(amount, unitMillis) }
                .getOrNull()
                ?.takeIf { it <= MAX_RELATIVE_AGE_MILLIS }
                ?: return null
            return PublishedAtDecision(
                nowMillis - ageMillis,
                PublishedAtEvidence.WEB_RELATIVE,
            )
        }

        for (formatter in ABSOLUTE_DATE_FORMATTERS) {
            try {
                val date = LocalDate.parse(text.trim(), formatter)
                return PublishedAtDecision(
                    millis = date.atStartOfDay(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli(),
                    evidence = PublishedAtEvidence.WEB_DATE,
                )
            } catch (_: DateTimeParseException) {
                // Spróbuj następnego formatu.
            }
        }
        return runCatching { Instant.parse(text.trim()).toEpochMilli() }
            .getOrNull()
            ?.let {
                PublishedAtDecision(
                    millis = it,
                    evidence = PublishedAtEvidence.WEB_TIMESTAMP,
                )
            }
    }

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
        const val TAB_MEMORY_CACHE_MILLIS = 15L * 60L * 1_000L
        const val BROWSE_ENDPOINT = "https://www.youtube.com/youtubei/v1/browse"
        const val PUBLIC_WEB_API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
        const val DEFAULT_CLIENT_VERSION = "2.20260727.10.00"
        const val LOCKUP_VIDEO_CONTENT_TYPE = "LOCKUP_CONTENT_TYPE_VIDEO"
        const val DAY_MILLIS = 24L * 60L * 60L * 1_000L
        const val MAX_FUTURE_MILLIS = 2L * 365L * DAY_MILLIS
        const val MAX_RELATIVE_AGE_MILLIS = 10L * 365L * DAY_MILLIS
        val RELATIVE_TIME = Regex(
            """(?:^|[^\p{L}\p{N}])(\d+)\s+""" +
                """(sekund(?:a|y|ę)?|minut(?:a|y|ę)?|godzin(?:a|y|ę)?|""" +
                """dzień|dni|tydzień|tygodnie|tygodni|miesiąc|miesiące|""" +
                """miesięcy|rok|roku|lata|lat|seconds?|minutes?|hours?|""" +
                """days?|weeks?|months?|years?)\s+(?:temu|ago)""" +
                """(?:$|[^\p{L}])""",
        )
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
    val tabs: Map<YouTubeHistoryTab, String>,
    val absentCheckedAt: Map<YouTubeHistoryTab, Long>,
    val cachedAtMillis: Long,
) {
    fun isFreshlyAbsent(
        tab: YouTubeHistoryTab,
        nowMillis: Long,
    ): Boolean {
        val checkedAt = absentCheckedAt[tab] ?: return false
        return checkedAt in 1..nowMillis &&
            nowMillis - checkedAt < ABSENT_TAB_CACHE_MILLIS_VALUE
    }
}

private const val ABSENT_TAB_CACHE_MILLIS_VALUE = 24L * 60L * 60L * 1_000L

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

private fun YouTubeHistoryTab.storageName(): String = when (this) {
    YouTubeHistoryTab.VIDEOS -> CHANNEL_TAB_VIDEOS
    YouTubeHistoryTab.STREAMS -> CHANNEL_TAB_STREAMS
    YouTubeHistoryTab.SHORTS -> CHANNEL_TAB_SHORTS
    YouTubeHistoryTab.PLAYLIST ->
        throw IllegalArgumentException("Playlista nie jest kartą kanału")
}

private fun channelTabs(): List<YouTubeHistoryTab> = listOf(
    YouTubeHistoryTab.VIDEOS,
    YouTubeHistoryTab.STREAMS,
    YouTubeHistoryTab.SHORTS,
)

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
                        ?.takeIf {
                            it.isNotBlank() && it.length <= MAX_TAB_PARAMS_CHARS
                        }
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

/**
 * Braki zapisujemy wyłącznie z właściwego, kompletnego paska kart kanału.
 * Dowolna zagnieżdżona lista (np. rekomendacje) nie może utworzyć ABSENT.
 */
internal fun extractCompleteYouTubeChannelTabs(
    value: Any?,
    expectedBrowseId: String? = null,
): Map<YouTubeHistoryTab, String>? {
    fun inspectRenderer(renderer: JSONObject?): Map<YouTubeHistoryTab, String>? {
        val tabsArray = renderer?.optJSONArray("tabs") ?: return null
        val recognized = linkedMapOf<YouTubeHistoryTab, String>()
        var selectedFound = false
        var matchingBrowseIdFound = expectedBrowseId == null
        for (index in 0 until tabsArray.length()) {
            val tab = tabsArray.optJSONObject(index)
                ?.optJSONObject("tabRenderer")
                ?: continue
            selectedFound = selectedFound || tab.optBoolean("selected", false)
            val endpointContainer = tabEndpointContainer(tab)
            val endpoint = endpointContainer?.optJSONObject("browseEndpoint")
            if (endpoint?.optString("browseId") == expectedBrowseId) {
                matchingBrowseIdFound = true
            }
            val type = identifyYouTubeTab(tab, endpointContainer, endpoint) ?: continue
            val params = endpoint?.optString("params")
                ?.takeIf { it.isNotBlank() && it.length <= MAX_TAB_PARAMS_CHARS }
                ?.let(::decodeTabParams)
                ?: return null
            recognized.putIfAbsent(type, params)
        }
        return recognized.takeIf {
            it.isNotEmpty() && selectedFound && matchingBrowseIdFound
        }
    }

    fun search(current: Any?): Map<YouTubeHistoryTab, String>? = when (current) {
        is JSONObject -> {
            inspectRenderer(current.optJSONObject("twoColumnBrowseResultsRenderer"))
                ?: inspectRenderer(current.optJSONObject("singleColumnBrowseResultsRenderer"))
                ?: current.keys().asSequence()
                    .mapNotNull { key -> search(current.opt(key)) }
                    .firstOrNull()
        }

        is JSONArray -> (0 until current.length()).asSequence()
            .mapNotNull { index -> search(current.opt(index)) }
            .firstOrNull()

        else -> null
    }

    return search(value)
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
        ?.takeIf { it.isNotBlank() }
        ?: browseEndpoint?.optString("canonicalBaseUrl")
        .orEmpty()
    val endpointTab = endpointPath
        .orEmpty()
        .substringBefore('?')
        .trimEnd('/')
        .substringAfterLast('/')
        .lowercase(Locale.ROOT)
    return when {
        identifier == "videos" ||
            endpointTab == "videos" ||
            params.startsWith(VIDEOS_PARAMS_PREFIX) -> YouTubeHistoryTab.VIDEOS
        identifier == "streams" ||
            endpointTab == "streams" ||
            params.startsWith(STREAMS_PARAMS_PREFIX) -> YouTubeHistoryTab.STREAMS
        identifier == "shorts" ||
            endpointTab == "shorts" ||
            params.startsWith(SHORTS_PARAMS_PREFIX) -> YouTubeHistoryTab.SHORTS
        else -> null
    }
}

internal fun decodeTabParams(value: String): String = runCatching {
    // URLDecoder interpretuje niezakodowane `+` jak spację, co uszkadza
    // parametry Base64 zwrócone bez procentowego kodowania.
    URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())
}.getOrDefault(value)

private const val VIDEOS_PARAMS = "EgZ2aWRlb3PyBgQKAjoA"
private const val STREAMS_PARAMS = "EgdzdHJlYW1z8gYECgJ6AA=="
private const val SHORTS_PARAMS = "EgZzaG9ydHPyBgUKA5oBAA=="
private const val VIDEOS_PARAMS_PREFIX = "EgZ2aWRlb3M"
private const val STREAMS_PARAMS_PREFIX = "EgdzdHJlYW1z"
private const val SHORTS_PARAMS_PREFIX = "EgZzaG9ydHM"
private const val MAX_TAB_PARAMS_CHARS = 2_048

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
 * Ogranicza odpowiedź kolejnej strony do kontenerów, które YouTube przeznaczył
 * do dopisania na bieżącej karcie. Renderery nawigacji i rekomendacji obecne
 * obok nich nie mogą uczestniczyć w klasyfikacji.
 *
 * Pusta tablica oznacza poprawny koniec; `null` oznacza nieznany format.
 */
internal fun youtubeContinuationContent(value: Any?): JSONArray? {
    val combined = JSONArray()
    var foundKnownContainer = false

    fun append(array: JSONArray?) {
        if (array == null) return
        foundKnownContainer = true
        for (index in 0 until array.length()) combined.put(array.opt(index))
    }

    fun collect(current: Any?) {
        when (current) {
            is JSONObject -> {
                current.optJSONObject("appendContinuationItemsAction")
                    ?.let { append(it.optJSONArray("continuationItems")) }
                current.optJSONObject("reloadContinuationItemsCommand")
                    ?.let { append(it.optJSONArray("continuationItems")) }
                current.optJSONObject("continuationContents")?.let { continuation ->
                    append(
                        continuation.optJSONObject("richGridContinuation")
                            ?.optJSONArray("contents"),
                    )
                    append(
                        continuation.optJSONObject("gridContinuation")
                            ?.optJSONArray("items"),
                    )
                    append(
                        continuation.optJSONObject("sectionListContinuation")
                            ?.optJSONArray("contents"),
                    )
                    append(
                        continuation.optJSONObject("playlistVideoListContinuation")
                            ?.optJSONArray("contents"),
                    )
                }
                current.keys().forEach { key -> collect(current.opt(key)) }
            }

            is JSONArray -> for (index in 0 until current.length()) {
                collect(current.opt(index))
            }
        }
    }

    collect(value)
    return combined.takeIf { foundKnownContainer }
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
