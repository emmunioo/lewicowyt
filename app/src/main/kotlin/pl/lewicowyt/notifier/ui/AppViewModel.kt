package pl.lewicowyt.notifier.ui

import android.app.Application
import android.os.SystemClock
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import pl.lewicowyt.notifier.audio.CommunityMediaCache
import pl.lewicowyt.notifier.AppGraph
import pl.lewicowyt.notifier.AppLog
import pl.lewicowyt.notifier.BuildConfig
import pl.lewicowyt.notifier.community.AddServerOutcome
import pl.lewicowyt.notifier.community.CommunityConsentState
import pl.lewicowyt.notifier.community.CommunityContentEvents
import pl.lewicowyt.notifier.community.AppTerms
import pl.lewicowyt.notifier.community.CommunityDefaults
import pl.lewicowyt.notifier.community.CommunityContentFilter
import pl.lewicowyt.notifier.community.CommunityLinkPlatform
import pl.lewicowyt.notifier.community.CommunityLinkPlatformStore
import pl.lewicowyt.notifier.community.CommunityPrimaryHealth
import pl.lewicowyt.notifier.community.CommunityPrimaryHealthEvaluator
import pl.lewicowyt.notifier.community.CommunityPrimaryHealthStore
import pl.lewicowyt.notifier.community.PrimaryHealthDifference
import pl.lewicowyt.notifier.community.PrimaryHealthKind
import pl.lewicowyt.notifier.community.RssVideo
import pl.lewicowyt.notifier.community.CommunityServerState
import pl.lewicowyt.notifier.community.CommunityTermsPrompt
import pl.lewicowyt.notifier.community.CommunityUiState
import pl.lewicowyt.notifier.community.push.CommunityPushManager
import pl.lewicowyt.notifier.community.push.EmbeddedPush
import pl.lewicowyt.notifier.community.push.NonPrivateTorSettings
import pl.lewicowyt.notifier.network.PrivacySettings
import pl.lewicowyt.notifier.community.push.PushSettings
import pl.lewicowyt.notifier.community.push.CommunityPushWorker
import pl.lewicowyt.notifier.community.push.CommunityTorClient
import pl.lewicowyt.notifier.community.push.EmbeddedTor
import pl.lewicowyt.notifier.community.normalizeCommunityEndpoint
import pl.lewicowyt.notifier.data.AppSettings
import pl.lewicowyt.notifier.data.HistorySearchEngine
import pl.lewicowyt.notifier.data.ThemeMode
import pl.lewicowyt.notifier.i18n.Pronoun
import pl.lewicowyt.notifier.i18n.Translations
import pl.lewicowyt.notifier.data.YouTubeLinkTarget
import pl.lewicowyt.notifier.data.hasEnabledContentForSelectedCreators
import pl.lewicowyt.notifier.data.isHistoryEnabledFor
import pl.lewicowyt.notifier.data.isNotificationEnabledFor
import pl.lewicowyt.notifier.images.CommunityImageExporter
import pl.lewicowyt.notifier.images.CommunityImageLoader
import pl.lewicowyt.notifier.images.JxlImageCache
import pl.lewicowyt.notifier.images.MediaImageSaver
import pl.lewicowyt.notifier.diagnostics.DiagnosticCategory
import pl.lewicowyt.notifier.diagnostics.DiagnosticLevel
import pl.lewicowyt.notifier.diagnostics.DiagnosticLogStore
import pl.lewicowyt.notifier.diagnostics.DiagnosticReasonCode
import pl.lewicowyt.notifier.diagnostics.DiagnosticSyncRun
import pl.lewicowyt.notifier.diagnostics.DiagnosticSyncTrigger
import pl.lewicowyt.notifier.diagnostics.diagnosticYouTubeVideoUrl
import pl.lewicowyt.notifier.model.Creator
import pl.lewicowyt.notifier.model.SourceType
import pl.lewicowyt.notifier.network.ResolvedSource
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
import pl.lewicowyt.notifier.worker.ActiveHoursReleaseWorker
import pl.lewicowyt.notifier.worker.allowsSync
import pl.lewicowyt.notifier.worker.currentSyncNetworkAccess

private val POLISH_LOCALE: Locale = Locale.forLanguageTag("pl")
private const val MAX_HISTORY_DAYS = 60
private const val DAY_MILLIS = 24L * 60L * 60L * 1000L
private const val HISTORY_PAGE_SIZE = 20
private const val HISTORY_PREFETCH_DISTANCE = 5
// dolny limit puli historii w pamięci (okno najnowszych, rośnie przy
// przewijaniu). zamiast dawnych sztywnych 10 000 (#5)
private const val HISTORY_POOL_MIN = 50
private const val DESELECTED_HISTORY_RETENTION_MILLIS = 7L * DAY_MILLIS
private const val MAX_SEARCH_QUERY_CHARS = 200
private const val MAX_VISIBLE_SYNC_ERRORS = 5
private const val MAX_VISIBLE_ERROR_CHARS = 500
private const val MAX_PARALLEL_AVATARS = 6
private const val MAX_FOREGROUND_DESCRIPTION_BATCHES = 16
private const val DESCRIPTION_BATCH_YIELD_MILLIS = 100L
// fallback: gdy push nie dojdzie, dociągamy świeże manifesty co 2 h (poza tym user odświeża ręcznie)
private const val COMMUNITY_AUTO_REFRESH_MILLIS = 2L * 60L * 60L * 1000L
// po tylu kolejnych rozbieżnych sprawdzeniach serwera głównego ostrzegamy usera (baner + ciche powiadomienie)
private const val PRIMARY_HEALTH_FAIL_THRESHOLD = 3
// ile filmów per kanał bierzemy z RSS do porównania (RSS i tak zwraca ~15 ostatnich)
private const val PRIMARY_HEALTH_MAX_CREATORS = 60
// J-11e: RSS do weryfikacji serwera głównego — ile kanałów naraz i łączny limit czasu całego sprawdzenia
private const val PRIMARY_HEALTH_RSS_PARALLELISM = 6
private const val PRIMARY_HEALTH_RSS_TIMEOUT_MILLIS = 2L * 60L * 1000L
private val PRIMARY_HEALTH_CHANNEL_ID = Regex("^UC[A-Za-z0-9_-]{22}$")
private val PRIMARY_HEALTH_CHANNEL_ID_IN_URL = Regex("UC[A-Za-z0-9_-]{22}")

/** krótkie streszczenie różnic do treści cichego powiadomienia (szczegóły user widzi w banerze). */
private fun primaryHealthSummary(differences: List<PrimaryHealthDifference>): String {
    if (differences.isEmpty()) return "brak różnic"
    return differences.joinToString("; ") { d ->
        when (d.kind) {
            PrimaryHealthKind.SERVER_UNREACHABLE -> "brak odpowiedzi"
            PrimaryHealthKind.MISSING_CREATORS -> "brakujący twórcy"
            PrimaryHealthKind.EXTRA_CREATORS -> "dorzuceni twórcy"
            PrimaryHealthKind.DUPLICATE_CREATORS -> "zduplikowani twórcy"
            PrimaryHealthKind.MISSING_VIDEOS -> "brakujące filmy"
            PrimaryHealthKind.EXTRA_VIDEOS -> "filmy spoza RSS"
            PrimaryHealthKind.DUPLICATE_VIDEOS -> "zduplikowane filmy"
            PrimaryHealthKind.HIGH_LATENCY -> "wysokie opóźnienia"
        }
    }
}

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
    // videoId streamów wykrytych jako prywatne — badge 🔒, brak transkrypcji, poza wyszukiwaniem
    val privateVideoIds: Set<String> = emptySet(),
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
    // połączona zakładka Historia: widok „Wszystkie” albo „🔔 Powiadomienia”
    val historyView: HistoryView = HistoryView.ALL,
    // powiadomienia po filtrach widoku (wyszukiwarka, typy, Ulubione) — lista dla widoku 🔔
    val notificationsInView: List<HistoryItem> = emptyList(),
    // liczba powiadomień nowszych niż ostatnie otwarcie widoku 🔔 (licznik na przełączniku)
    val unseenNotificationCount: Int = 0,
    // granica separatora „Nowe od ostatniej wizyty” (stan sprzed bieżącego otwarcia widoku 🔔)
    val notificationsSeparatorAtMillis: Long = 0L,
)

