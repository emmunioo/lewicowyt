package pl.lewicowyt.notifier.ui

import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.net.toUri
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.lewicowyt.notifier.BuildConfig
import pl.lewicowyt.notifier.AppGraph
import pl.lewicowyt.notifier.R
import pl.lewicowyt.notifier.data.DEFAULT_ACCENT_COLOR_ARGB
import pl.lewicowyt.notifier.data.AppSettings
import pl.lewicowyt.notifier.data.MAX_YOUTUBE_API_KEY_CHARS
import pl.lewicowyt.notifier.data.ThemeMode
import pl.lewicowyt.notifier.data.YouTubeLinkTarget
import pl.lewicowyt.notifier.data.isHistoryEnabledFor
import pl.lewicowyt.notifier.data.isNotificationEnabledFor
import pl.lewicowyt.notifier.diagnostics.DiagnosticLogState
import pl.lewicowyt.notifier.diagnostics.DiagnosticLogStore
import pl.lewicowyt.notifier.model.Creator
import pl.lewicowyt.notifier.model.DescriptionAvailability
import pl.lewicowyt.notifier.model.HistoryFilter
import pl.lewicowyt.notifier.model.HistoryItem
import pl.lewicowyt.notifier.model.MaterialStatusBadge
import pl.lewicowyt.notifier.model.SourceType
import pl.lewicowyt.notifier.model.VideoKind
import pl.lewicowyt.notifier.model.displayKindAt
import pl.lewicowyt.notifier.model.statusBadgesAt
import pl.lewicowyt.notifier.updates.AppUpdateManager
import pl.lewicowyt.notifier.updates.UpdatePolicy

private enum class Screen(val title: String) {
    CREATORS("Twórcy"),
    HISTORY("Historia"),
    NOTIFICATIONS("Powiadomienia"),
    SETTINGS("Ustawienia"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LewicowYTApp(
    viewModel: AppViewModel,
    exactAlarmAccessGranted: Boolean = true,
    batteryOptimizationIgnored: Boolean = true,
    notificationPolicyAccessGranted: Boolean = false,
    whatsNewVisible: Boolean = false,
    acknowledgeWhatsNew: () -> Unit = {},
    requestExactAlarmAccess: () -> Unit = {},
    openBatteryOptimizationSettings: () -> Unit = {},
    requestNotificationPolicyAccess: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val whatsNewScrollState = rememberScrollState()
    val whatsNewUriHandler = LocalUriHandler.current
    val youtubeLinks = remember { AppGraph.youtubeLinks }
    val openYouTubeLink: (String) -> Unit = { url ->
        youtubeLinks.open(
            url = url,
            target = state.settings.youtubeLinkTarget,
            otherAppPackage = state.settings.otherYouTubeAppPackage,
        )
    }
    var selectedScreenIndex by rememberSaveable { mutableIntStateOf(0) }
    var batteryOptimizationNoticeVisible by remember {
        mutableStateOf(false)
    }
    val screen = Screen.entries[selectedScreenIndex]

    LaunchedEffect(screen) {
        if (screen == Screen.HISTORY || screen == Screen.NOTIFICATIONS) {
            viewModel.refreshHistory()
        }
    }
    LaunchedEffect(screen, batteryOptimizationIgnored) {
        batteryOptimizationNoticeVisible = shouldShowBatteryOptimizationNotice(
            isSettingsScreen = screen == Screen.SETTINGS,
            batteryOptimizationIgnored = batteryOptimizationIgnored,
        )
    }
    LaunchedEffect(state.notificationNavigationRequest) {
        if (state.notificationNavigationRequest > 0L) {
            selectedScreenIndex = Screen.NOTIFICATIONS.ordinal
        }
    }

    if (whatsNewVisible) {
        AlertDialog(
            onDismissRequest = acknowledgeWhatsNew,
            title = { Text("Co nowego w lewicowYT 1.8-beta") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 440.dp)
                        .verticalScroll(whatsNewScrollState),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Nowości w 1.8-beta", fontWeight = FontWeight.Bold)
                    Text(
                        "Wydanie skupione na płynności: przewijanie i wyszukiwanie w Historii " +
                            "nie liczy się już na tym samym wątku, który rysuje ekran, więc " +
                            "aplikacja powinna mniej się zacinać, zwłaszcza przy większej historii.",
                    )
                    Text(
                        "Odświeżanie Historii jest szybsze — usunięto zbędną pracę wykonywaną " +
                            "wcześniej przy każdym odświeżeniu, niezależnie od tego, ile materiałów " +
                            "faktycznie się zmieniło.",
                    )
                    Text(
                        "Miniatury materiałów pobierają się w mniejszym rozmiarze dopasowanym do " +
                            "wyświetlanego kafelka, a konwersja obrazów w tle zużywa mniej baterii " +
                            "przy niemal niezmienionym rozmiarze pliku.",
                    )
                    Text(
                        "Dodano dwóch nowych twórców: Koroluk i PROsiaczek.",
                    )
                    Text(
                        "Ikonka 🗓️ przy zaplanowanej transmisji kręci się teraz podczas pobierania " +
                            "opisu materiału, żeby było widać, że coś się dzieje w tle.",
                    )
                    Spacer(Modifier.height(6.dp))
                    TextButton(
                        onClick = { whatsNewUriHandler.openUri("https://emmunioo.github.io/lewicowyt") },
                    ) {
                        Text("Pełna lista zmian na stronie projektu")
                    }
                }
            },
            confirmButton = {
                Button(onClick = acknowledgeWhatsNew) {
                    Text("Rozumiem")
                }
            },
        )
    } else if (batteryOptimizationNoticeVisible) {
        AlertDialog(
            onDismissRequest = { batteryOptimizationNoticeVisible = false },
            title = { Text("Wymagane działanie bez ograniczeń") },
            text = {
                Text(
                    "Aby automatyczne sprawdzanie i powiadomienia działały możliwie " +
                        "niezawodnie również przy wyłączonym ekranie, ustaw dla lewicowYT " +
                        "użycie baterii „Bez ograniczeń”. Może to zwiększyć zużycie energii.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        batteryOptimizationNoticeVisible = false
                        openBatteryOptimizationSettings()
                    },
                ) {
                    Text("Przejdź do ustawień")
                }
            },
            dismissButton = {
                TextButton(onClick = { batteryOptimizationNoticeVisible = false }) {
                    Text("Później")
                }
            },
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Column {
                TopAppBar(title = { Text("lewicowYT") })
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Screen.entries.forEachIndexed { index, item ->
                        TextButton(
                            onClick = { selectedScreenIndex = index },
                            modifier = Modifier.semantics {
                                selected = index == selectedScreenIndex
                                stateDescription = if (index == selectedScreenIndex) {
                                    "Wybrana karta"
                                } else {
                                    "Karta niewybrana"
                                }
                            },
                        ) {
                            Text(
                                text = item.title,
                                fontWeight = if (index == selectedScreenIndex) {
                                    FontWeight.Bold
                                } else {
                                    FontWeight.Normal
                                },
                            )
                        }
                    }
                }
                HorizontalDivider()
            }
        },
    ) { padding ->
        Surface(Modifier.fillMaxSize().padding(padding)) {
            when (screen) {
                Screen.CREATORS -> CreatorsScreen(state, viewModel, openYouTubeLink)
                Screen.HISTORY -> HistoryScreen(state, viewModel, openYouTubeLink)
                Screen.NOTIFICATIONS -> NotificationsScreen(state, viewModel, openYouTubeLink)
                Screen.SETTINGS -> SettingsScreen(
                    state = state,
                    viewModel = viewModel,
                    exactAlarmAccessGranted = exactAlarmAccessGranted,
                    batteryOptimizationIgnored = batteryOptimizationIgnored,
                    notificationPolicyAccessGranted = notificationPolicyAccessGranted,
                    requestExactAlarmAccess = requestExactAlarmAccess,
                    openBatteryOptimizationSettings = openBatteryOptimizationSettings,
                    requestNotificationPolicyAccess = requestNotificationPolicyAccess,
                    openYouTubeLink = openYouTubeLink,
                )
            }
        }
    }
}

