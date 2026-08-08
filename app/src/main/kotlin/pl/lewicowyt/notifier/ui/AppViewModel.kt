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
import pl.lewicowyt.notifier.data.ThemeMode
import pl.lewicowyt.notifier.data.YouTubeLinkTarget
import pl.lewicowyt.notifier.data.hasEnabledContentForSelectedCreators
import pl.lewicowyt.notifier.data.isHistoryEnabledFor
import pl.lewicowyt.notifier.data.isNotificationEnabledFor
import pl.lewicowyt.notifier.images.JxlImageCache
import pl.lewicowyt.notifier.diagnostics.DiagnosticSyncRun
import pl.lewicowyt.notifier.diagnostics.DiagnosticSyncTrigger
import pl.lewicowyt.notifier.model.Creator
import pl.lewicowyt.notifier.model.HistoryFilter
import pl.lewicowyt.notifier.model.HistoryItem
import pl.lewicowyt.notifier.model.VideoKind
import pl.lewicowyt.notifier.network.YouTubeApiKeyValidation
import pl.lewicowyt.notifier.network.normalizeYouTubeAvatarUrl
import pl.lewicowyt.notifier.images.BundledAvatarStore
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
    val allCreatorCount: Int = 0,
    val selectedCreatorIds: Set<String> = emptySet(),
    val creatorAvatars: Map<String, String> = emptyMap(),
    val settings: AppSettings = AppSettings(),
    val history: List<HistoryItem> = emptyList(),
    val notifications: List<HistoryItem> = emptyList(),
    val query: String = "",
    val isRefreshing: Boolean = false,
    val actionMessage: String? = null,
    val apiKeyState: ApiKeyUiState = ApiKeyUiState.Idle,
    val updateState: UpdateUiState = UpdateUiState.Idle,
    val favoritesOnly: Boolean = false,
    val historyHasMore: Boolean = true,
    val isLoadingHistory: Boolean = false,
    val historyLoadError: String? = null,
    val notificationNavigationRequest: Long = 0L,
)

private data class UiContent(
    val settings: AppSettings,
    val query: String,
    val history: List<HistoryItem>,
    val notifications: List<HistoryItem>,
    val avatars: Map<String, String>,
    val historyLimit: Int,
    val notificationNavigationRequest: Long,
    val apiKeyState: ApiKeyUiState,
    val favoritesOnly: Boolean,
)

private data class HistoryLoadState(
    val isLoading: Boolean,
    val endReached: Boolean,
    val error: String?,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = AppGraph.apply { initialize(application) }
    private val query = MutableStateFlow("")
    private val history = MutableStateFlow<List<HistoryItem>>(emptyList())
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
    private var syncJob: Job? = null
    private var initialSyncCheckRunning = false
    private var lastManualUpdateCheckAtMillis = 0L

    private data class StoredItems(
        val history: List<HistoryItem>,
        val notifications: List<HistoryItem>,
    )

    private data class ViewControls(
        val historyLimit: Int,
        val notificationNavigationRequest: Long,
        val apiKeyState: ApiKeyUiState,
        val favoritesOnly: Boolean,
    )

    private val storedItems = combine(history, notificationInbox) {
            currentHistory, currentNotifications ->
        StoredItems(currentHistory, currentNotifications)
    }

    private val viewControls = combine(
        historyLimit,
        notificationNavigationRequest,
        apiKeyStatus,
        favoritesOnly,
    ) { currentHistoryLimit, navigationRequest, currentApiKeyState, currentFavoritesOnly ->
        ViewControls(
            currentHistoryLimit,
            navigationRequest,
            currentApiKeyState,
            currentFavoritesOnly,
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
            currentItems.notifications,
            currentAvatars,
            controls.historyLimit,
            controls.notificationNavigationRequest,
            controls.apiKeyState,
            controls.favoritesOnly,
        )
    }

    private val historyLoadState = combine(
        loadingHistory,
        historyEndReached,
        historyLoadError,
    ) { isLoading, endReached, error ->
        HistoryLoadState(isLoading, endReached, error)
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
        val filteredHistory = contentState.history
            .asSequence()
            .filter {
                if (contentState.favoritesOnly) it.isFavorite
                else it.publishedAtMillis >= cutoff
            }
            .filter { it.creatorId in contentState.settings.selectedCreatorIds }
            .filter {
                contentState.settings.isHistoryEnabledFor(it.creatorId, it.kind)
            }
            .filter { it.matches(activeHistoryFilters) }
            .sortedWith(
                compareByDescending<HistoryItem> { it.publishedAtMillis }
                    .thenByDescending { it.detectedAtMillis },
            )
            .toList()
        AppUiState(
            creators = graph.catalog.creators.filter {
                normalized.isBlank() || it.name.lowercase(POLISH_LOCALE).contains(normalized)
            },
            allCreatorCount = graph.catalog.creators.size,
            selectedCreatorIds = contentState.settings.selectedCreatorIds,
            creatorAvatars = contentState.avatars,
            settings = contentState.settings,
            history = filteredHistory.take(contentState.historyLimit),
            notifications = contentState.notifications.filter {
                it.creatorId in contentState.settings.selectedCreatorIds &&
                    contentState.settings.isNotificationEnabledFor(it.creatorId, it.kind)
            },
            query = contentState.query,
            isRefreshing = isRefreshing,
            actionMessage = message,
            apiKeyState = contentState.apiKeyState,
            updateState = currentUpdateState,
            favoritesOnly = contentState.favoritesOnly,
            historyHasMore =
                activeHistoryFilters.isNotEmpty() &&
                    if (contentState.favoritesOnly) {
                        filteredHistory.size > contentState.historyLimit
                    } else {
                        filteredHistory.size > contentState.historyLimit ||
                            (!loadState.endReached && loadState.error == null)
                    },
            isLoadingHistory = loadState.isLoading,
            historyLoadError = loadState.error,
            notificationNavigationRequest = contentState.notificationNavigationRequest,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppUiState(
            creators = graph.catalog.creators,
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

    fun setFavoritesOnly(enabled: Boolean) {
        favoritesOnly.value = enabled
        historyLimit.value = HISTORY_PAGE_SIZE
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
        }
    }

    fun setHistoryFilter(filter: HistoryFilter, enabled: Boolean) {
        viewModelScope.launch {
            graph.preferences.setHistoryFilter(filter, enabled)
            // Wszystkie trzy karty kanału są pobierane niezależnie od filtra,
            // więc zmiana widoku nie może kasować postępu sieciowego. Zerujemy
            // tylko stronicowanie ekranu; rozpoczęty backfill zostanie wznowiony.
            resetHistoryPaging(resetBackfill = false)
        }
    }

    fun setGlobalHistoryType(type: HistoryFilter, enabled: Boolean) {
        viewModelScope.launch {
            graph.preferences.setGlobalHistoryType(type, enabled)
            graph.scheduler.schedule(graph.preferences.current())
            resetHistoryPaging()
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
        viewModelScope.launch { refreshHistoryNow() }
    }

    fun loadMoreHistory() {
        if (loadingHistory.value) return
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
                    avatars.value = graph.database.getCreatorAvatars().normalizedAvatarUrls()
                }
            } finally {
                loadingHistory.value = false
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
        graph.preferences.removeDeselectionRecords(expiredCreatorIds)
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
