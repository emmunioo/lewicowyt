package pl.lewicowyt.notifier.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import pl.lewicowyt.notifier.AppGraph
import pl.lewicowyt.notifier.AppLog
import pl.lewicowyt.notifier.BuildConfig
import pl.lewicowyt.notifier.data.AppSettings
import pl.lewicowyt.notifier.data.HistorySearchEngine
import pl.lewicowyt.notifier.data.ThemeMode
import pl.lewicowyt.notifier.data.YouTubeLinkTarget
import pl.lewicowyt.notifier.data.hasEnabledContentForSelectedCreators
import pl.lewicowyt.notifier.data.isHistoryEnabledFor
import pl.lewicowyt.notifier.data.isNotificationEnabledFor
import pl.lewicowyt.notifier.images.JxlImageCache
import pl.lewicowyt.notifier.diagnostics.DiagnosticCategory
import pl.lewicowyt.notifier.diagnostics.DiagnosticLevel
import pl.lewicowyt.notifier.diagnostics.DiagnosticLogStore
import pl.lewicowyt.notifier.diagnostics.DiagnosticReasonCode
import pl.lewicowyt.notifier.diagnostics.DiagnosticSyncRun
import pl.lewicowyt.notifier.diagnostics.DiagnosticSyncTrigger
import pl.lewicowyt.notifier.diagnostics.diagnosticYouTubeVideoUrl
import pl.lewicowyt.notifier.model.Creator
import pl.lewicowyt.notifier.model.ConfirmedOlderMaterial
import pl.lewicowyt.notifier.model.HistoryFilter
import pl.lewicowyt.notifier.model.HistoryItem
import pl.lewicowyt.notifier.model.OlderMaterialCandidate
import pl.lewicowyt.notifier.model.VideoKind
import pl.lewicowyt.notifier.network.YouTubeApiKeyValidation
import pl.lewicowyt.notifier.network.normalizeYouTubeAvatarUrl
import pl.lewicowyt.notifier.images.BundledAvatarStore
import pl.lewicowyt.notifier.sync.DescriptionBackfillStatus
import pl.lewicowyt.notifier.sync.DescriptionBackfillLoader
import pl.lewicowyt.notifier.updates.AvailableUpdate
import pl.lewicowyt.notifier.updates.UpdateCheckResult
import pl.lewicowyt.notifier.worker.isNotificationInterruptionSuppressed

private val POLISH_LOCALE: Locale = Locale.forLanguageTag("pl")
private const val MAX_HISTORY_DAYS = 60
private const val DAY_MILLIS = 24L * 60L * 60L * 1000L
private const val HISTORY_PAGE_SIZE = 20
private const val HISTORY_PREFETCH_DISTANCE = 5
private const val DESELECTED_HISTORY_RETENTION_MILLIS = 7L * DAY_MILLIS
private const val MAX_SEARCH_QUERY_CHARS = 200
private const val MAX_VISIBLE_SYNC_ERRORS = 5
private const val MAX_VISIBLE_ERROR_CHARS = 500
private const val MAX_PARALLEL_AVATARS = 6
private const val MAX_FOREGROUND_DESCRIPTION_BATCHES = 16
private const val DESCRIPTION_BATCH_YIELD_MILLIS = 100L

sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data object NotConfigured : UpdateUiState
    data class UpToDate(val latestVersion: String) : UpdateUiState
    data class Available(val update: AvailableUpdate) : UpdateUiState
    data class Downloading(val update: AvailableUpdate) : UpdateUiState
    data class ReadyToInstall(val update: AvailableUpdate) : UpdateUiState
    data class Error(
        val message: String,
        val update: AvailableUpdate? = null,
    ) : UpdateUiState
}

sealed interface ApiKeyUiState {
    data object Idle : ApiKeyUiState
    data object Validating : ApiKeyUiState
    data class Success(val message: String) : ApiKeyUiState
    data class Error(val message: String) : ApiKeyUiState
}

data class AppUiState(
    val creators: List<Creator> = emptyList(),
    val catalogCreators: List<Creator> = emptyList(),
    val allCreatorCount: Int = 0,
    val selectedCreatorIds: Set<String> = emptySet(),
    val creatorAvatars: Map<String, String> = emptyMap(),
    val settings: AppSettings = AppSettings(),
    val history: List<HistoryItem> = emptyList(),
    val notifications: List<HistoryItem> = emptyList(),
    val query: String = "",
    val historySearchQuery: String = "",
    val olderMaterialSearch: OlderMaterialSearchUiState = OlderMaterialSearchUiState(),
    val isRefreshing: Boolean = false,
    val actionMessage: String? = null,
    val apiKeyState: ApiKeyUiState = ApiKeyUiState.Idle,
    val updateState: UpdateUiState = UpdateUiState.Idle,
    val favoritesOnly: Boolean = false,
    val historyHasMore: Boolean = true,
    val isLoadingHistory: Boolean = false,
    val isLoadingDescriptions: Boolean = false,
    val descriptionLoadingSource: String? = null,
    val pendingDescriptionCount: Int = 0,
    val historyLoadError: String? = null,
    val notificationNavigationRequest: Long = 0L,
)