@Composable
private fun CreatorsScreen(
    state: AppUiState,
    viewModel: AppViewModel,
    openYouTubeLink: (String) -> Unit,
) {
    val creatorsListState = rememberLazyListState()
    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Szukaj twórcy") },
        )
        Spacer(Modifier.height(10.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Wybrano: ${state.selectedCreatorIds.size}/${state.allCreatorCount}",
                modifier = Modifier.semantics {
                    stateDescription =
                        "Wybrano ${state.selectedCreatorIds.size} z ${state.allCreatorCount} twórców"
                },
            )
            Button(
                onClick = viewModel::syncNow,
                enabled = !state.isRefreshing,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    disabledContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    disabledContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) {
                if (state.isRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(18.dp)
                            .semantics { contentDescription = "Synchronizacja trwa" },
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Sprawdzam…")
                } else {
                    Text("Sprawdź teraz")
                }
            }
        }
        state.actionMessage?.let { message ->
            Spacer(Modifier.height(8.dp))
            Card(Modifier.fillMaxWidth()) {
                Text(
                    text = message,
                    modifier = Modifier
                        .padding(12.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(
                onClick = { viewModel.setAllCreatorsSelected(true) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Zaznacz wszystkich") }
            OutlinedButton(
                onClick = { viewModel.setAllCreatorsSelected(false) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Odznacz wszystkich") }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            state = creatorsListState,
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.creators, key = Creator::id) { creator ->
                CreatorRow(
                    creator = creator,
                    avatarUrl = state.creatorAvatars[creator.id],
                    selected = creator.id in state.selectedCreatorIds,
                    settings = state.settings,
                    onSelectedChange = { viewModel.setCreatorSelected(creator.id, it) },
                    onHistoryTypeChange = { type, enabled ->
                        viewModel.setCreatorHistoryType(creator.id, type, enabled)
                    },
                    onNotificationTypeChange = { type, enabled ->
                        viewModel.setCreatorNotificationType(creator.id, type, enabled)
                    },
                    onOpenChannel = {
                        creatorYouTubeChannelUrl(creator)?.let(openYouTubeLink)
                    },
                )
            }
        }
    }
}

@Composable
private fun CreatorRow(
    creator: Creator,
    avatarUrl: String?,
    selected: Boolean,
    settings: AppSettings,
    onSelectedChange: (Boolean) -> Unit,
    onHistoryTypeChange: (HistoryFilter, Boolean) -> Unit,
    onNotificationTypeChange: (HistoryFilter, Boolean) -> Unit,
    onOpenChannel: () -> Unit,
) {
    var expanded by rememberSaveable(creator.id) { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .combinedClickable(
                            onClickLabel = if (expanded) {
                                "Zwiń ustawienia twórcy"
                            } else {
                                "Rozwiń ustawienia twórcy"
                            },
                            onClick = { expanded = !expanded },
                            onLongClickLabel = "Otwórz kanał",
                            onLongClick = onOpenChannel,
                        )
                        .semantics {
                            stateDescription = if (expanded) {
                                "Ustawienia rozwinięte"
                            } else {
                                "Ustawienia zwinięte"
                            }
                        }
                        .padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ProfileImage(
                        url = avatarUrl,
                        creatorName = creator.name,
                        modifier = Modifier.size(48.dp),
                    )
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text(creator.name, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = if (expanded) {
                                "Ustawienia rozwinięte"
                            } else if (creator.sources.size == 1) {
                                "1 źródło YouTube"
                            } else {
                                "${creator.sources.size} źródła YouTube"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Checkbox(
                    checked = selected,
                    onCheckedChange = onSelectedChange,
                    modifier = Modifier.semantics {
                        contentDescription = "Obserwuj twórcę ${creator.name}"
                        stateDescription = if (selected) {
                            "Obserwowany"
                        } else {
                            "Nieobserwowany"
                        }
                    },
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    HorizontalDivider()
                    Text(
                        "Ustawienia tego twórcy",
                        modifier = Modifier.semantics { heading() },
                        fontWeight = FontWeight.SemiBold,
                    )
                    HistoryFilter.entries.forEach { type ->
                        CreatorContentTypeSettings(
                            creatorId = creator.id,
                            type = type,
                            settings = settings,
                            onHistoryChange = { onHistoryTypeChange(type, it) },
                            onNotificationChange = {
                                onNotificationTypeChange(type, it)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CreatorContentTypeSettings(
    creatorId: String,
    type: HistoryFilter,
    settings: AppSettings,
    onHistoryChange: (Boolean) -> Unit,
    onNotificationChange: (Boolean) -> Unit,
) {
    val globalHistoryEnabled = type in settings.globalHistoryTypes
    val globalNotificationsEnabled = type in settings.globalNotificationTypes
    val historyEnabled = settings.isHistoryEnabledFor(creatorId, type)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(contentTypeLabel(type), style = MaterialTheme.typography.titleSmall)
        SwitchSetting(
            title = "Historia",
            checked = historyEnabled,
            onCheckedChange = onHistoryChange,
            enabled = globalHistoryEnabled,
        )
        SwitchSetting(
            title = "Powiadomienia",
            checked = settings.isNotificationEnabledFor(creatorId, type),
            onCheckedChange = onNotificationChange,
            enabled = historyEnabled && globalNotificationsEnabled,
        )
        if (!globalHistoryEnabled) {
            Text(
                "Wyłączono globalnie w Ustawieniach.",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryScreen(
    state: AppUiState,
    viewModel: AppViewModel,
    openYouTubeLink: (String) -> Unit,
) {
    var rangeMenuOpen by remember { mutableStateOf(false) }
    var olderSearchVisible by rememberSaveable { mutableStateOf(false) }
    var olderCreatorMenuOpen by remember { mutableStateOf(false) }
    var olderQuery by rememberSaveable { mutableStateOf("") }
    var olderCreatorId by rememberSaveable {
        mutableStateOf(state.selectedCreatorIds.firstOrNull().orEmpty())
    }
    val listState = rememberLazyListState()
    val availableHistoryFilters = HistoryFilter.entries.filter {
        it in state.settings.globalHistoryTypes
    }
    val activeHistoryFilters = state.settings.historyFilters
        .intersect(state.settings.globalHistoryTypes)
    val shouldLoadMore by remember(
        state.history.size,
        state.historyHasMore,
        activeHistoryFilters,
    ) {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            activeHistoryFilters.isNotEmpty() && state.historyHasMore &&
                (state.history.isEmpty() || lastVisible >= state.history.lastIndex - 3)
        }
    }

    LaunchedEffect(shouldLoadMore, state.isLoadingHistory, state.history.size) {
        if (shouldLoadMore && !state.isLoadingHistory) viewModel.loadMoreHistory()
    }

    Column(
        Modifier
            .fillMaxSize(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Od najnowszych",
                modifier = Modifier.semantics { heading() },
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.isLoadingDescriptions) {
                    Text(
                        "📓",
                        modifier = Modifier
                            .semantics {
                                contentDescription = descriptionLoadingLabel(
                                    state.descriptionLoadingSource,
                                )
                            },
                        style = MaterialTheme.typography.titleMedium,
                    )
                } else if (state.pendingDescriptionCount > 0) {
                    Text(
                        "📓",
                        modifier = Modifier.semantics {
                            contentDescription =
                                "Opisy oczekujące na pobranie: " +
                                state.pendingDescriptionCount
                        },
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Box {
                    OutlinedButton(
                        onClick = { rangeMenuOpen = true },
                        modifier = Modifier.semantics {
                            contentDescription = "Zakres historii"
                            stateDescription = historyRangeLabel(
                                state.settings.historyWindowDays,
                            )
                        },
                    ) {
                        Text(historyRangeLabel(state.settings.historyWindowDays))
                    }
                    DropdownMenu(
                        expanded = rangeMenuOpen,
                        onDismissRequest = { rangeMenuOpen = false },
                    ) {
                        HISTORY_RANGES.forEach { days ->
                            DropdownMenuItem(
                                text = { Text(historyRangeLabel(days)) },
                                onClick = {
                                    rangeMenuOpen = false
                                    viewModel.setHistoryWindowDays(days)
                                },
                            )
                        }
                    }
                }
            }
        }
        OutlinedTextField(
            value = state.historySearchQuery,
            onValueChange = viewModel::setHistorySearchQuery,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            singleLine = true,
            label = { Text("Szukaj w lokalnej historii") },
            supportingText = {
                Text("Tytuły, twórcy i pobrane opisy — wyszukiwanie działa offline.")
            },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            availableHistoryFilters.forEach { filter ->
                FilterChip(
                    selected = filter in state.settings.historyFilters,
                    onClick = {
                        viewModel.setHistoryFilter(
                            filter,
                            filter !in state.settings.historyFilters,
                        )
                    },
                    label = { Text(historyFilterLabel(filter)) },
                )
            }
            FilterChip(
                selected = state.favoritesOnly,
                onClick = { viewModel.setFavoritesOnly(!state.favoritesOnly) },
                label = { Text("Ulubione") },
            )
            OutlinedButton(onClick = { olderSearchVisible = true }) {
                Text("Znajdź starszy")
            }
        }

        if (state.history.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (state.isLoadingHistory) {
                    CircularProgressIndicator(
                        modifier = Modifier.semantics {
                            contentDescription = "Ładowanie historii"
                        },
                    )
                } else if (state.historyLoadError != null) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(state.historyLoadError)
                        OutlinedButton(onClick = viewModel::retryHistoryLoading) {
                            Text("Spróbuj ponownie")
                        }
                    }
                } else {
                    Text(
                        if (state.historySearchQuery.isNotBlank()) {
                            "Brak wyników w lokalnej historii."
                        } else if (state.favoritesOnly) {
                            "Brak ulubionych materiałów pasujących do aktywnych filtrów."
                        } else if (state.selectedCreatorIds.isEmpty()) {
                            "Zaznacz co najmniej jednego twórcę. Historia pokazuje tylko " +
                                "aktualnie wybrane kanały."
                        } else if (state.settings.globalHistoryTypes.isEmpty()) {
                            "Włącz co najmniej jeden rodzaj historii w Ustawieniach."
                        } else if (activeHistoryFilters.isEmpty()) {
                            "Wybierz co najmniej jeden typ materiału: filmy, streamy lub Shorty."
                        } else {
                            "Brak materiałów z wybranego okresu. " +
                                "Pierwsza synchronizacja tworzy stan początkowy bez powiadomień."
                        },
                    )
                }
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.history, key = HistoryItem::videoId) { item ->
                MaterialHistoryCard(
                    item = item,
                    isDescriptionDownloadActive = state.isLoadingDescriptions,
                    onOpen = {
                        youtubeWatchUrl(item.videoId)?.let(openYouTubeLink)
                    },
                    onFavoriteChange = { viewModel.setFavorite(item.videoId, it) },
                )
            }
            if (state.historyHasMore) {
                item(key = "history-loading") {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(72.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (state.isLoadingHistory) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .size(28.dp)
                                    .semantics {
                                        contentDescription = "Ładowanie dalszej historii"
                                    },
                            )
                        }
                    }
                }
            }
            state.historyLoadError?.let { error ->
                item(key = "history-error") {
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                error,
                                modifier = Modifier.semantics {
                                    liveRegion = LiveRegionMode.Polite
                                },
                                color = MaterialTheme.colorScheme.error,
                            )
                            OutlinedButton(onClick = viewModel::retryHistoryLoading) {
                                Text("Spróbuj ponownie")
                            }
                        }
                    }
                }
            }
        }
    }

    if (olderSearchVisible) {
        val selectableCreators = state.catalogCreators.filter {
            it.id in state.selectedCreatorIds
        }
        LaunchedEffect(selectableCreators, olderCreatorId) {
            if (selectableCreators.none { it.id == olderCreatorId }) {
                olderCreatorId = selectableCreators.firstOrNull()?.id.orEmpty()
            }
        }
        AlertDialog(
            onDismissRequest = {
                olderSearchVisible = false
                viewModel.clearOlderMaterialSearch()
            },
            title = { Text("Znajdź starszy materiał") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "Wyniki pochodzą z YouTube Web. Materiał zostanie dodany " +
                            "dopiero po osobnym potwierdzeniu kanału i danych.",
                    )
                    Box {
                        OutlinedButton(
                            onClick = { olderCreatorMenuOpen = true },
                            enabled = selectableCreators.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                selectableCreators.firstOrNull { it.id == olderCreatorId }
                                    ?.name ?: "Wybierz obserwowanego twórcę",
                            )
                        }
                        DropdownMenu(
                            expanded = olderCreatorMenuOpen,
                            onDismissRequest = { olderCreatorMenuOpen = false },
                        ) {
                            selectableCreators.forEach { creator ->
                                DropdownMenuItem(
                                    text = { Text(creator.name) },
                                    onClick = {
                                        olderCreatorId = creator.id
                                        olderCreatorMenuOpen = false
                                    },
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = olderQuery,
                        onValueChange = { olderQuery = it.take(100) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Tytuł lub słowa kluczowe") },
                    )
                    Button(
                        onClick = {
                            viewModel.searchOlderMaterials(olderCreatorId, olderQuery)
                        },
                        enabled = olderCreatorId.isNotBlank() &&
                            olderQuery.trim().length >= 2 &&
                            !state.olderMaterialSearch.isLoading,
                    ) {
                        Text("Szukaj na YouTube")
                    }
                    if (state.olderMaterialSearch.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.semantics {
                                contentDescription = "Weryfikowanie materiału"
                            },
                        )
                    }
                    state.olderMaterialSearch.error?.let { error ->
                        Text(error, color = MaterialTheme.colorScheme.error)
                    }
                    state.olderMaterialSearch.results.forEach { candidate ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(candidate.creatorName, fontWeight = FontWeight.SemiBold)
                                Text(candidate.title)
                                OutlinedButton(
                                    onClick = { viewModel.confirmOlderMaterial(candidate) },
                                ) {
                                    Text("Sprawdź przed dodaniem")
                                }
                            }
                        }
                    }
                    state.olderMaterialSearch.confirmed?.let { confirmed ->
                        HorizontalDivider()
                        Text("Potwierdzony materiał", fontWeight = FontWeight.Bold)
                        Text(confirmed.creatorName)
                        Text(confirmed.title)
                        Text(formatTime(confirmed.publishedAtMillis))
                        Button(
                            onClick = viewModel::addConfirmedOlderMaterial,
                            enabled = !state.olderMaterialSearch.isLoading,
                        ) {
                            Text("Potwierdź dodanie do Ulubionych")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        olderSearchVisible = false
                        viewModel.clearOlderMaterialSearch()
                    },
                ) {
                    Text("Zamknij")
                }
            },
        )
    }
}

@Composable
private fun NotificationsScreen(
    state: AppUiState,
    viewModel: AppViewModel,
    openYouTubeLink: (String) -> Unit,
) {
    val notificationsListState = rememberLazyListState()

    if (state.notifications.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Brak zapisanych powiadomień. Każdy nowy materiał wykryty podczas " +
                    "synchronizacji pojawi się tutaj od najnowszego.",
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize(),
        state = notificationsListState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                "Najnowsze powiadomienia",
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        items(state.notifications, key = HistoryItem::videoId) { item ->
            MaterialHistoryCard(
                item = item,
                isDescriptionDownloadActive = state.isLoadingDescriptions,
                onOpen = {
                    youtubeWatchUrl(item.videoId)?.let(openYouTubeLink)
                },
                onFavoriteChange = { viewModel.setFavorite(item.videoId, it) },
            )
        }
    }
}

@Composable
private fun MaterialHistoryCard(
    item: HistoryItem,
    isDescriptionDownloadActive: Boolean,
    onOpen: () -> Unit,
    onFavoriteChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val nowMillis = System.currentTimeMillis()
    val displayKind = item.displayKindAt(nowMillis)
    val statusBadges = item.statusBadgesAt(nowMillis).map { badge ->
        val (icon, label) = when (badge) {
            MaterialStatusBadge.SCHEDULED -> "🗓️" to "Zaplanowana transmisja"
            MaterialStatusBadge.DESCRIPTION -> "📓" to "Opis materiału został pobrany"
            MaterialStatusBadge.MEMBERS_ONLY -> "💵" to "Materiał tylko dla wspierających"
        }
        Triple(badge, icon, label)
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .combinedClickable(
                        onClickLabel = "Otwórz materiał",
                        onLongClickLabel = "Kopiuj link do materiału",
                        role = Role.Button,
                        onClick = onOpen,
                        onLongClick = {
                            youtubeWatchUrl(item.videoId)?.let { url ->
                                copyYouTubeLink(context, url)
                            }
                        },
                    )
                    .semantics {
                        contentDescription = historyItemDescription(item)
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VideoThumbnail(
                    videoId = item.videoId,
                    modifier = Modifier
                        .size(width = 128.dp, height = 72.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp, end = 4.dp),
                ) {
                    Text(item.creatorName, fontWeight = FontWeight.SemiBold)
                    Text(
                        item.title,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${kindLabel(displayKind)} · ${formatTime(item.publishedAtMillis)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    item.descriptionSnippet?.takeIf(String::isNotBlank)?.let { snippet ->
                        Spacer(Modifier.height(3.dp))
                        Text(
                            snippet,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FavoriteToggle(
                    item = item,
                    onFavoriteChange = onFavoriteChange,
                )
                statusBadges.forEach { (badge, icon, label) ->
                    StatusBadgeText(
                        icon = icon,
                        label = label,
                        // Zaplanowana transmisja to jedyna odznaka, której stan
                        // może się jeszcze zmienić w trakcie aktywnego pobierania
                        // opisów (potwierdzenie/wygaśnięcie po nadejściu terminu),
                        // więc tylko ona sygnalizuje trwającą pracę animacją.
                        spinning = isDescriptionDownloadActive &&
                            badge == MaterialStatusBadge.SCHEDULED,
                    )
                }
            }
        }
    }
}

/**
 * Odznaka statusu materiału. Gdy [spinning] jest aktywne, ikona wykonuje
 * płynny, ciągły pełny obrót — sygnalizuje trwające w tle pobieranie opisów
 * bez odrywania uwagi (tylko odznaka „🗓️” bywa nim objęta, patrz wywołanie).
 */
@Composable
private fun StatusBadgeText(icon: String, label: String, spinning: Boolean) {
    if (!spinning) {
        Text(
            text = icon,
            modifier = Modifier.semantics { contentDescription = label },
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }
    val infiniteTransition = rememberInfiniteTransition(label = "statusBadgeSpin")
    val angle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            // Stała prędkość (LinearEasing) zamiast przyspieszania/hamowania na
            // każdym okrążeniu — bez „zacinania” na złączeniu pętli, czyli
            // satysfakcjonujący, płynny obrót jak u typowego wskaźnika ładowania.
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "statusBadgeSpinAngle",
    )
    Text(
        text = icon,
        modifier = Modifier
            .graphicsLayer { rotationZ = angle }
            .semantics {
                contentDescription = "$label — trwa pobieranie opisu"
            },
        style = MaterialTheme.typography.bodyMedium,
    )
}

private fun copyYouTubeLink(context: Context, url: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Link YouTube", url))
    Toast.makeText(context, "Skopiowano link do materiału", Toast.LENGTH_SHORT).show()
}

@Composable
private fun FavoriteToggle(
    item: HistoryItem,
    onFavoriteChange: (Boolean) -> Unit,
) {
    IconToggleButton(
        checked = item.isFavorite,
        onCheckedChange = onFavoriteChange,
        modifier = Modifier.semantics {
            contentDescription = if (item.isFavorite) {
                "Usuń ${item.title} z ulubionych"
            } else {
                "Dodaj ${item.title} do ulubionych"
            }
            stateDescription = if (item.isFavorite) "Ulubiony" else "Nieulubiony"
        },
    ) {
        Text(
            text = if (item.isFavorite) "★" else "☆",
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

@Composable
private fun SettingsScreen(
    state: AppUiState,
    viewModel: AppViewModel,
    exactAlarmAccessGranted: Boolean,
    batteryOptimizationIgnored: Boolean,
    notificationPolicyAccessGranted: Boolean,
    requestExactAlarmAccess: () -> Unit,
    openBatteryOptimizationSettings: () -> Unit,
    requestNotificationPolicyAccess: () -> Unit,
    openYouTubeLink: (String) -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val uriHandler = LocalUriHandler.current
    val intervals = listOf(15, 30, 60, 120, 180, 360, 720, 1440)
    var intervalMenuOpen by remember { mutableStateOf(false) }
    var clearConfirmationVisible by rememberSaveable { mutableStateOf(false) }
    var thirdPartyNoticesVisible by rememberSaveable { mutableStateOf(false) }
    var diagnosticState by remember { mutableStateOf(DiagnosticLogStore.state()) }
    var diagnosticMessage by remember { mutableStateOf<String?>(null) }
    var dnsTapCount by rememberSaveable { mutableIntStateOf(0) }
    val settingsListState = rememberLazyListState()
    val noticesScrollState = rememberScrollState()
    var lastDnsTapAt by remember { mutableLongStateOf(0L) }
    val diagnosticScope = rememberCoroutineScope()
    val diagnosticExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/gzip"),
    ) { destination ->
        if (destination != null) {
            diagnosticScope.launch {
                val exported = withContext(Dispatchers.IO) {
                    DiagnosticLogStore.exportTo(destination)
                }
                diagnosticMessage = if (exported) {
                    "Zapisano skompresowany dziennik diagnostyczny."
                } else {
                    "Nie udało się zapisać dziennika diagnostycznego."
                }
                diagnosticState = DiagnosticLogStore.state()
            }
        }
    }
    val thirdPartyNotices = remember(resources) {
        resources.openRawResource(R.raw.third_party_notices)
            .bufferedReader()
            .use { it.readText() }
    }

    LaunchedEffect(dnsTapCount, diagnosticState.unlocked) {
        if (dnsTapCount > 0 && !diagnosticState.unlocked) {
            delay(DIAGNOSTIC_UNLOCK_SEQUENCE_TIMEOUT_MILLIS)
            dnsTapCount = 0
        }
    }

    if (clearConfirmationVisible) {
        AlertDialog(
            onDismissRequest = { clearConfirmationVisible = false },
            title = { Text("Wyczyścić historię?") },
            text = {
                Text(
                    "Usunięte zostaną lokalna historia, sekcja Powiadomienia oraz " +
                        "punkty odniesienia synchronizacji. Tej operacji nie można cofnąć.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        clearConfirmationVisible = false
                        viewModel.clearHistoryAndBaselines()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text("Usuń dane")
                }
            },
            dismissButton = {
                TextButton(onClick = { clearConfirmationVisible = false }) {
                    Text("Anuluj")
                }
            },
        )
    }
    if (thirdPartyNoticesVisible) {
        AlertDialog(
            onDismissRequest = { thirdPartyNoticesVisible = false },
            title = { Text("Licencje komponentów") },
            text = {
                Text(
                    text = thirdPartyNotices,
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(noticesScrollState),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = { thirdPartyNoticesVisible = false }) {
                    Text("Zamknij")
                }
            },
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize(),
        state = settingsListState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ThemeSettings(state, viewModel)
        }
        item {
            YouTubeLinkSettings(state, viewModel)
        }
        item {
            FastHistorySettings(state, viewModel, openYouTubeLink)
        }
        item {
            GlobalContentSettings(state, viewModel)
        }
        item {
            ExactAlarmSettings(
                exactAlarmAccessGranted = exactAlarmAccessGranted,
                batteryOptimizationIgnored = batteryOptimizationIgnored,
                notificationPolicyAccessGranted = notificationPolicyAccessGranted,
                requestExactAlarmAccess = requestExactAlarmAccess,
                openBatteryOptimizationSettings = openBatteryOptimizationSettings,
                requestNotificationPolicyAccess = requestNotificationPolicyAccess,
            )
        }
        item {
            Text(
                "Częstotliwość",
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            Box {
                OutlinedButton(
                    onClick = { intervalMenuOpen = true },
                    modifier = Modifier.semantics {
                        contentDescription = "Częstotliwość synchronizacji"
                        stateDescription = intervalLabel(state.settings.intervalMinutes)
                    },
                ) {
                    Text(intervalLabel(state.settings.intervalMinutes))
                }
                DropdownMenu(
                    expanded = intervalMenuOpen,
                    onDismissRequest = { intervalMenuOpen = false },
                ) {
                    intervals.forEach { minutes ->
                        DropdownMenuItem(
                            text = { Text(intervalLabel(minutes)) },
                            onClick = {
                                intervalMenuOpen = false
                                viewModel.setInterval(minutes)
                            },
                        )
                    }
                }
            }
            if (
                state.settings.intervalMinutes == 15 &&
                !state.settings.youtubeApiEnabled
            ) {
                Text(
                    text = "Interwał 15 minut bez aktywnego klucza YouTube Data API " +
                        "częściej wybudza urządzenie. RSS jest lekkie, ale gdy potrzebne " +
                        "jest uzupełnienie przez YouTube Web, rośnie transfer i praca " +
                        "procesora.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (state.settings.intervalMinutes == 1440) {
            item {
                Text(
                    "Godzina codziennego sprawdzenia",
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleMedium,
                )
                OutlinedButton(
                    onClick = {
                        TimePickerDialog(
                            context,
                            { _, hour, minute -> viewModel.setDailyTime(hour, minute) },
                            state.settings.dailyHour,
                            state.settings.dailyMinute,
                            true,
                        ).show()
                    },
                    modifier = Modifier.semantics {
                        contentDescription = "Godzina codziennego sprawdzenia"
                        stateDescription = formatClock(
                            state.settings.dailyHour,
                            state.settings.dailyMinute,
                        )
                    },
                ) {
                    Text(formatClock(state.settings.dailyHour, state.settings.dailyMinute))
                }
                Text(
                    text = "Alarm jest ustawiany na wybraną godzinę. Synchronizacja " +
                        "wymaga jednak dostępnej, dozwolonej sieci i nie zadziała po " +
                        "użyciu systemowej funkcji „Wymuś zatrzymanie”.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SwitchSetting(
                    title = "Zezwól na wykorzystywanie danych komórkowych",
                    checked = state.settings.allowMobileData,
                    onCheckedChange = viewModel::setAllowMobileData,
                )
                Text(
                    "Każde automatyczne sprawdzenie uruchomione przez alarm przestrzega " +
                        "tego ustawienia.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Aktualizacje",
                        modifier = Modifier.semantics { heading() },
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Zainstalowana wersja: ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    SwitchSetting(
                        title = "Automatyczne aktualizacje",
                        checked = state.settings.automaticUpdatesEnabled,
                        onCheckedChange = viewModel::setAutomaticUpdatesEnabled,
                    )
                    Text(
                        "Po włączeniu aplikacja pobiera nowe APK w tle podczas zwykłego " +
                            "sprawdzania YouTube. Instalacja nie otwiera przeglądarki, ale " +
                            "Android wymaga jej potwierdzenia.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        onClick = viewModel::checkForUpdates,
                        enabled = state.updateState != UpdateUiState.Checking &&
                            state.updateState !is UpdateUiState.Downloading,
                    ) {
                        if (state.updateState == UpdateUiState.Checking) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .size(18.dp)
                                    .semantics {
                                        contentDescription = "Sprawdzanie aktualizacji"
                                    },
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Sprawdzam…")
                        } else {
                            Text("Sprawdź aktualizacje")
                        }
                    }
                    when (val updateState = state.updateState) {
                        UpdateUiState.Idle, UpdateUiState.Checking -> Unit
                        UpdateUiState.NotConfigured -> Text(
                            "Aktualizacje nie są jeszcze skonfigurowane. Uzupełnij " +
                                "UPDATE_REPOSITORY=login/repozytorium w gradle.properties " +
                                "i zbuduj aplikację ponownie.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        is UpdateUiState.UpToDate -> Text(
                            "Masz najnowszą wersję (${updateState.latestVersion}).",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        is UpdateUiState.Error -> {
                            Text(
                                if (updateState.update == null) {
                                    "Nie udało się sprawdzić aktualizacji: " +
                                        updateState.message
                                } else {
                                    "Nie udało się pobrać aktualizacji: " +
                                        updateState.message
                                },
                                modifier = Modifier.semantics {
                                    liveRegion = LiveRegionMode.Polite
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            updateState.update?.let { update ->
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            viewModel.downloadAndInstallUpdate(update)
                                        },
                                    ) {
                                        Text("Spróbuj ponownie")
                                    }
                                    TextButton(
                                        onClick = {
                                            uriHandler.openUri(update.releasePageUrl)
                                        },
                                    ) {
                                        Text("Otwórz stronę wydania")
                                    }
                                }
                            }
                        }
                        is UpdateUiState.Available -> {
                            Text(
                                "Dostępna aktualizacja",
                                modifier = Modifier.semantics { heading() },
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                "${BuildConfig.VERSION_NAME} → ${updateState.update.version}",
                                fontWeight = FontWeight.SemiBold,
                            )
                            updateState.update.apkSizeBytes?.let { size ->
                                Text(
                                    "Rozmiar APK: ${formatApkSize(size)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            when (updateState.update.policy) {
                                UpdatePolicy.OPTIONAL -> Unit
                                UpdatePolicy.MANDATORY_SECURITY_UPDATE -> Text(
                                    "Bieżące wydanie zostało wycofane. Z powodów " +
                                        "bezpieczeństwa ta aktualizacja jest obowiązkowa.",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                UpdatePolicy.SECURITY_ROLLBACK -> Text(
                                    AppUpdateManager.SECURITY_ROLLBACK_MESSAGE,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (updateState.update.releaseNotes.isNotBlank()) {
                                Text("Co nowego", fontWeight = FontWeight.SemiBold)
                                Text(
                                    updateState.update.releaseNotes,
                                    maxLines = 5,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            updateState.update.sha256Digest?.let { digest ->
                                Text(
                                    "SHA-256 APK: $digest",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    viewModel.downloadAndInstallUpdate(updateState.update)
                                },
                            ) {
                                Text("Pobierz aktualizację")
                            }
                            TextButton(
                                onClick = {
                                    uriHandler.openUri(updateState.update.releasePageUrl)
                                },
                            ) {
                                Text("Szczegóły wydania na GitHubie")
                            }
                        }
                        is UpdateUiState.Downloading -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .size(18.dp)
                                        .semantics {
                                            contentDescription = "Pobieranie aktualizacji"
                                        },
                                    strokeWidth = 2.dp,
                                )
                                Text("Pobieranie i sprawdzanie APK…")
                            }
                        }
                        is UpdateUiState.ReadyToInstall -> {
                            Text(
                                "APK zostało pobrane i sprawdzone. Potwierdź instalację " +
                                    "w systemie Android.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            OutlinedButton(onClick = viewModel::openPreparedUpdateInstaller) {
                                Text("Otwórz instalator ponownie")
                            }
                        }
                    }
                    Text(
                        "Kontrola aktualizacji w tle odbywa się najwyżej raz na 2 godziny. " +
                            "Przed instalacją aplikacja sprawdza SHA-256, identyfikator, " +
                            "podpis i techniczny numer wersji APK.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Jeśli wydanie odpowiadające zainstalowanej wersji zostanie usunięte " +
                            "z GitHuba, aplikacja potraktuje to jako wycofanie ze względów " +
                            "bezpieczeństwa. Niezależnie od ustawienia automatycznych " +
                            "aktualizacji pobierze wydanie zastępcze i poprosi o jego " +
                            "instalację. Awaryjny rollback musi być podpisany tym samym " +
                            "kluczem i mieć wyższy versionCode, choć zawiera starszy kod.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Automatyczne sprawdzanie używa wyłącznie dokładnego alarmu " +
                            "systemowego i krótkiej usługi pierwszoplanowej. Alarm może " +
                            "obudzić urządzenie w Doze, a następny termin jest zapisywany " +
                            "zanim rozpocznie się pobieranie. Wszystkie wybrane kanały " +
                            "rozpoczynają sprawdzanie równocześnie, a kosztowna klasyfikacja " +
                            "pojedynczych filmów ma osobny limit. Rozległa awaria jest " +
                            "ponawiana najwyżej dwa razy. " +
                            "Minimum harmonogramu to 15 minut.",
                    )
                    TextButton(
                        onClick = {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    "package:${context.packageName}".toUri(),
                                ),
                            )
                        },
                    ) {
                        Text("Otwórz ustawienia systemowe aplikacji")
                    }
                }
            }
        }
        item {
            OutlinedButton(onClick = { clearConfirmationVisible = true }) {
                Text("Wyczyść historię i stan początkowy")
            }
        }
        item {
            AccentColorSettings(state, viewModel)
        }
        item {
            Text(
                text = "Informacje",
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Ostatnia synchronizacja", fontWeight = FontWeight.SemiBold)
                    Text(state.settings.lastSyncSummary)
                    if (state.settings.lastSyncAtMillis > 0L) {
                        Text(
                            formatTime(state.settings.lastSyncAtMillis),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (
                        state.settings.lastCompletedSyncAtMillis > 0L &&
                        state.settings.lastCompletedSyncAtMillis !=
                        state.settings.lastSyncAtMillis
                    ) {
                        Text(
                            "Ostatnie ukończone sprawdzenie: " +
                                formatTime(state.settings.lastCompletedSyncAtMillis),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        item {
            PrivacyDnsNote(
                unlockProgress = dnsTapCount,
                onSecretTap = {
                    if (!diagnosticState.unlocked) {
                        val now = SystemClock.elapsedRealtime()
                        dnsTapCount = if (
                            lastDnsTapAt > 0L &&
                            now - lastDnsTapAt <= DIAGNOSTIC_UNLOCK_SEQUENCE_TIMEOUT_MILLIS
                        ) {
                            dnsTapCount + 1
                        } else {
                            1
                        }
                        lastDnsTapAt = now
                        if (dnsTapCount >= DIAGNOSTIC_UNLOCK_TAPS) {
                            DiagnosticLogStore.unlock()
                            diagnosticState = DiagnosticLogStore.state()
                            diagnosticMessage =
                                "Odblokowano narzędzia diagnostyczne. Zapis nadal jest wyłączony."
                            dnsTapCount = 0
                        }
                    }
                },
            )
        }
        if (diagnosticState.unlocked) {
            item {
                DiagnosticLogSettings(
                    state = diagnosticState,
                    descriptionStatus = if (state.isLoadingDescriptions) {
                        descriptionLoadingLabel(state.descriptionLoadingSource)
                    } else if (state.pendingDescriptionCount > 0) {
                        "Opisy oczekujące na pobranie: ${state.pendingDescriptionCount}"
                    } else {
                        "Pobieranie opisów: bezczynne"
                    },
                    message = diagnosticMessage,
                    onEnabledChange = { enabled ->
                        DiagnosticLogStore.setEnabled(enabled)
                        diagnosticState = DiagnosticLogStore.state()
                        diagnosticMessage = if (enabled) {
                            "Rozpoczęto zapisywanie krótkich zdarzeń diagnostycznych."
                        } else {
                            "Zatrzymano zapisywanie. Dotychczasowy dziennik zachowano."
                        }
                    },
                    onExport = {
                        diagnosticExportLauncher.launch(diagnosticExportFileName())
                    },
                    onClear = {
                        DiagnosticLogStore.clear()
                        diagnosticState = DiagnosticLogStore.state()
                        diagnosticMessage = "Usunięto zapisany dziennik."
                    },
                    onSnapshot = {
                        viewModel.saveDiagnosticSnapshot { message ->
                            diagnosticMessage = message
                            diagnosticState = DiagnosticLogStore.state()
                        }
                    },
                    onCheckDatabase = {
                        viewModel.checkDiagnosticDatabase { message ->
                            diagnosticMessage = message
                            diagnosticState = DiagnosticLogStore.state()
                        }
                    },
                    onHide = {
                        DiagnosticLogStore.hide()
                        diagnosticState = DiagnosticLogStore.state()
                        diagnosticMessage = null
                        dnsTapCount = 0
                    },
                )
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Strona projektu", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Pobieranie aplikacji, informacje o wydaniu i dokumentacja projektu.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(
                        onClick = {
                            uriHandler.openUri("https://emmunioo.github.io/lewicowyt")
                        },
                    ) {
                        Text("Otwórz stronę lewicowYT")
                    }
                }
            }
        }
        item {
            TextButton(onClick = { thirdPartyNoticesVisible = true }) {
                Text("Licencje komponentów zewnętrznych")
            }
        }
    }
}

@Composable
private fun PrivacyDnsNote(
    unlockProgress: Int,
    onSecretTap: () -> Unit,
) {
    Card(
        Modifier
            .fillMaxWidth()
            // Brak clickable/ripple: pierwsze cztery dotknięcia są całkowicie
            // niewidoczne i karta nie zdradza ukrytej funkcji w semantyce UI.
            .pointerInput(onSecretTap) {
                detectTapGestures(onTap = { onSecretTap() })
            },
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Prywatny DNS", fontWeight = FontWeight.SemiBold)
            Text(
                "Domyślnie aplikacja szyfruje zapytania DNS przez AdGuard DNS (DoH): " +
                    "94.140.14.14 i 94.140.15.15. Aktywny Prywatny DNS ustawiony w Androidzie " +
                    "ma pierwszeństwo. Przy awarii obu serwerów aplikacja tymczasowo użyje " +
                    "DNS systemowego, aby nie utracić połączenia.",
                style = MaterialTheme.typography.bodySmall,
            )
            AnimatedVisibility(
                visible = unlockProgress in DIAGNOSTIC_VISIBLE_PROGRESS_START until
                    DIAGNOSTIC_UNLOCK_TAPS,
            ) {
                Text(
                    "Narzędzia diagnostyczne: jeszcze " +
                        diagnosticRemainingTapsLabel(
                            DIAGNOSTIC_UNLOCK_TAPS - unlockProgress,
                        ) + ".",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun DiagnosticLogSettings(
    state: DiagnosticLogState,
    descriptionStatus: String,
    message: String?,
    onEnabledChange: (Boolean) -> Unit,
    onExport: () -> Unit,
    onClear: () -> Unit,
    onSnapshot: () -> Unit,
    onCheckDatabase: () -> Unit,
    onHide: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Diagnostyka", fontWeight = FontWeight.SemiBold)
            SwitchSetting(
                title = "Zapisuj dziennik diagnostyczny",
                checked = state.enabled,
                onCheckedChange = onEnabledChange,
            )
            Text(
                "Dziennik zapisuje uruchomienia synchronizacji, jej wynik, " +
                    "problemy harmonogramu, niedostępną sieć oraz krótkie błędy. " +
                    "Dla opisów zapisuje źródło API/Web, czas oraz rozmiar przed i po " +
                    "kompresji. Zapisuje też treść lokalnych wyszukiwań i pokazane wyniki. " +
                    "Nie zapisuje klucza API, nagłówków autoryzacji, pełnych odpowiedzi " +
                    "HTTP ani powtarzających się komunikatów HTTP 200.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                descriptionStatus,
                style = MaterialTheme.typography.labelSmall,
                color = if (descriptionStatus.endsWith("bezczynne")) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
            Text(
                "W pamięci telefonu rekordy mają skrócony format binarny i są " +
                    "kompresowane natywnym DEFLATE na poziomie 9. Eksport jest " +
                    "czytelnym plikiem tekstowym GZIP.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Zapisano: ${formatDiagnosticBytes(state.storedBytes)}; " +
                    "zdarzenia: ${state.eventCount}",
                style = MaterialTheme.typography.labelSmall,
            )
            Button(
                onClick = onExport,
                enabled = state.eventCount > 0,
            ) {
                Text("Zapisz skompresowany log")
            }
            OutlinedButton(
                onClick = onSnapshot,
                enabled = state.enabled,
            ) {
                Text("Zapisz stan diagnostyczny teraz")
            }
            OutlinedButton(
                onClick = onCheckDatabase,
                enabled = state.enabled,
            ) {
                Text("Sprawdź bazę danych")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onClear,
                    enabled = state.eventCount > 0,
                ) {
                    Text("Wyczyść")
                }
                TextButton(onClick = onHide) {
                    Text("Wyłącz i ukryj")
                }
            }
            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun GlobalContentSettings(state: AppUiState, viewModel: AppViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Rodzaje materiałów",
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Ustawienia globalne dotyczą wszystkich twórców. Wyłączenie historii " +
                    "automatycznie wyłącza także powiadomienia danego rodzaju.",
                style = MaterialTheme.typography.bodySmall,
            )
            HistoryFilter.entries.forEach { type ->
                val historyEnabled = type in state.settings.globalHistoryTypes
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(contentTypeLabel(type), fontWeight = FontWeight.SemiBold)
                    SwitchSetting(
                        title = "Pokazuj w historii",
                        checked = historyEnabled,
                        onCheckedChange = {
                            viewModel.setGlobalHistoryType(type, it)
                        },
                    )
                    SwitchSetting(
                        title = "Wysyłaj powiadomienia",
                        checked = type in state.settings.globalNotificationTypes,
                        onCheckedChange = {
                            viewModel.setGlobalNotificationType(type, it)
                        },
                        enabled = historyEnabled,
                    )
                }
            }
            Text(
                "Wyłączone karty historii nie są pobierane z YouTube Web. RSS jest " +
                    "wspólnym, małym plikiem kanału, więc może zawierać identyfikatory " +
                    "różnych rodzajów, ale wyłączone materiały nie są zapisywane ani " +
                    "zgłaszane.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun YouTubeLinkSettings(state: AppUiState, viewModel: AppViewModel) {
    var menuOpen by remember { mutableStateOf(false) }
    var appPickerOpen by remember { mutableStateOf(false) }
    var launchableApps by remember { mutableStateOf(emptyList<pl.lewicowyt.notifier.links.ExternalAppOption>()) }
    var appPickerLoading by remember { mutableStateOf(false) }
    val youtubeLinks = remember { AppGraph.youtubeLinks }

    LaunchedEffect(appPickerOpen) {
        if (appPickerOpen) {
            appPickerLoading = true
            launchableApps = withContext(Dispatchers.Default) {
                youtubeLinks.launchableApplications()
            }
            appPickerLoading = false
        }
    }

    if (appPickerOpen) {
        AlertDialog(
            onDismissRequest = { appPickerOpen = false },
            title = { Text("Wybierz dowolną aplikację") },
            text = {
                when {
                    appPickerLoading -> Box(
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                    launchableApps.isEmpty() -> Text("Nie znaleziono aplikacji do wyboru.")
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                    ) {
                        items(
                            items = launchableApps,
                            key = { it.packageName },
                        ) { app ->
                            TextButton(
                                onClick = {
                                    viewModel.setOtherYouTubeAppPackage(app.packageName)
                                    appPickerOpen = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(app.label, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        app.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { appPickerOpen = false }) {
                    Text("Anuluj")
                }
            },
        )
    }

    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Otwieraj linki w:",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Box {
                OutlinedButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.semantics {
                        contentDescription = "Aplikacja do otwierania linków YouTube"
                        stateDescription = youtubeLinkTargetLabel(
                            state.settings.youtubeLinkTarget,
                        )
                    },
                ) {
                    Text(youtubeLinkTargetLabel(state.settings.youtubeLinkTarget))
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    YouTubeLinkTarget.entries.forEach { target ->
                        DropdownMenuItem(
                            text = { Text(youtubeLinkTargetLabel(target)) },
                            onClick = {
                                menuOpen = false
                                if (target == YouTubeLinkTarget.OTHER_APP) {
                                    appPickerOpen = true
                                } else {
                                    viewModel.setYouTubeLinkTarget(target)
                                }
                            },
                        )
                    }
                }
            }
            OutlinedButton(onClick = { appPickerOpen = true }) {
                Text("Wybierz dowolną inną aplikację")
            }
            if (state.settings.youtubeLinkTarget == YouTubeLinkTarget.OTHER_APP) {
                val selectedPackage = state.settings.otherYouTubeAppPackage
                val selectedLabel = remember(selectedPackage) {
                    youtubeLinks.applicationLabel(selectedPackage)
                }
                Text(
                    "Wybrano: ${selectedLabel ?: selectedPackage ?: "brak"}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                "Jedno ustawienie dotyczy filmów, Shortów, transmisji i kanałów.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun FastHistorySettings(
    state: AppUiState,
    viewModel: AppViewModel,
    openYouTubeLink: (String) -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    // Sekret nie trafia do SavedState/Bundle i jest czyszczony po zapisaniu.
    var apiKey by remember { mutableStateOf("") }
    val isValidating = state.apiKeyState == ApiKeyUiState.Validating

    LaunchedEffect(state.apiKeyState) {
        if (state.apiKeyState is ApiKeyUiState.Success) apiKey = ""
    }

    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Szybka historia",
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Opcjonalny klucz YouTube Data API v3 pozwala pobierać historię dużo szybciej " +
                    "i zatrzymywać pobieranie po osiągnięciu wybranego zakresu czasu. " +
                    "Gdy klucz jest aktywny, API wykrywa i klasyfikuje również materiały " +
                    "dla powiadomień. Historia zawsze zaczyna od lekkiego kanału RSS, " +
                    "który zwykle zwraca około 15 najnowszych pozycji. Starszy zakres " +
                    "uzupełnia następnie Data API albo, bez klucza, YouTube Web.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = {
                    apiKey = it.trim().take(MAX_YOUTUBE_API_KEY_CHARS)
                    viewModel.clearApiKeyStatus()
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !isValidating,
                label = { Text("Klucz YouTube Data API v3") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrectEnabled = false,
                ),
            )
            Button(
                onClick = { viewModel.setYoutubeApiKey(apiKey) },
                enabled = !isValidating,
            ) {
                if (isValidating) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(18.dp)
                            .semantics { contentDescription = "Weryfikowanie klucza API" },
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Weryfikuję…")
                } else {
                    Text(
                        if (apiKey.isBlank()) {
                            "Używaj trybu bez klucza"
                        } else {
                            "Zweryfikuj i zapisz klucz"
                        },
                    )
                }
            }
            when (val apiKeyState = state.apiKeyState) {
                ApiKeyUiState.Idle, ApiKeyUiState.Validating -> Unit
                is ApiKeyUiState.Success -> Text(
                    apiKeyState.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                is ApiKeyUiState.Error -> Text(
                    apiKeyState.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            TextButton(
                onClick = {
                    uriHandler.openUri(
                        "https://developers.google.com/youtube/v3/getting-started",
                    )
                },
            ) {
                Text("Jak uzyskać darmowy klucz API")
            }
            TextButton(
                onClick = {
                    openYouTubeLink("https://youtu.be/EPeDTRNKAVo")
                },
            ) {
                Text("Jak uzyskać darmowy klucz API - wideo poradnik")
            }
            Text(
                if (state.settings.youtubeApiNeedsValidation) {
                    "Zapisany wcześniej klucz nie został zweryfikowany i pozostaje wyłączony. " +
                        "Wklej go ponownie, aby aplikacja sprawdziła go przed aktywacją."
                } else if (!state.settings.youtubeApiEnabled) {
                    "Aktywny: YouTube RSS dla najnowszych pozycji, następnie YouTube Web."
                } else {
                    "Aktywny: YouTube RSS, następnie oficjalne Data API dla historii " +
                        "i powiadomień."
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ThemeSettings(state: AppUiState, viewModel: AppViewModel) {
    var themeMenuOpen by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Wygląd",
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            Text("Motyw aplikacji", fontWeight = FontWeight.SemiBold)
            Box {
                OutlinedButton(
                    onClick = { themeMenuOpen = true },
                    modifier = Modifier.semantics {
                        contentDescription = "Motyw aplikacji"
                        stateDescription = themeModeLabel(state.settings.themeMode)
                    },
                ) {
                    Text(themeModeLabel(state.settings.themeMode))
                }
                DropdownMenu(
                    expanded = themeMenuOpen,
                    onDismissRequest = { themeMenuOpen = false },
                ) {
                    ThemeMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(themeModeLabel(mode)) },
                            onClick = {
                                themeMenuOpen = false
                                viewModel.setThemeMode(mode)
                            },
                        )
                    }
                }
            }
            SwitchSetting(
                title = "Wysoki kontrast",
                checked = state.settings.highContrastEnabled,
                onCheckedChange = viewModel::setHighContrastEnabled,
            )
            Text(
                "Zwiększa kontrast tekstu, obramowań i elementów sterujących, " +
                    "zachowując wybrany kolor akcentu.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun AccentColorSettings(state: AppUiState, viewModel: AppViewModel) {
    var red by rememberSaveable(state.settings.accentColorArgb) {
        mutableFloatStateOf(AndroidColor.red(state.settings.accentColorArgb.toInt()).toFloat())
    }
    var green by rememberSaveable(state.settings.accentColorArgb) {
        mutableFloatStateOf(AndroidColor.green(state.settings.accentColorArgb.toInt()).toFloat())
    }
    var blue by rememberSaveable(state.settings.accentColorArgb) {
        mutableFloatStateOf(AndroidColor.blue(state.settings.accentColorArgb.toInt()).toFloat())
    }

    fun saveAccent() {
        val color = AndroidColor.rgb(red.toInt(), green.toInt(), blue.toInt())
        viewModel.setAccentColor(color.toLong() and 0xFFFFFFFFL)
    }

    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Kolor akcentu",
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clearAndSetSemantics { }
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            androidx.compose.ui.graphics.Color(
                                AndroidColor.rgb(red.toInt(), green.toInt(), blue.toInt()),
                            ),
                        ),
                )
                Text(
                    String.format(
                        Locale.ROOT,
                        "#%02X%02X%02X",
                        red.toInt(),
                        green.toInt(),
                        blue.toInt(),
                    ),
                    fontWeight = FontWeight.Medium,
                )
            }
            ColorChannelSlider("Czerwony", red, { red = it }, ::saveAccent)
            ColorChannelSlider("Zielony", green, { green = it }, ::saveAccent)
            ColorChannelSlider("Niebieski", blue, { blue = it }, ::saveAccent)
            Text(
                "Suwaki pozwalają wybrać dowolny kolor RGB. Zmiana jest zapisywana automatycznie.",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(
                onClick = {
                    red = AndroidColor.red(DEFAULT_ACCENT_COLOR_ARGB.toInt()).toFloat()
                    green = AndroidColor.green(DEFAULT_ACCENT_COLOR_ARGB.toInt()).toFloat()
                    blue = AndroidColor.blue(DEFAULT_ACCENT_COLOR_ARGB.toInt()).toFloat()
                    saveAccent()
                },
            ) {
                Text("Przywróć domyślną czerwień")
            }
        }
    }
}

@Composable
private fun ColorChannelSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Column {
        Text("$label: ${value.toInt()}", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = 0f..255f,
            steps = 254,
            modifier = Modifier.semantics {
                contentDescription = "Składowa koloru: $label"
                stateDescription = "${value.toInt()} z 255"
            },
        )
    }
}

@Composable
private fun ExactAlarmSettings(
    exactAlarmAccessGranted: Boolean,
    batteryOptimizationIgnored: Boolean,
    notificationPolicyAccessGranted: Boolean,
    requestExactAlarmAccess: () -> Unit,
    openBatteryOptimizationSettings: () -> Unit,
    requestNotificationPolicyAccess: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Działanie w tle",
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "Automatyczne sprawdzanie korzysta z AlarmManagera. Dokładny alarm " +
                "RTC_WAKEUP może obudzić urządzenie także przy wyłączonym ekranie, " +
                "a pobieranie wykonuje widoczna usługa pierwszoplanowa.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (exactAlarmAccessGranted) {
            Text(
                "Automatyczne alarmy są aktywne. Android może nadal zatrzymać " +
                    "aplikację po użyciu funkcji „Wymuś zatrzymanie”.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Text(
                "Automatyczne sprawdzanie nie zadziała, dopóki nie przyznasz " +
                    "systemowego dostępu „Alarmy i przypomnienia”.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(onClick = requestExactAlarmAccess) {
                Text("Zezwól na alarmy i przypomnienia")
            }
        }
        if (batteryOptimizationIgnored) {
            Text(
                "Optymalizacja baterii: Bez ograniczeń.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Text(
                "Optymalizacja baterii nadal ogranicza aplikację. Ustaw dla lewicowYT " +
                    "użycie baterii „Bez ograniczeń”, aby zwiększyć niezawodność " +
                    "powiadomień przy wyłączonym ekranie. Może to zwiększyć zużycie energii.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        OutlinedButton(onClick = openBatteryOptimizationSettings) {
            Text(
                if (batteryOptimizationIgnored) {
                    "Otwórz ustawienia aplikacji"
                } else {
                    "Ustaw działanie bez ograniczeń"
                },
            )
        }
        if (notificationPolicyAccessGranted) {
            Text(
                "Reakcja na „Nie przeszkadzać” jest aktywna. W tym trybie aplikacja " +
                    "nie łączy się z YouTube, a po jego zakończeniu nadrabia sprawdzenie.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Text(
                "Zezwól na dostęp do trybu „Nie przeszkadzać”, aby aplikacja mogła " +
                    "natychmiast wstrzymywać sprawdzanie i wznawiać je po zakończeniu " +
                    "trybu snu. Bez dostępu stan jest kontrolowany przy każdym alarmie.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(onClick = requestNotificationPolicyAccess) {
                Text("Zezwól na wykrywanie Nie przeszkadzać")
            }
        }
    }
}

internal fun shouldShowBatteryOptimizationNotice(
    isSettingsScreen: Boolean,
    batteryOptimizationIgnored: Boolean,
): Boolean = isSettingsScreen && !batteryOptimizationIgnored

@Composable
private fun SwitchSetting(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .semantics(mergeDescendants = true) {
                stateDescription = when {
                    !enabled -> "Niedostępne"
                    checked -> "Włączone"
                    else -> "Wyłączone"
                }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            modifier = Modifier
                .fillMaxWidth(0.82f)
                .padding(end = 12.dp),
        )
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

private fun kindLabel(kind: VideoKind): String = when (kind) {
    VideoKind.LIVE -> "Transmisja na żywo"
    VideoKind.UPCOMING -> "Zaplanowana transmisja"
    VideoKind.STREAM_ARCHIVE -> "Zapis transmisji"
    VideoKind.SHORT -> "Short"
    VideoKind.VIDEO -> "Film"
    VideoKind.UNKNOWN -> "Materiał"
}

private fun historyFilterLabel(filter: HistoryFilter): String = when (filter) {
    HistoryFilter.VIDEOS -> "Filmy"
    HistoryFilter.STREAMS -> "Streamy"
    HistoryFilter.SHORTS -> "Shorty"
}

private fun contentTypeLabel(type: HistoryFilter): String = when (type) {
    HistoryFilter.VIDEOS -> "Filmy"
    HistoryFilter.STREAMS -> "Streamy"
    HistoryFilter.SHORTS -> "Shorty"
}

private fun themeModeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "Zgodny z systemem"
    ThemeMode.LIGHT -> "Jasny"
    ThemeMode.DARK -> "Ciemny"
}

private fun intervalLabel(minutes: Int): String = when (minutes) {
    15 -> "Co 15 minut"
    30 -> "Co 30 minut"
    60 -> "Co godzinę"
    120 -> "Co 2 godziny"
    180 -> "Co 3 godziny"
    360 -> "Co 6 godzin"
    720 -> "Co 12 godzin"
    1440 -> "Raz dziennie"
    else -> "Co $minutes minut"
}

private fun historyRangeLabel(days: Int): String = when (days) {
    7 -> "Ostatni tydzień"
    14 -> "Ostatnie 2 tygodnie"
    21 -> "Ostatnie 3 tygodnie"
    30 -> "Ostatni miesiąc"
    60 -> "Ostatnie 2 miesiące"
    else -> "Ostatnie $days dni"
}

private fun descriptionLoadingLabel(source: String?): String = when (source) {
    "DATA_API" -> "Pobieranie opisów: YouTube Data API"
    "WEB" -> "Pobieranie opisów: YouTube Web"
    else -> "Pobieranie opisów"
}

internal fun youtubeLinkTargetLabel(target: YouTubeLinkTarget): String = when (target) {
    YouTubeLinkTarget.SYSTEM_DEFAULT -> "Domyślna aplikacja systemowa"
    YouTubeLinkTarget.ALWAYS_ASK -> "Pytaj za każdym razem"
    YouTubeLinkTarget.YOUTUBE -> "YouTube"
    YouTubeLinkTarget.ALTERNATIVE_YOUTUBE -> "ReVanced / inny klient YouTube"
    YouTubeLinkTarget.NEWPIPE -> "NewPipe"
    YouTubeLinkTarget.BROWSER -> "Przeglądarka"
    YouTubeLinkTarget.OTHER_APP -> "Dowolna inna aplikacja"
}

private fun formatClock(hour: Int, minute: Int): String =
    String.format(Locale.ROOT, "%02d:%02d", hour, minute)

private fun diagnosticExportFileName(): String =
    "lewicowYT-diagnostyka-${DIAGNOSTIC_FILE_TIME_FORMAT.format(Instant.now())}.txt.gz"

private fun formatDiagnosticBytes(bytes: Long): String = when {
    bytes < 1_024L -> "$bytes B"
    else -> String.format(Locale.ROOT, "%.1f KiB", bytes / 1_024.0)
}

private fun formatApkSize(bytes: Long): String =
    String.format(Locale.forLanguageTag("pl"), "%.1f MB", bytes / (1024.0 * 1024.0))

private fun diagnosticRemainingTapsLabel(remaining: Int): String = when (remaining) {
    1 -> "1 dotknięcie"
    else -> "$remaining dotknięcia"
}

private val HISTORY_RANGES = listOf(7, 14, 21, 30, 60)

private val DATE_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
    .withZone(ZoneId.systemDefault())

private val DIAGNOSTIC_FILE_TIME_FORMAT = DateTimeFormatter.ofPattern("uuuuMMdd-HHmm")
    .withZone(ZoneId.systemDefault())

private const val DIAGNOSTIC_UNLOCK_TAPS = 9
private const val DIAGNOSTIC_VISIBLE_PROGRESS_START = 5
private const val DIAGNOSTIC_UNLOCK_SEQUENCE_TIMEOUT_MILLIS = 3_000L

private fun formatTime(epochMillis: Long): String =
    DATE_FORMATTER.format(Instant.ofEpochMilli(epochMillis))

private fun historyItemDescription(item: HistoryItem): String =
    "${item.creatorName}. ${item.title}. " +
        "${kindLabel(item.displayKindAt(System.currentTimeMillis()))}. " +
        formatTime(item.publishedAtMillis)

private fun youtubeWatchUrl(videoId: String): String? =
    videoId.takeIf(YOUTUBE_VIDEO_ID::matches)
        ?.let { "https://www.youtube.com/watch?v=$it" }

private val YOUTUBE_VIDEO_ID = Regex("""[A-Za-z0-9_-]{11}""")

internal fun creatorYouTubeChannelUrl(creator: Creator): String? {
    val channel = creator.profileChannelId
        ?.let { profileId ->
            creator.sources.firstOrNull {
                it.type == SourceType.CHANNEL && it.externalId == profileId
            }
        }
        ?: creator.sources.firstOrNull { it.type == SourceType.CHANNEL }
    channel?.externalId
        ?.takeIf(YOUTUBE_CHANNEL_ID::matches)
        ?.let { return "https://www.youtube.com/channel/$it" }

    val source = channel ?: creator.sources.firstOrNull() ?: return null
    return source.url.takeIf(::isSafeYouTubeUrl)
}

private fun isSafeYouTubeUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme.equals("https", ignoreCase = true) &&
        uri.host?.lowercase(Locale.ROOT) in YOUTUBE_HOSTS &&
        uri.userInfo == null &&
        uri.port in setOf(-1, 443)
}.getOrDefault(false)

private val YOUTUBE_CHANNEL_ID = Regex("""UC[A-Za-z0-9_-]{18,}""")
private val YOUTUBE_HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com")
