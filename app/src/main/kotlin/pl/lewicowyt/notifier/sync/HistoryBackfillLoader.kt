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
import pl.lewicowyt.notifier.AppLog
import pl.lewicowyt.notifier.data.AppSettings
import pl.lewicowyt.notifier.data.CreatorCatalog
import pl.lewicowyt.notifier.data.LocalDatabase
import pl.lewicowyt.notifier.data.PreferencesRepository
import pl.lewicowyt.notifier.data.contentSettingsSignature
import pl.lewicowyt.notifier.data.contentType
import pl.lewicowyt.notifier.data.historyTypesFor
import pl.lewicowyt.notifier.diagnostics.DiagnosticDownloadArea
import pl.lewicowyt.notifier.diagnostics.DiagnosticDownloadRole
import pl.lewicowyt.notifier.diagnostics.DiagnosticLevel
import pl.lewicowyt.notifier.diagnostics.DiagnosticReasonCode
import pl.lewicowyt.notifier.diagnostics.DiagnosticYouTubeSource
import pl.lewicowyt.notifier.diagnostics.DiagnosticYouTubeOperation
import pl.lewicowyt.notifier.diagnostics.DiagnosticSyncRun
import pl.lewicowyt.notifier.diagnostics.DiagnosticSyncStage
import pl.lewicowyt.notifier.diagnostics.DiagnosticSyncTrigger
import pl.lewicowyt.notifier.diagnostics.logYouTubeDownload
import pl.lewicowyt.notifier.diagnostics.youtubeIssue
import pl.lewicowyt.notifier.model.Creator
import pl.lewicowyt.notifier.model.CreatorSource
import pl.lewicowyt.notifier.model.HistoryFilter
import pl.lewicowyt.notifier.model.PublishedAtEvidence
import pl.lewicowyt.notifier.model.SourceType
import pl.lewicowyt.notifier.model.VideoEntry
import pl.lewicowyt.notifier.model.VideoKind
import pl.lewicowyt.notifier.model.VideoKindDecision
import pl.lewicowyt.notifier.model.VideoKindEvidence
import pl.lewicowyt.notifier.model.VideoOrigin
import pl.lewicowyt.notifier.network.ResolvedSource
import pl.lewicowyt.notifier.network.YouTubeDataApiHistoryClient
import pl.lewicowyt.notifier.network.YouTubeFeedClient
import pl.lewicowyt.notifier.network.YouTubeHistoryClient
import pl.lewicowyt.notifier.network.YouTubeHistoryCursor
import pl.lewicowyt.notifier.network.YouTubeHistoryItem
import pl.lewicowyt.notifier.network.YouTubeHistoryTab
import pl.lewicowyt.notifier.network.YouTubePageClassifier
import pl.lewicowyt.notifier.network.YouTubeSourceResolver
import pl.lewicowyt.notifier.network.rssVideoKindDecision

data class BackfillResult(
    val insertedCount: Int,
    val exhausted: Boolean,
    val error: String? = null,
)

/**
 * Kanały są pobierane współbieżnie w małych grupach. Ograniczenie chroni
 * pamięć telefonu, ponieważ pojedyncza strona kanału YouTube może zawierać
 * kilka megabajtów JSON. Kanał kończy pracę natychmiast po dojściu do granicy
 * czasu wybranej w ustawieniach.
 */
