package pl.lewicowyt.notifier.sync

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import pl.lewicowyt.notifier.data.AppSettings
import pl.lewicowyt.notifier.data.CreatorCatalog
import pl.lewicowyt.notifier.data.LocalDatabase
import pl.lewicowyt.notifier.data.PreferencesRepository
import pl.lewicowyt.notifier.data.isHistoryEnabledFor
import pl.lewicowyt.notifier.diagnostics.DiagnosticCategory
import pl.lewicowyt.notifier.diagnostics.DiagnosticLevel
import pl.lewicowyt.notifier.diagnostics.DiagnosticNetworkOperation
import pl.lewicowyt.notifier.diagnostics.DiagnosticNetworkUsage
import pl.lewicowyt.notifier.diagnostics.DiagnosticReasonCode
import pl.lewicowyt.notifier.diagnostics.DiagnosticSyncRun
import pl.lewicowyt.notifier.diagnostics.DiagnosticSyncStage
import pl.lewicowyt.notifier.diagnostics.DiagnosticSyncTrigger
import pl.lewicowyt.notifier.diagnostics.DiagnosticYouTubeSource
import pl.lewicowyt.notifier.diagnostics.diagnosticYouTubeVideoUrl
import pl.lewicowyt.notifier.network.YouTubeDataApiHistoryClient
import pl.lewicowyt.notifier.network.DescriptionFetchResult
import pl.lewicowyt.notifier.network.YouTubePageClassifier
import pl.lewicowyt.notifier.model.SourceType
import pl.lewicowyt.notifier.model.MEMBERS_ONLY_DESCRIPTION_MARKER
import pl.lewicowyt.notifier.model.SCHEDULED_STREAM_DESCRIPTION_MARKER

internal data class DescriptionBackfillStatus(
    val active: Boolean = false,
    val source: String? = null,
    val pendingCount: Int = 0,
)

private data class DescriptionValue(
    val succeeded: Boolean,
    val description: String?,
    val savedResult: String? = null,
)

/**
 * Niekrytyczny, ostatni etap partii Historii. Nigdy nie pobiera nowych filmów:
 * uzupełnia wyłącznie opisy rekordów, które już istnieją w lokalnej bazie.
 */
