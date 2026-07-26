package pl.lewicowyt.notifier.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
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
import pl.lewicowyt.notifier.BuildConfig
import pl.lewicowyt.notifier.data.AppSettings
import pl.lewicowyt.notifier.data.BackgroundMode
import pl.lewicowyt.notifier.data.ThemeMode
import pl.lewicowyt.notifier.images.JxlImageCache
import pl.lewicowyt.notifier.model.Creator
import pl.lewicowyt.notifier.model.HistoryFilter
import pl.lewicowyt.notifier.model.HistoryItem
import pl.lewicowyt.notifier.model.VideoKind
import pl.lewicowyt.notifier.network.YouTubeApiKeyValidation
import pl.lewicowyt.notifier.updates.AvailableUpdate
import pl.lewicowyt.notifier.updates.UpdateCheckResult

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
    data class Error(val message: String) : UpdateUiState
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
    private val avatars = MutableStateFlow(graph.database.getCreatorAvatars())
    private val refreshing = MutableStateFlow(false)
    private val actionMessage = MutableStateFlow<String?>(null)
    private val apiKeyStatus = MutableStateFlow<ApiKeyUiState>(ApiKeyUiState.Idle)
    private val updateStatus = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    private val historyLimit = MutableStateFlow(HISTORY_PAGE_SIZE)
    private val loadingHistory = MutableStateFlow(false)
    private val historyEndReached = MutableStateFlow(false)
    private val historyLoadError = MutableStateFlow<String?>(null)
    private val notificationNavigationRequest = MutableStateFlow(0L)
    private var historyLoadJob: Job? = null
    private var syncJob: Job? = null

    private data class StoredItems(
        val history: List<HistoryItem>,
        val notifications: List<HistoryItem>,
    )

    private data class ViewControls(
        val historyLimit: Int,
        val notificationNavigationRequest: Long,
        val apiKeyState: ApiKeyUiState,
    )

    private val storedItems = combine(history, notificationInbox) {
            currentHistory, currentNotifications ->
        StoredItems(currentHistory, currentNotifications)
    }

    private val viewControls = combine(
        historyLimit,
        notificationNavigationRequest,
        apiKeyStatus,
    ) { currentHistoryLimit, navigationRequest, currentApiKeyState ->
        ViewControls(currentHistoryLimit, navigationRequest, currentApiKeyState)
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
        val filteredHistory = contentState.history
            .asSequence()
            .filter { it.publishedAtMillis >= cutoff }
            .filter { it.creatorId in contentState.settings.selectedCreatorIds }
            .filter { it.matches(contentState.settings.historyFilters) }
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
            notifications = contentState.notifications,
            query = contentState.query,
            isRefreshing = isRefreshing,
            actionMessage = message,
            apiKeyState = contentState.apiKeyState,
            updateState = currentUpdateState,
            historyHasMore =
                filteredHistory.size > contentState.historyLimit ||
                    (!loadState.endReached && loadState.error == null),
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
        viewModelScope.launch { graph.scheduler.ensureScheduled() }
    }

    fun setQuery(value: String) {
        query.value = value.take(MAX_SEARCH_QUERY_CHARS)
    }

    fun setCreatorSelected(creatorId: String, selected: Boolean) {
        viewModelScope.launch {
            graph.preferences.setCreatorSelected(creatorId, selected)
            graph.scheduler.schedule(graph.preferences.current())
            resetHistoryPaging()
            if (selected) refreshMissingAvatars(setOf(creatorId))
        }
    }

    fun setAllCreatorsSelected(selected: Boolean) {
        viewModelScope.launch {
            graph.preferences.setCreatorsSelected(graph.catalog.creators.map { it.id }, selected)
            graph.scheduler.schedule(graph.preferences.current())
            resetHistoryPaging()
            if (selected) {
                refreshMissingAvatars(graph.catalog.creators.mapTo(mutableSetOf()) { it.id })
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
            resetHistoryPaging()
        }
    }

    fun setAllowMobileData(value: Boolean) {
        viewModelScope.launch {
            graph.preferences.setAllowMobileData(value)
            graph.scheduler.schedule(graph.preferences.current())
        }
    }

    fun setBatteryNotLow(value: Boolean) {
        viewModelScope.launch {
            graph.preferences.setRequireBatteryNotLow(value)
            graph.scheduler.schedule(graph.preferences.current())
        }
    }

    fun setBackgroundMode(value: BackgroundMode) {
        viewModelScope.launch {
            graph.preferences.setBackgroundMode(value)
            graph.scheduler.schedule(graph.preferences.current())
        }
    }

    fun refreshBackgroundSchedule() {
        viewModelScope.launch { graph.scheduler.ensureScheduled() }
    }

    fun setThemeMode(value: ThemeMode) {
        viewModelScope.launch { graph.preferences.setThemeMode(value) }
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

    fun syncNow() {
        if (refreshing.value) return
        refreshing.value = true
        syncJob = viewModelScope.launch {
            actionMessage.value = null
            try {
                val outcome = graph.syncEngine.sync()
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
                avatars.value = graph.database.getCreatorAvatars()
            } catch (error: Exception) {
                actionMessage.value =
                    "Synchronizacja nie powiodła się: " +
                        error.displayMessage()
            } finally {
                refreshing.value = false
            }
        }
    }

    fun checkForUpdates() {
        if (updateStatus.value == UpdateUiState.Checking) return
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

    fun refreshHistory() {
        viewModelScope.launch { refreshHistoryNow() }
    }

    fun loadMoreHistory() {
        if (loadingHistory.value) return
        loadingHistory.value = true
        historyLoadJob = viewModelScope.launch {
            try {
                historyLimit.value += HISTORY_PAGE_SIZE
                val settings = graph.preferences.current()
                val availableCount = history.value.count {
                    it.isInWindow(settings.historyWindowDays) &&
                        it.creatorId in settings.selectedCreatorIds &&
                        it.matches(settings.historyFilters)
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
                    avatars.value = graph.database.getCreatorAvatars()
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

    private suspend fun resetHistoryPaging() {
        historyLoadJob?.cancelAndJoin()
        historyLoadJob = null
        loadingHistory.value = true
        historyLimit.value = HISTORY_PAGE_SIZE
        historyEndReached.value = false
        historyLoadError.value = null
        try {
            graph.historyBackfill.reset()
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
        VideoKind.VIDEO, VideoKind.UNKNOWN -> HistoryFilter.VIDEOS in filters
        VideoKind.LIVE,
        VideoKind.UPCOMING,
        VideoKind.STREAM_ARCHIVE -> HistoryFilter.STREAMS in filters
        VideoKind.SHORT -> HistoryFilter.SHORTS in filters
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