/** widok połączonej zakładki Historia. */
enum class HistoryView { ALL, NOTIFICATIONS }

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
    // odczyt startowy poszedł do init{} (Dispatchers.IO) – w konstruktorze VM
    // blokował main thread na każdym zimnym starcie. UI rusza z pustą mapą,
    // awatary dochodzą async (jak leniwe produceState gdzie indziej)
    private val avatars = MutableStateFlow<Map<String, String>>(emptyMap())
    private val refreshing = MutableStateFlow(false)
    private val privateVideoIds = MutableStateFlow<Set<String>>(emptySet())
    private var privacyCheckRunning = false
    private val actionMessage = MutableStateFlow<String?>(null)
    private val apiKeyStatus = MutableStateFlow<ApiKeyUiState>(ApiKeyUiState.Idle)
    private val updateStatus = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    private val historyLimit = MutableStateFlow(HISTORY_PAGE_SIZE)
    private val loadingHistory = MutableStateFlow(false)
    private val historyEndReached = MutableStateFlow(false)
    private val historyLoadError = MutableStateFlow<String?>(null)
    private val notificationNavigationRequest = MutableStateFlow(0L)
    private val favoritesOnly = MutableStateFlow(false)
    private val historyView = MutableStateFlow(HistoryView.ALL)
    // „ostatnio widziane” powiadomienia trzymamy lokalnie (wygoda UI, nie dane synchronizowane)
    private val uiPrefs = application.getSharedPreferences(UI_PREFS, android.content.Context.MODE_PRIVATE)
    private val notificationsSeenAt = MutableStateFlow(uiPrefs.getLong(KEY_NOTIFICATIONS_SEEN_AT, 0L))
    private val notificationsSeparatorAt = MutableStateFlow(notificationsSeenAt.value)
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
        // zaimki użytkownika wpływają na formy rodzajowe w tr() — utrzymujemy je zsynchronizowane z ustawieniami
        Translations.pronoun = contentState.settings.pronoun
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
    }
        .combine(privateVideoIds) { ui, priv ->
            ui.copy(
                privateVideoIds = priv,
                // z wyszukiwań usuwamy prywatne streamy; w zwykłej historii zostają (z badge 🔒)
                history = if (ui.historySearchQuery.isNotBlank()) {
                    ui.history.filterNot { it.videoId in priv }
                } else {
                    ui.history
                },
            )
        }
        .combine(
            combine(historyView, notificationsSeenAt, notificationsSeparatorAt) { view, seenAt, separatorAt ->
                Triple(view, seenAt, separatorAt)
            },
        ) { ui, (view, seenAt, separatorAt) ->
            ui.copy(
                historyView = view,
                notificationsInView = notificationsForView(ui),
                unseenNotificationCount = ui.notifications.count { it.detectedAtMillis > seenAt },
                notificationsSeparatorAtMillis = separatorAt,
            )
        }
        // ciężki potok (filtr + sort całej historii, lowercase nazw twórców) leci
        // na puli Default, nie na UI (viewModelScope = Main.immediate). main dostaje
        // gotowy AppUiState – mniej janku przy pisaniu i scrollu (#1)
        .flowOn(Dispatchers.Default)
        .stateIn(
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
        viewModelScope.launch(Dispatchers.IO) {
            val storedAvatarSnapshot = graph.database.getCreatorAvatars()
            val normalized = storedAvatarSnapshot.normalizedAvatarUrls()
            avatars.value = normalized
            val legacyAvatarIdsToRefresh = storedAvatarSnapshot.keys - normalized.keys
            if (legacyAvatarIdsToRefresh.isNotEmpty()) {
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
            resetHistoryPaging()
            if (selected) refreshMissingAvatars(setOf(creatorId))
            if (selected) syncOnFirstLaunchIfNeeded()
        }
    }

    fun setAllCreatorsSelected(selected: Boolean) {
        viewModelScope.launch {
            graph.preferences.setCreatorsSelected(graph.catalog.creators.map { it.id }, selected)
            resetHistoryPaging()
            if (selected) {
                refreshMissingAvatars(graph.catalog.creators.mapTo(mutableSetOf()) { it.id })
                syncOnFirstLaunchIfNeeded()
            }
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
            // wszystkie trzy karty i tak pobieramy niezależnie od filtra, więc
            // zmiana widoku nie kasuje postępu sieci – zerujemy tylko stronicowanie
            // ekranu, rozpoczęty backfill wznowi się
            resetHistoryPaging(resetBackfill = false)
            refreshHistorySearch()
        }
    }

    fun setGlobalHistoryType(type: HistoryFilter, enabled: Boolean) {
        viewModelScope.launch {
            graph.preferences.setGlobalHistoryType(type, enabled)
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
        }
    }

    /**
     * Przełącznik „Nieprywatny Tor". Pozwala serwerom .onion, które tego WYMAGAJĄ (push.requiresNonPrivateTor),
     * używać FCM — Tor służy wtedy tylko do omijania CGNAT / ukrycia IP serwera, a NIE do anonimowości
     * subskrybenta (Google widzi, że urządzenie subskrybuje serwer). Zmiana od razu przelicza rejestrację push:
     * włączenie rejestruje FCM dla takich serwerów, wyłączenie zdejmuje je (spadają na UnifiedPush/pull).
     * Ma znaczenie tylko przy włączonym awaryjnym FCM (domyślnie wyłączony — wtedy żaden serwer nie używa Google).
     */
    fun setNonPrivateTorEnabled(enabled: Boolean) {
        NonPrivateTorSettings.setEnabled(getApplication(), enabled)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { syncCommunityPush() } // od razu zastosuj flagę do serwerów społeczności
        }
    }

    /** Czy tryb „Nieprywatny Tor" jest włączony — do stanu przełącznika w ustawieniach. */
    fun isNonPrivateTorEnabled(): Boolean = NonPrivateTorSettings.isEnabled(getApplication())

    /** T-09: tryb ścisły DNS — awaria szyfrowanego DNS nie przełącza na jawny DNS systemu (domyślnie wyłączony). */
    fun isStrictDnsEnabled(): Boolean = PrivacySettings.strictDns(getApplication())

    fun setStrictDnsEnabled(enabled: Boolean) = PrivacySettings.setStrictDns(getApplication(), enabled)

    /** T-10: rejestracja i wyrejestrowanie push przez Tor także u serwerów w zwykłym internecie (domyślnie wyłączone). */
    fun isRegisterViaTorEnabled(): Boolean = PrivacySettings.registerViaTor(getApplication())

    fun setRegisterViaTorEnabled(enabled: Boolean) {
        PrivacySettings.setRegisterViaTor(getApplication(), enabled)
        viewModelScope.launch(Dispatchers.IO) { runCatching { syncCommunityPush() } }
    }

    // --- UnifiedPush: tryb (wbudowany/zewnętrzny), podtryb wbudowanego (instant/oszczędny), awaryjny FCM ---

    fun pushMode(): PushSettings.Mode = PushSettings.mode(getApplication())

    fun setPushMode(mode: PushSettings.Mode) {
        PushSettings.setMode(getApplication(), mode)
        viewModelScope.launch(Dispatchers.IO) { runCatching { syncCommunityPush() } }
    }

    fun embeddedMode(): PushSettings.Embedded = PushSettings.embedded(getApplication())

    fun setEmbeddedMode(mode: PushSettings.Embedded) {
        PushSettings.setEmbedded(getApplication(), mode)
        viewModelScope.launch(Dispatchers.IO) { runCatching { EmbeddedPush.applyRunState(getApplication()) } }
    }

    fun isFcmFallbackEnabled(): Boolean = PushSettings.fcmFallbackEnabled(getApplication())

    /** Awaryjny push przez Google (FCM) — domyślnie wyłączony; dotyczy tylko serwerów społeczności z `push.fcm`. */
    fun setFcmFallbackEnabled(enabled: Boolean) {
        val wasEnabled = PushSettings.fcmFallbackEnabled(getApplication())
        PushSettings.setFcmFallbackEnabled(getApplication(), enabled)
        viewModelScope.launch(Dispatchers.IO) {
            // A-48: wyłączenie = faktyczny koniec kontaktu z Google: wyrejestrowanie tokenów FCM u serwerów,
            // zdjęcie subskrypcji `feed` i skasowanie tokenów. Tylko przy przejściu ON → OFF (bez zbędnego
            // dzwonienia do Google, gdy FCM i tak był wyłączony).
            if (!enabled && wasEnabled) runCatching { CommunityPushManager.disableFcm(getApplication()) }
            runCatching { syncCommunityPush() }
        }
    }

    fun setActiveHoursEnabled(value: Boolean) {
        viewModelScope.launch {
            graph.preferences.setActiveHoursEnabled(value)
            // wyłączenie albo bycie w oknie → od razu flush wstrzymanych;
            // włączenie poza oknem → przekładamy na najbliższe otwarcie
            ActiveHoursReleaseWorker.schedule(graph.appContext, graph.preferences.current())
        }
    }

    fun setActiveHours(startMinutes: Int, endMinutes: Int) {
        viewModelScope.launch {
            graph.preferences.setActiveHours(startMinutes, endMinutes)
            ActiveHoursReleaseWorker.schedule(graph.appContext, graph.preferences.current())
        }
    }

    fun setAutomaticUpdatesEnabled(value: Boolean) {
        viewModelScope.launch {
            graph.preferences.setAutomaticUpdatesEnabled(value)
        }
    }

    fun setCommunityRecompressImages(value: Boolean) {
        viewModelScope.launch {
            graph.preferences.setCommunityRecompressImages(value)
        }
    }

    fun setCommunityMaxJxlKb(value: Int) {
        viewModelScope.launch { graph.preferences.setCommunityMaxJxlKb(value) }
    }

    fun setCommunityDownloadImages(value: Boolean) {
        viewModelScope.launch { graph.preferences.setCommunityDownloadImages(value) }
    }

    /** Q-06: jedno powiadomienie testowe na zegarek (false = powiadomienia aplikacji wyłączone w systemie). */
    fun sendWatchTestNotification(): Boolean = graph.notifications.notifyWatchTest()

    fun setCommunityDownloadNotificationImages(value: Boolean) {
        viewModelScope.launch { graph.preferences.setCommunityDownloadNotificationImages(value) }
    }

    fun setCommunityDownloadWebpAnimations(value: Boolean) {
        viewModelScope.launch { graph.preferences.setCommunityDownloadWebpAnimations(value) }
    }

    fun setCommunityTextRetentionDays(value: Int) {
        viewModelScope.launch {
            graph.preferences.setCommunityTextRetentionDays(value)
            pruneCommunityContentNow()
        }
    }

    fun setCommunityImageRetentionDays(value: Int) {
        viewModelScope.launch {
            graph.preferences.setCommunityImageRetentionDays(value)
            pruneCommunityContentNow()
        }
    }

    /** Q3: przełącznik „miniaturki YouTube: WebP (mniej CPU) vs JXL (mniejszy dysk, więcej CPU)". */
    fun setYoutubeThumbnailsAsJxl(value: Boolean) {
        viewModelScope.launch {
            graph.preferences.setYoutubeThumbnailsAsJxl(value)
            JxlImageCache.convertDownloadsToJxl = value // od razu stosujemy do kolejnych pobrań
        }
    }

    fun setCommunityMemeSaveFormat(value: CommunityImageExporter.Format) {
        viewModelScope.launch { graph.preferences.setCommunityMemeSaveFormat(value.name) }
    }

    fun setCommunityMemeSaveQuality(value: Int) {
        viewModelScope.launch { graph.preferences.setCommunityMemeSaveQuality(value) }
    }

    fun setCommunityMemeSaveEffort(value: Int) {
        viewModelScope.launch { graph.preferences.setCommunityMemeSaveEffort(value) }
    }

    /**
     * Zapis mema do galerii (długie przytrzymanie). Format/jakość/moc bierzemy z ustawień społeczności.
     * Cała robota na IO; wynik pokazujemy przez [communityMessage]. Na Androidzie <10 zapis wymaga
     * uprawnienia — warstwa UI upewnia się o nim przed wywołaniem.
     */
    fun saveMeme(url: String, baseName: String) {
        viewModelScope.launch {
            communityMessage.value = "Zapisywanie…"
            val settings = graph.preferences.current()
            val format = runCatching { CommunityImageExporter.Format.valueOf(settings.communityMemeSaveFormat) }
                .getOrDefault(CommunityImageExporter.Format.JXL) // A-38: ten sam domyślny co w preferencjach
            val encoded = CommunityImageExporter.export(
                getApplication(), url, format,
                settings.communityMemeSaveQuality, settings.communityMemeSaveEffort,
            )
            if (encoded == null) {
                communityMessage.value = "Nie udało się przygotować obrazu do zapisu."
                return@launch
            }
            communityMessage.value = when (
                val r = MediaImageSaver.save(getApplication(), encoded.bytes, baseName, encoded.extension, encoded.mime)
            ) {
                is MediaImageSaver.Result.Saved -> "Zapisano do Galerii: ${r.displayName}"
                is MediaImageSaver.Result.Failure -> "Nie udało się zapisać: ${r.reason}"
            }
        }
    }

    /** Postęp masowej konwersji zapisanych obrazów społeczności do JXL (null = nie trwa i nie zakończono). */
    data class JxlConversion(val done: Int, val total: Int, val running: Boolean)

    private val _jxlConversion = MutableStateFlow<JxlConversion?>(null)
    val communityJxlConversion: StateFlow<JxlConversion?> = _jxlConversion.asStateFlow()

    /**
     * Masowo konwertuje/skompresuje wszystkie zapisane w cache obrazy społeczności do JXL wg ustawień
     * „Kompresuj ciężkie obrazy" / „Limit rozmiaru JXL" (drabinka jakość → 75% → 50% rozdzielczości).
     * Postęp raportujemy przez [communityJxlConversion] (x/y + %).
     */
    fun convertCommunityImagesToJxl() {
        if (_jxlConversion.value?.running == true) return
        viewModelScope.launch {
            _jxlConversion.value = JxlConversion(0, 0, running = true)
            val converted = CommunityImageLoader.convertCachedToJxl(getApplication()) { done, total ->
                _jxlConversion.value = JxlConversion(done, total, running = true)
            }
            val total = _jxlConversion.value?.total ?: converted
            _jxlConversion.value = JxlConversion(converted, total.coerceAtLeast(converted), running = false)
            communityMessage.value = if (converted > 0) {
                "Przekonwertowano $converted zdjęć do JXL."
            } else {
                // E-04: pomijane są też animacje i pliki, dla których JXL nie byłby mniejszy
                "Nie przekonwertowano żadnego zdjęcia (już są w JXL, są animowane albo JXL nie byłby mniejszy)."
            }
        }
    }

    fun toggleCommunityFavorite(serverId: String, itemId: String, favorite: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                graph.communityRepository.setFavorite(serverId, itemId, favorite)
            }
            reloadCommunityServersFromStore()
        }
    }

    /** przycina magazyn treści wg aktualnych okien retencji (osobno tekst i obrazy). */
    private suspend fun pruneCommunityContentNow() {
        val settings = graph.preferences.current()
        withContext(Dispatchers.IO) {
            graph.communityRepository.pruneContent(
                textRetentionDays = settings.communityTextRetentionDays,
                imageRetentionDays = settings.communityImageRetentionDays,
            )
        }
        reloadCommunityServersFromStore()
    }


    fun setThemeMode(value: ThemeMode) {
        viewModelScope.launch { graph.preferences.setThemeMode(value) }
    }

    /** wybór zaimków (on/ona/ono) — zapis + natychmiastowe odświeżenie form rodzajowych w UI. */
    fun setPronoun(value: Pronoun) {
        Translations.pronoun = value
        viewModelScope.launch { graph.preferences.setPronoun(value) }
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

    // licznik żądań „pokaż zakładkę Społeczność" (np. dotknięcie powiadomienia odtwarzacza dźwięku)
    private val _communityNavigationRequest = MutableStateFlow(0L)
    val communityNavigationRequest: StateFlow<Long> = _communityNavigationRequest.asStateFlow()

    fun openCommunity() {
        _communityNavigationRequest.value += 1L
    }

    fun openNotifications() {
        setHistoryView(HistoryView.NOTIFICATIONS)
        notificationNavigationRequest.value += 1L
    }

    /**
     * przełącza widok połączonej zakładki Historia. Wejście w 🔔 zapamiętuje granicę separatora
     * („Nowe od ostatniej wizyty” = nowsze niż poprzednie otwarcie) i oznacza powiadomienia jako widziane.
     */
    fun setHistoryView(view: HistoryView) {
        if (view == HistoryView.NOTIFICATIONS && historyView.value != HistoryView.NOTIFICATIONS) {
            notificationsSeparatorAt.value = notificationsSeenAt.value
            markNotificationsSeen()
            refreshHistory() // świeżość „na już”, jak dawna zakładka Powiadomienia
        }
        historyView.value = view
    }

    /** przy wyjściu z widoku 🔔 / z zakładki: wszystko, co było widać, jest już widziane. */
    fun markNotificationsSeen() {
        val now = System.currentTimeMillis()
        notificationsSeenAt.value = now
        runCatching { uiPrefs.edit().putLong(KEY_NOTIFICATIONS_SEEN_AT, now).apply() }
    }

    fun syncNow() = syncNowWithTrigger(DiagnosticSyncTrigger.MANUAL)

    private fun syncNowWithTrigger(trigger: DiagnosticSyncTrigger) {
        if (refreshing.value) return
        refreshing.value = true
        syncJob = viewModelScope.launch {
            actionMessage.value = null
            // M-02: szanujemy przełącznik „Zezwól na dane komórkowe". Na sieci taryfowej bez zgody NIE
            // odświeżamy ręcznie (dotąd opcja była martwa — sync i tak szedł po danych komórkowych).
            val settings = graph.preferences.current()
            if (!currentSyncNetworkAccess(getApplication()).allowsSync(settings.allowMobileData)) {
                actionMessage.value = "Sieć komórkowa wyłączona w ustawieniach — odświeżanie pominięte. " +
                    "Połącz się z Wi-Fi albo włącz „Zezwól na dane komórkowe”."
                refreshing.value = false
                return@launch
            }
            val diagnosticRun = DiagnosticSyncRun.create(trigger)
            try {
                // 1.9: sync tylko zasila Historię/skrzynkę. powiadomienia systemowe
                // idą z serwera (budzik push), więc lokalny sync ich nie pokazuje – inaczej duplikaty
                val outcome = graph.syncEngine.sync(
                    deliverSystemNotifications = false,
                    // R-20: user patrzy na Historię — nie odkładaj tych wpisów do późniejszego zalewu
                    suppressPendingNotifications = true,
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
                avatars.value = withContext(Dispatchers.IO) { graph.database.getCreatorAvatars().normalizedAvatarUrls() }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                // R-21: anulowanie (np. zamknięcie ViewModelu) to nie błąd syncu — nie logujemy SYNC_ERROR
                // i nie wykonujemy reszty; przepuszczamy wyjątek, by korutyna anulowała się normalnie.
                throw cancelled
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
                    settings.hasEnabledContentForSelectedCreators()
                ) {
                    // sync 1.9 nie pokazuje powiadomień systemowych (robi to serwer),
                    // więc pierwszy start nie musi już sprawdzać DND – i dlatego
                    // znika uprawnienie ACCESS_NOTIFICATION_POLICY
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
                    "Baza: ${if (result.databaseOk) "OK" else "błąd"}; " +
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
            } catch (cancelled: CancellationException) {
                throw cancelled // B-90: zamknięcie ekranu to nie „błąd sprawdzania aktualizacji”
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
                    // B-21: backfill to najcięższy ruch aplikacji — ta sama zgoda na dane komórkowe co przy
                    // ręcznym odświeżaniu i opisach. Błąd zatrzymuje automatyczne ponawianie z LaunchedEffect.
                    if (!currentSyncNetworkAccess(getApplication()).allowsSync(settings.allowMobileData)) {
                        historyLoadError.value =
                            "Doczytywanie Historii wstrzymane — sieć komórkowa jest wyłączona w ustawieniach. " +
                                "Połącz się z Wi-Fi albo włącz „Zezwól na dane komórkowe”."
                        return@launch
                    }
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
                    avatars.value = withContext(Dispatchers.IO) {
                        graph.database.getCreatorAvatars().normalizedAvatarUrls()
                    }
                }
            } finally {
                loadingHistory.value = false
            }
        }
    }

    /**
     * opisy mają własny, nieblokujący przebieg – koniec stronicowania Historii
     * nie zabiera im wyzwalacza, a równoległy sync i UI nie odpalają dwóch partii
     * sieciowych naraz.
     */
    private fun requestDescriptionBackfill() {
        if (descriptionBackfillJob?.isActive == true) return
        descriptionBackfillJob = viewModelScope.launch {
            try {
                // M-02: pobieranie opisów to też ruch sieciowy — na sieci taryfowej bez zgody go pomijamy.
                val settings0 = graph.preferences.current()
                if (!currentSyncNetworkAccess(getApplication()).allowsSync(settings0.allowMobileData)) {
                    return@launch
                }
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
                        // odświeżamy modele Historii i Powiadomień po każdej partii,
                        // żeby znaczniki 📓 dochodziły stopniowo (T-08: bez ponownego czyszczenia bazy —
                        // dopisanie opisów niczego nie przeterminowuje; pełne odświeżenie było przed serią)
                        refreshHistoryNow(pruneExpired = false)
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
            try {
                resetHistoryPaging()
                syncJob?.cancelAndJoin()
                syncJob = null
                graph.syncEngine.runExclusiveMaintenance {
                    graph.notifications.cancelAll()
                    withContext(Dispatchers.IO) {
                        graph.database.clearHistory()
                    }
                    JxlImageCache.clear(getApplication())
                    CommunityImageLoader.clear(getApplication())
                    // C-12: obejrzane filmy i nagrania to też ślad tego, co odtwarzano
                    withContext(Dispatchers.IO) { CommunityMediaCache.clear(getApplication()) }
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
            }
        }
    }

    /**
     * @param pruneExpired T-08: false przy kolejnych partiach dociągania opisów — czyszczenie przeterminowanych
     *   danych zostało już zrobione na początku tej serii, a co partię (do 16 razy) wykonywało zbędne DELETE.
     */
    private suspend fun refreshHistoryNow(pruneExpired: Boolean = true) {
        val settings = graph.preferences.current()
        val expiredCreatorIds = expiredDeselectedCreatorIds(
            deselectedAtMillis = settings.deselectedCreatorAtMillis,
            selectedCreatorIds = settings.selectedCreatorIds,
            nowMillis = System.currentTimeMillis(),
        )
        // pula w pamięci = okno najnowszych wielkości aktualnego przewinięcia
        // (+ zapas na prefetch), min. 50 – zamiast sztywnych 10 000 co odświeżenie.
        // Ulubione recentHistory i tak zwraca w całości (#5)
        val historyPoolLimit = (
            historyLimit.value + HISTORY_PAGE_SIZE + HISTORY_PREFETCH_DISTANCE
            ).coerceIn(HISTORY_POOL_MIN, 10_000)
        val refreshed = withContext(Dispatchers.IO) {
            if (pruneExpired) graph.database.pruneExpiredData()
            if (expiredCreatorIds.isNotEmpty()) {
                graph.database.deleteHistoryForCreators(expiredCreatorIds)
            }
            graph.database.recentHistory(days = MAX_HISTORY_DAYS, limit = historyPoolLimit) to
                graph.database.recentNotificationInbox(limit = 2_000)
        }
        history.value = refreshed.first
        notificationInbox.value = refreshed.second
        privateVideoIds.value = withContext(Dispatchers.IO) { graph.database.privateVideoIds() }
        refreshHistorySearch()
        graph.preferences.removeDeselectionRecords(expiredCreatorIds)
        runStreamPrivacyCheck()
    }

    /**
     * W tle sprawdza streamy z historii, które mogły stać się prywatne (dobę po realnym starcie),
     * i po wykryciu odświeża zbiór prywatnych (badge/filtr). Bounded (kilka pobrań), bez nakładania.
     */
    private fun runStreamPrivacyCheck() {
        if (privacyCheckRunning) return
        privacyCheckRunning = true
        viewModelScope.launch {
            try {
                if (runCatching { graph.streamPrivacyChecker.run() }.getOrDefault(false)) {
                    privateVideoIds.value = withContext(Dispatchers.IO) { graph.database.privateVideoIds() }
                }
            } finally {
                privacyCheckRunning = false
            }
        }
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
                        // szukamy w całej lokalnej historii (do 60 dni), nie tylko
                        // w aktualnym filtrze czasu UI
                        cutoffMillis = 0L,
                        favoritesOnly = favoritesOnly.value,
                        // +1 rekord daje UI pewność, czy jest następna strona,
                        // bez ładowania całej tabeli
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
            // A-40: bez TREŚCI zapytania — tylko długość i krótki skrót (do porównania powtórzeń w jednym logu)
            fields = mapOf(
                "queryChars" to query.length,
                "queryHash" to query.trim().lowercase().hashCode().toUInt().toString(16),
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
                    // A-40: bez tytułów i nazw — wystarczy id twórcy i filmu do odtworzenia rankingu
                    "creatorId" to item.creatorId,
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
        // brak pewnego dowodu = klasyfikujemy dalej w tle, a nie wrzucamy
        // na siłę do zakładki Filmy
        VideoKind.UNKNOWN -> false
    }

    private fun HistoryItem.isInWindow(days: Int): Boolean =
        publishedAtMillis >= System.currentTimeMillis() - days.toLong() * DAY_MILLIS

    // --- serwery społeczności ---
    // osobny StateFlow, celowo poza wielkim combine AppUiState (mniej ryzyka).

    private val communityServers = MutableStateFlow<List<CommunityServerState>>(emptyList())
    private val communityPendingTerms = MutableStateFlow<CommunityTermsPrompt?>(null)

    // zagregowane platformy linków deklarowane przez serwery (do ustawień „otwieraj w") — z trwałego cache
    private val _communityLinkPlatforms = MutableStateFlow<List<CommunityLinkPlatform>>(emptyList())
    val communityLinkPlatforms: StateFlow<List<CommunityLinkPlatform>> = _communityLinkPlatforms.asStateFlow()

    private fun recomputeCommunityLinkPlatforms() {
        val ids = communityServers.value.map { it.record.serverId }
        _communityLinkPlatforms.value = CommunityLinkPlatformStore.aggregate(getApplication(), ids)
    }

    /** ustawia „otwieraj w" dla jednej platformy linków (jak dla YouTube, ale per platforma). */
    fun setCommunityLinkPref(platformId: String, target: YouTubeLinkTarget, packageName: String?) {
        viewModelScope.launch { graph.preferences.setCommunityLinkPref(platformId, target, packageName) }
    }
    private val communityMessage = MutableStateFlow<String?>(null)
    private val communityBusy = MutableStateFlow(false)
    private val communityRefreshing = MutableStateFlow(false)

    // T-01: czy ekran aplikacji jest widoczny (MainActivity.onStart/onStop) i kiedy ostatnio odświeżano społeczność.
    // Zadeklarowane PRZED blokiem init niżej (startCommunityAutoRefresh czyta je od razu).
    private val uiVisible = MutableStateFlow(true)
    @Volatile private var lastCommunityRefreshAtElapsed = SystemClock.elapsedRealtime()

    // stan weryfikacji „serwera głównego" — trwały (przeżywa restart), napędza baner i ciche powiadomienie
    private val _primaryHealth = MutableStateFlow<CommunityPrimaryHealth?>(
        CommunityPrimaryHealthStore.load(getApplication()),
    )

    val communityUiState = combine(
        communityServers,
        communityPendingTerms,
        communityMessage,
        communityBusy,
        graph.preferences.primaryServerIdFlow,
    ) { servers, pending, message, busy, primaryId ->
        CommunityUiState(
            servers = servers,
            pendingTerms = pending,
            message = message,
            isBusy = busy,
            primaryServerId = primaryId,
        )
    }.combine(communityRefreshing) { ui, refreshing ->
        ui.copy(isRefreshing = refreshing)
    }.combine(_primaryHealth) { ui, health ->
        // baner tylko dla wybranego serwera głównego i tylko gdy przekroczono próg kolejnych rozbieżności
        val relevant = health
            ?.takeIf { it.serverId == ui.primaryServerId && !CommunityDefaults.isDefault(ui.primaryServerId) }
            ?.takeIf { it.shouldWarn(PRIMARY_HEALTH_FAIL_THRESHOLD) }
        ui.copy(primaryHealth = relevant)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), CommunityUiState())

    init {
        // WBUDOWANY serwer domyślny: dodaj (jeśli skonfigurowany adres) i ustaw jako główny, gdy nie wybrano innego
        ensureDefaultServer()
        // po starcie odświeżamy manifesty serwerów – żeby wiedzieć, czy zapalić zakładkę
        refreshCommunityServers()
        startCommunityAutoRefresh()
        prewarmTorIfNeeded()
        // budzik push zapisał w tle nową treść — przeładuj ją do otwartej zakładki z bazy
        viewModelScope.launch {
            var seen = CommunityContentEvents.changed.value.first
            CommunityContentEvents.changed.collect { (count, _) ->
                if (count == seen) return@collect
                seen = count
                // D-15: wszystkie serwery zapisane od ostatniego razu (nie tylko ostatni z połączonych zdarzeń)
                CommunityContentEvents.drainChanged().forEach { applyStoredCommunityContent(it) }
            }
        }
    }

    /**
     * podmienia listy treści serwera na te z bazy, zostawiając resztę manifestu z pamięci (push,
     * linkPlatforms, regulamin). Dla serwera bez zgody albo bez manifestu nic nie robi.
     */
    private suspend fun applyStoredCommunityContent(serverId: String) {
        val current = communityServers.value.firstOrNull { it.record.serverId == serverId } ?: return
        val manifest = current.manifest ?: return
        if (current.consentState != CommunityConsentState.ACCEPTED) return
        val stored = withContext(Dispatchers.IO) {
            graph.communityRepository.restoredState(current.record)
        }
        val lists = stored.manifest ?: return
        val updated = current.copy(
            manifest = manifest.copy(
                // nowy rodzaj treści (np. pierwszy post) musi też odblokować swój filtr w zakładce
                capabilities = manifest.capabilities + lists.capabilities,
                creators = lists.creators,
                foreignItems = lists.foreignItems,
                posts = lists.posts,
                ads = lists.ads,
                contests = lists.contests,
                articles = lists.articles,
                memes = lists.memes,
            ),
            favoriteItemIds = stored.favoriteItemIds,
        )
        communityServers.update { list -> list.map { if (it.record.serverId == serverId) updated else it } }
    }

    /**
     * Wbudowany serwer domyślny (powiadomienia o filmach przez UnifiedPush). Dodaje go best-effort i — gdy
     * user nie wybrał jeszcze żadnego serwera głównego — ustawia go jako główny, by jego budziki z filmami
     * trafiały w główny potok powiadomień ([maybeDeliverPrimaryVideo]).
     */
    private fun ensureDefaultServer() {
        viewModelScope.launch(Dispatchers.IO) {
            val present = runCatching { graph.communityRepository.ensureDefaultServer() }.getOrDefault(false)
            if (present) {
                applyDefaultServerActivity()
                reloadCommunityServersFromStore()
                runCatching { syncCommunityPush() }
            }
        }
    }

    /**
     * Gdy mamy skonfigurowany choć jeden serwer `.onion`, startujemy wbudowany Tor JUŻ przy uruchomieniu
     * apki (w tle) — żeby zbootstrapował obwody, zanim user wejdzie na zakładkę. Bez tego pierwsze
     * pobranie `.onion` czekało na cały bootstrap Tora (dziesiątki sekund, zwłaszcza na emulatorze).
     */
    private fun prewarmTorIfNeeded() {
        viewModelScope.launch(Dispatchers.IO) {
            val hasOnion = runCatching {
                graph.communityRepository.storedServers().any { CommunityTorClient.isOnion(it.endpoint) }
            }.getOrDefault(false)
            if (hasOnion) EmbeddedTor.ensureStarted(getApplication())
        }
    }

    /** T-01: wołane przez MainActivity — ekran pojawił się (onStart) albo zniknął (onStop). */
    fun setUiVisible(visible: Boolean) {
        uiVisible.value = visible
    }

    /**
     * Cykliczne dociąganie świeżych manifestów, żeby zakładka sama się odnawiała bez akcji usera
     * (np. gdy serwer zaktualizuje post – jak watcher terminów WORD). Pomijamy tick, gdy nie ma jeszcze
     * żadnego serwera.
     *
     * T-01: odświeżamy co [COMMUNITY_AUTO_REFRESH_MILLIS] tylko WTEDY, GDY APLIKACJA JEST WIDOCZNA. Usługa push
     * trzyma proces przy życiu, więc dawniej pętla co 2 h w tle pobierała manifesty wszystkich serwerów (także
     * przez Tor), rejestrowała push i sprawdzała RSS — nikt tego nie oglądał. Po powrocie na ekran, gdy minął już
     * okres, odświeżenie idzie od razu, więc treść jest tak samo świeża jak przy działaniu w tle. Nowe wpisy
     * z budzików zapisują się w tle niezależnie od tej pętli.
     */
    private fun startCommunityAutoRefresh() {
        viewModelScope.launch {
            uiVisible.collectLatest { visible ->
                if (!visible) return@collectLatest // w tle nic — collectLatest przerwie czekanie przy zmianie stanu
                autoRefreshWhileVisible()
            }
        }
    }

    private suspend fun autoRefreshWhileVisible() {
        while (currentCoroutineContext().isActive) {
            val sinceLast = SystemClock.elapsedRealtime() - lastCommunityRefreshAtElapsed
            if (sinceLast < COMMUNITY_AUTO_REFRESH_MILLIS) {
                delay(COMMUNITY_AUTO_REFRESH_MILLIS - sinceLast)
                continue // w czasie czekania mogło przyjść ręczne odświeżenie — przelicz od nowa
            }
            // tick liczymy także wtedy, gdy nie ma serwerów albo odczyt bazy zawiódł — inaczej pętla kręciłaby się bez przerwy
            lastCommunityRefreshAtElapsed = SystemClock.elapsedRealtime()
            // G-04: błąd odczytu bazy pomija tick zamiast kończyć proces
            val hasServers = try {
                withContext(Dispatchers.IO) { graph.communityRepository.storedServers().isNotEmpty() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppLog.warning("CommunityDatabase", "Pominięto automatyczne odświeżanie społeczności", error)
                false
            }
            if (hasServers) refreshCommunityServers()
        }
    }

    /** user wpisał adres serwera (może skrócony): normalizujemy, pobieramy manifest+regulamin. */
    fun addCommunityServer(endpoint: String) {
        val normalized = normalizeCommunityEndpoint(endpoint)
        if (normalized == null) {
            communityMessage.value = "Nieprawidłowy adres serwera."
            return
        }
        viewModelScope.launch {
            communityBusy.value = true
            communityMessage.value = null
            // A-15: każde wywołanie repozytorium (DB pod ReentrantLock + sieć) poza wątkiem głównym
            when (val outcome = withContext(Dispatchers.IO) { graph.communityRepository.addServer(normalized) }) {
                is AddServerOutcome.Added -> {
                    upsertCommunityServer(outcome.state)
                    val manifest = outcome.state.manifest
                    if (outcome.state.consentState == CommunityConsentState.CONSENT_REQUIRED && manifest != null) {
                        communityPendingTerms.value = CommunityTermsPrompt(
                            serverId = outcome.state.record.serverId,
                            serverName = manifest.name,
                            terms = manifest.terms,
                            presentedAtMillis = System.currentTimeMillis(),
                        )
                    } else {
                        communityMessage.value = "Dodano serwer „${manifest?.name ?: normalized}”."
                    }
                }
                AddServerOutcome.InvalidEndpoint ->
                    communityMessage.value = "To nie jest poprawny adres HTTPS serwera."
                is AddServerOutcome.Failed ->
                    communityMessage.value = "Nie udało się pobrać serwera: ${outcome.reason}"
            }
            communityBusy.value = false
        }
    }

    fun acceptCommunityTerms() = recordCommunityDecision(accepted = true)

    fun declineCommunityTerms() = recordCommunityDecision(accepted = false)

    private fun recordCommunityDecision(accepted: Boolean) {
        val prompt = communityPendingTerms.value ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                graph.communityRepository.decide(prompt.serverId, prompt.terms, accepted, prompt.presentedAtMillis)
            }
            // H-02: odmowa regulaminu serwera, który był „główny", też musi wyczyścić wybór — inaczej primary
            // wskazuje serwer, z którym nie wolno się już kontaktować, a oficjalne powiadomienia znikają.
            if (!accepted && graph.preferences.currentPrimaryServerId() == prompt.serverId) {
                graph.preferences.setPrimaryServerId(CommunityDefaults.SERVER_ID) // B-11
                applyDefaultServerActivity()
                _primaryHealth.value = null
                CommunityPrimaryHealthStore.clear(getApplication())
                graph.notifications.cancelPrimaryHealth()
            }
            // R-03: odmowa regulaminu = nie wolno już kontaktować serwera → wyrejestruj push (adres+klucz),
            // zdejmij instancję UP i usuń z rejestru, by rotacja po cudzym budziku go nie re-rejestrowała.
            if (!accepted) {
                withContext(Dispatchers.IO) { CommunityPushManager.unregisterServer(getApplication(), prompt.serverId) }
            }
            communityPendingTerms.value = null
            reloadCommunityServersFromStore()
            refreshCommunityServer(prompt.serverId, syncPush = accepted)
            communityMessage.value = if (accepted) "Zapisano zgodę na regulamin." else "Zapisano odmowę."
        }
    }

    /** Czy użytkownik zaakceptował regulamin APLIKACJI (pierwsze uruchomienie); do tego czasu zero kontaktu z serwerem domyślnym. */
    private val _appTermsAccepted = MutableStateFlow(AppTerms.accepted(getApplication()))
    val appTermsAccepted: StateFlow<Boolean> = _appTermsAccepted

    fun acceptAppTerms() {
        AppTerms.accept(getApplication())
        _appTermsAccepted.value = true
        ensureDefaultServer() // dopiero teraz dodajemy/odświeżamy serwer domyślny i rejestrujemy push
    }

    /** T-11: użytkownik akceptuje nowy regulamin serwera domyślnego (zasłona na ekranie). */
    fun acceptDefaultServerTerms() {
        val state = communityServers.value.firstOrNull { CommunityDefaults.isDefault(it.record.serverId) } ?: return
        val terms = state.manifest?.terms ?: state.reviewableTerms ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                graph.communityRepository.decide(state.record.serverId, terms, true, System.currentTimeMillis())
                runCatching { graph.communityRepository.refresh(state.record) } // pobierz treść pod nową zgodę
            }
            reloadCommunityServersFromStore()
            runCatching { syncCommunityPush() } // serwer wraca do rejestru push
        }
    }

    fun dismissCommunityTermsPrompt() {
        communityPendingTerms.value = null
    }

    /** ponowne przejrzenie regulaminu z listy (np. gdy serwer go zmienił). */
    fun reviewCommunityTerms(serverId: String) {
        val state = communityServers.value.firstOrNull { it.record.serverId == serverId } ?: return
        // R-09: regulamin bierzemy ze świeżego manifestu, a gdy go brak (restart przy zmienionym regulaminie)
        // — z zapisanego w bazie (reviewableTerms), żeby dało się go przejrzeć i zaakceptować bez usuwania serwera.
        val terms = state.manifest?.terms ?: state.reviewableTerms ?: return
        val name = state.manifest?.name ?: state.record.displayName?.takeIf { it.isNotBlank() } ?: "Serwer społeczności"
        communityPendingTerms.value = CommunityTermsPrompt(
            serverId = serverId,
            serverName = name,
            terms = terms,
            presentedAtMillis = System.currentTimeMillis(),
        )
    }

    /**
     * Wbudowany serwer domyślny jest UKRYTY (nie ma go na liście serwerów) i nie da się go wyłączyć ręcznie.
     * Działa dokładnie wtedy, gdy jest serwerem głównym: wybór innego serwera głównego go wyłącza (i wyrejestrowuje
     * z niego push), a „Przywróć domyślny" — albo utrata wybranego serwera głównego — włącza z powrotem.
     */
    private suspend fun applyDefaultServerActivity() {
        val primary = graph.preferences.currentPrimaryServerId()
        val shouldBeActive = primary.isEmpty() || CommunityDefaults.isDefault(primary)
        val record = withContext(Dispatchers.IO) {
            graph.communityRepository.storedServers().firstOrNull { CommunityDefaults.isDefault(it.serverId) }
        } ?: return
        if (record.enabled == shouldBeActive) return
        withContext(Dispatchers.IO) { graph.communityRepository.setEnabled(record.serverId, shouldBeActive) }
        if (!shouldBeActive) {
            withContext(Dispatchers.IO) { CommunityPushManager.unregisterServer(getApplication(), record.serverId) }
        }
        reloadCommunityServersFromStore()
        if (shouldBeActive) runCatching { syncCommunityPush() } // ponowna rejestracja budzików z filmami
    }

    fun setCommunityServerEnabled(serverId: String, enabled: Boolean) {
        // serwer domyślny włącza/wyłącza tylko zmiana serwera głównego (applyDefaultServerActivity)
        if (CommunityDefaults.isDefault(serverId)) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { graph.communityRepository.setEnabled(serverId, enabled) }
            // H-02: wyłączony serwer nie może zostać „głównym" — inaczej handler odrzucałby wszystkie
            // oficjalne powiadomienia o filmach jako należące do (teraz nieaktywnego) primary.
            if (!enabled && graph.preferences.currentPrimaryServerId() == serverId) {
                graph.preferences.setPrimaryServerId(CommunityDefaults.SERVER_ID) // B-11
                applyDefaultServerActivity()
                _primaryHealth.value = null
                CommunityPrimaryHealthStore.clear(getApplication())
                graph.notifications.cancelPrimaryHealth()
            }
            // R-03: wyłączony serwer nie powinien już nas budzić — wyrejestruj push i usuń z rejestru.
            // Po ponownym włączeniu kolejny syncCommunityPush zarejestruje go na nowo.
            if (!enabled) {
                withContext(Dispatchers.IO) { CommunityPushManager.unregisterServer(getApplication(), serverId) }
            }
            reloadCommunityServersFromStore()
        }
    }

    fun setCommunityServerNotifyEnabled(serverId: String, enabled: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { graph.communityRepository.setNotifyEnabled(serverId, enabled) }
            // R-04: „Powiadomienia" sterują też PUSHEM. Wyłączenie = wyrejestruj (adres+klucz przestają iść
            // do serwera, telefon nie jest budzony). Włączenie = zarejestruj ponownie przy najbliższym syncu.
            if (!enabled) {
                withContext(Dispatchers.IO) { CommunityPushManager.unregisterServer(getApplication(), serverId) }
            }
            reloadCommunityServersFromStore()
            if (enabled) runCatching { syncCommunityPush() }
        }
    }

    fun setCommunityServerAdsEnabled(serverId: String, enabled: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { graph.communityRepository.setAdsEnabled(serverId, enabled) }
            reloadCommunityServersFromStore()
        }
    }

    // przełączniki: to APKA decyduje. poziomy: platforma / platforma+twórca / +typ.
    private fun keyFor(platform: String, creator: String?, type: String?) = when {
        creator == null -> CommunityContentFilter.platformKey(platform)
        type == null -> CommunityContentFilter.creatorKey(platform, creator)
        else -> CommunityContentFilter.typeKey(platform, creator, type)
    }

    fun toggleCommunityNotify(serverId: String, platform: String, creator: String? = null, type: String? = null) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val rec = graph.communityRepository.storedServers()
                    .firstOrNull { it.serverId == serverId } ?: return@withContext
                graph.communityRepository.setNotifyMuted(
                    serverId,
                    CommunityContentFilter.toggle(rec.notifyMuted, keyFor(platform, creator, type)),
                )
            }
            reloadCommunityServersFromStore()
        }
    }

    fun toggleCommunityDisplay(serverId: String, platform: String, creator: String? = null, type: String? = null) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val rec = graph.communityRepository.storedServers()
                    .firstOrNull { it.serverId == serverId } ?: return@withContext
                graph.communityRepository.setDisplayMuted(
                    serverId,
                    CommunityContentFilter.toggle(rec.displayMuted, keyFor(platform, creator, type)),
                )
            }
            reloadCommunityServersFromStore()
        }
    }

    /** wybór serwera głównego. "" = przywróć domyślny (oficjalny). */
    fun setPrimaryServer(serverId: String) {
        viewModelScope.launch {
            graph.preferences.setPrimaryServerId(serverId)
            applyDefaultServerActivity()
            // zmiana serwera głównego unieważnia dotychczasowy stan zdrowia — kasujemy i sprawdzamy od nowa
            _primaryHealth.value = null
            CommunityPrimaryHealthStore.clear(getApplication())
            graph.notifications.cancelPrimaryHealth()
            checkPrimaryHealth()
        }
    }

    fun removeCommunityServer(serverId: String) {
        if (CommunityDefaults.isDefault(serverId)) { // WBUDOWANY serwer domyślny jest NIEUSUWALNY
            actionMessage.value = "Serwer domyślny jest wbudowany — można go zastąpić jako główny, ale nie usunąć."
            return
        }
        viewModelScope.launch {
            val wasPrimary = graph.preferences.currentPrimaryServerId() == serverId
            // R-03: najpierw wyrejestruj push (wyśle `unregister`, zdejmie instancję UP, usunie z rejestru),
            // potem skasuj rekord. Inaczej usunięty serwer wisiał w rejestrze i rotacja po cudzym budziku
            // słała mu nową rejestrację (a instancja UP/token FCM dalej mogły budzić telefon).
            withContext(Dispatchers.IO) { CommunityPushManager.unregisterServer(getApplication(), serverId) }
            withContext(Dispatchers.IO) {
                graph.communityRepository.removeServer(serverId)
                CommunityLinkPlatformStore.remove(getApplication(), serverId)
                // C-12: nazwy plików to skróty URL (nie wiadomo, który serwer) — czyścimy całość
                CommunityMediaCache.clear(getApplication())
            }
            if (wasPrimary) {
                // usuwany serwer był „główny" — wracamy do WBUDOWANEGO serwera domyślnego jako głównego
                // (zamiast pustego), żeby powiadomienia o filmach dalej działały.
                graph.preferences.setPrimaryServerId(CommunityDefaults.SERVER_ID)
                applyDefaultServerActivity()
                _primaryHealth.value = null
                CommunityPrimaryHealthStore.clear(getApplication())
                graph.notifications.cancelPrimaryHealth()
            }
            communityPendingTerms.value = communityPendingTerms.value?.takeIf { it.serverId != serverId }
            reloadCommunityServersFromStore()
            communityMessage.value = "Usunięto serwer."
        }
    }

    fun clearCommunityMessage() {
        communityMessage.value = null
    }

    fun refreshCommunityServers() {
        lastCommunityRefreshAtElapsed = SystemClock.elapsedRealtime() // T-01: każde odświeżenie (też ręczne) zeruje okres
        viewModelScope.launch {
            communityRefreshing.value = true
            try {
                // retencja treści (osobno tekst/obraz) przy każdym starcie odświeżania
                val settings = graph.preferences.current()
                withContext(Dispatchers.IO) {
                    graph.communityRepository.pruneContent(
                        textRetentionDays = settings.communityTextRetentionDays,
                        imageRetentionDays = settings.communityImageRetentionDays,
                    )
                }
                reloadCommunityServersFromStore()
                // N-10: odświeżamy (kontaktujemy) TYLKO serwery włączone i z zaakceptowanym regulaminem —
                // nie odpytujemy wyłączonych/odrzuconych ani nie zapisujemy ich treści przed zgodą.
                withContext(Dispatchers.IO) { graph.communityRepository.contactableServers() }.forEach { record ->
                    mergeCommunityRefresh(withContext(Dispatchers.IO) { graph.communityRepository.refresh(record) })
                }
                // M-09: świeży manifest mógł wstawić pozycje starsze niż okno retencji (replaceContent wstawia
                // wszystko z manifestu). Tniemy ZARAZ po odświeżeniu, nie dopiero przy następnym — inaczej
                // treść spoza okna (albo przy „nie zapisuj") leżałaby w bazie do kolejnego cyklu.
                withContext(Dispatchers.IO) {
                    graph.communityRepository.pruneContent(
                        textRetentionDays = settings.communityTextRetentionDays,
                        imageRetentionDays = settings.communityImageRetentionDays,
                    )
                }
                syncCommunityPush()
                checkPrimaryHealth()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // G-04: wyjątek bazy (pełny dysk, zablokowana/uszkodzona baza) nie może kończyć procesu — to
                // wywołanie startuje z init, więc bez catch aplikacja padałaby przy każdym otwarciu
                AppLog.error("CommunityDatabase", "Odświeżanie serwerów społeczności nie powiodło się", error)
                communityMessage.value = "Nie udało się odświeżyć serwerów społeczności (${error.javaClass.simpleName})."
            } finally {
                communityRefreshing.value = false
            }
        }
    }

    /**
     * Weryfikacja „serwera głównego": gdy user wskazał serwer społeczności jako zastępujący serwer domyślny,
     * porównujemy to, co serwer DEKLARUJE ([CommunityManifest.primaryFeed]) z YouTube RSS (ground truth —
     * dokładnie to, czym posługuje się serwer domyślny) oraz z listą wybranych twórców. Wynik to
     * kategoryzowana lista różnic; dopiero po [PRIMARY_HEALTH_FAIL_THRESHOLD] kolejnych rozbieżnych
     * sprawdzeniach zapalamy baner i wysyłamy ciche powiadomienie. Zgodność zeruje licznik i chowa baner.
     */
    private suspend fun checkPrimaryHealth() {
        val primaryId = graph.preferences.currentPrimaryServerId()
        // C-05: od B-11 identyfikator nigdy nie jest pusty — serwer domyślny to po prostu SERVER_ID
        if (CommunityDefaults.isDefault(primaryId)) {
            // serwer domyślny jest wzorcem — nie ma czego weryfikować (nie porównujemy go z samym sobą)
            if (_primaryHealth.value != null) {
                _primaryHealth.value = null
                CommunityPrimaryHealthStore.clear(getApplication())
                graph.notifications.cancelPrimaryHealth()
            }
            return
        }

        val state = communityServers.value.firstOrNull { it.record.serverId == primaryId }
        val manifest = state?.manifest
        // osiągalny = mamy świeży/odtworzony manifest bez błędu; brak = serwer nie odpowiedział
        val reachable = state != null && state.error == null && manifest != null
        val feed = manifest?.primaryFeed

        // czytelne nazwy kanałów z manifestu (channelId → name) do komunikatów w banerze
        val nameByChannel = manifest?.creators.orEmpty()
            .mapNotNull { c -> c.channelUrl?.let(::channelIdFromUrl)?.let { it to c.name } }
            .toMap()

        // wybrani twórcy → ich kanały YouTube (UC...). Diff twórców robimy tylko, gdy potrafimy zmapować
        // WSZYSTKICH wybranych — inaczej nierozpoznany kanał fałszywie wyglądałby na „dorzuconego".
        val selectedIds = graph.preferences.selectedCreatorIds()
        val selectedCreators = graph.catalog.creators.filter { it.id in selectedIds }
        val channelBySelected = selectedCreators.map { it to channelIdOf(it) }
        val expectedCreators: Set<String> =
            if (selectedCreators.isNotEmpty() && channelBySelected.all { it.second != null }) {
                channelBySelected.mapNotNull { it.second }.toSet()
            } else {
                emptySet() // pomijamy diff twórców; duplikaty i różnice filmów sprawdzamy nadal
            }

        // RSS ground truth dla kanałów zadeklarowanych przez serwer (to samo źródło, co serwer domyślny);
        // E-03: feed z samymi `videos` — kanały tych filmów (limit kanałów jak B-05 w fetchRssGroundTruth)
        val rss: Map<String, List<RssVideo>> = if (reachable && feed != null) {
            val channels = feed.creators.ifEmpty { feed.videos.map { it.channelId } }
            withContext(Dispatchers.IO) { fetchRssGroundTruth(channels) }
        } else {
            emptyMap()
        }

        val now = System.currentTimeMillis()
        val differences = CommunityPrimaryHealthEvaluator.evaluate(
            reachable = reachable,
            feed = feed,
            expectedCreators = expectedCreators,
            rss = rss,
            nowMs = now,
            nameOf = { id -> nameByChannel[id] ?: id },
        )

        val previous = _primaryHealth.value?.takeIf { it.serverId == primaryId }
        val consecutive = if (differences.isEmpty()) 0 else (previous?.consecutiveFailures ?: 0) + 1
        val health = CommunityPrimaryHealth(
            serverId = primaryId,
            consecutiveFailures = consecutive,
            differences = differences,
            checkedAtMillis = now,
        )
        _primaryHealth.value = health
        CommunityPrimaryHealthStore.save(getApplication(), health)

        val wasWarning = previous?.shouldWarn(PRIMARY_HEALTH_FAIL_THRESHOLD) == true
        val isWarning = health.shouldWarn(PRIMARY_HEALTH_FAIL_THRESHOLD)
        when {
            // przejście do stanu ostrzeżenia — ciche powiadomienie (szczegóły w banerze)
            isWarning && !wasWarning -> {
                val name = state?.manifest?.name ?: state?.record?.displayName ?: "serwer społeczności"
                graph.notifications.notifyPrimaryHealth(
                    title = "Serwer główny może nie działać poprawnie",
                    text = "„$name” różni się od serwera domyślnego: ${primaryHealthSummary(differences)}",
                )
            }
            // wróciło do zgodności — chowamy powiadomienie
            !isWarning && wasWarning -> graph.notifications.cancelPrimaryHealth()
        }
    }

    /** kanał YouTube (UC...) wybranego twórcy: profil albo pierwsze źródło typu kanał; null gdy nieznany. */
    private fun channelIdOf(creator: Creator): String? {
        creator.profileChannelId?.let { if (PRIMARY_HEALTH_CHANNEL_ID.matches(it)) return it }
        return creator.sources
            .firstOrNull { it.type == SourceType.CHANNEL && it.externalId?.let(PRIMARY_HEALTH_CHANNEL_ID::matches) == true }
            ?.externalId
    }

    /** wyłuskuje UC… z adresu kanału (…/channel/UC…); null, gdy to handle/nietypowy URL. */
    private fun channelIdFromUrl(url: String): String? =
        PRIMARY_HEALTH_CHANNEL_ID_IN_URL.find(url)?.value

    /**
     * pobiera RSS (feeds/videos.xml?channel_id=…) dla zadeklarowanych kanałów; błędy per kanał pomijamy.
     * J-11e: dawniej sekwencyjnie do 60 kanałów wewnątrz refreshCommunityServers — przy wolnym YouTube (35 s na
     * kanał) „Odświeżanie…" trwało kwadrans. Teraz równolegle (limit połączeń) i z łącznym limitem czasu;
     * po jego przekroczeniu zostają kanały pobrane do tej pory (brakujące pomijamy jak przy błędzie pobrania).
     */
    private suspend fun fetchRssGroundTruth(channelIds: List<String>): Map<String, List<RssVideo>> {
        val ids = channelIds.filter(PRIMARY_HEALTH_CHANNEL_ID::matches).distinct().take(PRIMARY_HEALTH_MAX_CREATORS)
        val permits = Semaphore(PRIMARY_HEALTH_RSS_PARALLELISM)
        val out = java.util.concurrent.ConcurrentHashMap<String, List<RssVideo>>()
        withTimeoutOrNull(PRIMARY_HEALTH_RSS_TIMEOUT_MILLIS) {
            coroutineScope {
                ids.forEach { channelId ->
                    launch {
                        permits.withPermit {
                            val source = ResolvedSource(
                                sourceKey = "primary-health|$channelId",
                                type = SourceType.CHANNEL,
                                externalId = channelId,
                                feedUrl = "https://www.youtube.com/feeds/videos.xml?channel_id=$channelId",
                            )
                            runCatching { graph.feedClient.fetch(source) }
                                .getOrNull()
                                ?.let { entries -> out[channelId] = entries.map { RssVideo(it.id, it.publishedAtMillis) } }
                        }
                    }
                }
            }
        }
        // kolejność jak w deklaracji serwera (komunikaty w banerze)
        return ids.mapNotNull { id -> out[id]?.let { id to it } }.toMap(LinkedHashMap())
    }

    /**
     * Po odświeżeniu manifestów: jeśli któryś serwer oferuje push (UnifiedPush) i jest dystrybutor,
     * włączamy push i zgłaszamy mu nasz endpoint. Push zastępuje odpytywanie – polling zostaje tylko
     * jako fallback co 2 h (COMMUNITY_AUTO_REFRESH_MILLIS). Bez dystrybutora nic się nie dzieje (działa sam fallback).
     */
    private suspend fun syncCommunityPush() {
        val app = getApplication<Application>()
        val states = communityServers.value
        withContext(Dispatchers.IO) {
            // rejestruje aplikację w serwerach z push (FCM per-serwer albo UnifiedPush);
            // UnifiedPush włącza tylko, gdy któryś serwer faktycznie go potrzebuje
            CommunityPushManager.syncFromServers(app, states)
        }
    }

    /** przycisk „Przeładuj bazę z serwerów (SHA-256)": pełne pobranie + weryfikacja zmian. */
    fun reloadCommunityDatabase() {
        viewModelScope.launch {
            communityBusy.value = true
            communityMessage.value = null
            try {
                val result = withContext(Dispatchers.IO) {
                    graph.communityRepository.reloadFromServers()
                }
                reloadCommunityServersFromStore()
                communityMessage.value = when {
                    result.checked == 0 -> "Brak serwerów do przeładowania."
                    result.failed > 0 ->
                        "Przeładowano ${result.checked} serw.: ${result.changed} zmienionych, ${result.failed} błędów."
                    else -> "Przeładowano ${result.checked} serw. (SHA-256): ${result.changed} zmienionych."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // G-04: jak w refreshCommunityServers — błąd bazy to komunikat, nie awaria aplikacji
                AppLog.error("CommunityDatabase", "Przeładowanie bazy społeczności nie powiodło się", error)
                communityMessage.value = "Nie udało się przeładować bazy (${error.javaClass.simpleName})."
            } finally {
                communityBusy.value = false
            }
        }
    }

    /**
     * Nowa tożsamość w sieci Tor jednym kliknięciem: wymusza NEWNYM (nowe obwody) + ponowną rejestrację
     * (rotacja token1) we wszystkich serwerach OD RAZU. Wyrejestrowanie STAREJ tożsamości odbędzie się w
     * losowym momencie 1 s–5 min później (rozspójnienie czasowe, utrudnia powiązanie starej i nowej).
     * Całą robotę (sieć przez Tor) wykonuje worker — przeżyje nawigację i ubicie procesu.
     */
    fun rotateTorIdentity() {
        CommunityPushWorker.enqueue(getApplication(), CommunityPushWorker.ACTION_ROTATE_IDENTITY)
        communityMessage.value =
            "Zmieniam tożsamość Tor i przełączam rejestracje. Stare wyrejestrowanie nastąpi w losowym " +
                "momencie (do 5 min)."
    }

    private fun refreshCommunityServer(serverId: String, syncPush: Boolean = false) {
        viewModelScope.launch {
            val record = withContext(Dispatchers.IO) {
                graph.communityRepository.storedServers().firstOrNull { it.serverId == serverId }
            } ?: return@launch
            mergeCommunityRefresh(withContext(Dispatchers.IO) { graph.communityRepository.refresh(record) })
            // Q-05: po akceptacji regulaminu zarejestruj push serwera OD RAZU — wcześniej dopiero przy
            // autoodświeżaniu (2 h) albo restarcie aplikacji
            if (syncPush) runCatching { syncCommunityPush() }
        }
    }

    private suspend fun reloadCommunityServersFromStore() {
        val previous = communityServers.value.associateBy { it.record.serverId }
        // czytanie bazy + dekompresja ZSTD poza main-thread
        val states = withContext(Dispatchers.IO) {
            graph.communityRepository.storedServers().map { record ->
                // trzymamy świeży stan z tej sesji; inaczej odtwarzamy treść z lokalnego magazynu
                // (ZSTD), żeby po restarcie/offline zakładka nie była pusta do czasu odświeżenia
                previous[record.serverId]?.copy(
                    record = record,
                    // odśwież ulubione, by gwiazdka w feedzie od razu odzwierciedlała zmianę
                    favoriteItemIds = graph.communityRepository.favoriteItemIds(record.serverId),
                ) ?: graph.communityRepository.restoredState(record)
            }
        }
        communityServers.value = states
        recomputeCommunityLinkPlatforms()
    }

    private fun upsertCommunityServer(state: CommunityServerState) {
        communityServers.update { list ->
            if (list.any { it.record.serverId == state.record.serverId }) {
                list.map { if (it.record.serverId == state.record.serverId) state else it }
            } else {
                list + state
            }
        }
    }

    private fun mergeCommunityRefresh(fresh: CommunityServerState) {
        communityServers.update { list ->
            val exists = list.any { it.record.serverId == fresh.record.serverId }
            val updated = list.map { existing ->
                when {
                    existing.record.serverId != fresh.record.serverId -> existing
                    // świeży manifest wygrywa; przy błędzie/304 trzymamy stary manifest, ale bierzemy nowy record
                    fresh.manifest != null -> fresh
                    else -> existing.copy(record = fresh.record, error = fresh.error)
                }
            }
            if (exists) updated else updated + fresh
        }
        // świeży manifest niesie linkPlatforms (odtworzony z bazy ich nie ma) — zapisujemy do trwałego
        // cache i przeliczamy zagregowaną listę dla ustawień/otwierania linków.
        if (fresh.manifest != null) {
            CommunityLinkPlatformStore.save(getApplication(), fresh.record.serverId, fresh.manifest.linkPlatforms)
            recomputeCommunityLinkPlatforms()
        }
    }

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

private const val UI_PREFS = "ui_state"
private const val KEY_NOTIFICATIONS_SEEN_AT = "notifications_seen_at"

/**
 * Lista widoku „🔔 Powiadomienia” połączonej zakładki Historia: te same filtry co Historia (wyszukiwarka,
 * typy materiałów, Ulubione), ale BEZ zakresu czasu — powiadomienia i tak żyją 14 dni, a zakres Historii
 * nie może chować świeżych powiadomień. Prywatne streamy znikają z wyników wyszukiwania (jak w Historii).
 */
internal fun notificationsForView(ui: AppUiState): List<HistoryItem> {
    val activeFilters = ui.settings.historyFilters.intersect(ui.settings.globalHistoryTypes)
    val query = ui.historySearchQuery.trim().lowercase(Locale.forLanguageTag("pl"))
    return ui.notifications.filter { item ->
        val kindMatches = when (item.kind) {
            VideoKind.VIDEO -> HistoryFilter.VIDEOS in activeFilters
            VideoKind.LIVE, VideoKind.UPCOMING, VideoKind.STREAM_ARCHIVE -> HistoryFilter.STREAMS in activeFilters
            VideoKind.SHORT -> HistoryFilter.SHORTS in activeFilters
            VideoKind.UNKNOWN -> true
        }
        kindMatches &&
            (!ui.favoritesOnly || item.isFavorite) &&
            (
                query.isEmpty() ||
                    (
                        item.videoId !in ui.privateVideoIds &&
                            listOfNotNull(item.title, item.creatorName, item.descriptionSnippet)
                                .any { it.lowercase(Locale.forLanguageTag("pl")).contains(query) }
                        )
                )
    }
}
