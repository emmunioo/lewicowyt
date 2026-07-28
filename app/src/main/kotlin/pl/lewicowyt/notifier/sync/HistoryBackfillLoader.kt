package pl.lewicowyt.notifier.sync

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import pl.lewicowyt.notifier.data.AppSettings
import pl.lewicowyt.notifier.data.CreatorCatalog
import pl.lewicowyt.notifier.data.LocalDatabase
import pl.lewicowyt.notifier.data.PreferencesRepository
import pl.lewicowyt.notifier.model.Creator
import pl.lewicowyt.notifier.model.CreatorSource
import pl.lewicowyt.notifier.model.HistoryFilter
import pl.lewicowyt.notifier.model.SourceType
import pl.lewicowyt.notifier.model.VideoEntry
import pl.lewicowyt.notifier.model.VideoKind
import pl.lewicowyt.notifier.model.VideoOrigin
import pl.lewicowyt.notifier.network.ResolvedSource
import pl.lewicowyt.notifier.network.YouTubeDataApiHistoryClient
import pl.lewicowyt.notifier.network.YouTubeFeedClient
import pl.lewicowyt.notifier.network.YouTubeHistoryClient
import pl.lewicowyt.notifier.network.YouTubeHistoryCursor
import pl.lewicowyt.notifier.network.YouTubeHistoryItem
import pl.lewicowyt.notifier.network.YouTubeHistoryTab
import pl.lewicowyt.notifier.network.YouTubeSourceResolver

data class BackfillResult(
    val insertedCount: Int,
    val exhausted: Boolean,
    val error: String? = null,
)

/**
 * Każdy kanał ma własne zadanie pobierania. Zadania pracują równolegle, ale wspólny
 * semafor ogranicza liczbę aktywnych połączeń, żeby nie przeciążyć telefonu ani YouTube.
 * Kanał kończy pracę natychmiast po dojściu do granicy czasu wybranej w ustawieniach.
 */