class HistoryBackfillLoader(
    private val catalog: CreatorCatalog,
    private val preferences: PreferencesRepository,
    private val database: LocalDatabase,
    private val resolver: YouTubeSourceResolver,
    private val feedClient: YouTubeFeedClient,
    private val client: YouTubeHistoryClient,
    private val dataApiClient: YouTubeDataApiHistoryClient,
    private val classifier: YouTubePageClassifier,
    private val sourcePriorityScheduler: SourcePriorityScheduler,
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
        val unresolvedVideoIds: Set<String> = emptySet(),
    )

    private data class TargetLoadResult(
        val insertedCount: Int,
        val complete: Boolean,
        val unresolvedVideoIds: Set<String> = emptySet(),
    )

    private data class WebTargetPagingState(
        var cursor: YouTubeHistoryCursor? = null,
        var started: Boolean = false,
        var loadedPageCount: Int = 0,
        var networkComplete: Boolean = false,
        val seenCursorTokens: MutableSet<String> = mutableSetOf(),
        val deferredItems: MutableMap<String, YouTubeHistoryItem> = linkedMapOf(),
    )

    private data class WebStageLoadResult(
        val insertedCount: Int,
        val stageComplete: Boolean,
        val targetComplete: Boolean,
    )

    private val loadMutex = Mutex()
    private val classificationSemaphore = Semaphore(MAX_PARALLEL_CLASSIFICATIONS)
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
        val diagnosticRun = DiagnosticSyncRun.create(DiagnosticSyncTrigger.HISTORY_BACKFILL)
        diagnosticRun.start()
        diagnosticRun.stage(DiagnosticSyncStage.HISTORY)
        val apiKey = if (settings.youtubeApiEnabled) preferences.youtubeApiKey() else ""
        val signature = buildSignature(settings, apiKey)
        if (completedSignature == signature) {
            diagnosticRun.finish(mapOf("result" to "ALREADY_COMPLETE"))
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
            diagnosticRun.finish(mapOf("result" to "NO_TARGETS"))
            return BackfillResult(insertedCount = 0, exhausted = true)
        }

        val cutoff = System.currentTimeMillis() -
            settings.historyWindowDays.toLong() * DAY_MILLIS
        val reporter = ProgressReporter(onProgress)
        val channelGroups = targetsByChannel.values.toList()
        val prioritizedChannelGroups = runCatching {
            sourcePriorityScheduler.prioritizeSourceGroups(
                groups = channelGroups,
                intervalMinutes = settings.intervalMinutes,
                creator = { it.first().creator },
                source = { it.first().source },
            )
        }.onFailure { error ->
            AppLog.warning(
                "SourcePriority",
                "Nie udało się ustalić kolejności historii; używam katalogu",
                error,
            )
        }.getOrDefault(channelGroups)
        val results = prioritizedChannelGroups.mapConcurrently(
            maxConcurrency = HISTORY_CHANNEL_CONCURRENCY,
        ) { channelTargets ->
            loadChannel(
                channelTargets,
                cutoff,
                settings,
                apiKey,
                reporter,
                diagnosticRun,
            )
        }
        reporter.report(force = true)

        val errors = results.flatMap(ChannelResult::errors)
        if (errors.isEmpty()) completedSignature = signature
        val result = BackfillResult(
            insertedCount = results.sumOf(ChannelResult::insertedCount),
            exhausted = errors.isEmpty(),
            error = errors.takeIf { it.isNotEmpty() }?.toSummary(),
        )
        diagnosticRun.finish(
            mapOf(
                "result" to if (errors.isEmpty()) "OK" else "PARTIAL",
                "inserted" to result.insertedCount,
                "errors" to errors.size,
            ),
        )
        result
    }

    private suspend fun loadChannel(
        targets: List<Target>,
        cutoff: Long,
        settings: AppSettings,
        apiKey: String,
        reporter: ProgressReporter,
        diagnosticRun: DiagnosticSyncRun,
    ): ChannelResult {
        val firstTarget = targets.first()
        val enabledHistoryTypes = settings.historyTypesFor(firstTarget.creator.id)
        if (enabledHistoryTypes.isEmpty()) return ChannelResult(0, emptyList())
        val sourceKey = resolver.sourceKey(firstTarget.creator, firstTarget.source)
        val resolved = try {
            if (firstTarget.tab == null) {
                resolveForDataApi(firstTarget, sourceKey, apiKey, diagnosticRun)
            } else {
                resolver.resolve(firstTarget.creator, firstTarget.source)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            diagnosticRun.youtubeIssue(
                name = "SOURCE_ERROR",
                level = DiagnosticLevel.ERROR,
                creatorId = firstTarget.creator.id,
                source = firstTarget.source,
                operation = DiagnosticYouTubeOperation.SOURCE_RESOLVE,
                fallbackReason = DiagnosticReasonCode.SOURCE_RESOLUTION_FAILED,
                error = error,
                extra = mapOf("area" to "HISTORY"),
                text = "Nie udało się rozpoznać źródła historii",
            )
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
        val rssCutoff = if (
            apiKey.isBlank() && settings.historyWindowDays > HISTORY_STAGE_DAYS
        ) {
            val rangeNowMillis = cutoff + settings.historyWindowDays.toLong() * DAY_MILLIS
            maxOf(cutoff, rangeNowMillis - HISTORY_STAGE_DAYS.toLong() * DAY_MILLIS)
        } else {
            cutoff
        }
        val rssResult = runSuspendCatching {
            loadRssSource(
                creator = firstTarget.creator,
                resolved = resolved,
                cutoff = rssCutoff,
                reporter = reporter,
                classifyEntries =
                    apiKey.isBlank() && resolved.type == SourceType.PLAYLIST,
                enabledHistoryTypes = enabledHistoryTypes,
                diagnosticRun = diagnosticRun,
            )
        }
        if (rssResult.isFailure) {
            diagnosticRun.sourceError(DiagnosticYouTubeSource.RSS)
            val error = rssResult.exceptionOrNull()!!
            diagnosticRun.youtubeIssue(
                name = "SOURCE_FALLBACK",
                level = DiagnosticLevel.WARNING,
                creatorId = firstTarget.creator.id,
                source = firstTarget.source,
                resolved = resolved,
                operation = DiagnosticYouTubeOperation.RSS_FEED,
                fallbackReason = DiagnosticReasonCode.RSS_SOURCE_FAILED,
                error = error,
                extra = mapOf("area" to "HISTORY"),
                text = "RSS historii nie odpowiedział; używane jest API lub Web",
            )
        }

        if (apiKey.isNotBlank()) {
            val apiResult = loadDataApiTargets(
                targets,
                resolved,
                cutoff,
                apiKey,
                reporter,
                enabledHistoryTypes,
                diagnosticRun,
            )
            // Data API zapewnia szybkie ID i dokładne daty, natomiast karta
            // wybrana przez użytkownika jest wiarygodnym dowodem rodzaju.
            // To jest zwykłe pobieranie historii do granicy czasu, a nie
            // osobny skan każdej karty w poszukiwaniu wszystkich kandydatów.
            val webTargets = if (apiResult.errors.isEmpty()) {
                buildApiKindVerificationTargets(
                    creator = firstTarget.creator,
                    source = firstTarget.source,
                    enabledHistoryTypes = enabledHistoryTypes,
                )
            } else {
                // Gdy API zawiedzie, publiczne karty nadal muszą dostarczyć
                // cały zakres aktualnie wybrany przez użytkownika.
                buildWebTargets(
                    creator = firstTarget.creator,
                    source = firstTarget.source,
                    settings = settings,
                )
            }
            val webResult = loadWebTargetsProgressively(
                targets = webTargets,
                resolved = resolved,
                overallCutoff = cutoff,
                historyWindowDays = settings.historyWindowDays,
                reporter = reporter,
                enabledHistoryTypes = enabledHistoryTypes,
                diagnosticRun = diagnosticRun,
            )
            if (webResult.errors.isEmpty()) {
                // Pełny zakres został dostarczony przez YouTube Web.
                // Wadliwy lub wyczerpany klucz nie może wymuszać kolejnych prób.
                targets.forEach { completedTargets += it.key }
                return mergeRssAndYouTubeResults(
                    rssResult = rssResult,
                    youtubeResult = ChannelResult(
                        insertedCount =
                            apiResult.insertedCount + webResult.insertedCount,
                        errors = emptyList(),
                    ),
                    creatorName = firstTarget.creator.name,
                )
            }
            return mergeRssAndYouTubeResults(
                rssResult = rssResult,
                youtubeResult = ChannelResult(
                    insertedCount = apiResult.insertedCount + webResult.insertedCount,
                    errors = (apiResult.errors + webResult.errors).distinct(),
                ),
                creatorName = firstTarget.creator.name,
            )
        }

        return mergeRssAndYouTubeResults(
            rssResult = rssResult,
            youtubeResult = loadWebTargetsProgressively(
                targets = targets,
                resolved = resolved,
                overallCutoff = cutoff,
                historyWindowDays = settings.historyWindowDays,
                reporter = reporter,
                enabledHistoryTypes = enabledHistoryTypes,
                diagnosticRun = diagnosticRun,
            ),
            creatorName = firstTarget.creator.name,
        )
    }

    private suspend fun loadRssSource(
        creator: Creator,
        resolved: ResolvedSource,
        cutoff: Long,
        reporter: ProgressReporter,
        classifyEntries: Boolean,
        enabledHistoryTypes: Set<HistoryFilter>,
        diagnosticRun: DiagnosticSyncRun,
    ): Int {
        diagnosticRun.sourceRequest(DiagnosticYouTubeSource.RSS)
        val entries = diagnosticRun.measure(DiagnosticSyncStage.RSS) {
            feedClient.fetch(resolved)
        }
        logYouTubeDownload(
            area = DiagnosticDownloadArea.HISTORY,
            source = DiagnosticYouTubeSource.RSS,
            videoIds = entries.map(VideoEntry::id),
            run = diagnosticRun,
        )
        val items = rssHistoryItems(
            entries = entries,
            cutoff = cutoff,
        ).filter { it.isSafeForEnabledContentTypes(enabledHistoryTypes) }
        val insertedCount = database.insertHistoricalVideos(creator, items)
        reporter.report()
        if (classifyEntries) classifyRssItems(items, reporter, diagnosticRun)
        return insertedCount
    }

    /**
     * RSS nie zawiera rodzaju materiału. Karty kanału są lżejszym źródłem tej
     * informacji, ale w EOG YouTube może zamiast nich zwrócić stronę zgody.
     * Najnowsze wpisy klasyfikujemy więc również po publicznej stronie filmu.
     */
    private suspend fun classifyRssItems(
        items: List<YouTubeHistoryItem>,
        reporter: ProgressReporter,
        diagnosticRun: DiagnosticSyncRun,
    ) {
        // Wpis RSS ma już słaby fallback VIDEO, ale playlista nie ujawnia
        // rodzaju materiału kartą kanału. Sprawdzamy więc bieżący pakiet RSS
        // niezależnie od wersji zapisanego fallbacku; silniejszy dowód PLAYER
        // może go bezpiecznie zastąpić.
        val candidateIds = items.map { it.entry.id }.distinct()
        if (candidateIds.isEmpty()) return

        val results = coroutineScope {
            candidateIds.map { videoId ->
                async(Dispatchers.IO) {
                    videoId to classificationSemaphore.withPermit {
                        diagnosticRun.sourceRequest(DiagnosticYouTubeSource.WEB)
                        classifier.classify(videoId)
                    }
                }
            }.awaitAll()
        }
        logYouTubeDownload(
            area = DiagnosticDownloadArea.HISTORY,
            source = DiagnosticYouTubeSource.WEB,
            videoIds = results.map { (videoId) -> videoId },
            role = DiagnosticDownloadRole.CLASSIFICATION,
            run = diagnosticRun,
        )
        database.markVideoClassifications(
            results
                .filter { (_, kind) -> kind != VideoKind.UNKNOWN }
                .associate { (videoId, kind) ->
                    videoId to VideoKindDecision(
                        kind = kind,
                        evidence = VideoKindEvidence.PLAYER_METADATA,
                    )
                },
        )
        database.recordFailedVideoClassifications(
            results
                .filter { (_, kind) -> kind == VideoKind.UNKNOWN }
                .map { (videoId) -> videoId },
        )
        reporter.report()
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
            unresolvedVideoIds = youtubeResult.unresolvedVideoIds,
        )
    }

    private suspend fun loadDataApiTargets(
        targets: List<Target>,
        resolved: ResolvedSource,
        cutoff: Long,
        apiKey: String,
        reporter: ProgressReporter,
        enabledHistoryTypes: Set<HistoryFilter>,
        diagnosticRun: DiagnosticSyncRun,
    ): ChannelResult {
        var insertedCount = 0
        val errors = mutableListOf<String>()
        val unresolvedVideoIds = mutableSetOf<String>()
        for (target in targets) {
            currentCoroutineContext().ensureActive()
            try {
                check(target.tab == null) { "Cel Data API nie może wskazywać karty Web" }
                val result = loadDataApiTarget(
                    target,
                    resolved,
                    cutoff,
                    apiKey,
                    reporter,
                    enabledHistoryTypes,
                    diagnosticRun,
                )
                insertedCount += result.insertedCount
                unresolvedVideoIds += result.unresolvedVideoIds
                if (result.complete) {
                    completedTargets += target.key
                } else {
                    diagnosticRun.youtubeIssue(
                        name = "HISTORY_PARTIAL",
                        level = DiagnosticLevel.WARNING,
                        creatorId = target.creator.id,
                        source = target.source,
                        resolved = resolved,
                        operation = DiagnosticYouTubeOperation.API_PLAYLIST_ITEMS,
                        fallbackReason = DiagnosticReasonCode.HISTORY_PAGE_LIMIT_REACHED,
                        extra = mapOf("area" to "HISTORY"),
                        text = "Data API osiągnęło limit stron przed objęciem zakresu",
                    )
                    errors +=
                        "${target.creator.name}: osiągnięto limit stron przed objęciem całego zakresu"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                diagnosticRun.sourceError(DiagnosticYouTubeSource.DATA_API)
                diagnosticRun.youtubeIssue(
                    name = "SOURCE_ERROR",
                    level = DiagnosticLevel.ERROR,
                    creatorId = target.creator.id,
                    source = target.source,
                    resolved = resolved,
                    operation = DiagnosticYouTubeOperation.API_PLAYLIST_ITEMS,
                    fallbackReason = DiagnosticReasonCode.DATA_API_SOURCE_FAILED,
                    error = error,
                    extra = mapOf("area" to "HISTORY"),
                    text = "Data API nie pobrało strony playlistItems historii",
                )
                errors += (
                    "${target.creator.name}: " +
                        (error.message ?: error.javaClass.simpleName)
                            .take(MAX_ERROR_DETAIL_CHARS)
                    ).take(MAX_ERROR_LINE_CHARS)
            }
        }
        return ChannelResult(insertedCount, errors, unresolvedVideoIds)
    }

    private fun resolveForDataApi(
        target: Target,
        sourceKey: String,
        apiKey: String,
        diagnosticRun: DiagnosticSyncRun,
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
            }.getOrElse { error ->
                diagnosticRun.sourceError(DiagnosticYouTubeSource.DATA_API)
                diagnosticRun.youtubeIssue(
                    name = "SOURCE_FALLBACK",
                    level = DiagnosticLevel.WARNING,
                    creatorId = target.creator.id,
                    source = target.source,
                    operation = DiagnosticYouTubeOperation.SOURCE_RESOLVE,
                    fallbackReason = DiagnosticReasonCode.SOURCE_RESOLUTION_FAILED,
                    error = error,
                    extra = mapOf("area" to "HISTORY", "fallback" to "WEB_RESOLVER"),
                    text = "Data API nie rozpoznało kanału; używany jest resolver Web",
                )
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

    /**
     * Karty jednego kanału są przeplatane co 14 dni: najpierw Filmy, potem
     * Shorty i Streamy. Kursor każdej karty pozostaje w pamięci, więc następny
     * etap nie zaczyna ponownie od najnowszej strony. Pięć kanałów nadal może
     * wykonywać ten sam algorytm równolegle.
     */
    private suspend fun loadWebTargetsProgressively(
        targets: List<Target>,
        resolved: ResolvedSource,
        overallCutoff: Long,
        historyWindowDays: Int,
        reporter: ProgressReporter,
        enabledHistoryTypes: Set<HistoryFilter>,
        diagnosticRun: DiagnosticSyncRun,
    ): ChannelResult {
        val states = targets.associateWith { WebTargetPagingState() }
        val failedTargets = mutableSetOf<Target>()
        val errors = mutableListOf<String>()
        var insertedCount = 0
        // Odtwarzamy dokładnie tę samą chwilę, z której wyliczono końcową
        // granicę. Dzięki temu ostatni etap zawsze kończy się na overallCutoff.
        val rangeNowMillis = overallCutoff + historyWindowDays.toLong() * DAY_MILLIS

        for (stageDepthDays in historyStageDepths(historyWindowDays)) {
            val stageCutoff = maxOf(
                overallCutoff,
                rangeNowMillis - stageDepthDays.toLong() * DAY_MILLIS,
            )
            for (target in targets) {
                currentCoroutineContext().ensureActive()
                if (target in failedTargets) continue
                val state = states.getValue(target)
                if (state.networkComplete && state.deferredItems.isEmpty()) continue
                try {
                    val result = loadWebTargetStage(
                        target = target,
                        resolved = resolved,
                        overallCutoff = overallCutoff,
                        stageCutoff = stageCutoff,
                        reporter = reporter,
                        state = state,
                        enabledHistoryTypes = enabledHistoryTypes,
                        diagnosticRun = diagnosticRun,
                    )
                    insertedCount += result.insertedCount
                    if (result.targetComplete) {
                        completedTargets += target.key
                    } else if (!result.stageComplete) {
                        failedTargets += target
                        diagnosticRun.youtubeIssue(
                            name = "HISTORY_PARTIAL",
                            level = DiagnosticLevel.WARNING,
                            creatorId = target.creator.id,
                            source = target.source,
                            resolved = resolved,
                            operation = DiagnosticYouTubeOperation.WEB_HISTORY,
                            fallbackReason = DiagnosticReasonCode.HISTORY_PAGE_LIMIT_REACHED,
                            extra = mapOf(
                                "area" to "HISTORY",
                                "tab" to target.tab?.name,
                                "stageDays" to stageDepthDays,
                            ),
                            text = "Web osiągnął limit stron przed objęciem zakresu",
                        )
                        errors +=
                            "${target.creator.name}: osiągnięto limit stron przed objęciem całego zakresu"
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    diagnosticRun.sourceError(DiagnosticYouTubeSource.WEB)
                    failedTargets += target
                    diagnosticRun.youtubeIssue(
                        name = "SOURCE_ERROR",
                        level = DiagnosticLevel.ERROR,
                        creatorId = target.creator.id,
                        source = target.source,
                        resolved = resolved,
                        operation = DiagnosticYouTubeOperation.WEB_HISTORY,
                        fallbackReason = DiagnosticReasonCode.WEB_SOURCE_FAILED,
                        error = error,
                        extra = mapOf(
                            "area" to "HISTORY",
                            "tab" to target.tab?.name,
                            "stageDays" to stageDepthDays,
                        ),
                        text = "YouTube Web nie pobrał etapu historii",
                    )
                    errors += (
                        "${target.creator.name}: " +
                            (error.message ?: error.javaClass.simpleName)
                                .take(MAX_ERROR_DETAIL_CHARS)
                        ).take(MAX_ERROR_LINE_CHARS)
                }
            }
            // Po każdym dwutygodniowym etapie odświeżamy ekran nawet wtedy,
            // gdy mała liczba pozycji nie uruchomiła ogranicznika czasowego.
            reporter.report(force = true)
        }
        states.forEach { (target, state) ->
            if (
                (!state.networkComplete || state.deferredItems.isNotEmpty()) &&
                target !in failedTargets
            ) {
                diagnosticRun.youtubeIssue(
                    name = "HISTORY_PARTIAL",
                    level = DiagnosticLevel.WARNING,
                    creatorId = target.creator.id,
                    source = target.source,
                    resolved = resolved,
                    operation = DiagnosticYouTubeOperation.WEB_HISTORY,
                    fallbackReason = DiagnosticReasonCode.HISTORY_RANGE_INCOMPLETE,
                    extra = mapOf("area" to "HISTORY", "tab" to target.tab?.name),
                    text = "Nie ukończono wszystkich etapów historii",
                )
                errors +=
                    "${target.creator.name}: nie ukończono dwutygodniowych etapów historii"
            }
        }
        return ChannelResult(insertedCount, errors.distinct())
    }

    private suspend fun loadWebTargetStage(
        target: Target,
        resolved: ResolvedSource,
        overallCutoff: Long,
        stageCutoff: Long,
        reporter: ProgressReporter,
        state: WebTargetPagingState,
        enabledHistoryTypes: Set<HistoryFilter>,
        diagnosticRun: DiagnosticSyncRun,
    ): WebStageLoadResult {
        var insertedCount = 0
        val readyDeferred = state.deferredItems.values.filter {
            it.entry.publishedAtMillis >= stageCutoff &&
                it.isSafeForEnabledContentTypes(enabledHistoryTypes)
        }
        if (readyDeferred.isNotEmpty()) {
            insertedCount += database.insertHistoricalVideos(target.creator, readyDeferred)
            readyDeferred.forEach { state.deferredItems.remove(it.entry.id) }
            reporter.report()
        }
        var stageComplete = state.networkComplete
        val tab = requireNotNull(target.tab)
        val chronological = target.source.type != SourceType.PLAYLIST

        while (
            !state.networkComplete &&
            !stageComplete &&
            state.loadedPageCount < MAX_PAGES_PER_TARGET
        ) {
            currentCoroutineContext().ensureActive()
            val page = if (!state.started) {
                state.started = true
                diagnosticRun.sourceRequest(DiagnosticYouTubeSource.WEB)
                diagnosticRun.measure(DiagnosticSyncStage.WEB) {
                    client.firstPage(resolved, tab)
                }
            } else {
                val cursor = state.cursor
                if (cursor == null) {
                    state.networkComplete = true
                    break
                }
                diagnosticRun.sourceRequest(DiagnosticYouTubeSource.WEB)
                diagnosticRun.measure(DiagnosticSyncStage.WEB) {
                    client.nextPage(cursor, tab)
                }
            }
            state.loadedPageCount += 1
            val datedIds = page.items.mapTo(linkedSetOf()) { it.entry.id }
            logYouTubeDownload(
                area = DiagnosticDownloadArea.HISTORY,
                source = DiagnosticYouTubeSource.WEB,
                videoIds = datedIds,
                run = diagnosticRun,
            )
            val classificationOnlyIds = page.membershipKinds.keys - datedIds
            if (classificationOnlyIds.isNotEmpty()) {
                logYouTubeDownload(
                    area = DiagnosticDownloadArea.HISTORY,
                    source = DiagnosticYouTubeSource.WEB,
                    videoIds = classificationOnlyIds,
                    role = DiagnosticDownloadRole.CLASSIFICATION,
                    run = diagnosticRun,
                )
            }
            val pageItems = if (
                target.source.type == SourceType.PLAYLIST &&
                enabledHistoryTypes != HistoryFilter.entries.toSet()
            ) {
                resolveAmbiguousDataApiKinds(page.items) { videoId ->
                    classificationSemaphore.withPermit { classifier.classify(videoId) }
                }.also { classified ->
                    logYouTubeDownload(
                        area = DiagnosticDownloadArea.HISTORY,
                        source = DiagnosticYouTubeSource.WEB,
                        videoIds = classified
                            .filter { it.evidence == VideoKindEvidence.PLAYER_METADATA }
                            .map { it.entry.id },
                        role = DiagnosticDownloadRole.CLASSIFICATION,
                        run = diagnosticRun,
                    )
                }
            } else {
                page.items
            }

            // Jedna strona YouTube może przeciąć granicę 14 dni. Jej starszą
            // część zachowujemy w pamięci i zapisujemy dopiero w kolejnym
            // etapie, bez ponownego pobierania strony.
            val (readyNow, deferred) = splitHistoryStageItems(
                items = pageItems.filter {
                    it.isSafeForEnabledContentTypes(enabledHistoryTypes)
                },
                overallCutoff = overallCutoff,
                stageCutoff = stageCutoff,
            )
            deferred
                .forEach { state.deferredItems.putIfAbsent(it.entry.id, it) }
            insertedCount += database.insertHistoricalVideos(target.creator, readyNow)
            database.markVideoClassifications(
                page.membershipKinds
                    .filterKeys { it !in datedIds }
                    .mapValues { (_, kind) ->
                        VideoKindDecision(kind, VideoKindEvidence.CHANNEL_TAB)
                    },
            )
            reporter.report()

            state.cursor = page.nextCursor
            if (
                tab != YouTubeHistoryTab.PLAYLIST &&
                page.items.isEmpty() &&
                page.membershipKinds.isNotEmpty()
            ) {
                // Karta bez dat nadal klasyfikuje bieżące wpisy RSS, lecz nie
                // nadaje się do bezpiecznego przewijania historii.
                state.networkComplete = true
                stageComplete = true
                break
            }

            val publishedTimes = page.items.map { it.entry.publishedAtMillis }
            state.networkComplete = isHistoryTargetComplete(
                publishedTimes = publishedTimes,
                cutoff = overallCutoff,
                hasNextPage = state.cursor != null,
                chronological = chronological,
            )
            stageComplete = state.networkComplete || isHistoryTargetComplete(
                publishedTimes = publishedTimes,
                cutoff = stageCutoff,
                hasNextPage = state.cursor != null,
                chronological = chronological,
            )

            state.cursor?.let { nextCursor ->
                if (!state.seenCursorTokens.add(nextCursor.token)) {
                    throw IOException("YouTube powtórzył kursor historii")
                }
            }
        }
        return WebStageLoadResult(
            insertedCount = insertedCount,
            stageComplete = stageComplete || state.networkComplete,
            targetComplete = state.networkComplete && state.deferredItems.isEmpty(),
        )
    }

    private suspend fun loadDataApiTarget(
        target: Target,
        resolved: ResolvedSource,
        cutoff: Long,
        apiKey: String,
        reporter: ProgressReporter,
        enabledHistoryTypes: Set<HistoryFilter>,
        diagnosticRun: DiagnosticSyncRun,
    ): TargetLoadResult {
        var pageToken: String? = null
        var insertedCount = 0
        var pageNumber = 0
        var complete = false
        val seenPageTokens = mutableSetOf<String>()

        while (pageNumber < MAX_PAGES_PER_TARGET) {
            currentCoroutineContext().ensureActive()
            diagnosticRun.sourceRequest(DiagnosticYouTubeSource.DATA_API)
            val page = diagnosticRun.measure(DiagnosticSyncStage.API) {
                dataApiClient.fetchPage(
                    source = resolved,
                    apiKey = apiKey,
                    pageToken = pageToken,
                    classifyAfterMillis = cutoff,
                )
            }
            pageNumber += 1
            logYouTubeDownload(
                area = DiagnosticDownloadArea.HISTORY,
                source = DiagnosticYouTubeSource.DATA_API,
                videoIds = page.items.map { it.entry.id },
                run = diagnosticRun,
            )
            val withinRange = page.items.filter { it.entry.publishedAtMillis >= cutoff }
            val classified = if (target.source.type == SourceType.PLAYLIST) {
                resolveAmbiguousDataApiKinds(withinRange) { videoId ->
                    classificationSemaphore.withPermit {
                        diagnosticRun.sourceRequest(DiagnosticYouTubeSource.WEB)
                        classifier.classify(videoId)
                    }
                }
            } else {
                withinRange
            }.map(YouTubeHistoryItem::withDefaultVideoFallback)
                .filter { it.isSafeForEnabledContentTypes(enabledHistoryTypes) }
            insertedCount += database.insertHistoricalVideos(target.creator, classified)
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
        return TargetLoadResult(
            insertedCount = insertedCount,
            complete = complete,
        )
    }

    private fun buildTargets(settings: AppSettings, useDataApi: Boolean): List<Target> {
        if (settings.historyFilters.intersect(settings.globalHistoryTypes).isEmpty()) {
            return emptyList()
        }
        if (useDataApi) {
            return buildList {
                catalog.creators
                    .filter {
                        it.id in settings.selectedCreatorIds &&
                            settings.historyTypesFor(it.id).isNotEmpty()
                    }
                    .forEach { creator ->
                        creator.sources.forEach { source ->
                            add(Target(creator, source, tab = null))
                        }
                    }
            }
        }
        return buildList {
            catalog.creators
                .filter {
                    it.id in settings.selectedCreatorIds &&
                        settings.historyTypesFor(it.id).isNotEmpty()
                }
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
        if (settings.historyFilters.intersect(settings.globalHistoryTypes).isEmpty()) {
            return emptyList()
        }
        val enabledHistoryTypes = settings.historyTypesFor(creator.id)
        // RSS miesza rodzaje materiałów. Pobranie wszystkich istniejących kart
        // daje poprawną klasyfikację niezależnie od aktualnie otwartego filtra;
        // SQL sprawia, że potwierdzonego braku karty już nie odpytujemy.
        return webHistoryTabsForSource(source.type, enabledHistoryTypes).map { tab ->
            Target(creator, source, tab)
        }
    }

    private fun buildApiKindVerificationTargets(
        creator: Creator,
        source: CreatorSource,
        enabledHistoryTypes: Set<HistoryFilter>,
    ): List<Target> = (
        if (enabledHistoryTypes == HistoryFilter.entries.toSet()) {
            apiKindVerificationTabs(source.type)
        } else {
            webHistoryTabsForSource(source.type, enabledHistoryTypes)
        }
        )
        .map { tab ->
            Target(creator, source, tab)
        }

    private fun buildSignature(settings: AppSettings, apiKey: String): String = buildString {
        append(settings.selectedCreatorIds.sorted().joinToString(","))
        append('|')
        // Konkretny filtr zmienia wyłącznie widok. Zestaw celów sieciowych jest
        // zawsze taki sam (Shorty, Streamy i Filmy), więc nie wolno przez jego
        // przełączenie kasować zakończonych celów i ponownie pobierać kanałów.
        append(historyFilterTargetSignature(settings.historyFilters))
        append('|')
        append(contentSettingsSignature(settings))
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

    private companion object {
        const val MAX_PARALLEL_CLASSIFICATIONS = 6
        const val MAX_PAGES_PER_TARGET = 120
        const val MAX_ERROR_DETAIL_CHARS = 400
        const val MAX_ERROR_LINE_CHARS = 600
        const val PROGRESS_INTERVAL_MILLIS = 400L
        const val DAY_MILLIS = 24L * 60L * 60L * 1_000L
    }
}

/**
 * API zachowuje przewagę szybkiego stronicowania, a dodatkowe żądanie strony
 * filmu wykonujemy wyłącznie wtedy, gdy metadane API nie dowodzą rodzaju
 * materiału. Kolejność wejścia zostaje zachowana.
 */
internal suspend fun resolveAmbiguousDataApiKinds(
    items: List<YouTubeHistoryItem>,
    classify: suspend (String) -> VideoKind,
): List<YouTubeHistoryItem> = coroutineScope {
    items.map { item ->
        async {
            if (item.kind != VideoKind.UNKNOWN) {
                item
            } else {
                val resolvedKind = classify(item.entry.id)
                item.copy(
                    kind = resolvedKind,
                    evidence = if (resolvedKind == VideoKind.UNKNOWN) {
                        VideoKindEvidence.NONE
                    } else {
                        VideoKindEvidence.PLAYER_METADATA
                    },
                )
            }
        }
    }.awaitAll()
}

internal const val HISTORY_CHANNEL_CONCURRENCY = 5

internal fun historyFilterTargetSignature(filters: Set<HistoryFilter>): String =
    if (filters.isEmpty()) "HISTORY_DISABLED" else "ALL_KINDS"

internal const val HISTORY_STAGE_DAYS = 14

internal fun historyStageDepths(historyWindowDays: Int): List<Int> {
    require(historyWindowDays > 0)
    val result = mutableListOf<Int>()
    var depth = minOf(HISTORY_STAGE_DAYS, historyWindowDays)
    while (true) {
        result += depth
        if (depth == historyWindowDays) return result
        depth = minOf(depth + HISTORY_STAGE_DAYS, historyWindowDays)
    }
}

internal fun splitHistoryStageItems(
    items: List<YouTubeHistoryItem>,
    overallCutoff: Long,
    stageCutoff: Long,
): Pair<List<YouTubeHistoryItem>, List<YouTubeHistoryItem>> {
    require(stageCutoff >= overallCutoff)
    val withinOverallRange = items.filter {
        it.entry.publishedAtMillis >= overallCutoff
    }
    return withinOverallRange.partition {
        it.entry.publishedAtMillis >= stageCutoff
    }
}

internal fun webHistoryTabsForSource(
    sourceType: SourceType,
    enabledTypes: Set<HistoryFilter> = HistoryFilter.entries.toSet(),
): List<YouTubeHistoryTab> =
    if (sourceType == SourceType.CHANNEL) {
        // W każdym dwutygodniowym etapie zwykłe filmy są dostarczane jako
        // pierwsze, następnie Shorty, a na końcu transmisje.
        listOf(
            YouTubeHistoryTab.VIDEOS,
            YouTubeHistoryTab.SHORTS,
            YouTubeHistoryTab.STREAMS,
        ).filter { it.contentType() in enabledTypes }
    } else {
        if (enabledTypes.isEmpty()) emptyList() else listOf(YouTubeHistoryTab.PLAYLIST)
    }

/**
 * API rozpoznaje bieżące LIVE/UPCOMING i długie filmy, ale nie potwierdza
 * archiwalnych transmisji ani Shorts. Tylko te dwie karty są więc potrzebne
 * jako zwykły backfill klasyfikacyjny; nie wykonujemy drugiego skanu VIDEOS.
 */
internal fun apiKindVerificationTabs(sourceType: SourceType): List<YouTubeHistoryTab> =
    if (sourceType == SourceType.CHANNEL) {
        listOf(YouTubeHistoryTab.SHORTS, YouTubeHistoryTab.STREAMS)
    } else {
        listOf(YouTubeHistoryTab.PLAYLIST)
    }

internal fun YouTubeHistoryItem.withDefaultVideoFallback(): YouTubeHistoryItem =
    if (kind == VideoKind.UNKNOWN || evidence == VideoKindEvidence.NONE) {
        copy(
            kind = VideoKind.VIDEO,
            evidence = VideoKindEvidence.DEFAULT_VIDEO_FALLBACK,
        )
    } else {
        this
    }

/**
 * RSS nie rozróżnia zwykłego filmu od archiwum transmisji, a Data API oznacza
 * archiwalne transmisje tak samo jak filmy. Gdy użytkownik wyłączył choć jeden
 * rodzaj, takie niejednoznaczne rekordy czekają na potwierdzenie odpowiednią
 * kartą YouTube Web zamiast chwilowo trafiać do złej sekcji.
 */
internal fun YouTubeHistoryItem.isSafeForEnabledContentTypes(
    enabledTypes: Set<HistoryFilter>,
): Boolean {
    val allTypesEnabled = enabledTypes.containsAll(HistoryFilter.entries)
    if (
        evidence == VideoKindEvidence.NONE ||
        evidence == VideoKindEvidence.DEFAULT_VIDEO_FALLBACK ||
        (evidence == VideoKindEvidence.API_METADATA && kind == VideoKind.VIDEO)
    ) {
        return allTypesEnabled
    }
    return kind.contentType() in enabledTypes
}

internal fun YouTubeHistoryTab.contentType(): HistoryFilter? = when (this) {
    YouTubeHistoryTab.VIDEOS -> HistoryFilter.VIDEOS
    YouTubeHistoryTab.STREAMS -> HistoryFilter.STREAMS
    YouTubeHistoryTab.SHORTS -> HistoryFilter.SHORTS
    YouTubeHistoryTab.PLAYLIST -> null
}

internal fun VideoKind.matchesHistoryFilters(filters: Set<HistoryFilter>): Boolean = when (this) {
    VideoKind.VIDEO -> HistoryFilter.VIDEOS in filters
    VideoKind.LIVE,
    VideoKind.UPCOMING,
    VideoKind.STREAM_ARCHIVE -> HistoryFilter.STREAMS in filters
    VideoKind.SHORT -> HistoryFilter.SHORTS in filters
    VideoKind.UNKNOWN -> false
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
        val decision = rssVideoKindDecision(entry)
        YouTubeHistoryItem(
            entry = entry.copy(origin = VideoOrigin.YOUTUBE),
            // Kanoniczny `/shorts/ID` jest mocnym dowodem; zwykły watch
            // dostaje słaby fallback VIDEO, który karta kanału może poprawić.
            kind = decision.kind,
            evidence = decision.evidence,
            publishedAtEvidence = PublishedAtEvidence.RSS,
        )
    }
    .toList()
