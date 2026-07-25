package pl.lewicowyt.notifier.network

import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import pl.lewicowyt.notifier.model.SourceType
import pl.lewicowyt.notifier.model.VideoEntry
import pl.lewicowyt.notifier.model.VideoKind
import pl.lewicowyt.notifier.model.VideoOrigin

data class PipedPage(
    val items: List<YouTubeHistoryItem>,
    val nextPageToken: String?,
    val instanceBaseUrl: String,
)

/**
 * Bezkluczowe, dodatkowe źródło metadanych. Publiczne instancje Piped są
 * zawodne, dlatego każde żądanie ma failover, krótki timeout i circuit breaker.
 * Linki zwracane przez instancję nie są używane bezpośrednio — URL YouTube jest
 * budowany lokalnie z wcześniej zweryfikowanego identyfikatora filmu.
 */
class PipedClient(
    private val http: HttpTextClient,
    private val instances: List<String> = DEFAULT_INSTANCES,
) {
    private val requestSemaphore = Semaphore(MAX_PARALLEL_REQUESTS)

    @Volatile
    private var preferredInstance: String? = null

    @Volatile
    private var disabledUntilMillis: Long = 0L

    @Volatile
    private var preferredFeedInstance: String? = null

    @Volatile
    private var feedDisabledUntilMillis: Long = 0L

    suspend fun firstPage(source: ResolvedSource): PipedPage =
        requestWithFailover(source, nextPageToken = null, preferredBaseUrl = null)

    /**
     * Publiczne instancje czasem zwracają pustą stronę `/channel`, mimo że ich
     * `/feed/unauthenticated` nadal działa. Historia odpytuje oba endpointy
     * równolegle i scala wyniki, aby Piped pozostał użytecznym źródłem awaryjnym.
     */
    suspend fun firstHistoryPage(source: ResolvedSource): PipedPage {
        if (source.type == SourceType.PLAYLIST) return firstPage(source)

        return coroutineScope {
            val channelRequest = async { runPipedCatching { firstPage(source) } }
            val feedRequest = async {
                runPipedCatching { requestFeedWithFailover(source.externalId) }
            }
            val channelResult = channelRequest.await()
            val feedResult = feedRequest.await()

            when {
                channelResult.isSuccess && feedResult.isSuccess ->
                    mergePipedHistoryPages(
                        channelPage = channelResult.getOrThrow(),
                        feedPage = feedResult.getOrThrow(),
                    )
                channelResult.isSuccess -> channelResult.getOrThrow()
                feedResult.isSuccess -> feedResult.getOrThrow()
                else -> throw IOException(
                    "Historia Piped jest niedostępna: " +
                        listOfNotNull(
                            channelResult.exceptionOrNull()?.message,
                            feedResult.exceptionOrNull()?.message,
                        ).take(2).joinToString(" | "),
                    channelResult.exceptionOrNull(),
                )
            }
        }
    }

    suspend fun nextPage(
        source: ResolvedSource,
        nextPageToken: String,
        instanceBaseUrl: String,
    ): PipedPage = requestWithFailover(source, nextPageToken, instanceBaseUrl)

    /**
     * Feed bez logowania jest przeznaczony do wykrywania najnowszych filmów.
     * Dla playlist, których ten endpoint nie obsługuje, używamy pierwszej strony.
     */
    suspend fun recentItems(source: ResolvedSource): List<YouTubeHistoryItem> {
        if (source.type == SourceType.PLAYLIST) return firstPage(source).items
        return requestFeedWithFailover(source.externalId).items
    }

    private suspend fun requestWithFailover(
        source: ResolvedSource,
        nextPageToken: String?,
        preferredBaseUrl: String?,
    ): PipedPage = requestSemaphore.withPermit {
        val now = System.currentTimeMillis()
        if (now < disabledUntilMillis) {
            throw IOException("Piped jest tymczasowo niedostępny")
        }

        val candidates = buildList {
            preferredBaseUrl?.let(::add)
            preferredInstance?.let(::add)
            addAll(instances)
        }.map { it.trimEnd('/') }.distinct().take(MAX_INSTANCE_ATTEMPTS)

        val failures = mutableListOf<String>()
        for (baseUrl in candidates) {
            currentCoroutineContext().ensureActive()
            try {
                val path = source.pipedPath(nextPageToken)
                val json = http.getText(
                    url = "$baseUrl$path",
                    maxChars = MAX_RESPONSE_CHARS,
                    connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS,
                    readTimeoutMillis = READ_TIMEOUT_MILLIS,
                    callTimeoutMillis = CALL_TIMEOUT_MILLIS,
                )
                val page = parsePipedPage(json, System.currentTimeMillis(), baseUrl)
                if (page.items.isEmpty() && page.nextPageToken != null) {
                    throw IOException(
                        "Piped zwrócił pustą stronę z kontynuacją; próbuję innej instancji",
                    )
                }
                preferredInstance = baseUrl
                disabledUntilMillis = 0L
                return@withPermit page
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                failures += "$baseUrl: ${
                    (error.message ?: error.javaClass.simpleName).take(MAX_ERROR_CHARS)
                }"
            }
        }

        disabledUntilMillis = System.currentTimeMillis() + CIRCUIT_BREAK_MILLIS
        throw IOException(
            "Żadna instancja Piped nie odpowiedziała poprawnie: " +
                failures.joinToString(" | "),
        )
    }

    private suspend fun requestFeedWithFailover(
        channelId: String,
    ): PipedPage = requestSemaphore.withPermit {
        val now = System.currentTimeMillis()
        if (now < feedDisabledUntilMillis) {
            throw IOException("Feed Piped jest tymczasowo niedostępny")
        }

        val candidates = buildList {
            preferredFeedInstance?.let(::add)
            addAll(instances)
        }.map { it.trimEnd('/') }.distinct().take(MAX_INSTANCE_ATTEMPTS)

        val failures = mutableListOf<String>()
        for (baseUrl in candidates) {
            currentCoroutineContext().ensureActive()
            try {
                val json = http.getText(
                    url = "$baseUrl/feed/unauthenticated?channels=${channelId.urlEncode()}",
                    maxChars = MAX_RESPONSE_CHARS,
                    connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS,
                    readTimeoutMillis = READ_TIMEOUT_MILLIS,
                    callTimeoutMillis = CALL_TIMEOUT_MILLIS,
                )
                val items = parsePipedFeed(json, System.currentTimeMillis())
                preferredFeedInstance = baseUrl
                feedDisabledUntilMillis = 0L
                return@withPermit PipedPage(
                    items = items,
                    nextPageToken = null,
                    instanceBaseUrl = baseUrl,
                )
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                failures += "$baseUrl: ${
                    (error.message ?: error.javaClass.simpleName).take(MAX_ERROR_CHARS)
                }"
            }
        }

        feedDisabledUntilMillis = System.currentTimeMillis() + CIRCUIT_BREAK_MILLIS
        throw IOException(
            "Żadna instancja feedu Piped nie odpowiedziała poprawnie: " +
                failures.joinToString(" | "),
        )
    }

    private fun ResolvedSource.pipedPath(nextPageToken: String?): String {
        if (nextPageToken.isNullOrBlank()) {
            return when (type) {
                SourceType.CHANNEL -> "/channel/${externalId.urlEncode()}"
                SourceType.PLAYLIST -> "/playlists/${externalId.urlEncode()}"
            }
        }
        val endpoint = when (type) {
            SourceType.CHANNEL -> "/nextpage/channel/${externalId.urlEncode()}"
            SourceType.PLAYLIST -> "/nextpage/playlists/${externalId.urlEncode()}"
        }
        return "$endpoint?nextpage=${nextPageToken.urlEncode()}"
    }

    private fun String.urlEncode(): String =
        URLEncoder.encode(this, StandardCharsets.UTF_8.name())

    private companion object {
        const val MAX_PARALLEL_REQUESTS = 8
        const val MAX_INSTANCE_ATTEMPTS = 4
        const val MAX_RESPONSE_CHARS = 3_000_000
        const val CONNECT_TIMEOUT_MILLIS = 3_000
        const val READ_TIMEOUT_MILLIS = 5_000
        const val CALL_TIMEOUT_MILLIS = 10_000
        const val CIRCUIT_BREAK_MILLIS = 10L * 60L * 1_000L

        val DEFAULT_INSTANCES = listOf(
            "https://api.piped.private.coffee",
            "https://pipedapi.kavin.rocks",
            "https://pipedapi-libre.kavin.rocks",
            "https://pipedapi.reallyaweso.me",
        )
    }
}

