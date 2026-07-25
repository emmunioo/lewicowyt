package pl.lewicowyt.notifier.sync

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import pl.lewicowyt.notifier.AppLog
import pl.lewicowyt.notifier.data.CreatorCatalog
import pl.lewicowyt.notifier.data.DataRetentionPolicy
import pl.lewicowyt.notifier.data.LocalDatabase
import pl.lewicowyt.notifier.data.NotificationCursor
import pl.lewicowyt.notifier.data.PreferencesRepository
import pl.lewicowyt.notifier.model.Creator
import pl.lewicowyt.notifier.model.CreatorSource
import pl.lewicowyt.notifier.model.SourceType
import pl.lewicowyt.notifier.model.SyncOutcome
import pl.lewicowyt.notifier.model.VideoEntry
import pl.lewicowyt.notifier.model.VideoKind
import pl.lewicowyt.notifier.model.VideoOrigin
import pl.lewicowyt.notifier.network.ResolvedSource
import pl.lewicowyt.notifier.network.YouTubeDataApiHistoryClient
import pl.lewicowyt.notifier.network.YouTubeFeedClient
import pl.lewicowyt.notifier.network.YouTubeHistoryClient
import pl.lewicowyt.notifier.network.YouTubeHistoryCursor
import pl.lewicowyt.notifier.network.YouTubeHistoryTab
import pl.lewicowyt.notifier.network.YouTubePageClassifier
import pl.lewicowyt.notifier.network.YouTubeSourceResolver
import pl.lewicowyt.notifier.notifications.NotificationHelper
import pl.lewicowyt.notifier.notifications.toNotificationCandidate