class HistoryBackfillLoader(
    private val catalog: CreatorCatalog,
    private val preferences: PreferencesRepository,
    private val database: LocalDatabase,
    private val resolver: YouTubeSourceResolver,
    private val feedClient: YouTubeFeedClient,
    private val client: YouTubeHistoryClient,
    private val dataApiClient: YouTubeDataApiHistoryClient,
) {
    private data class Target(
        val creator: Creator,
        val source: CreatorSource,
        val tab: YouTubeHistoryTab?,
    ) {
        val key: String =
            "${creator.id}|${source.type}|${source.url}|${tab?.name ?: "DATA_API"}"
    }

    private data class ChannelResult(
        val insertedCount: Int,
        val errors: List<String>,
    )

    private data class TargetLoadResult(
        val insertedCount: Int,
        val complete: Boolean,
    )

    private val loadMutex = Mutex()
    private val channelSemaphore = Semaphore(MAX_PARALLEL_CHANNELS)
    private var completedSignature: String? = null
    private var activeSignature: String? = null
    private val completedTargets = ConcurrentHashMap.newKeySet<String>()

    suspend fun reset() = loadMutex.withLock {
        completedSignature = null
        activeSignature = null
        completedTargets.clear()
    }

    suspend fun loadRange(
        settings: AppSettings,
        onProgress: suspend () -> Unit = {},
    ): BackfillResult = loadMutex.withLock {
        val apiKey = if (settings.youtubeApiEnabled) preferences.youtubeApiKey() else ""
        val signature = buildSignature(settings, apiKey)
        if (completedSignature == signature) {
            return BackfillResult(insertedCount = 0, exhausted = true)
        }
        if (activeSignature != signature) {
            activeSignature = signature
            completedTargets.clear()
        }

        val targetsByChannel = buildTargets(settings, apiKey.isNotBlank())
            .filterNot { it.key in completedTargets }
            .groupBy { resolver.sourceKey(it.creator, it.source) }
        if (targetsByChannel.isEmpty()) {
            completedSignature = signature
            return BackfillResult(insertedCount = 0, exhausted = true)
        }

        val cutoff = System.currentTimeMillis() -
            settings.historyWindowDays.toLong() * DAY_MILLIS
        val reporter = ProgressReporter(onProgress)
        val results = coroutineScope {
            targetsByChannel.values.map { channelTargets ->
                async(Dispatchers.IO) {
                    channelSemaphore.withPermit {
                        loadChannel(channelTargets, cutoff, settings, apiKey, reporter)
                    }
                }
            }.awaitAll()
        }
        reporter.report(force = true)

        val errors = results.flatMap(ChannelResult::errors)
        if (errors.isEmpty()) completedSignature = signature
        BackfillResult(
            insertedCount = results.sumOf(ChannelResult::insertedCount),
            exhausted = errors.isEmpty(),
            error = errors.takeIf { it.isNotEmpty() }?.toSummary(),
        )
    }

    private suspend fun loadChannel(
        targets: List<Target>,
        cutoff: Long,
        settings: AppSettings,
        apiKey: String,
        reporter: ProgressReporter,
    ): ChannelResult {
        val firstTarget = targets.first()
        val sourceKey = resolver.sourceKey(firstTarget.creator, firstTarget.source)
        val resolved = try {
            if (firstTarget.tab == null) {
                resolveForDataApi(firstTarget, sourceKey, apiKey)
            } else {
                resolver.resolve(firstTarget.creator, firstTarget.source)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return ChannelResult(
                insertedCount = 0,
                errors = listOf(
                    "${firstTarget.creator.name}: " +
                        (error.message ?: error.javaClass.simpleName)
                            .take(MAX_ERROR_DETAIL_CHARS),
                ),
            )
        }

        // RSS jest najmniejszą i najszybszą odpowiedzią YouTube. Zapisujemy ją
        // przed uruchomieniem cięższego stronicowania, aby około 15 najnowszych
        // pozycji mogło pojawić się na ekranie od razu.
        val rssResult = runSuspendCatching {
            loadRssSource(
                creator = firstTarget.creator,
                resolved = resolved,
                cutoff = cutoff,
                reporter = reporter,
            )
        }

        if (apiKey.isNotBlank()) {
            val apiResult = loadYouTubeTargets(
                targets,
                resolved,
                cutoff,
                settings,
                apiKey,
                reporter,
            )
            if (apiResult.errors.isEmpty()) {
                return mergeRssAndYouTubeResults(
                    rssResult = rssResult,
                    youtubeResult = apiResult,
                    creatorName = firstTarget.creator.name,
                )
            }

            val fallbackTargets = buildWebTargets(
                creator = firstTarget.creator,
                source = firstTarget.source,
                settings = settings,
            )
            val fallbackResult = loadYouTubeTargets(
                targets = fallbackTargets,
                resolved = resolved,
                cutoff = cutoff,
                settings = settings,
                apiKey = "",
                reporter = reporter,
            )
            if (fallbackResult.errors.isEmpty()) {
                // Pełny zakres został dostarczony przez YouTube Web.
                // Wadliwy lub wyczerpany klucz nie może wymuszać kolejnych prób.
                targets.forEach { completedTargets += it.key }
                return mergeRssAndYouTubeResults(
                    rssResult = rssResult,
                    youtubeResult = ChannelResult(
                        insertedCount =
                            apiResult.insertedCount + fallbackResult.insertedCount,
                        errors = emptyList(),
                    ),
                    creatorName = firstTarget.creator.name,
                )
            }
            return mergeRssAndYouTubeResults(
                rssResult = rssResult,
                youtubeResult = ChannelResult(
                    insertedCount = apiResult.insertedCount + fallbackResult.insertedCount,
                    errors = (apiResult.errors + fallbackResult.errors).distinct(),
                ),
                creatorName = firstTarget.creator.name,
            )
        }

        return mergeRssAndYouTubeResults(
            rssResult = rssResult,
            youtubeResult = loadYouTubeTargets(
                targets = targets,
                resolved = resolved,
                cutoff = cutoff,
                settings = settings,
                apiKey = "",
                reporter = reporter,
            ),
            creatorName = firstTarget.creator.name,
        )
    }

    private suspend fun loadRssSource(
        creator: Creator,
        resolved: ResolvedSource,
        cutoff: Long,
        reporter: ProgressReporter,
    ): Int {
        val items = rssHistoryItems(
            entries = feedClient.fetch(resolved),
            cutoff = cutoff,
        )
        val insertedCount = database.insertHistoricalVideos(creator, items)
        reporter.report()
        return insertedCount
    }

    private fun mergeRssAndYouTubeResults(
        rssResult: Result<Int>,
        youtubeResult: ChannelResult,
        creatorName: String,
    ): ChannelResult {
        val rssError = rssResult.exceptionOrNull()?.let { error ->
            "$creatorName (YouTube RSS): " +
                (error.message ?: error.javaClass.simpleName).take(MAX_ERROR_DETAIL_CHARS)
        }
        return ChannelResult(
            insertedCount = rssResult.getOrDefault(0) + youtubeResult.insertedCount,
            // RSS jest szybkim początkiem, ale sam nie dowodzi kompletności
            // wybranego zakresu. Jego błąd zgłaszamy tylko wtedy, gdy zawiodło
            // również źródło stronicowane.
            errors = if (youtubeResult.errors.isEmpty()) {
                emptyList()
            } else {
                (listOfNotNull(rssError) + youtubeResult.errors).distinct()
            },
        )
    }

    private suspend fun loadYouTubeTargets(
        targets: List<Target>,
        resolved: ResolvedSource,
        cutoff: Long,
        settings: AppSettings,
        apiKey: String,
        reporter: ProgressReporter,
    ): ChannelResult {
        var insertedCount = 0
        val errors = mutableListOf<String>()
        for (target in targets) {
            currentCoroutineContext().ensureActive()
            try {
                val result = if (target.tab == null) {
                    loadDataApiTarget(target, resolved, cutoff, settings, apiKey, reporter)
                } else {
                    loadWebTarget(target, resolved, cutoff, reporter)
                }
                insertedCount += result.insertedCount
                if (result.complete) {
                    completedTargets += target.key
                } else {
                    errors +=
                        "${target.creator.name}: osiągnięto limit stron przed objęciem całego zakresu"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                errors += (
                    "${target.creator.name}: " +
                        (error.message ?: error.javaClass.simpleName)
                            .take(MAX_ERROR_DETAIL_CHARS)
                    ).take(MAX_ERROR_LINE_CHARS)
            }
        }
        return ChannelResult(insertedCount, errors)
    }

    private fun resolveForDataApi(
        target: Target,
        sourceKey: String,
        apiKey: String,
    ): ResolvedSource {
        if (target.source.type == SourceType.PLAYLIST) {
            return resolver.resolve(target.creator, target.source)
        }
        val externalId = target.source.externalId
            ?.takeIf { it.startsWith("UC") }
            ?: database.getResolvedId(sourceKey)
                ?.takeIf { it.startsWith("UC") }
            ?: runCatching {
                dataApiClient.resolveChannelId(target.source.url, apiKey)
            }.getOrElse {
                return resolver.resolve(target.creator, target.source)
            }
        database.saveResolvedId(sourceKey, externalId)
        return ResolvedSource(
            sourceKey = sourceKey,
            type = SourceType.CHANNEL,
            externalId = externalId,
            feedUrl = "https://www.youtube.com/feeds/videos.xml?channel_id=$externalId",
        )
    }

    private suspend fun loadWebTarget(
        target: Target,
        resolved: ResolvedSource,
        cutoff: Long,
        reporter: ProgressReporter,
    ): TargetLoadResult {
        var cursor: YouTubeHistoryCursor? = null
        var insertedCount = 0
        var pageNumber = 0
        var complete = false
        val seenCursorTokens = mutableSetOf<String>()

        while (pageNumber < MAX_PAGES_PER_TARGET) {
            currentCoroutineContext().ensureActive()
            val page = if (cursor == null) {
                client.firstPage(resolved, requireNotNull(target.tab))
            } else {
                client.nextPage(cursor, requireNotNull(target.tab))
            }
            pageNumber += 1

            val relevant = page.items.filter { it.entry.publishedAtMillis >= cutoff }
            insertedCount += database.insertHistoricalVideos(target.creator, relevant)
            reporter.report()

            cursor = page.nextCursor
            complete = isHistoryTargetComplete(
                publishedTimes = page.items.map { it.entry.publishedAtMillis },
                cutoff = cutoff,
                hasNextPage = cursor != null,
                chronological = target.source.type != SourceType.PLAYLIST,
            )
            if (complete) {
                break
            }
            val nextCursor = requireNotNull(cursor)
            if (!seenCursorTokens.add(nextCursor.token)) {
                throw IOException("YouTube powtórzył kursor historii")
            }
        }
        return TargetLoadResult(insertedCount = insertedCount, complete = complete)
    }

    private suspend fun loadDataApiTarget(
        target: Target,
        resolved: ResolvedSource,
        cutoff: Long,
        settings: AppSettings,
        apiKey: String,
        reporter: ProgressReporter,
    ): TargetLoadResult {
        var pageToken: String? = null
        var insertedCount = 0
        var pageNumber = 0
        var complete = false
        val seenPageTokens = mutableSetOf<String>()

        while (pageNumber < MAX_PAGES_PER_TARGET) {
            currentCoroutineContext().ensureActive()
            val page = dataApiClient.fetchPage(
                source = resolved,
                apiKey = apiKey,
                pageToken = pageToken,
                classifyAfterMillis = cutoff,
            )
            pageNumber += 1
            val relevant = page.items.filter {
                it.entry.publishedAtMillis >= cutoff && it.kind.matches(settings.historyFilters)
            }
            insertedCount += database.insertHistoricalVideos(target.creator, relevant)
            reporter.report()

            pageToken = page.nextPageToken
            complete = isHistoryTargetComplete(
                publishedTimes = page.items.map { it.entry.publishedAtMillis },
                cutoff = cutoff,
                hasNextPage = pageToken != null,
                chronological = target.source.type != SourceType.PLAYLIST,
            )
            if (complete) {
                break
            }
            val nextPageToken = requireNotNull(pageToken)
            if (!seenPageTokens.add(nextPageToken)) {
                throw IOException("YouTube Data API powtórzył token strony")
            }
        }
        return TargetLoadResult(insertedCount = insertedCount, complete = complete)
    }

    private fun buildTargets(settings: AppSettings, useDataApi: Boolean): List<Target> {
        if (settings.historyFilters.isEmpty()) return emptyList()
        if (useDataApi) {
            return buildList {
                catalog.creators
                    .filter { it.id in settings.selectedCreatorIds }
                    .forEach { creator ->
                        creator.sources.forEach { source ->
                            add(Target(creator, source, tab = null))
                        }
                    }
            }
        }
        return buildList {
            catalog.creators
                .filter { it.id in settings.selectedCreatorIds }
                .forEach { creator ->
                    creator.sources.forEach { source ->
                        addAll(buildWebTargets(creator, source, settings))
                    }
                }
        }
    }

    private fun buildWebTargets(
        creator: Creator,
        source: CreatorSource,
        settings: AppSettings,
    ): List<Target> {
        if (settings.historyFilters.isEmpty()) return emptyList()
        if (source.type == SourceType.PLAYLIST) {
            // Typ materiału z playlisty jest klasyfikowany po pobraniu; sama
            // playlista nie znika już przy wyłączeniu filtra „Filmy”.
            return listOf(Target(creator, source, YouTubeHistoryTab.PLAYLIST))
        }
        return buildList {
            if (HistoryFilter.VIDEOS in settings.historyFilters) {
                add(Target(creator, source, YouTubeHistoryTab.VIDEOS))
            }
            if (HistoryFilter.STREAMS in settings.historyFilters) {
                add(Target(creator, source, YouTubeHistoryTab.STREAMS))
            }
            if (HistoryFilter.SHORTS in settings.historyFilters) {
                add(Target(creator, source, YouTubeHistoryTab.SHORTS))
            }
        }
    }

    private fun buildSignature(settings: AppSettings, apiKey: String): String = buildString {
        append(settings.selectedCreatorIds.sorted().joinToString(","))
        append('|')
        append(settings.historyFilters.map { it.name }.sorted().joinToString(","))
        append('|')
        append(settings.historyWindowDays)
        append('|')
        append(
            if (apiKey.isBlank()) {
                "RSS+WEB"
            } else {
                "RSS+API:${apiKey.hashCode()}"
            },
        )
    }

    private class ProgressReporter(
        private val callback: suspend () -> Unit,
    ) {
        private val mutex = Mutex()
        private var lastReportAt = 0L

        suspend fun report(force: Boolean = false) = mutex.withLock {
            val now = System.currentTimeMillis()
            if (force || now - lastReportAt >= PROGRESS_INTERVAL_MILLIS) {
                lastReportAt = now
                callback()
            }
        }
    }

    private fun List<String>.toSummary(): String {
        val visible = take(3).joinToString("\n")
        val remaining = size - 3
        return if (remaining > 0) "$visible\n…i jeszcze $remaining" else visible
    }

    private fun pl.lewicowyt.notifier.model.VideoKind.matches(
        filters: Set<HistoryFilter>,
    ): Boolean = when (this) {
        pl.lewicowyt.notifier.model.VideoKind.VIDEO,
        pl.lewicowyt.notifier.model.VideoKind.UNKNOWN -> HistoryFilter.VIDEOS in filters
        pl.lewicowyt.notifier.model.VideoKind.LIVE,
        pl.lewicowyt.notifier.model.VideoKind.UPCOMING,
        pl.lewicowyt.notifier.model.VideoKind.STREAM_ARCHIVE -> HistoryFilter.STREAMS in filters
        pl.lewicowyt.notifier.model.VideoKind.SHORT -> HistoryFilter.SHORTS in filters
    }

    private companion object {
        const val MAX_PARALLEL_CHANNELS = 8
        const val MAX_PAGES_PER_TARGET = 120
        const val MAX_ERROR_DETAIL_CHARS = 400
        const val MAX_ERROR_LINE_CHARS = 600
        const val PROGRESS_INTERVAL_MILLIS = 400L
        const val DAY_MILLIS = 24L * 60L * 60L * 1_000L
    }
}

private suspend inline fun <T> runSuspendCatching(
    crossinline block: suspend () -> T,
): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    Result.failure(error)
}

internal fun shouldContinueHistoryPaging(
    publishedTimes: List<Long>,
    cutoff: Long,
    hasNextPage: Boolean,
    loadedPageCount: Int,
    maxPages: Int = 30,
): Boolean =
    !isHistoryRangeComplete(publishedTimes, cutoff, hasNextPage) &&
        loadedPageCount < maxPages

internal fun isHistoryTargetComplete(
    publishedTimes: List<Long>,
    cutoff: Long,
    hasNextPage: Boolean,
    chronological: Boolean,
): Boolean =
    if (chronological) {
        isHistoryRangeComplete(publishedTimes, cutoff, hasNextPage)
    } else {
        !hasNextPage
    }

internal fun isHistoryRangeComplete(
    publishedTimes: List<Long>,
    cutoff: Long,
    hasNextPage: Boolean,
): Boolean =
    !hasNextPage ||
        (publishedTimes.isNotEmpty() && publishedTimes.min() < cutoff)

internal fun rssHistoryItems(
    entries: List<VideoEntry>,
    cutoff: Long,
): List<YouTubeHistoryItem> = entries
    .asSequence()
    .filter { it.publishedAtMillis >= cutoff }
    .distinctBy { it.id }
    .map { entry ->
        YouTubeHistoryItem(
            entry = entry.copy(origin = VideoOrigin.YOUTUBE),
            // RSS nie rozróżnia niezawodnie filmu, Shorta i transmisji.
            // Następna odpowiedź API/Web uzupełni dokładny typ.
            kind = VideoKind.UNKNOWN,
        )
    }
    .toList()