internal fun mergePipedHistoryPages(
    channelPage: PipedPage,
    feedPage: PipedPage,
): PipedPage = channelPage.copy(
    items = (feedPage.items + channelPage.items)
        .distinctBy { it.entry.id }
        .sortedByDescending { it.entry.publishedAtMillis },
)

internal fun parsePipedPage(
    json: String,
    nowMillis: Long,
    instanceBaseUrl: String,
): PipedPage {
    if (!hasSafeJsonNesting(json)) {
        throw IOException("Odpowiedź Piped ma zbyt głęboką strukturę JSON")
    }
    val root = JSONObject(json)
    root.optString("message").takeIf {
        root.has("error") && it.isNotBlank()
    }?.let { throw IOException(it.take(MAX_ERROR_CHARS)) }

    val streams = root.optJSONArray("relatedStreams")
        ?: root.optJSONArray("items")
        ?: throw IOException("Odpowiedź Piped nie zawiera listy materiałów")
    val items = parsePipedItems(streams, nowMillis)

    val nextPage = root.optString("nextpage")
        .takeIf { it.isNotBlank() && it != "null" }
        ?.also {
            if (it.length > MAX_NEXT_PAGE_TOKEN_CHARS) {
                throw IOException("Token kontynuacji Piped przekracza limit")
            }
        }
    return PipedPage(
        items = items,
        nextPageToken = nextPage,
        instanceBaseUrl = instanceBaseUrl,
    )
}