data class OlderMaterialSearchUiState(
    val creatorId: String? = null,
    val query: String = "",
    val results: List<OlderMaterialCandidate> = emptyList(),
    val confirmed: ConfirmedOlderMaterial? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

private data class UiContent(
    val settings: AppSettings,
    val query: String,
    val history: List<HistoryItem>,
    val historySearchResults: List<HistoryItem>,
    val historySearchLimit: Int,
    val notifications: List<HistoryItem>,
    val avatars: Map<String, String>,
    val historyLimit: Int,
    val notificationNavigationRequest: Long,
    val apiKeyState: ApiKeyUiState,
    val favoritesOnly: Boolean,
    val historySearchQuery: String,
    val olderMaterialSearch: OlderMaterialSearchUiState,
)

private data class HistoryLoadState(
    val isLoading: Boolean,
    val endReached: Boolean,
    val error: String?,
    val descriptionStatus: DescriptionBackfillStatus,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = AppGraph.apply { initialize(application) }
    private val query = MutableStateFlow("")
    private val history = MutableStateFlow<List<HistoryItem>>(emptyList())
    private val historySearchQuery = MutableStateFlow("")
    private val historySearchResults = MutableStateFlow<List<HistoryItem>>(emptyList())
    private val historySearchLimit = MutableStateFlow(HISTORY_SEARCH_PAGE_SIZE)
    private val olderMaterialSearch = MutableStateFlow(OlderMaterialSearchUiState())
    private val notificationInbox = MutableStateFlow<List<HistoryItem>>(emptyList())
    private val storedAvatarSnapshot = graph.database.getCreatorAvatars()
    private val avatars = MutableStateFlow(
        storedAvatarSnapshot.normalizedAvatarUrls(),
    )
    private val legacyAvatarIdsToRefresh =
        storedAvatarSnapshot.keys - avatars.value.keys
    private val refreshing = MutableStateFlow(false)
    private val actionMessage = MutableStateFlow<String?>(null)
    private val apiKeyStatus = MutableStateFlow<ApiKeyUiState>(ApiKeyUiState.Idle)
    private val updateStatus = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    private val historyLimit = MutableStateFlow(HISTORY_PAGE_SIZE)
    private val loadingHistory = MutableStateFlow(false)
    private val historyEndReached = MutableStateFlow(false)
    private val historyLoadError = MutableStateFlow<String?>(null)
    private val notificationNavigationRequest = MutableStateFlow(0L)
    private val favoritesOnly = MutableStateFlow(false)
    private var historyLoadJob: Job? = null
    private var descriptionBackfillJob: Job? = null
    private var historySearchJob: Job? = null
    private var syncJob: Job? = null
    private var initialSyncCheckRunning = false
    private var lastManualUpdateCheckAtMillis = 0L

    private data class StoredItems(
        val history: List<HistoryItem>,
        val notifications: List<HistoryItem>,
        val historySearchResults: List<HistoryItem>,
        val historySearchLimit: Int,
        val olderMaterialSearch: OlderMaterialSearchUiState,
    )

    private data class ViewControls(
        val historyLimit: Int,
        val notificationNavigationRequest: Long,
        val apiKeyState: ApiKeyUiState,
        val favoritesOnly: Boolean,
        val historySearchQuery: String,
    )

    private val storedItems = combine(
        history,
        notificationInbox,
        historySearchResults,
        historySearchLimit,
        olderMaterialSearch,
    ) { currentHistory, currentNotifications, currentSearchResults, currentSearchLimit,
        currentOlderSearch ->
        StoredItems(
            currentHistory,
            currentNotifications,
            currentSearchResults,
            currentSearchLimit,
            currentOlderSearch,
        )
    }

    private val viewControls = combine(
        historyLimit,
        notificationNavigationRequest,
        apiKeyStatus,
        favoritesOnly,
        historySearchQuery,
    ) { currentHistoryLimit, navigationRequest, currentApiKeyState, currentFavoritesOnly,
        currentHistorySearchQuery ->
        ViewControls(
            currentHistoryLimit,
            navigationRequest,
            currentApiKeyState,
            currentFavoritesOnly,
            currentHistorySearchQuery,
        )
    }

    private val content = combine(
        graph.preferences.settingsFlow,
        query,
        storedItems,
        avatars,
        viewControls,
    ) {
            settings,
            currentQuery,
            currentItems,
            currentAvatars,
            controls,
        ->
        UiContent(
            settings,
            currentQuery,
            currentItems.history,
            currentItems.historySearchResults,
            currentItems.historySearchLimit,
            currentItems.notifications,
            currentAvatars,
            controls.historyLimit,
            controls.notificationNavigationRequest,
            controls.apiKeyState,
            controls.favoritesOnly,
            controls.historySearchQuery,
            currentItems.olderMaterialSearch,
        )
    }

    private val historyLoadState = combine(
        loadingHistory,
        historyEndReached,
        historyLoadError,
        graph.descriptionBackfill.status,
    ) { isLoading, endReached, error, descriptionStatus ->
        HistoryLoadState(isLoading, endReached, error, descriptionStatus)
    }

    val uiState = combine(
        content,
        refreshing,
        actionMessage,
        updateStatus,
        historyLoadState,
    ) { contentState, isRefreshing, message, currentUpdateState, loadState ->
        val normalized = contentState.query.trim().lowercase(POLISH_LOCALE)
        val cutoff = System.currentTimeMillis() -
            contentState.settings.historyWindowDays.toLong() * DAY_MILLIS
        val activeHistoryFilters = contentState.settings.historyFilters
            .intersect(contentState.settings.globalHistoryTypes)
        val historySource = if (contentState.historySearchQuery.isBlank()) {
            contentState.history
        } else {
            contentState.historySearchResults
        }
        val filteredHistory = historySource
            .asSequence()
            .filter {
                shouldIncludeHistoryItem(
                    favoritesOnly = contentState.favoritesOnly,
                    isFavorite = it.isFavorite,
                    searchActive = contentState.historySearchQuery.isNotBlank(),
                    publishedAtMillis = it.publishedAtMillis,
                    cutoffMillis = cutoff,
                )
            }
            .filter { it.creatorId in contentState.settings.selectedCreatorIds }
            .filter {
                contentState.settings.isHistoryEnabledFor(it.creatorId, it.kind)
            }
            .filter { it.matches(activeHistoryFilters) }
            .let { sequence ->
                if (contentState.historySearchQuery.isBlank()) {
                    sequence.sortedWith(
                        compareByDescending<HistoryItem> { it.publishedAtMillis }
                            .thenByDescending { it.detectedAtMillis },
                    )
                } else sequence
            }
            .toList()
        AppUiState(
            creators = graph.catalog.creators.filter {
                normalized.isBlank() || it.name.lowercase(POLISH_LOCALE).contains(normalized)
            },
            catalogCreators = graph.catalog.creators,
            allCreatorCount = graph.catalog.creators.size,
            selectedCreatorIds = contentState.settings.selectedCreatorIds,
            creatorAvatars = contentState.avatars,
            settings = contentState.settings,
            history = filteredHistory.take(
                if (contentState.historySearchQuery.isBlank()) {
                    contentState.historyLimit
                } else {
                    contentState.historySearchLimit
                },
            ),
            notifications = contentState.notifications.filter {
                it.creatorId in contentState.settings.selectedCreatorIds &&
                    contentState.settings.isNotificationEnabledFor(it.creatorId, it.kind)
            },
            query = contentState.query,
            historySearchQuery = contentState.historySearchQuery,
            olderMaterialSearch = contentState.olderMaterialSearch,
            isRefreshing = isRefreshing,
            actionMessage = message,
            apiKeyState = contentState.apiKeyState,
            updateState = currentUpdateState,
            favoritesOnly = contentState.favoritesOnly,
            historyHasMore = if (contentState.historySearchQuery.isNotBlank()) {
                contentState.historySearchLimit < MAX_HISTORY_SEARCH_RESULTS &&
                    filteredHistory.size > contentState.historySearchLimit
            } else {
                activeHistoryFilters.isNotEmpty() &&
                    if (contentState.favoritesOnly) {
                        filteredHistory.size > contentState.historyLimit
                    } else {
                        filteredHistory.size > contentState.historyLimit ||
                            (!loadState.endReached && loadState.error == null)
                    }
            },
            isLoadingHistory = loadState.isLoading,
            isLoadingDescriptions = loadState.descriptionStatus.active,
            descriptionLoadingSource = loadState.descriptionStatus.source,
            pendingDescriptionCount = loadState.descriptionStatus.pendingCount,
            historyLoadError = loadState.error,
            notificationNavigationRequest = contentState.notificationNavigationRequest,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppUiState(
            creators = graph.catalog.creators,
            catalogCreators = graph.catalog.creators,
            allCreatorCount = graph.catalog.creators.size,
            creatorAvatars = avatars.value,
        ),
    )

    init {
        refreshHistory()
        syncOnFirstLaunchIfNeeded()
        if (legacyAvatarIdsToRefresh.isNotEmpty()) {
            viewModelScope.launch {
                refreshMissingAvatars(legacyAvatarIdsToRefresh)
            }
        }
    }

    fun setQuery(value: String) {
        query.value = value.take(MAX_SEARCH_QUERY_CHARS)
    }

    fun setHistorySearchQuery(value: String) {
        val normalized = value.take(MAX_SEARCH_QUERY_CHARS)
        if (normalized != historySearchQuery.value) {
            historySearchLimit.value = HISTORY_SEARCH_PAGE_SIZE
            historySearchResults.value = emptyList()
        }
        historySearchQuery.value = normalized
        refreshHistorySearch(debounce = true)
    }

    fun setFavoritesOnly(enabled: Boolean) {
        favoritesOnly.value = enabled
        historyLimit.value = HISTORY_PAGE_SIZE
        refreshHistorySearch()
    }

    fun setFavorite(videoId: String, favorite: Boolean) {
        viewModelScope.launch {
            try {
                val updated = withContext(Dispatchers.IO) {
                    graph.database.setFavorite(videoId, favorite)
                }
                if (updated) {
                    refreshHistoryNow()
                } else {
                    actionMessage.value = "Nie znaleziono materiału w lokalnej historii."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                actionMessage.value =
                    "Nie udało się zmienić Ulubionych: ${error.displayMessage()}"
            }
        }
    }

    fun setCreatorSelected(creatorId: String, selected: Boolean) {
        viewModelScope.launch {
            graph.preferences.setCreatorSelected(creatorId, selected)
            graph.scheduler.schedule(graph.preferences.current())
            resetHistoryPaging()
            if (selected) refreshMissingAvatars(setOf(creatorId))
            if (selected) syncOnFirstLaunchIfNeeded()
        }
    }

    fun setAllCreatorsSelected(selected: Boolean) {
        viewModelScope.launch {
            graph.preferences.setCreatorsSelected(graph.catalog.creators.map { it.id }, selected)
            graph.scheduler.schedule(graph.preferences.current())
            resetHistoryPaging()
            if (selected) {
                refreshMissingAvatars(graph.catalog.creators.mapTo(mutableSetOf()) { it.id })
                syncOnFirstLaunchIfNeeded()
            }
        }
    }

    fun setInterval(minutes: Int) {
        viewModelScope.launch {
            graph.preferences.setIntervalMinutes(minutes)
            graph.scheduler.schedule(graph.preferences.current())
        }
    }

    fun setDailyTime(hour: Int, minute: Int) {
        viewModelScope.launch {
            graph.preferences.setDailyTime(hour, minute)
            graph.scheduler.schedule(graph.preferences.current())
        }
    }

    fun setHistoryWindowDays(days: Int) {
        viewModelScope.launch {
            graph.preferences.setHistoryWindowDays(days)
            resetHistoryPaging()
            refreshHistorySearch()
        }
    }

    fun setHistoryFilter(filter: HistoryFilter, enabled: Boolean) {
        viewModelScope.launch {
            graph.preferences.setHistoryFilter(filter, enabled)
            // Wszystkie trzy karty kanału są pobierane niezależnie od filtra,
            // więc zmiana widoku nie może kasować postępu sieciowego. Zerujemy
            // tylko stronicowanie ekranu; rozpoczęty backfill zostanie wznowiony.
            resetHistoryPaging(resetBackfill = false)
            refreshHistorySearch()
        }
    }

    fun setGlobalHistoryType(type: HistoryFilter, enabled: Boolean) {
        viewModelScope.launch {
            graph.preferences.setGlobalHistoryType(type, enabled)
            graph.scheduler.schedule(graph.preferences.current())
            resetHistoryPaging()
            refreshHistorySearch()
        }
    }

    fun setGlobalNotificationType(type: HistoryFilter, enabled: Boolean) {
        viewModelScope.launch {
            graph.preferences.setGlobalNotificationType(type, enabled)
        }
    }

    fun setCreatorHistoryType(creatorId: String, type: HistoryFilter, enabled: Boolean) {
        viewModelScope.launch {
            graph.preferences.setCreatorHistoryType(creatorId, type, enabled)
            graph.scheduler.schedule(graph.preferences.current())
            resetHistoryPaging()
            refreshHistorySearch()
        }
    }

    fun setCreatorNotificationType(
        creatorId: String,
        type: HistoryFilter,
        enabled: Boolean,
    ) {
        viewModelScope.launch {
            graph.preferences.setCreatorNotificationType(creatorId, type, enabled)
        }
    }

    fun setAllowMobileData(value: Boolean) {
        viewModelScope.launch {
            graph.preferences.setAllowMobileData(value)
            graph.scheduler.schedule(graph.preferences.current())
        }
    }

    fun setAutomaticUpdatesEnabled(value: Boolean) {
        viewModelScope.launch {
            graph.preferences.setAutomaticUpdatesEnabled(value)
        }
    }

    fun refreshBackgroundSchedule() {
        viewModelScope.launch { graph.scheduler.ensureScheduled() }
    }

    fun setThemeMode(value: ThemeMode) {
        viewModelScope.launch { graph.preferences.setThemeMode(value) }
    }

    fun setHighContrastEnabled(value: Boolean) {
        viewModelScope.launch { graph.preferences.setHighContrastEnabled(value) }
    }

    fun setYouTubeLinkTarget(value: YouTubeLinkTarget) {
        viewModelScope.launch { graph.preferences.setYouTubeLinkTarget(value) }
    }

    fun setOtherYouTubeAppPackage(packageName: String) {
        viewModelScope.launch {
            graph.preferences.setOtherYouTubeAppPackage(packageName)
        }
    }

    fun setAccentColor(argb: Long) {
        viewModelScope.launch { graph.preferences.setAccentColor(argb) }
    }

    fun setYoutubeApiKey(value: String) {
        if (apiKeyStatus.value == ApiKeyUiState.Validating) return
        val normalized = value.trim()
        apiKeyStatus.value = ApiKeyUiState.Validating
        viewModelScope.launch {
            try {
                if (normalized.isBlank()) {
                    graph.preferences.clearYoutubeApiKey()
                    resetHistoryPaging()
                    apiKeyStatus.value = ApiKeyUiState.Success(
                        "Wyłączono YouTube Data API. Aktywny jest tryb bez klucza.",
                    )
                    return@launch
                }

                when (
                    val validation = withContext(Dispatchers.IO) {
                        graph.dataApiClient.validateApiKey(normalized)
                    }
                ) {
                    YouTubeApiKeyValidation.Valid -> {
                        graph.preferences.setValidatedYoutubeApiKey(normalized)
                        resetHistoryPaging()
                        apiKeyStatus.value = ApiKeyUiState.Success(
                            "Klucz został zweryfikowany przez YouTube i zapisany " +
                                "w zaszyfrowanej pamięci Androida.",
                        )
                    }
                    is YouTubeApiKeyValidation.Rejected ->
                        apiKeyStatus.value = ApiKeyUiState.Error(validation.message)
                    is YouTubeApiKeyValidation.TemporarilyUnavailable ->
                        apiKeyStatus.value = ApiKeyUiState.Error(validation.message)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                apiKeyStatus.value = ApiKeyUiState.Error(
                    "Nie można teraz zweryfikować klucza; nie został zapisany: " +
                        error.displayMessage(),
                )
            }
        }
    }

    fun clearApiKeyStatus() {
        if (apiKeyStatus.value != ApiKeyUiState.Validating) {
            apiKeyStatus.value = ApiKeyUiState.Idle
        }
    }

    fun openNotifications() {
        notificationNavigationRequest.value += 1L
        refreshHistory()
    }

    fun syncNow() = syncNowWithTrigger(DiagnosticSyncTrigger.MANUAL)

    private fun syncNowWithTrigger(trigger: DiagnosticSyncTrigger) {
        if (refreshing.value) return
        refreshing.value = true
        syncJob = viewModelScope.launch {
            actionMessage.value = null
            val diagnosticRun = DiagnosticSyncRun.create(trigger)
            try {
                val outcome = graph.syncEngine.sync(
                    diagnosticRun = diagnosticRun,
                )
                actionMessage.value = buildString {
                    append(outcome.toPolishSummary())
                    if (outcome.errors.isNotEmpty()) {
                        appendLine()
                        appendLine("Szczegóły błędów:")
                        outcome.errors.take(MAX_VISIBLE_SYNC_ERRORS)
                            .forEach { error -> appendLine("• $error") }
                        val hiddenErrors = outcome.errors.size - MAX_VISIBLE_SYNC_ERRORS
                        if (hiddenErrors > 0) append("…i jeszcze $hiddenErrors")
                    }
                }.trimEnd()
                refreshHistoryNow()
                avatars.value = graph.database.getCreatorAvatars().normalizedAvatarUrls()
            } catch (error: Exception) {
                diagnosticRun.event(
                    "SYNC_ERROR",
                    level = pl.lewicowyt.notifier.diagnostics.DiagnosticLevel.ERROR,
                    reason = pl.lewicowyt.notifier.diagnostics.DiagnosticReasonCode.DELIVERY_FAILED,
                    fields = mapOf("type" to error.javaClass.simpleName),
                )
                diagnosticRun.finish(mapOf("result" to "ERROR"))
                AppLog.error(
                    "ManualSync",
                    "Ręcznie uruchomiona synchronizacja nie została ukończona",
                    error,
                )
                actionMessage.value =
                    "Synchronizacja nie powiodła się: " +
                        error.displayMessage()
            } finally {
                refreshing.value = false
            }
        }
    }

    private fun syncOnFirstLaunchIfNeeded() {
        if (initialSyncCheckRunning) return
        initialSyncCheckRunning = true
        viewModelScope.launch {
            try {
                val settings = graph.preferences.current()
                if (
                    settings.lastCompletedSyncAtMillis <= 0L &&
                    settings.hasEnabledContentForSelectedCreators() &&
                    !isNotificationInterruptionSuppressed(getApplication())
                ) {
                    syncNowWithTrigger(DiagnosticSyncTrigger.FIRST_SYNC)
                }
            } finally {
                initialSyncCheckRunning = false
            }
        }
    }

    fun saveDiagnosticSnapshot(onResult: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = graph.diagnostics.writeSnapshot("MANUAL_PANEL")
            withContext(Dispatchers.Main) {
                onResult(
                    "Alarmy: ${result.alarmsPresent}/${result.alarmsExpected}; " +
                        "FTS5: ${if (result.fts5Available) "OK" else "niedostępne"}",
                )
            }
        }
    }

    fun checkDiagnosticDatabase(onResult: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = graph.syncEngine.runExclusiveMaintenance {
                graph.diagnostics.quickCheck()
            }
            withContext(Dispatchers.Main) {
                onResult(
                    if (ok) "Baza danych: OK" else
                        "Wykryto problem — szczegóły zapisano w diagnostyce.",
                )
            }
        }
    }

    fun checkForUpdates() {
        if (updateStatus.value == UpdateUiState.Checking) return
        if (updateStatus.value is UpdateUiState.Downloading) return
        val now = System.currentTimeMillis()
        if (now - lastManualUpdateCheckAtMillis < MANUAL_UPDATE_CHECK_INTERVAL_MILLIS) {
            val previous = updateStatus.value
            updateStatus.value = UpdateUiState.Checking
            viewModelScope.launch {
                delay(CACHED_CHECK_INDICATOR_MILLIS)
                if (updateStatus.value == UpdateUiState.Checking) {
                    updateStatus.value = previous
                }
            }
            return
        }
        lastManualUpdateCheckAtMillis = now
        updateStatus.value = UpdateUiState.Checking
        viewModelScope.launch {
            updateStatus.value = try {
                when (val result = withContext(Dispatchers.IO) {
                    graph.updateChecker.check(BuildConfig.VERSION_NAME)
                }) {
                    UpdateCheckResult.NotConfigured -> UpdateUiState.NotConfigured
                    is UpdateCheckResult.UpToDate -> UpdateUiState.UpToDate(result.latestVersion)
                    is UpdateCheckResult.Available -> UpdateUiState.Available(result.update)
                }
            } catch (error: Exception) {
                UpdateUiState.Error(error.displayMessage())
            }
        }
    }

    fun downloadAndInstallUpdate(update: AvailableUpdate) {
        if (updateStatus.value is UpdateUiState.Downloading) return
        updateStatus.value = UpdateUiState.Downloading(update)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    graph.updateManager.prepare(update)
                }
                updateStatus.value = UpdateUiState.ReadyToInstall(update)
                graph.updateManager.launchInstaller()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                updateStatus.value = UpdateUiState.Error(
                    message = error.displayMessage(),
                    update = update,
                )
            }
        }
    }

    fun openPreparedUpdateInstaller() {
        graph.updateManager.launchInstaller()
    }

    fun refreshHistory() {
        viewModelScope.launch {
            refreshHistoryNow()
            requestDescriptionBackfill()
        }
    }

    fun loadMoreHistory() {
        if (loadingHistory.value) return
        if (historySearchQuery.value.isNotBlank()) {
            if (historySearchLimit.value >= MAX_HISTORY_SEARCH_RESULTS) return
            loadingHistory.value = true
            historySearchLimit.value =
                (historySearchLimit.value + HISTORY_SEARCH_PAGE_SIZE)
                    .coerceAtMost(MAX_HISTORY_SEARCH_RESULTS)
            refreshHistorySearch(markLoading = true)
            return
        }
        loadingHistory.value = true
        historyLoadJob = viewModelScope.launch {
            try {
                historyLimit.value += HISTORY_PAGE_SIZE
                if (favoritesOnly.value) return@launch
                val settings = graph.preferences.current()
                val activeHistoryFilters = settings.historyFilters
                    .intersect(settings.globalHistoryTypes)
                if (activeHistoryFilters.isEmpty()) {
                    historyEndReached.value = true
                    return@launch
                }
                val availableCount = history.value.count {
                    it.isInWindow(settings.historyWindowDays) &&
                        it.creatorId in settings.selectedCreatorIds &&
                        settings.isHistoryEnabledFor(it.creatorId, it.kind) &&
                        it.matches(activeHistoryFilters)
                }
                if (
                    availableCount <= historyLimit.value + HISTORY_PREFETCH_DISTANCE &&
                    !historyEndReached.value
                ) {
                    val result = graph.historyBackfill.loadRange(settings) {
                        refreshHistoryNow()
                    }
                    if (result.error == null) {
                        historyLoadError.value = null
                        historyEndReached.value = result.exhausted
                    } else {
                        historyLoadError.value =
                            "Nie udało się pobrać dalszej historii: ${result.error}"
                    }
                    refreshHistoryNow()
                    requestDescriptionBackfill()
                    avatars.value = graph.database.getCreatorAvatars().normalizedAvatarUrls()
                }
            } finally {
                loadingHistory.value = false
            }
        }
    }

    /**
     * Opisy mają własny, nieblokujący przebieg. Dzięki temu zakończenie
     * stronicowania Historii nie odbiera im wyzwalacza, a równoległy sync i UI
     * nie uruchamiają dwóch partii sieciowych jednocześnie.
     */
    private fun requestDescriptionBackfill() {
        if (descriptionBackfillJob?.isActive == true) return
        descriptionBackfillJob = viewModelScope.launch {
            try {
                var batches = 0
                var totalSaved = 0
                var savedInBatch: Int
                do {
                    savedInBatch = graph.descriptionBackfill.enrichExistingHistory(
                        settings = graph.preferences.current(),
                        maxItems = DescriptionBackfillLoader.FOREGROUND_BATCH_SIZE,
                    )
                    batches += 1
                    totalSaved += savedInBatch
                    if (savedInBatch > 0) {
                        // Odświeżamy wspólne modele Historii i Powiadomień po każdej
                        // partii, aby znaczniki 📓 pojawiały się stopniowo.
                        refreshHistoryNow()
                        delay(DESCRIPTION_BATCH_YIELD_MILLIS)
                    }
                } while (
                    savedInBatch > 0 &&
                    batches < MAX_FOREGROUND_DESCRIPTION_BATCHES
                )
                DiagnosticLogStore.event(
                    category = DiagnosticCategory.HISTORY,
                    level = DiagnosticLevel.INFO,
                    name = "DESCRIPTION_FOREGROUND_DRAIN",
                    fields = mapOf(
                        "batches" to batches,
                        "saved" to totalSaved,
                        "batchSize" to DescriptionBackfillLoader.FOREGROUND_BATCH_SIZE,
                        "stoppedAtSafetyLimit" to (
                            savedInBatch > 0 &&
                                batches >= MAX_FOREGROUND_DESCRIPTION_BATCHES
                            ),
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                DiagnosticLogStore.event(
                    category = DiagnosticCategory.HISTORY,
                    level = DiagnosticLevel.WARNING,
                    name = "DESCRIPTION_STAGE_FAILED",
                    reason = DiagnosticReasonCode.DESCRIPTION_STAGE_FAILED,
                    fields = mapOf("errorType" to error.javaClass.simpleName),
                )
                AppLog.warning(
                    "DescriptionBackfill",
                    "Nie udało się uruchomić uzupełniania opisów",
                    error,
                )
            }
        }
    }

    fun retryHistoryLoading() {
        viewModelScope.launch {
            graph.historyBackfill.reset()
            historyLoadError.value = null
            historyEndReached.value = false
            loadMoreHistory()
        }
    }

    fun clearHistoryAndBaselines() {
        viewModelScope.launch {
            var restoreSchedule = false
            try {
                resetHistoryPaging()
                syncJob?.cancelAndJoin()
                syncJob = null
                restoreSchedule = true
                graph.scheduler.cancelScheduledAndWait()
                graph.syncEngine.runExclusiveMaintenance {
                    graph.notifications.cancelAll()
                    withContext(Dispatchers.IO) {
                        graph.database.clearHistory()
                    }
                    JxlImageCache.clear(getApplication())
                }
                history.value = emptyList()
                notificationInbox.value = emptyList()
                actionMessage.value =
                    "Wyczyszczono historię. Następna synchronizacja utworzy nowy stan początkowy."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                actionMessage.value =
                    "Nie udało się bezpiecznie wyczyścić danych: " +
                        error.displayMessage()
            } finally {
                if (restoreSchedule) {
                    graph.scheduler.schedule(graph.preferences.current())
                }
            }
        }
    }

    private suspend fun refreshHistoryNow() {
        val settings = graph.preferences.current()
        val expiredCreatorIds = expiredDeselectedCreatorIds(
            deselectedAtMillis = settings.deselectedCreatorAtMillis,
            selectedCreatorIds = settings.selectedCreatorIds,
            nowMillis = System.currentTimeMillis(),
        )
        val refreshed = withContext(Dispatchers.IO) {
            graph.database.pruneExpiredData()
            if (expiredCreatorIds.isNotEmpty()) {
                graph.database.deleteHistoryForCreators(expiredCreatorIds)
            }
            graph.database.recentHistory(days = MAX_HISTORY_DAYS, limit = 10_000) to
                graph.database.recentNotificationInbox(limit = 2_000)
        }
        history.value = refreshed.first
        notificationInbox.value = refreshed.second
        refreshHistorySearch()
        graph.preferences.removeDeselectionRecords(expiredCreatorIds)
    }

    private fun refreshHistorySearch(
        debounce: Boolean = false,
        markLoading: Boolean = false,
    ) {
        historySearchJob?.cancel()
        val rawQuery = historySearchQuery.value.trim()
        if (rawQuery.isBlank()) {
            historySearchResults.value = emptyList()
            if (markLoading) loadingHistory.value = false
            return
        }
        historySearchJob = viewModelScope.launch {
            try {
                if (debounce) delay(HISTORY_SEARCH_DEBOUNCE_MILLIS)
                val settings = graph.preferences.current()
                val kinds = settings.historyFilters
                    .intersect(settings.globalHistoryTypes)
                    .flatMapTo(mutableSetOf(), HistoryFilter::videoKinds)
                val visibleLimit = historySearchLimit.value
                val searchResult = withContext(Dispatchers.IO) {
                    graph.database.searchHistory(
                        query = rawQuery,
                        selectedCreatorIds = settings.selectedCreatorIds,
                        kinds = kinds,
                        // Wyszukiwanie obejmuje całą historię zachowaną lokalnie
                        // (maksymalnie 60 dni), a nie tylko aktualny filtr czasu UI.
                        cutoffMillis = 0L,
                        favoritesOnly = favoritesOnly.value,
                        // Jeden dodatkowy rekord pozwala UI jednoznacznie ustalić,
                        // czy istnieje następna strona, bez ładowania całej tabeli.
                        limit = (historySearchLimit.value + 1)
                            .coerceAtMost(MAX_HISTORY_SEARCH_RESULTS + 1),
                    )
                }
                val results = searchResult.items
                historySearchResults.value = results
                recordLocalSearchDiagnostic(
                    query = rawQuery,
                    results = results
                        .filter { settings.isHistoryEnabledFor(it.creatorId, it.kind) }
                        .take(visibleLimit),
                    favoritesOnly = favoritesOnly.value,
                    kinds = kinds,
                    engine = searchResult.engine,
                    strategy = searchResult.strategy.name,
                )
            } finally {
                if (markLoading) loadingHistory.value = false
            }
        }
    }

    private fun recordLocalSearchDiagnostic(
        query: String,
        results: List<HistoryItem>,
        favoritesOnly: Boolean,
        kinds: Set<VideoKind>,
        engine: HistorySearchEngine,
        strategy: String,
    ) {
        if (!DiagnosticLogStore.isEnabled()) return
        DiagnosticLogStore.event(
            category = DiagnosticCategory.DATABASE,
            level = DiagnosticLevel.INFO,
            name = "LOCAL_SEARCH",
            fields = mapOf(
                "query" to query,
                "resultCount" to results.size,
                "engine" to engine.name,
                "strategy" to strategy,
                "favoritesOnly" to favoritesOnly,
                "kinds" to kinds.joinToString(",", transform = VideoKind::name),
            ),
        )
        results.forEachIndexed { index, item ->
            DiagnosticLogStore.event(
                category = DiagnosticCategory.DATABASE,
                level = DiagnosticLevel.INFO,
                name = "LOCAL_SEARCH_RESULT",
                fields = mapOf(
                    "rank" to index + 1,
                    "engine" to engine.name,
                    "creatorId" to item.creatorId,
                    "creator" to item.creatorName,
                    "title" to item.title,
                    "video" to diagnosticYouTubeVideoUrl(item.videoId),
                ),
            )
        }
    }

    fun searchOlderMaterials(creatorId: String, value: String) {
        val creator = graph.catalog.creators.firstOrNull { it.id == creatorId }
            ?: return
        val queryValue = value.trim().take(MAX_OLDER_SEARCH_QUERY_CHARS)
        olderMaterialSearch.value = OlderMaterialSearchUiState(
            creatorId = creatorId,
            query = queryValue,
            isLoading = true,
        )
        viewModelScope.launch {
            olderMaterialSearch.value = try {
                val results = graph.olderMaterialSearch.search(creator, queryValue)
                OlderMaterialSearchUiState(
                    creatorId = creatorId,
                    query = queryValue,
                    results = results,
                    error = if (results.isEmpty()) "Nie znaleziono materiałów." else null,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OlderMaterialSearchUiState(
                    creatorId = creatorId,
                    query = queryValue,
                    error = error.displayMessage(),
                )
            }
        }
    }

    fun confirmOlderMaterial(candidate: OlderMaterialCandidate) {
        val creator = graph.catalog.creators.firstOrNull { it.id == candidate.creatorId }
            ?: return
        val previous = olderMaterialSearch.value
        olderMaterialSearch.value = previous.copy(isLoading = true, error = null, confirmed = null)
        viewModelScope.launch {
            olderMaterialSearch.value = try {
                previous.copy(
                    isLoading = false,
                    confirmed = graph.olderMaterialSearch.confirm(creator, candidate),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                previous.copy(isLoading = false, error = error.displayMessage())
            }
        }
    }

    fun addConfirmedOlderMaterial() {
        val material = olderMaterialSearch.value.confirmed ?: return
        olderMaterialSearch.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val saved = graph.olderMaterialSearch.addConfirmedFavorite(material)
                if (!saved) error("Nie udało się zapisać materiału.")
                actionMessage.value = "Dodano materiał do Ulubionych."
                olderMaterialSearch.value = OlderMaterialSearchUiState()
                refreshHistoryNow()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                olderMaterialSearch.update {
                    it.copy(isLoading = false, error = error.displayMessage())
                }
            }
        }
    }

    fun clearOlderMaterialSearch() {
        olderMaterialSearch.value = OlderMaterialSearchUiState()
    }

    private suspend fun resetHistoryPaging(resetBackfill: Boolean = true) {
        historyLoadJob?.cancelAndJoin()
        historyLoadJob = null
        loadingHistory.value = true
        historyLimit.value = HISTORY_PAGE_SIZE
        historyEndReached.value = false
        historyLoadError.value = null
        try {
            if (resetBackfill) graph.historyBackfill.reset()
        } finally {
            loadingHistory.value = false
        }
    }

    private suspend fun refreshMissingAvatars(creatorIds: Set<String>) = coroutineScope {
        val missing = graph.catalog.creators.filter {
            it.id in creatorIds && avatars.value[it.id].isNullOrBlank()
        }
        val semaphore = Semaphore(MAX_PARALLEL_AVATARS)
        val resolved = missing.map { creator ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    try {
                        creator.id to graph.resolver.resolveCreatorAvatar(creator)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        creator.id to null
                    }
                }
            }
        }.awaitAll().mapNotNull { (creatorId, avatar) ->
            avatar?.takeIf { it.isNotBlank() }?.let { creatorId to it }
        }.toMap()
        if (resolved.isNotEmpty()) {
            avatars.update { current -> current + resolved }
        }
    }

    private fun HistoryItem.matches(filters: Set<HistoryFilter>): Boolean = when (kind) {
        VideoKind.VIDEO -> HistoryFilter.VIDEOS in filters
        VideoKind.LIVE,
        VideoKind.UPCOMING,
        VideoKind.STREAM_ARCHIVE -> HistoryFilter.STREAMS in filters
        VideoKind.SHORT -> HistoryFilter.SHORTS in filters
        // Brak rozstrzygającego dowodu oznacza dalszą klasyfikację w tle,
        // a nie domyślne wrzucenie materiału do zakładki Filmy.
        VideoKind.UNKNOWN -> false
    }

    private fun HistoryItem.isInWindow(days: Int): Boolean =
        publishedAtMillis >= System.currentTimeMillis() - days.toLong() * DAY_MILLIS

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AppViewModel(application) as T
    }
}