class SyncEngine(
    private val catalog: CreatorCatalog,
    private val preferences: PreferencesRepository,
    private val database: LocalDatabase,
    private val resolver: YouTubeSourceResolver,
    private val feedClient: YouTubeFeedClient,
    private val classifier: YouTubePageClassifier,
    private val notifications: NotificationHelper,
    private val dataApiClient: YouTubeDataApiHistoryClient,
    private val historyClient: YouTubeHistoryClient,
) {
    private val syncMutex = Mutex()

    /**
     * Pozwala wykonać operację administracyjną dopiero po zakończeniu lub
     * anulowaniu synchronizacji i blokuje start następnej do czasu jej końca.
     */
    suspend fun <T> runExclusiveMaintenance(block: suspend () -> T): T =
        syncMutex.withLock { block() }

    suspend fun sync(): SyncOutcome = syncMutex.withLock {
        withContext(Dispatchers.IO) {
            database.pruneExpiredData()
            val settings = preferences.current()
            val apiKey = if (settings.youtubeApiEnabled) preferences.youtubeApiKey() else ""
            val selectedIds = settings.selectedCreatorIds
            val selectedCreators = catalog.creators.filter { it.id in selectedIds }
            val sourceSemaphore = Semaphore(MAX_PARALLEL_SOURCES)
            val classificationSemaphore = Semaphore(MAX_PARALLEL_CLASSIFICATIONS)
            val sourceResults = coroutineScope {
                selectedCreators
                    .flatMap { creator -> creator.sources.map { creator to it } }
                    .map { (creator, source) ->
                        async(Dispatchers.IO) {
                            sourceSemaphore.withPermit {
                                synchronizeSource(
                                    creator = creator,
                                    source = source,
                                    apiKey = apiKey,
                                    classificationSemaphore = classificationSemaphore,
                                )
                            }
                        }
                    }
                    .awaitAll()
            }
            val checkedSources = sourceResults.count(SourceSyncResult::checked)
            val detectedItems = sourceResults.sumOf(SourceSyncResult::detectedItems)
            val errors = sourceResults.mapNotNullTo(mutableListOf(), SourceSyncResult::error)

            val pendingItems = database.pendingUpcoming(selectedIds)
            val unclassifiedItems = database.unclassifiedHistory(selectedIds, limit = 12)
            val apiKinds = if (apiKey.isNotBlank()) {
                try {
                    dataApiClient.fetchVideoKinds(
                        (pendingItems + unclassifiedItems).map { it.videoId },
                        apiKey,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    AppLog.warning(
                        "lewicowYTSync",
                        "YouTube Data API nie sklasyfikowało istniejących materiałów; " +
                            "używam strony YouTube",
                        error,
                    )
                    emptyMap()
                }
            } else {
                emptyMap()
            }
            val missingKindIds = (pendingItems + unclassifiedItems)
                .map { it.videoId }
                .distinct()
                .filterNot(apiKinds::containsKey)
            val fallbackKinds = coroutineScope {
                missingKindIds.map { videoId ->
                    async(Dispatchers.IO) {
                        videoId to classificationSemaphore.withPermit {
                            classifier.classify(videoId)
                        }
                    }
                }.awaitAll().toMap()
            }
            val classifiedKinds = apiKinds + fallbackKinds

            for (pending in pendingItems) {
                when (val kind = classifiedKinds[pending.videoId] ?: VideoKind.UNKNOWN) {
                    VideoKind.LIVE,
                    VideoKind.STREAM_ARCHIVE,
                    VideoKind.VIDEO,
                    VideoKind.SHORT -> {
                        database.markVideoState(pending.videoId, kind, notified = false)
                    }

                    VideoKind.UPCOMING, VideoKind.UNKNOWN -> Unit
                }
            }

            // Starsze rekordy z wersji <= 0.3.0 były zapisywane jako zwykłe filmy.
            // Klasyfikujemy je stopniowo, aby nie wykonywać dziesiątek zapytań naraz.
            for (historyItem in unclassifiedItems) {
                val classified = classifiedKinds[historyItem.videoId] ?: VideoKind.UNKNOWN
                if (classified != VideoKind.UNKNOWN) {
                    database.markVideoClassification(
                        videoId = historyItem.videoId,
                        kind = classified,
                    )
                } else {
                    database.recordFailedVideoClassification(historyItem.videoId)
                }
            }

            // Użytkownik mógł zmienić wybór kanałów podczas trwających zapytań.
            // Odczytujemy ustawienie ponownie bezpośrednio przed dostarczeniem.
            val deliveryCreatorIds = preferences.current().selectedCreatorIds
            val uniqueCandidates = database.pendingNotifications(deliveryCreatorIds)
                .map { it.toNotificationCandidate() }
                .distinctBy { it.entry.id }
            if (uniqueCandidates.isNotEmpty()) {
                database.addNotificationInbox(uniqueCandidates.map { it.entry.id })
            }
            val delivery = notifications.notifyBatch(uniqueCandidates) { candidate ->
                candidate.creator.id in preferences.selectedCreatorIds()
            }
            database.markVideosNotified(delivery.deliveredVideoIds)

            val outcome = SyncOutcome(
                checkedSources = checkedSources,
                detectedItems = detectedItems,
                notificationsSent = delivery.systemNotificationsSent,
                errors = errors,
            )
            preferences.updateLastSync(System.currentTimeMillis(), outcome.toPolishSummary())
            outcome
        }
    }

    private suspend fun synchronizeSource(
        creator: Creator,
        source: CreatorSource,
        apiKey: String,
        classificationSemaphore: Semaphore,
    ): SourceSyncResult {
        val sourceKey = resolver.sourceKey(creator, source)
        return try {
            val resolved = resolver.resolve(creator, source)
            val sourceInitialized = database.isSourceInitialized(sourceKey)
            val notificationCursor = database.getNotificationCursor(sourceKey)
            val lastCheckedMillis = database.getSourceLastCheckedMillis(sourceKey)
            val entries = fetchNotificationEntries(
                resolved = resolved,
                apiKey = apiKey,
                cursor = notificationCursor,
            )

            if (!sourceInitialized) {
                // Pierwsze uruchomienie tworzy punkt odniesienia bez zgłaszania
                // całej istniejącej historii. Baza ufa przy tym wyłącznie
                // wpisom potwierdzonym przez YouTube podczas zapisu kursora.
                database.seedSource(sourceKey, creator, entries.map { it.entry })
                return SourceSyncResult(checked = true)
            }

            var detectedItems = 0
            for (fetched in entries) {
                val entry = fetched.entry.copy(origin = VideoOrigin.YOUTUBE)
                val shouldNotify = shouldProcessNotificationEntry(
                    entry = entry,
                    cursor = notificationCursor,
                    lastCheckedMillis = lastCheckedMillis,
                )
                val kind = when {
                    fetched.apiKind != null -> fetched.apiKind
                    shouldNotify -> classificationSemaphore.withPermit {
                        classifier.classify(entry.id)
                    }
                    else -> VideoKind.UNKNOWN
                }
                val becamePending = database.upsertVerifiedVideoFromSync(
                    creator = creator,
                    entry = entry,
                    kind = kind,
                    shouldNotify = shouldNotify,
                    classificationVersion = if (kind == VideoKind.UNKNOWN) 0 else 1,
                )
                if (becamePending) detectedItems += 1
            }
            newestTrustedNotificationEntry(entries.map { it.entry })?.let {
                database.saveNotificationCursor(sourceKey, it)
            }
            database.markSourceChecked(sourceKey, null)
            SourceSyncResult(
                checked = true,
                detectedItems = detectedItems,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val message = (
                "${creator.name}: ${error.message ?: error.javaClass.simpleName}"
                ).take(MAX_SOURCE_ERROR_CHARS)
            database.markSourceChecked(sourceKey, message)
            AppLog.error(
                "lewicowYTSync",
                "Błąd pojedynczego źródła YouTube",
                error,
            )
            SourceSyncResult(error = message)
        }
    }

    private suspend fun fetchNotificationEntries(
        resolved: ResolvedSource,
        apiKey: String,
        cursor: NotificationCursor?,
    ): List<FetchedNotificationEntry> = coroutineScope {
        val retentionCutoff = DataRetentionPolicy.cutoffs(System.currentTimeMillis())
            .historyBeforeMillis
        val coverageCursor = notificationCoverageCursor(cursor, retentionCutoff)
        val rssRequest = async(Dispatchers.IO) {
            runSuspendCatching { feedClient.fetch(resolved) }
        }
        val dataApiRequest = if (apiKey.isNotBlank()) {
            async(Dispatchers.IO) {
                runSuspendCatching {
                    fetchDataApiNotificationEntries(
                        resolved = resolved,
                        apiKey = apiKey,
                        cursorPublishedAtMillis = coverageCursor?.publishedAtMillis,
                    )
                }
            }
        } else {
            null
        }
        val rssResult = rssRequest.await()
        val dataApiResult = dataApiRequest?.await()

        val rssCoversRange = rssResult.isSuccess &&
            notificationFeedCoversCursor(
                entries = rssResult.getOrDefault(emptyList()),
                cursor = coverageCursor,
            )
        val pagedApiComplete = dataApiResult?.isSuccess == true
        val webResult = if (!pagedApiComplete && !rssCoversRange) {
            runSuspendCatching {
                fetchWebNotificationEntries(
                    resolved = resolved,
                    cursorPublishedAtMillis = coverageCursor?.publishedAtMillis,
                )
            }
        } else {
            null
        }

        if (!pagedApiComplete && !rssCoversRange && webResult?.isSuccess != true) {
            throw IOException(
                buildNotificationSourceError(rssResult, dataApiResult, webResult)
                    .take(MAX_SOURCE_ERROR_CHARS),
            )
        }

        rssResult.exceptionOrNull()?.let { error ->
            AppLog.warning(
                "lewicowYTSync",
                "YouTube RSS nie odpowiedział; używam innego źródła YouTube",
                error,
            )
        }
        dataApiResult?.exceptionOrNull()?.let { error ->
            AppLog.warning(
                "lewicowYTSync",
                "YouTube Data API nie odpowiedziało; używam YouTube RSS",
                error,
            )
        }
        webResult?.exceptionOrNull()?.let { error ->
            AppLog.warning(
                "lewicowYTSync",
                "YouTube Web nie uzupełnił luki w RSS",
                error,
            )
        }

        val merged = linkedMapOf<String, FetchedNotificationEntry>()
        rssResult.getOrDefault(emptyList()).forEach { entry ->
            merged[entry.id] = FetchedNotificationEntry(
                entry = entry.copy(origin = VideoOrigin.YOUTUBE),
            )
        }
        webResult?.getOrDefault(emptyList()).orEmpty().forEach { item ->
            merged[item.entry.id] = item
        }
        dataApiResult?.getOrDefault(emptyList()).orEmpty().forEach { item ->
            merged[item.entry.id] = item
        }
        merged.values
            .filter { cursor == null || it.entry.publishedAtMillis >= retentionCutoff }
            .sortedByDescending { it.entry.publishedAtMillis }
    }

    private suspend fun fetchDataApiNotificationEntries(
        resolved: ResolvedSource,
        apiKey: String,
        cursorPublishedAtMillis: Long?,
    ): List<FetchedNotificationEntry> {
        val result = linkedMapOf<String, FetchedNotificationEntry>()
        val seenTokens = mutableSetOf<String>()
        var pageToken: String? = null
        var pageCount = 0

        while (pageCount < MAX_NOTIFICATION_PAGES) {
            currentCoroutineContext().ensureActive()
            val page = dataApiClient.fetchPage(
                source = resolved,
                apiKey = apiKey,
                pageToken = pageToken,
                classifyAfterMillis = cursorPublishedAtMillis ?: Long.MAX_VALUE,
            )
            pageCount += 1
            page.items.forEach { item ->
                result[item.entry.id] = FetchedNotificationEntry(
                    entry = item.entry.copy(origin = VideoOrigin.YOUTUBE),
                    apiKind = item.kind.takeUnless { it == VideoKind.UNKNOWN },
                )
            }

            val nextToken = page.nextPageToken
            if (isNotificationPagingComplete(
                    publishedTimes = page.items.map { it.entry.publishedAtMillis },
                    cursorPublishedAtMillis = cursorPublishedAtMillis,
                    hasNextPage = nextToken != null,
                    chronological = resolved.type != SourceType.PLAYLIST,
                )
            ) {
                return result.values.toList()
            }
            checkNotNull(nextToken)
            if (!seenTokens.add(nextToken)) {
                throw IOException("YouTube Data API powtórzyło token strony powiadomień")
            }
            pageToken = nextToken
        }
        throw IOException("YouTube Data API przekroczyło limit stron powiadomień")
    }

    private suspend fun fetchWebNotificationEntries(
        resolved: ResolvedSource,
        cursorPublishedAtMillis: Long?,
    ): List<FetchedNotificationEntry> = coroutineScope {
        val tabs = if (resolved.type == SourceType.PLAYLIST) {
            listOf(YouTubeHistoryTab.PLAYLIST)
        } else {
            listOf(
                YouTubeHistoryTab.VIDEOS,
                YouTubeHistoryTab.STREAMS,
                YouTubeHistoryTab.SHORTS,
            )
        }
        tabs.map { tab ->
            async(Dispatchers.IO) {
                fetchWebNotificationTab(resolved, tab, cursorPublishedAtMillis)
            }
        }.awaitAll().flatten()
    }

    private suspend fun fetchWebNotificationTab(
        resolved: ResolvedSource,
        tab: YouTubeHistoryTab,
        cursorPublishedAtMillis: Long?,
    ): List<FetchedNotificationEntry> {
        val result = linkedMapOf<String, FetchedNotificationEntry>()
        val seenTokens = mutableSetOf<String>()
        var cursor: YouTubeHistoryCursor? = null
        var pageCount = 0

        while (pageCount < MAX_NOTIFICATION_PAGES) {
            currentCoroutineContext().ensureActive()
            val page = if (cursor == null) {
                historyClient.firstPage(resolved, tab)
            } else {
                historyClient.nextPage(cursor, tab)
            }
            pageCount += 1
            page.items.forEach { item ->
                result[item.entry.id] = FetchedNotificationEntry(
                    entry = item.entry.copy(origin = VideoOrigin.YOUTUBE),
                    apiKind = item.kind,
                )
            }
            val nextCursor = page.nextCursor
            if (isNotificationPagingComplete(
                    publishedTimes = page.items.map { it.entry.publishedAtMillis },
                    cursorPublishedAtMillis = cursorPublishedAtMillis,
                    hasNextPage = nextCursor != null,
                    chronological = resolved.type != SourceType.PLAYLIST,
                )
            ) {
                return result.values.toList()
            }
            checkNotNull(nextCursor)
            if (!seenTokens.add(nextCursor.token)) {
                throw IOException("YouTube Web powtórzył kursor powiadomień")
            }
            cursor = nextCursor
        }
        throw IOException("YouTube Web przekroczył limit stron powiadomień")
    }

    private companion object {
        const val MAX_PARALLEL_SOURCES = 6
        const val MAX_PARALLEL_CLASSIFICATIONS = 6
        const val MAX_NOTIFICATION_PAGES = 40
        const val MAX_SOURCE_ERROR_CHARS = 600
    }
}

private data class SourceSyncResult(
    val checked: Boolean = false,
    val detectedItems: Int = 0,
    val error: String? = null,
)

internal data class FetchedNotificationEntry(
    val entry: VideoEntry,
    val apiKind: VideoKind? = null,
)

internal fun notificationFeedCoversCursor(
    entries: List<VideoEntry>,
    cursor: NotificationCursor?,
): Boolean =
    cursor == null ||
        entries.any { entry -> !isAfterNotificationCursor(entry, cursor) }

internal fun notificationCoverageCursor(
    cursor: NotificationCursor?,
    retentionCutoffMillis: Long,
): NotificationCursor? = when {
    cursor == null -> null
    cursor.publishedAtMillis >= retentionCutoffMillis -> cursor
    else -> NotificationCursor(videoId = "", publishedAtMillis = retentionCutoffMillis)
}

internal fun isNotificationPagingComplete(
    publishedTimes: List<Long>,
    cursorPublishedAtMillis: Long?,
    hasNextPage: Boolean,
    chronological: Boolean,
): Boolean =
    !hasNextPage ||
        (
            chronological &&
                (
                    cursorPublishedAtMillis == null ||
                        publishedTimes.any { it < cursorPublishedAtMillis }
                    )
            )

private fun buildNotificationSourceError(
    rssResult: Result<List<VideoEntry>>,
    dataApiResult: Result<List<FetchedNotificationEntry>>?,
    webResult: Result<List<FetchedNotificationEntry>>?,
): String = buildString {
    append("Nie udało się objąć całego zakresu powiadomień")
    listOf(
        "YouTube RSS" to rssResult.exceptionOrNull(),
        "YouTube Data API" to dataApiResult?.exceptionOrNull(),
        "YouTube Web" to webResult?.exceptionOrNull(),
    ).forEach { (source, error) ->
        if (error != null) {
            append(" | ")
            append(source)
            append(": ")
            append((error.message ?: error.javaClass.simpleName).take(300))
        }
    }
}

internal fun isAfterNotificationCursor(
    entry: VideoEntry,
    cursor: NotificationCursor,
): Boolean = entry.publishedAtMillis > cursor.publishedAtMillis ||
    (
        entry.publishedAtMillis == cursor.publishedAtMillis &&
            entry.id > cursor.videoId
        )

internal fun newestTrustedNotificationEntry(entries: Collection<VideoEntry>): VideoEntry? =
    entries.filter { it.origin == VideoOrigin.YOUTUBE }.maxWithOrNull(
        compareBy<VideoEntry> { it.publishedAtMillis }.thenBy { it.id },
    )

internal fun shouldProcessNotificationEntry(
    entry: VideoEntry,
    cursor: NotificationCursor?,
    lastCheckedMillis: Long,
    notificationGraceMillis: Long = 15L * 60L * 1_000L,
): Boolean = when {
    entry.origin != VideoOrigin.YOUTUBE -> false
    cursor != null ->
        isAfterNotificationCursor(entry, cursor)
    else -> entry.publishedAtMillis >=
        (lastCheckedMillis - notificationGraceMillis).coerceAtLeast(0L)
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