internal fun parsePipedFeed(
    json: String,
    nowMillis: Long,
): List<YouTubeHistoryItem> {
    if (!hasSafeJsonNesting(json)) {
        throw IOException("Odpowiedź feedu Piped ma zbyt głęboką strukturę JSON")
    }
    val trimmed = json.trimStart()
    val streams = if (trimmed.startsWith("[")) {
        JSONArray(json)
    } else {
        val root = JSONObject(json)
        root.optString("message").takeIf {
            root.has("error") && it.isNotBlank()
        }?.let { throw IOException(it.take(MAX_ERROR_CHARS)) }
        root.optJSONArray("items")
            ?: root.optJSONArray("relatedStreams")
            ?: throw IOException("Odpowiedź feedu Piped nie zawiera listy materiałów")
    }
    return parsePipedItems(streams, nowMillis)
}

private fun parsePipedItems(
    streams: JSONArray,
    nowMillis: Long,
): List<YouTubeHistoryItem> = buildList {
    for (index in 0 until minOf(streams.length(), MAX_ITEMS_PER_PAGE)) {
        val item = streams.optJSONObject(index) ?: continue
        val videoId = extractPipedVideoId(item.optString("url")) ?: continue
        val uploadedAt = normalizePipedTimestamp(
            value = item.optLong("uploaded", 0L),
            relativeText = item.optString("uploadedDate"),
            nowMillis = nowMillis,
        )
        if (uploadedAt !in 1L..(nowMillis + MAX_FUTURE_TIMESTAMP_MILLIS)) continue
        val kind = when {
            item.optBoolean("isShort", false) -> VideoKind.SHORT
            item.optLong("duration", -1L) == 0L -> VideoKind.LIVE
            else -> VideoKind.VIDEO
        }
        add(
            YouTubeHistoryItem(
                entry = VideoEntry(
                    id = videoId,
                    title = item.optString("title")
                        .ifBlank { "Materiał bez tytułu" }
                        .take(MAX_TITLE_CHARS),
                    url = "https://www.youtube.com/watch?v=$videoId",
                    publishedAtMillis = uploadedAt,
                    author = item.optString("uploaderName").take(MAX_AUTHOR_CHARS),
                    origin = VideoOrigin.PIPED,
                ),
                kind = kind,
            ),
        )
    }
}.distinctBy { it.entry.id }