class DescriptionBackfillLoader(
    private val database: LocalDatabase,
    private val classifier: YouTubePageClassifier,
    private val preferences: PreferencesRepository,
    private val dataApiClient: YouTubeDataApiHistoryClient,
    catalog: CreatorCatalog,
) {
    private val runMutex = Mutex()
    private val activeRuns = AtomicInteger(0)
    private val mutableStatus = MutableStateFlow(DescriptionBackfillStatus())
    internal val status = mutableStatus.asStateFlow()
    private val expectedChannelIdsByCreatorId = catalog.creators.associate { creator ->
        creator.id to buildSet {
            creator.profileChannelId?.let(::add)
            creator.sources
                .asSequence()
                .filter { it.type == SourceType.CHANNEL }
                .mapNotNull { it.externalId }
                .forEach(::add)
        }
    }

    internal suspend fun enrichExistingHistory(
        settings: AppSettings,
        maxItems: Int? = null,
        diagnosticRun: DiagnosticSyncRun? = null,
    ): Int = runMutex.withLock {
        withContext(Dispatchers.IO) {
            if (settings.selectedCreatorIds.isEmpty()) {
                markStatusPending(0)
                return@withContext 0
            }
            val ownsRun = diagnosticRun == null
            val run = diagnosticRun ?: DiagnosticSyncRun.create(
                DiagnosticSyncTrigger.HISTORY_BACKFILL,
            ).also {
                it.start()
                it.stage(DiagnosticSyncStage.DESCRIPTION)
            }
            val batchNetworkBefore = DiagnosticNetworkUsage.snapshot(
                DiagnosticNetworkOperation.DESCRIPTION,
            )
            val now = System.currentTimeMillis()
            val apiKey = if (settings.youtubeApiEnabled) {
                runCatching { preferences.youtubeApiKey() }.getOrDefault("")
            } else {
                ""
            }
            val batchLimit = descriptionBatchLimit(
                requested = maxItems,
                apiAvailable = apiKey.isNotBlank(),
            )
            run.event(
                name = "DESCRIPTION_STAGE_START",
                category = DiagnosticCategory.HISTORY,
                fields = mapOf(
                    "selectedCreators" to settings.selectedCreatorIds.size,
                    "historyWindowDays" to settings.historyWindowDays,
                    "batchLimit" to batchLimit,
                    "apiAvailable" to apiKey.isNotBlank(),
                    "retryDelayMinutes" to DESCRIPTION_RETRY_DELAY_MINUTES,
                ),
            )
        // Wyszukiwarka obejmuje całą zachowaną lokalnie historię, więc opisy
        // uzupełniamy stopniowo także dla rekordów poza bieżącym filtrem czasu UI.
        // Skan jest szerszy od partii, bo wyłączone per-kanał typy są
        // odfiltrowywane dopiero po odczycie reguł użytkownika.
            val cutoff = now - TimeUnit.DAYS.toMillis(DESCRIPTION_RETENTION_DAYS)
            val outstandingBefore = outstandingDescriptions(settings, cutoff).size
            val pending = database.pendingDescriptions(
                selectedCreatorIds = settings.selectedCreatorIds,
                cutoffMillis = cutoff,
                limit = MAX_PENDING_SCAN,
                retryBeforeMillis = descriptionRetryBeforeMillis(now),
            ).filter { item ->
                settings.isHistoryEnabledFor(item.creatorId, item.kind)
            }.take(batchLimit)

            val statusStarted = pending.isNotEmpty()
            val batchStartedAtNanos = System.nanoTime()
            if (statusStarted) markStatusStarted(outstandingBefore)
            else markStatusPending(outstandingBefore)

            var saved = 0
            var empty = 0
            var failed = 0
            var apiDurationMillis = 0L
            var webDurationMillis = 0L
            var originalBytes = 0L
            var storedBytes = 0L
            var completion = "OK"
            var preferredSource = DiagnosticYouTubeSource.WEB.name
            try {
            val apiAttempted = apiKey.isNotBlank() && pending.isNotEmpty()
            if (apiAttempted) {
                preferredSource = DiagnosticYouTubeSource.DATA_API.name
                markStatusSource(DiagnosticYouTubeSource.DATA_API)
            } else if (pending.isNotEmpty()) {
                markStatusSource(DiagnosticYouTubeSource.WEB)
            }
            val apiDescriptions = if (apiAttempted) {
                val apiStartedAtNanos = System.nanoTime()
                val apiNetworkBefore = DiagnosticNetworkUsage.snapshot(
                    DiagnosticNetworkOperation.DESCRIPTION,
                )
                run.sourceRequest(DiagnosticYouTubeSource.DATA_API)
                try {
                    DiagnosticNetworkUsage.withOperation(
                        DiagnosticNetworkOperation.DESCRIPTION,
                    ) {
                        dataApiClient.fetchVideoDescriptions(
                            videoIds = pending.map { it.videoId },
                            apiKey = apiKey,
                        )
                    }.also { descriptions ->
                        apiDurationMillis = elapsedMillis(apiStartedAtNanos)
                        val network = DiagnosticNetworkUsage.snapshot(
                            DiagnosticNetworkOperation.DESCRIPTION,
                        ).deltaSince(apiNetworkBefore)
                        run.event(
                            name = "DESCRIPTION_API_BATCH",
                            category = DiagnosticCategory.HISTORY,
                            fields = mapOf(
                                "source" to DiagnosticYouTubeSource.DATA_API.name,
                                "result" to "SUCCESS",
                                "requested" to pending.size,
                                "returned" to descriptions.size,
                                "durationMillis" to apiDurationMillis,
                                "uploadedHttpBodyBytes" to network.uploadedBytes,
                                "downloadedHttpBodyBytes" to network.downloadedBytes,
                                "totalHttpBodyBytes" to network.totalBytes,
                            ),
                        )
                    }
                } catch (cancelled: CancellationException) {
                    apiDurationMillis = elapsedMillis(apiStartedAtNanos)
                    completion = "CANCELLED"
                    throw cancelled
                } catch (error: Exception) {
                    apiDurationMillis = elapsedMillis(apiStartedAtNanos)
                    markStatusSource(DiagnosticYouTubeSource.WEB)
                    run.sourceError(DiagnosticYouTubeSource.DATA_API)
                    val network = DiagnosticNetworkUsage.snapshot(
                        DiagnosticNetworkOperation.DESCRIPTION,
                    ).deltaSince(apiNetworkBefore)
                    run.event(
                        name = "DESCRIPTION_API_BATCH",
                        category = DiagnosticCategory.HISTORY,
                        level = DiagnosticLevel.WARNING,
                        reason = DiagnosticReasonCode.DATA_API_SOURCE_FAILED,
                        fields = mapOf(
                            "source" to DiagnosticYouTubeSource.DATA_API.name,
                            "result" to "FALLBACK_WEB",
                            "requested" to pending.size,
                            "errorType" to error.javaClass.simpleName,
                            "durationMillis" to apiDurationMillis,
                            "uploadedHttpBodyBytes" to network.uploadedBytes,
                            "downloadedHttpBodyBytes" to network.downloadedBytes,
                            "totalHttpBodyBytes" to network.totalBytes,
                        ),
                    )
                    null
                }
            } else {
                null
            }

            pending.forEach { item ->
                coroutineContext.ensureActive()
                val itemStartedAtNanos = System.nanoTime()
                val fetchStartedAtNanos = System.nanoTime()
                val itemNetworkBefore = DiagnosticNetworkUsage.snapshot(
                    DiagnosticNetworkOperation.DESCRIPTION,
                )
                val apiReturnedItem = apiDescriptions?.containsKey(item.videoId) == true
                val source = if (apiReturnedItem) {
                    DiagnosticYouTubeSource.DATA_API
                } else {
                    DiagnosticYouTubeSource.WEB
                }
                markStatusSource(source)
                try {
                    val fetchResult = if (apiReturnedItem) {
                        DescriptionValue(
                            succeeded = true,
                            description = apiDescriptions[item.videoId],
                        )
                    } else {
                        run.sourceRequest(DiagnosticYouTubeSource.WEB)
                        when (val result = DiagnosticNetworkUsage.withOperation(
                            DiagnosticNetworkOperation.DESCRIPTION,
                        ) {
                            classifier.fetchDescriptionResult(
                                videoId = item.videoId,
                                expectedTitle = item.title,
                                expectedChannelIds = expectedChannelIdsByCreatorId[item.creatorId]
                                    .orEmpty(),
                            )
                        }) {
                            is DescriptionFetchResult.Available -> DescriptionValue(
                                succeeded = true,
                                description = result.description,
                            )
                            DescriptionFetchResult.MembersOnly -> DescriptionValue(
                                succeeded = true,
                                description = MEMBERS_ONLY_DESCRIPTION_MARKER,
                                savedResult = "SAVED_MEMBERS_ONLY",
                            )
                            DescriptionFetchResult.ScheduledStream -> DescriptionValue(
                                succeeded = true,
                                description = SCHEDULED_STREAM_DESCRIPTION_MARKER,
                                savedResult = "SAVED_SCHEDULED_STREAM",
                            )
                            DescriptionFetchResult.Invalid -> DescriptionValue(
                                succeeded = false,
                                description = null,
                            )
                        }
                    }
                    val rawDescription = fetchResult.description
                    val description = descriptionForStorage(
                        fetchSucceeded = fetchResult.succeeded,
                        description = rawDescription,
                    )
                    val fetchDurationMillis = if (source == DiagnosticYouTubeSource.WEB) {
                        elapsedMillis(fetchStartedAtNanos).also { webDurationMillis += it }
                    } else {
                        apiDurationMillis
                    }
                    val network = DiagnosticNetworkUsage.snapshot(
                        DiagnosticNetworkOperation.DESCRIPTION,
                    ).deltaSince(itemNetworkBefore)
                    if (description == null) {
                        failed += 1
                        database.recordDescriptionFailure(item.videoId)
                        run.sourceError(source)
                        run.event(
                            name = "DESCRIPTION_FETCH",
                            category = DiagnosticCategory.HISTORY,
                            level = DiagnosticLevel.WARNING,
                            reason = DiagnosticReasonCode.DESCRIPTION_FETCH_FAILED,
                            fields = descriptionFields(item.creatorId, item.videoId, source, network) +
                                mapOf(
                                    "result" to "INVALID_RESPONSE",
                                    "fetchDurationMillis" to fetchDurationMillis,
                                    "totalDurationMillis" to elapsedMillis(itemStartedAtNanos),
                                ),
                        )
                    } else {
                        val storedNoDescription =
                            fetchResult.savedResult == null && rawDescription.isNullOrBlank()
                        if (storedNoDescription) empty += 1
                        val storageStartedAtNanos = System.nanoTime()
                        val storage = database.saveDescriptionWithStats(item.videoId, description)
                        val storageDurationMillis = elapsedMillis(storageStartedAtNanos)
                        if (storage?.saved == true) {
                            saved += 1
                            originalBytes += storage.originalBytes
                            storedBytes += storage.storedBytes
                            run.event(
                                name = "DESCRIPTION_FETCH",
                                category = DiagnosticCategory.HISTORY,
                                fields = descriptionFields(
                                    item.creatorId,
                                    item.videoId,
                                    source,
                                    network,
                                ) + mapOf(
                                    "result" to (fetchResult.savedResult ?: if (storedNoDescription) {
                                        "SAVED_NO_DESCRIPTION"
                                    } else {
                                        "SAVED"
                                    }),
                                    "noDescription" to storedNoDescription,
                                    "fetchDurationMillis" to fetchDurationMillis,
                                    "storageDurationMillis" to storageDurationMillis,
                                    "totalDurationMillis" to elapsedMillis(itemStartedAtNanos),
                                    "originalBytes" to storage.originalBytes,
                                    "storedBytes" to storage.storedBytes,
                                    "codec" to storage.codec.name,
                                    "compressionMethod" to storage.compressionMethod,
                                    "dictionaryMode" to if (storage.dictionaryId == null) {
                                        "NONE"
                                    } else {
                                        "CUSTOM"
                                    },
                                    "dictionaryId" to (storage.dictionaryId ?: "NONE"),
                                    "dictionaryVersion" to (storage.dictionaryVersion ?: 0),
                                ),
                            )
                        } else {
                            failed += 1
                            run.sourceError(source)
                            run.event(
                                name = "DESCRIPTION_FETCH",
                                category = DiagnosticCategory.HISTORY,
                                level = DiagnosticLevel.WARNING,
                                reason = DiagnosticReasonCode.DESCRIPTION_SAVE_FAILED,
                                fields = descriptionFields(
                                    item.creatorId,
                                    item.videoId,
                                    source,
                                    network,
                                ) + mapOf(
                                    "result" to "SAVE_FAILED",
                                    "fetchDurationMillis" to fetchDurationMillis,
                                    "storageDurationMillis" to storageDurationMillis,
                                    "totalDurationMillis" to elapsedMillis(itemStartedAtNanos),
                                ),
                            )
                        }
                    }
                } catch (cancelled: CancellationException) {
                    completion = "CANCELLED"
                    throw cancelled
                } catch (error: Exception) {
                    failed += 1
                    runCatching { database.recordDescriptionFailure(item.videoId) }
                    run.sourceError(source)
                    val network = DiagnosticNetworkUsage.snapshot(
                        DiagnosticNetworkOperation.DESCRIPTION,
                    ).deltaSince(itemNetworkBefore)
                    run.event(
                        name = "DESCRIPTION_FETCH",
                        category = DiagnosticCategory.HISTORY,
                        level = DiagnosticLevel.WARNING,
                        reason = DiagnosticReasonCode.DESCRIPTION_FETCH_FAILED,
                        fields = descriptionFields(item.creatorId, item.videoId, source, network) + mapOf(
                            "result" to "ERROR",
                            "errorType" to error.javaClass.simpleName,
                            "fallbackFromApi" to apiAttempted,
                            "totalDurationMillis" to elapsedMillis(itemStartedAtNanos),
                        ),
                    )
                }
            }
                saved
            } finally {
            val outstandingAfter = runCatching {
                outstandingDescriptions(settings, cutoff).size
            }.getOrDefault(0)
            val network = DiagnosticNetworkUsage.snapshot(
                DiagnosticNetworkOperation.DESCRIPTION,
            ).deltaSince(batchNetworkBefore)
            run.event(
                name = "DESCRIPTION_SUMMARY",
                category = DiagnosticCategory.HISTORY,
                fields = mapOf(
                    "result" to completion,
                    "attempted" to pending.size,
                    "batchLimit" to batchLimit,
                    "scanLimit" to MAX_PENDING_SCAN,
                    "saved" to saved,
                    "empty" to empty,
                    "failed" to failed,
                    "pending" to outstandingAfter,
                    "preferredSource" to preferredSource,
                    "durationMillis" to elapsedMillis(batchStartedAtNanos),
                    "apiDurationMillis" to apiDurationMillis,
                    "webDurationMillis" to webDurationMillis,
                    "originalBytes" to originalBytes,
                    "storedBytes" to storedBytes,
                    "savedBytes" to (originalBytes - storedBytes).coerceAtLeast(0L),
                    "uploadedHttpBodyBytes" to network.uploadedBytes,
                    "downloadedHttpBodyBytes" to network.downloadedBytes,
                    "totalHttpBodyBytes" to network.totalBytes,
                    "scope" to "HTTP_BODY_ONLY",
                ),
            )
            if (ownsRun) {
                run.finish(
                    mapOf(
                        "result" to completion,
                        "descriptionsSaved" to saved,
                        "descriptionErrors" to failed,
                    ),
                )
            }
                if (statusStarted) markStatusFinished(outstandingAfter)
                else markStatusPending(outstandingAfter)
            }
        }
    }

    private fun outstandingDescriptions(
        settings: AppSettings,
        cutoffMillis: Long,
    ) = database.pendingDescriptions(
        selectedCreatorIds = settings.selectedCreatorIds,
        cutoffMillis = cutoffMillis,
        limit = MAX_PENDING_SCAN,
        // Long.MAX_VALUE obejmuje także rekordy czekające obecnie na retry,
        // dzięki czemu UI nie udaje, że wszystkie opisy są już gotowe.
        retryBeforeMillis = Long.MAX_VALUE,
    ).filter { item ->
        settings.isHistoryEnabledFor(item.creatorId, item.kind)
    }

    private fun markStatusStarted(pendingCount: Int) {
        activeRuns.incrementAndGet()
        mutableStatus.value = DescriptionBackfillStatus(
            active = true,
            pendingCount = pendingCount,
        )
    }

    private fun markStatusPending(pendingCount: Int) {
        if (activeRuns.get() <= 0) {
            mutableStatus.value = DescriptionBackfillStatus(pendingCount = pendingCount)
        }
    }

    private fun markStatusSource(source: DiagnosticYouTubeSource) {
        if (activeRuns.get() > 0) {
            mutableStatus.value = mutableStatus.value.copy(
                active = true,
                source = source.name,
            )
        }
    }

    private fun markStatusFinished(pendingCount: Int) {
        if (activeRuns.decrementAndGet() <= 0) {
            activeRuns.set(0)
            mutableStatus.value = DescriptionBackfillStatus(pendingCount = pendingCount)
        } else {
            mutableStatus.value = mutableStatus.value.copy(pendingCount = pendingCount)
        }
    }

    private fun elapsedMillis(startedAtNanos: Long): Long =
        TimeUnit.NANOSECONDS.toMillis((System.nanoTime() - startedAtNanos).coerceAtLeast(0L))

    private fun descriptionFields(
        creatorId: String,
        videoId: String,
        source: DiagnosticYouTubeSource,
        network: pl.lewicowyt.notifier.diagnostics.DiagnosticNetworkSnapshot,
    ): Map<String, Any?> = mapOf(
        "creatorId" to creatorId,
        "video" to diagnosticYouTubeVideoUrl(videoId),
        "source" to source.name,
        "fallbackWatch" to false,
        "uploadedHttpBodyBytes" to network.uploadedBytes,
        "downloadedHttpBodyBytes" to network.downloadedBytes,
        "totalHttpBodyBytes" to network.totalBytes,
    )

    companion object {
        /** Większa partia jest używana tylko podczas jawnego doczytywania Historii. */
        const val FOREGROUND_BATCH_SIZE = 24

        private const val MAX_PENDING_SCAN = 200
        const val DESCRIPTION_RETENTION_DAYS = 60L
    }
}

internal const val DESCRIPTION_RETRY_DELAY_MINUTES = 15L

internal fun descriptionRetryBeforeMillis(nowMillis: Long): Long =
    nowMillis - TimeUnit.MINUTES.toMillis(DESCRIPTION_RETRY_DELAY_MINUTES)

internal fun descriptionBatchLimit(
    requested: Int?,
    apiAvailable: Boolean,
): Int = (requested ?: if (apiAvailable) 50 else 8).coerceIn(1, 50)

internal const val NO_DESCRIPTION_SEARCH_TEXT = "Bez opisu"

/** null oznacza błąd pobrania/walidacji; pusty poprawny opis staje się stanem końcowym. */
internal fun descriptionForStorage(
    fetchSucceeded: Boolean,
    description: String?,
): String? = when {
    !fetchSucceeded -> null
    description.isNullOrBlank() -> NO_DESCRIPTION_SEARCH_TEXT
    else -> description
}
