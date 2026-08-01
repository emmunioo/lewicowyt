package pl.lewicowyt.notifier.sync

import java.io.IOException
import java.util.Collections
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
import pl.lewicowyt.notifier.data.NEW_ENTRY_TAB_REFRESH_MILLIS
import pl.lewicowyt.notifier.data.NotificationCursor
import pl.lewicowyt.notifier.data.PreferencesRepository
import pl.lewicowyt.notifier.data.contentType
import pl.lewicowyt.notifier.data.dailyChannelTabRefreshBoundaryMillis
import pl.lewicowyt.notifier.data.historyTypesFor
import pl.lewicowyt.notifier.data.isHistoryEnabledFor
import pl.lewicowyt.notifier.data.isNotificationEnabledFor
import pl.lewicowyt.notifier.data.notificationTypesFor
import pl.lewicowyt.notifier.diagnostics.DiagnosticCategory
import pl.lewicowyt.notifier.diagnostics.DiagnosticDownloadArea
import pl.lewicowyt.notifier.diagnostics.DiagnosticDownloadRole
import pl.lewicowyt.notifier.diagnostics.DiagnosticLogStore
import pl.lewicowyt.notifier.diagnostics.DiagnosticYouTubeSource
import pl.lewicowyt.notifier.diagnostics.logYouTubeDownload
import pl.lewicowyt.notifier.images.CreatorAvatarUpdater
import pl.lewicowyt.notifier.model.Creator
import pl.lewicowyt.notifier.model.CreatorSource
import pl.lewicowyt.notifier.model.HistoryFilter
import pl.lewicowyt.notifier.model.PublishedAtDecision
import pl.lewicowyt.notifier.model.PublishedAtEvidence
import pl.lewicowyt.notifier.model.SourceType
import pl.lewicowyt.notifier.model.SyncOutcome
import pl.lewicowyt.notifier.model.VideoEntry
import pl.lewicowyt.notifier.model.VideoKind
import pl.lewicowyt.notifier.model.VideoKindDecision
import pl.lewicowyt.notifier.model.VideoKindEvidence
import pl.lewicowyt.notifier.model.VideoOrigin
import pl.lewicowyt.notifier.model.choosePublishedAtDecision
import pl.lewicowyt.notifier.model.chooseVideoKindDecision
import pl.lewicowyt.notifier.network.ResolvedSource
import pl.lewicowyt.notifier.network.YouTubeDataApiHistoryClient
import pl.lewicowyt.notifier.network.YouTubeFeedClient
import pl.lewicowyt.notifier.network.YouTubeHistoryClient
import pl.lewicowyt.notifier.network.YouTubeHistoryCursor
import pl.lewicowyt.notifier.network.YouTubeHistoryTab
import pl.lewicowyt.notifier.network.YouTubePageClassifier
import pl.lewicowyt.notifier.network.YouTubeSourceResolver
import pl.lewicowyt.notifier.network.rssVideoKindDecision
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
    private val sourcePriorityScheduler: SourcePriorityScheduler,
    private val avatarUpdater: CreatorAvatarUpdater,
) {
    private val syncMutex = Mutex()

    fun isSyncInProgress(): Boolean = syncMutex.isLocked

    /**
     * Pozwala wykonać operację administracyjną dopiero po zakończeniu lub
     * anulowaniu synchronizacji i blokuje start następnej do czasu jej końca.
     */
    suspend fun <T> runExclusiveMaintenance(block: suspend () -> T): T =
        syncMutex.withLock { block() }

    suspend fun sync(): SyncOutcome = syncMutex.withLock {
        withContext(Dispatchers.IO) {
            val startedAtNanos = System.nanoTime()
            database.pruneExpiredData()
            val settings = preferences.current()
            val apiKey = if (settings.youtubeApiEnabled) preferences.youtubeApiKey() else ""
            val selectedIds = settings.selectedCreatorIds
            val selectedCreators = catalog.creators.filter {
                it.id in selectedIds && settings.historyTypesFor(it.id).isNotEmpty()
            }
            DiagnosticLogStore.info(
                DiagnosticCategory.SYNC,
                "Start; twórcy=${selectedCreators.size}; źródło=${if (apiKey.isBlank()) "RSS+Web" else "RSS+API"}",
            )
            val classificationSemaphore = Semaphore(MAX_PARALLEL_CLASSIFICATIONS)
            val prioritizedSources = runCatching {
                sourcePriorityScheduler.prioritizeSources(
                    creators = selectedCreators,
                    intervalMinutes = settings.intervalMinutes,
                )
            }.onFailure { error ->
                AppLog.warning(
                    "SourcePriority",
                    "Nie udało się ustalić adaptacyjnej kolejności; używam katalogu",
                    error,
                )
            }.getOrElse {
                selectedCreators.flatMap { creator ->
                    creator.sources.map { source -> creator to source }
                }
            }
            val completedPriorityObservations = Collections.synchronizedList(
                mutableListOf<SourcePriorityObservation>(),
            )
            val sourceResults = try {
                prioritizedSources
                    .mapConcurrently(MAX_PARALLEL_SOURCES) { (creator, source) ->
                        synchronizeSource(
                            creator = creator,
                            source = source,
                            apiKey = apiKey,
                            classificationSemaphore = classificationSemaphore,
                            enabledHistoryTypes = settings.historyTypesFor(creator.id),
                            enabledNotificationTypes = settings.notificationTypesFor(creator.id),
                        ).also { result ->
                            result.priorityObservation?.let(
                                completedPriorityObservations::add,
                            )
                        }
                    }
            } finally {
                val completed = synchronized(completedPriorityObservations) {
                    completedPriorityObservations.toList()
                }
                runCatching {
                    sourcePriorityScheduler.recordOutcomes(completed)
                }.onFailure { error ->
                    // Model jest optymalizacją kolejności. Jego awaria nie może
                    // unieważnić poprawnie zakończonego sprawdzania YouTube.
                    AppLog.warning(
                        "SourcePriority",
                        "Nie udało się zaktualizować lokalnego modelu kolejności",
                        error,
                    )
                }
            }
            val checkedSources = sourceResults.count(SourceSyncResult::checked)
            val detectedItems = sourceResults.sumOf(SourceSyncResult::detectedItems)
            val errors = sourceResults.mapNotNullTo(mutableListOf(), SourceSyncResult::error)

            val pendingItems = database.pendingUpcoming(selectedIds).filter {
                settings.isHistoryEnabledFor(it.creatorId, it.kind)
            }
            val unclassifiedItems = database.unclassifiedHistory(selectedIds, limit = 12)
            val apiKinds = if (apiKey.isNotBlank()) {
                try {
                    dataApiClient.fetchVideoKindDecisions(
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
                        val kind = classificationSemaphore.withPermit {
                            classifier.classify(videoId)
                        }
                        videoId to if (kind == VideoKind.UNKNOWN) {
                            VideoKindDecision.Unknown
                        } else {
                            VideoKindDecision(kind, VideoKindEvidence.PLAYER_METADATA)
                        }
                    }
                }.awaitAll().toMap()
            }
            val classifiedKinds = apiKinds + fallbackKinds
            if (apiKinds.isNotEmpty()) {
                val apiIds = apiKinds.keys
                logYouTubeDownload(
                    area = DiagnosticDownloadArea.NOTIFICATIONS,
                    source = DiagnosticYouTubeSource.DATA_API,
                    videoIds = pendingItems.map { it.videoId }.filter(apiIds::contains),
                    role = DiagnosticDownloadRole.CLASSIFICATION,
                )
                logYouTubeDownload(
                    area = DiagnosticDownloadArea.HISTORY,
                    source = DiagnosticYouTubeSource.DATA_API,
                    videoIds = unclassifiedItems.map { it.videoId }.filter(apiIds::contains),
                    role = DiagnosticDownloadRole.CLASSIFICATION,
                )
            }
            if (fallbackKinds.isNotEmpty()) {
                val webIds = fallbackKinds.keys
                logYouTubeDownload(
                    area = DiagnosticDownloadArea.NOTIFICATIONS,
                    source = DiagnosticYouTubeSource.WEB,
                    videoIds = pendingItems.map { it.videoId }.filter(webIds::contains),
                    role = DiagnosticDownloadRole.CLASSIFICATION,
                )
                logYouTubeDownload(
                    area = DiagnosticDownloadArea.HISTORY,
                    source = DiagnosticYouTubeSource.WEB,
                    videoIds = unclassifiedItems.map { it.videoId }.filter(webIds::contains),
                    role = DiagnosticDownloadRole.CLASSIFICATION,
                )
            }

            for (pending in pendingItems) {
                val decision =
                    classifiedKinds[pending.videoId] ?: VideoKindDecision.Unknown
                when (decision.kind) {
                    VideoKind.LIVE,
                    VideoKind.STREAM_ARCHIVE,
                    VideoKind.VIDEO,
                    VideoKind.SHORT -> {
                        database.markVideoState(
                            videoId = pending.videoId,
                            kind = decision.kind,
                            evidence = decision.evidence,
                            notified = false,
                        )
                    }

                    VideoKind.UPCOMING, VideoKind.UNKNOWN -> Unit
                }
            }

            // Starsze rekordy z wersji <= 0.3.0 były zapisywane jako zwykłe filmy.
            // Klasyfikujemy je stopniowo, aby nie wykonywać dziesiątek zapytań naraz.
            val resolvedHistoryKinds = linkedMapOf<String, VideoKindDecision>()
            val failedHistoryKinds = mutableListOf<String>()
            for (historyItem in unclassifiedItems) {
                val decision =
                    classifiedKinds[historyItem.videoId] ?: VideoKindDecision.Unknown
                if (decision.kind != VideoKind.UNKNOWN) {
                    resolvedHistoryKinds[historyItem.videoId] = decision
                } else {
                    failedHistoryKinds += historyItem.videoId
                }
            }
            database.markVideoClassifications(resolvedHistoryKinds)
            database.recordFailedVideoClassifications(failedHistoryKinds)

            // Użytkownik mógł zmienić wybór kanałów podczas trwających zapytań.
            // Odczytujemy ustawienie ponownie bezpośrednio przed dostarczeniem.
            val deliverySettings = preferences.current()
            val deliveryCreatorIds = deliverySettings.selectedCreatorIds
            val (allowedPending, suppressedPending) =
                database.pendingNotifications(deliveryCreatorIds).partition {
                    deliverySettings.isNotificationEnabledFor(it.creatorId, it.kind)
                }
            // Rekord wyłączonego rodzaju nie może czekać ukryty i wywołać starego
            // powiadomienia po ponownym włączeniu ustawienia.
            database.markVideosNotified(suppressedPending.map { it.videoId })
            val uniqueCandidates = allowedPending
                .map { it.toNotificationCandidate() }
                .distinctBy { it.entry.id }
            if (uniqueCandidates.isNotEmpty()) {
                database.addNotificationInbox(uniqueCandidates.map { it.entry.id })
            }
            val delivery = notifications.notifyBatch(uniqueCandidates) { candidate ->
                val latest = preferences.current()
                candidate.creator.id in latest.selectedCreatorIds &&
                    latest.isNotificationEnabledFor(candidate.creator.id, candidate.kind)
            }
            database.markVideosNotified(delivery.deliveredVideoIds)

            // Kontrola profilowych jest rzadka i nie może zablokować właściwej
            // synchronizacji filmów ani dostarczenia powiadomień.
            try {
                avatarUpdater.refreshDue(catalog.creators)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppLog.warning(
                    "CreatorAvatarUpdater",
                    "Tygodniowa kontrola awatarów nie powiodła się",
                    error,
                )
            }

            val outcome = SyncOutcome(
                checkedSources = checkedSources,
                detectedItems = detectedItems,
                notificationsSent = delivery.systemNotificationsSent,
                errors = errors,
            )
            val durationSeconds = (System.nanoTime() - startedAtNanos) / 1_000_000_000L
            DiagnosticLogStore.info(
                DiagnosticCategory.SYNC,
                "Koniec; sprawdzone=$checkedSources; nowe=$detectedItems; " +
                    "powiadomienia=${delivery.systemNotificationsSent}; błędy=${errors.size}; " +
                    "czas_s=${durationSeconds.coerceAtLeast(0L)}",
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
        enabledHistoryTypes: Set<HistoryFilter>,
        enabledNotificationTypes: Set<HistoryFilter>,
    ): SourceSyncResult {
        val sourceKey = resolver.sourceKey(creator, source)
        val priorityCandidate = sourcePriorityScheduler.candidate(creator, source)
        return try {
            val resolved = resolver.resolve(creator, source)
            refreshMissingChannelTabsDaily(resolved)
            val sourceInitialized = database.isSourceInitialized(sourceKey)
            val notificationCursor = database.getNotificationCursor(sourceKey)
            val lastCheckedMillis = database.getSourceLastCheckedMillis(sourceKey)
            val notificationFetch = fetchNotificationEntries(
                resolved = resolved,
                apiKey = apiKey,
                cursor = notificationCursor,
                enabledHistoryTypes = enabledHistoryTypes,
            )
            val entries = notificationFetch.entries
            val previousRssSnapshot = database.getRssNotificationSnapshot(sourceKey)
            val currentRssIds = notificationFetch.rssEntries
                ?.map(VideoEntry::id)
                .orEmpty()
            val rssNewVideoIds = if (apiKey.isBlank()) {
                newRssNotificationVideoIds(
                    sourceInitialized = sourceInitialized,
                    previousKnownVideoIds = previousRssSnapshot?.knownVideoIds,
                    currentVideoIds = currentRssIds,
                )
            } else {
                emptySet()
            }
            val nextRssKnownIds = notificationFetch.rssEntries?.let {
                mergeRssKnownVideoIds(
                    currentVideoIds = currentRssIds,
                    previousKnownVideoIds = previousRssSnapshot?.knownVideoIds.orEmpty(),
                )
            }
            val historyEntries = entries.filter {
                it.isSafeForEnabledContentTypes(enabledHistoryTypes)
            }

            if (!sourceInitialized) {
                // Pierwsze uruchomienie tworzy punkt odniesienia bez zgłaszania
                // całej istniejącej historii. Baza ufa przy tym wyłącznie
                // wpisom potwierdzonym przez YouTube podczas zapisu kursora.
                database.seedSource(
                    sourceKey = sourceKey,
                    creator = creator,
                    items = historyEntries.map(FetchedNotificationEntry::asHistoryItem),
                )
                nextRssKnownIds?.let {
                    database.saveRssNotificationSnapshot(sourceKey, it)
                }
                return SourceSyncResult(
                    checked = true,
                    priorityObservation = SourcePriorityObservation(
                        candidate = priorityCandidate,
                        successful = true,
                        learnFromResult = false,
                        previousSuccessfulCheckMillis = lastCheckedMillis,
                    ),
                )
            }

            var detectedItems = 0
            val detectedVideoIds = linkedSetOf<String>()
            for (fetched in entries) {
                val entry = fetched.entry.copy(origin = VideoOrigin.YOUTUBE)
                val notificationCandidate = if (apiKey.isBlank()) {
                    entry.id in rssNewVideoIds
                } else {
                    shouldProcessNotificationEntry(
                        entry = entry,
                        cursor = notificationCursor,
                        lastCheckedMillis = lastCheckedMillis,
                    )
                }
                val decision = when {
                    fetched.kind != VideoKind.UNKNOWN &&
                        fetched.evidence != VideoKindEvidence.DEFAULT_VIDEO_FALLBACK ->
                        VideoKindDecision(fetched.kind, fetched.evidence)
                    notificationCandidate &&
                        (resolved.type == SourceType.PLAYLIST ||
                            enabledHistoryTypes.containsAll(HistoryFilter.entries)) -> {
                        val kind = classificationSemaphore.withPermit {
                            classifier.classify(entry.id)
                        }
                        logYouTubeDownload(
                            area = DiagnosticDownloadArea.NOTIFICATIONS,
                            source = DiagnosticYouTubeSource.WEB,
                            videoIds = listOf(entry.id),
                            role = DiagnosticDownloadRole.CLASSIFICATION,
                        )
                        if (kind == VideoKind.UNKNOWN) {
                            VideoKindDecision.Unknown
                        } else {
                            VideoKindDecision(kind, VideoKindEvidence.PLAYER_METADATA)
                        }
                    }
                    else -> VideoKindDecision(fetched.kind, fetched.evidence)
                }
                val classified = fetched.copy(
                    entry = entry,
                    kind = decision.kind,
                    evidence = decision.evidence,
                )
                if (!classified.isSafeForEnabledContentTypes(enabledHistoryTypes)) continue
                val shouldNotify = notificationCandidate &&
                    decision.kind.contentType() in enabledNotificationTypes
                val becamePending = database.upsertVerifiedVideoFromSync(
                    creator = creator,
                    entry = entry,
                    publishedAtEvidence = fetched.publishedAtEvidence,
                    kind = decision.kind,
                    evidence = decision.evidence,
                    shouldNotify = shouldNotify,
                )
                if (becamePending) detectedItems += 1
                if (
                    shouldNotify &&
                    entry.publishedAtMillis in 1..System.currentTimeMillis()
                ) {
                    detectedVideoIds += entry.id
                }
            }
            newestTrustedNotificationEntry(entries.map { it.entry })?.let {
                database.saveNotificationCursor(sourceKey, it)
            }
            nextRssKnownIds?.let {
                database.saveRssNotificationSnapshot(sourceKey, it)
            }
            database.markSourceChecked(sourceKey, null)
            SourceSyncResult(
                checked = true,
                detectedItems = detectedItems,
                priorityObservation = SourcePriorityObservation(
                    candidate = priorityCandidate,
                    successful = true,
                    learnFromResult = true,
                    previousSuccessfulCheckMillis = lastCheckedMillis,
                    detectedVideoIds = detectedVideoIds,
                ),
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
            SourceSyncResult(
                error = message,
                priorityObservation = SourcePriorityObservation(
                    candidate = priorityCandidate,
                    successful = false,
                    learnFromResult = false,
                    previousSuccessfulCheckMillis = 0L,
                ),
            )
        }
    }

    private fun refreshMissingChannelTabsDaily(resolved: ResolvedSource) {
        if (resolved.type != SourceType.CHANNEL) return
        val now = System.currentTimeMillis()
        val boundary = dailyChannelTabRefreshBoundaryMillis(now)
        if (
            !database.claimYouTubeChannelTabsRefreshAfterBoundary(
                sourceKey = resolved.sourceKey,
                channelId = resolved.externalId,
                nowMillis = now,
                attemptBoundaryMillis = boundary,
            )
        ) return

        runCatching { historyClient.refreshChannelTabs(resolved, now) }
            .onFailure { error ->
                AppLog.warning(
                    "lewicowYTSync",
                    "Dobowa kontrola brakujących kart kanału nie powiodła się",
                    error,
                )
            }
    }

    private suspend fun fetchNotificationEntries(
        resolved: ResolvedSource,
        apiKey: String,
        cursor: NotificationCursor?,
        enabledHistoryTypes: Set<HistoryFilter>,
    ): NotificationFetchResult = coroutineScope {
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
        rssResult.getOrNull()?.let { entries ->
            logYouTubeDownload(
                area = DiagnosticDownloadArea.NOTIFICATIONS,
                source = DiagnosticYouTubeSource.RSS,
                videoIds = entries.map(VideoEntry::id),
            )
        }
        dataApiResult?.getOrNull()?.let { entries ->
            logYouTubeDownload(
                area = DiagnosticDownloadArea.NOTIFICATIONS,
                source = DiagnosticYouTubeSource.DATA_API,
                videoIds = entries.map { it.entry.id },
            )
        }

        val rssCoversRange = rssResult.isSuccess &&
            notificationFeedCoversCursor(
                entries = rssResult.getOrDefault(emptyList()),
                cursor = coverageCursor,
            )
        val pagedApiComplete = dataApiResult?.isSuccess == true
        val webResult = if (apiKey.isNotBlank() && !pagedApiComplete && !rssCoversRange) {
            runSuspendCatching {
                fetchWebNotificationEntries(
                    resolved = resolved,
                    cursorPublishedAtMillis = coverageCursor?.publishedAtMillis,
                    enabledHistoryTypes = enabledHistoryTypes,
                )
            }
        } else {
            null
        }
        webResult?.getOrNull()?.let { entries ->
            logYouTubeDownload(
                area = DiagnosticDownloadArea.NOTIFICATIONS,
                source = DiagnosticYouTubeSource.WEB,
                videoIds = entries.map { it.entry.id },
            )
        }

        if (apiKey.isBlank() && rssResult.isFailure) {
            throw IOException(
                "YouTube RSS nie odpowiedział: " +
                    (rssResult.exceptionOrNull()?.message ?: "nieznany błąd"),
            )
        }
        if (
            apiKey.isNotBlank() &&
            !pagedApiComplete &&
            !rssCoversRange &&
            webResult?.isSuccess != true
        ) {
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
            val normalizedEntry = entry.copy(origin = VideoOrigin.YOUTUBE)
            val decision = rssVideoKindDecision(normalizedEntry)
            val incoming = FetchedNotificationEntry(
                entry = normalizedEntry,
                kind = decision.kind,
                evidence = decision.evidence,
                publishedAtEvidence = PublishedAtEvidence.RSS,
            )
            merged[entry.id] = mergeFetchedNotificationEntry(merged[entry.id], incoming)
        }
        webResult?.getOrDefault(emptyList()).orEmpty().forEach { item ->
            merged[item.entry.id] = mergeFetchedNotificationEntry(
                merged[item.entry.id],
                item,
            )
        }
        dataApiResult?.getOrDefault(emptyList()).orEmpty().forEach { item ->
            merged[item.entry.id] = mergeFetchedNotificationEntry(
                merged[item.entry.id],
                item,
            )
        }

        val ambiguousEntries = merged.values.filter {
            it.kind == VideoKind.UNKNOWN ||
                it.evidence == VideoKindEvidence.DEFAULT_VIDEO_FALLBACK ||
                (it.kind == VideoKind.VIDEO &&
                    it.evidence == VideoKindEvidence.API_METADATA) ||
                !it.evidence.isFinal
        }
        val storedUnclassifiedIds = database.unclassifiedVideoIds(
            ambiguousEntries.map { it.entry.id },
        ).toSet()
        val membershipCandidateIds = ambiguousEntries
            .filter {
                cursor == null ||
                    isAfterNotificationCursor(it.entry, cursor) ||
                    it.entry.id in storedUnclassifiedIds
            }
            .mapTo(linkedSetOf()) { it.entry.id }
        if (resolved.type == SourceType.CHANNEL && membershipCandidateIds.isNotEmpty()) {
            val now = System.currentTimeMillis()
            if (
                database.claimYouTubeChannelTabsRefresh(
                    sourceKey = resolved.sourceKey,
                    channelId = resolved.externalId,
                    nowMillis = now,
                    minAgeMillis = NEW_ENTRY_TAB_REFRESH_MILLIS,
                )
            ) {
                runCatching { historyClient.refreshChannelTabs(resolved, now) }
                    .onFailure { error ->
                        AppLog.warning(
                            "lewicowYTSync",
                            "Nie udało się odświeżyć listy kart nowego materiału",
                            error,
                        )
                    }
            }
            runSuspendCatching {
                fetchRecentChannelMembership(
                    resolved,
                    membershipCandidateIds,
                    enabledHistoryTypes,
                )
            }.onSuccess { membership ->
                if (membership.isNotEmpty()) {
                    logYouTubeDownload(
                        area = DiagnosticDownloadArea.NOTIFICATIONS,
                        source = DiagnosticYouTubeSource.WEB,
                        videoIds = membership.keys,
                        role = DiagnosticDownloadRole.CLASSIFICATION,
                    )
                }
                membership.forEach { (videoId, kind) ->
                    val current = merged[videoId] ?: return@forEach
                    merged[videoId] = mergeFetchedNotificationEntry(
                        current = current,
                        incoming = current.copy(
                            kind = kind,
                            evidence = VideoKindEvidence.CHANNEL_TAB,
                        ),
                    )
                }
            }.onFailure { error ->
                AppLog.warning(
                    "lewicowYTSync",
                    "YouTube nie potwierdził kart nowych materiałów; " +
                        "nierozpoznane wpisy zostaną ponowione",
                    error,
                )
            }
        }
        NotificationFetchResult(
            entries = merged.values
                .map(FetchedNotificationEntry::withDefaultVideoFallback)
                .filter { cursor == null || it.entry.publishedAtMillis >= retentionCutoff }
                .sortedByDescending { it.entry.publishedAtMillis },
            rssEntries = rssResult.getOrNull(),
        )
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
                    kind = item.kind,
                    evidence = item.evidence,
                    publishedAtEvidence = item.publishedAtEvidence,
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
        enabledHistoryTypes: Set<HistoryFilter>,
    ): List<FetchedNotificationEntry> = coroutineScope {
        val tabs = if (resolved.type == SourceType.PLAYLIST) {
            listOf(YouTubeHistoryTab.PLAYLIST)
        } else {
            val available = historyClient.cachedAvailableChannelTabs(resolved)
            listOf(
                YouTubeHistoryTab.VIDEOS,
                YouTubeHistoryTab.STREAMS,
                YouTubeHistoryTab.SHORTS,
            ).filter {
                (available == null || it in available) &&
                    it.contentType() in enabledHistoryTypes
            }
        }
        tabs.map { tab ->
            async(Dispatchers.IO) {
                fetchWebNotificationTab(resolved, tab, cursorPublishedAtMillis)
            }
        }.awaitAll().flatten()
    }

    private suspend fun fetchRecentChannelMembership(
        resolved: ResolvedSource,
        candidateIds: Set<String>,
        enabledHistoryTypes: Set<HistoryFilter>,
    ): Map<String, VideoKind> = coroutineScope {
        val available = historyClient.cachedAvailableChannelTabs(resolved)
        val tabs = listOf(
            YouTubeHistoryTab.VIDEOS,
            YouTubeHistoryTab.STREAMS,
            YouTubeHistoryTab.SHORTS,
        ).filter {
            (available == null || it in available) &&
                it.contentType() in enabledHistoryTypes
        }
        val results = tabs.map { tab ->
            async(Dispatchers.IO) {
                fetchNotificationMembershipTab(resolved, tab, candidateIds)
            }
        }.awaitAll()
        buildMap {
            candidateIds.forEach { videoId ->
                val kinds = results.mapNotNull { it[videoId] }.toSet()
                if (kinds.size == 1) put(videoId, kinds.single())
            }
        }
    }

    private suspend fun fetchNotificationMembershipTab(
        resolved: ResolvedSource,
        tab: YouTubeHistoryTab,
        candidateIds: Set<String>,
    ): Map<String, VideoKind> {
        currentCoroutineContext().ensureActive()
        val page = historyClient.firstPage(resolved, tab)
        return page.membershipKinds.filterKeys(candidateIds::contains)
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
                    kind = item.kind,
                    evidence = item.evidence,
                    publishedAtEvidence = item.publishedAtEvidence,
                )
            }
            if (
                tab != YouTubeHistoryTab.PLAYLIST &&
                page.items.isEmpty() &&
                page.membershipKinds.isNotEmpty()
            ) {
                return result.values.toList()
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
    val priorityObservation: SourcePriorityObservation? = null,
)

internal data class FetchedNotificationEntry(
    val entry: VideoEntry,
    val kind: VideoKind = VideoKind.UNKNOWN,
    val evidence: VideoKindEvidence = VideoKindEvidence.NONE,
    val publishedAtEvidence: PublishedAtEvidence = PublishedAtEvidence.UNKNOWN,
) {
    fun asHistoryItem(): pl.lewicowyt.notifier.network.YouTubeHistoryItem =
        pl.lewicowyt.notifier.network.YouTubeHistoryItem(
            entry = entry,
            kind = kind,
            evidence = evidence,
            publishedAtEvidence = publishedAtEvidence,
        )
}

private data class NotificationFetchResult(
    val entries: List<FetchedNotificationEntry>,
    val rssEntries: List<VideoEntry>?,
)

/**
 * Powiadomienia bez Data API wynikają wyłącznie z różnicy stabilnych ID między
 * kolejnymi poprawnie pobranymi kanałami RSS. Zmiana tytułu, kolejności albo
 * wypadnięcie najstarszego wpisu nie może samo wywołać powiadomienia.
 */
internal fun newRssNotificationVideoIds(
    sourceInitialized: Boolean,
    previousKnownVideoIds: Collection<String>?,
    currentVideoIds: Collection<String>,
): Set<String> {
    if (!sourceInitialized || previousKnownVideoIds == null) return emptySet()
    val known = previousKnownVideoIds.toHashSet()
    return currentVideoIds.filterTo(linkedSetOf()) { it !in known }
}

/** Pamięć 60 ID chroni przed ponownym zgłoszeniem wpisu po zmianie feedu. */
internal fun mergeRssKnownVideoIds(
    currentVideoIds: Collection<String>,
    previousKnownVideoIds: Collection<String>,
    limit: Int = 60,
): List<String> = (currentVideoIds + previousKnownVideoIds)
    .distinct()
    .take(limit.coerceAtLeast(0))

internal fun FetchedNotificationEntry.withDefaultVideoFallback(): FetchedNotificationEntry =
    if (kind == VideoKind.UNKNOWN || evidence == VideoKindEvidence.NONE) {
        copy(
            kind = VideoKind.VIDEO,
            evidence = VideoKindEvidence.DEFAULT_VIDEO_FALLBACK,
        )
    } else {
        this
    }

internal fun FetchedNotificationEntry.isSafeForEnabledContentTypes(
    enabledTypes: Set<HistoryFilter>,
): Boolean {
    val allTypesEnabled = enabledTypes.containsAll(HistoryFilter.entries)
    if (
        evidence == VideoKindEvidence.NONE ||
        evidence == VideoKindEvidence.DEFAULT_VIDEO_FALLBACK ||
        (kind == VideoKind.VIDEO && evidence == VideoKindEvidence.API_METADATA)
    ) {
        return allTypesEnabled
    }
    return kind.contentType() in enabledTypes
}

internal fun mergeFetchedNotificationEntry(
    current: FetchedNotificationEntry?,
    incoming: FetchedNotificationEntry,
): FetchedNotificationEntry {
    if (current == null) return incoming
    val selected = chooseVideoKindDecision(
        current = VideoKindDecision(current.kind, current.evidence),
        incoming = VideoKindDecision(incoming.kind, incoming.evidence),
    )
    val selectedPublishedAt = choosePublishedAtDecision(
        current = PublishedAtDecision(
            millis = current.entry.publishedAtMillis,
            evidence = current.publishedAtEvidence,
        ),
        incoming = PublishedAtDecision(
            millis = incoming.entry.publishedAtMillis,
            evidence = incoming.publishedAtEvidence,
        ),
    )
    return incoming.copy(
        entry = incoming.entry.copy(
            publishedAtMillis = selectedPublishedAt.millis,
        ),
        kind = selected.kind,
        evidence = selected.evidence,
        publishedAtEvidence = selectedPublishedAt.evidence,
    )
}

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