private fun extractPipedVideoId(url: String): String? {
    val match = PIPED_VIDEO_ID.find(url) ?: return null
    return match.groupValues[1].takeIf { it.length == 11 }
}

private fun normalizePipedTimestamp(
    value: Long,
    relativeText: String,
    nowMillis: Long,
): Long {
    if (value > 0L) {
        return if (value < MILLIS_THRESHOLD) value * 1_000L else value
    }
    val normalized = relativeText.lowercase(Locale.ENGLISH).trim()
    if ("today" in normalized || "just now" in normalized) return nowMillis
    if ("yesterday" in normalized) return nowMillis - DAY_MILLIS
    val amount = NUMBER.find(normalized)?.groupValues?.getOrNull(1)?.toLongOrNull()
        ?: return 0L
    val unitMillis = when {
        "second" in normalized -> 1_000L
        "minute" in normalized -> 60_000L
        "hour" in normalized -> 3_600_000L
        "day" in normalized -> DAY_MILLIS
        "week" in normalized -> 7L * DAY_MILLIS
        "month" in normalized -> 30L * DAY_MILLIS
        "year" in normalized -> 365L * DAY_MILLIS
        else -> return 0L
    }
    val ageMillis = runCatching { Math.multiplyExact(amount, unitMillis) }
        .getOrNull()
        ?.takeIf { it <= MAX_RELATIVE_AGE_MILLIS }
        ?: return 0L
    return nowMillis - ageMillis
}

internal fun hasSafeJsonNesting(
    json: String,
    maxDepth: Int = MAX_JSON_DEPTH,
): Boolean {
    var depth = 0
    var inString = false
    var escaped = false
    val stack = CharArray(maxDepth.coerceAtLeast(0))
    for (character in json) {
        if (inString) {
            when {
                escaped -> escaped = false
                character == '\\' -> escaped = true
                character == '"' -> inString = false
            }
            continue
        }
        when (character) {
            '"' -> inString = true
            '{', '[' -> {
                if (depth >= stack.size) return false
                stack[depth] = character
                depth += 1
            }
            '}', ']' -> {
                if (depth == 0) return false
                val opening = stack[depth - 1]
                if (
                    (character == '}' && opening != '{') ||
                    (character == ']' && opening != '[')
                ) {
                    return false
                }
                depth -= 1
            }
        }
    }
    return !inString && depth == 0
}

private const val MILLIS_THRESHOLD = 1_000_000_000_000L
private const val DAY_MILLIS = 24L * 60L * 60L * 1_000L
private const val MAX_FUTURE_TIMESTAMP_MILLIS = DAY_MILLIS
private const val MAX_RELATIVE_AGE_MILLIS = 10L * 365L * DAY_MILLIS
private const val MAX_ITEMS_PER_PAGE = 200
private const val MAX_NEXT_PAGE_TOKEN_CHARS = 4_096
private const val MAX_TITLE_CHARS = 300
private const val MAX_AUTHOR_CHARS = 200
private const val MAX_ERROR_CHARS = 300
private const val MAX_JSON_DEPTH = 100
private val NUMBER = Regex("""(\d+)""")
private val PIPED_VIDEO_ID = Regex("""(?:[?&]v=|/shorts/|/live/)([A-Za-z0-9_-]{11})""")

private suspend inline fun <T> runPipedCatching(
    crossinline block: suspend () -> T,
): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    Result.failure(error)
}