private fun Throwable.displayMessage(): String =
    (message ?: javaClass.simpleName).take(MAX_VISIBLE_ERROR_CHARS)

private fun Map<String, String>.normalizedAvatarUrls(): Map<String, String> =
    mapNotNull { (creatorId, url) ->
        when {
            BundledAvatarStore.isBundledAvatarUrl(url) -> creatorId to url
            else -> normalizeYouTubeAvatarUrl(url)?.let { creatorId to it }
        }
    }.toMap()

private val MANUAL_UPDATE_CHECK_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(15)
private const val CACHED_CHECK_INDICATOR_MILLIS = 550L
private const val HISTORY_SEARCH_DEBOUNCE_MILLIS = 300L
private const val HISTORY_SEARCH_PAGE_SIZE = 40
private const val MAX_HISTORY_SEARCH_RESULTS = 100
private const val MAX_OLDER_SEARCH_QUERY_CHARS = 100

private fun HistoryFilter.videoKinds(): Set<VideoKind> = when (this) {
    HistoryFilter.VIDEOS -> setOf(VideoKind.VIDEO)
    HistoryFilter.STREAMS -> setOf(
        VideoKind.LIVE,
        VideoKind.UPCOMING,
        VideoKind.STREAM_ARCHIVE,
    )
    HistoryFilter.SHORTS -> setOf(VideoKind.SHORT)
}

internal fun expiredDeselectedCreatorIds(
    deselectedAtMillis: Map<String, Long>,
    selectedCreatorIds: Set<String>,
    nowMillis: Long,
    retentionMillis: Long = DESELECTED_HISTORY_RETENTION_MILLIS,
): Set<String> = deselectedAtMillis
    .filter { (creatorId, deselectedAt) ->
        creatorId !in selectedCreatorIds &&
            deselectedAt <= nowMillis - retentionMillis
    }
    .keys

internal fun shouldIncludeHistoryItem(
    favoritesOnly: Boolean,
    isFavorite: Boolean,
    searchActive: Boolean,
    publishedAtMillis: Long,
    cutoffMillis: Long,
): Boolean = when {
    favoritesOnly -> isFavorite
    searchActive -> true
    else -> publishedAtMillis >= cutoffMillis
}
