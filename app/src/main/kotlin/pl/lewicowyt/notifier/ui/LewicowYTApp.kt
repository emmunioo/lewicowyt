package pl.lewicowyt.notifier.ui

import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
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
import pl.lewicowyt.notifier.BuildConfig
import pl.lewicowyt.notifier.R
import pl.lewicowyt.notifier.data.DEFAULT_ACCENT_COLOR_ARGB
import pl.lewicowyt.notifier.data.MAX_YOUTUBE_API_KEY_CHARS
import pl.lewicowyt.notifier.data.ThemeMode
import pl.lewicowyt.notifier.model.Creator
import pl.lewicowyt.notifier.model.HistoryFilter
import pl.lewicowyt.notifier.model.HistoryItem
import pl.lewicowyt.notifier.model.SourceType
import pl.lewicowyt.notifier.model.VideoKind
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
    whatsNewVisible: Boolean = false,
    acknowledgeWhatsNew: () -> Unit = {},
    requestExactAlarmAccess: () -> Unit = {},
    openBatteryOptimizationSettings: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
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
            title = { Text("Co nowego w lewicowYT 1.4-beta") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 440.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Stabilniejsza historia bez klucza API", fontWeight = FontWeight.Bold)
                    Text(
                        "Najnowsze materiały nadal pojawiają się szybko dzięki RSS. " +
                            "Starsza historia jest teraz pobierana bezpośrednio z właściwej " +
                            "karty YouTube przy mniejszej liczbie zapytań.",
                    )
                    Spacer(Modifier.height(2.dp))
                    Text("Poprawny podział materiałów", fontWeight = FontWeight.Bold)
                    Text(
                        "Filmy, Shorty oraz transmisje są rozpoznawane z aktualnych danych " +
                            "YouTube. Kanał bez karty transmisji nie może już zwrócić " +
                            "zwykłych filmów jako streamów.",
                    )
                    Spacer(Modifier.height(2.dp))
                    Text("Historia nie znika podczas poprawiania typu", fontWeight = FontWeight.Bold)
                    Text(
                        "Ponowna klasyfikacja zachowuje dotychczasowy rodzaj materiału, " +
                            "dopóki YouTube nie dostarczy pewnej odpowiedzi. Nieudana próba " +
                            "nie zamienia już streamów ani Shortów w filmy.",
                    )
                    Spacer(Modifier.height(2.dp))
                    Text("Klucz YouTube API pozostaje opcjonalny", fontWeight = FontWeight.Bold)
                    Text(
                        "Aplikacja działa również bez klucza. Data API może nadal szybciej " +
                            "pobierać długą historię i jest mniej podatne na zmiany strony " +
                            "YouTube, dlatego pozostaje opcjonalnym trybem zwiększonej " +
                            "stabilności.",
                    )
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
                        TextButton(onClick = { selectedScreenIndex = index }) {
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
                Screen.CREATORS -> CreatorsScreen(state, viewModel)
                Screen.HISTORY -> HistoryScreen(state, viewModel)
                Screen.NOTIFICATIONS -> NotificationsScreen(state)
                Screen.SETTINGS -> SettingsScreen(
                    state = state,
                    viewModel = viewModel,
                    exactAlarmAccessGranted = exactAlarmAccessGranted,
                    batteryOptimizationIgnored = batteryOptimizationIgnored,
                    requestExactAlarmAccess = requestExactAlarmAccess,
                    openBatteryOptimizationSettings = openBatteryOptimizationSettings,
                )
            }
        }
    }
}

@Composable
private fun CreatorsScreen(state: AppUiState, viewModel: AppViewModel) {
    val uriHandler = LocalUriHandler.current
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Szukaj twórcy") },
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Wybrano: ${state.selectedCreatorIds.size}/${state.allCreatorCount}")
            Button(
                onClick = viewModel::syncNow,
                enabled = !state.isRefreshing,
                modifier = Modifier.widthIn(min = 156.dp),
                colors = ButtonDefaults.buttonColors(
                    disabledContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    disabledContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) {
                if (state.isRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
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
                    modifier = Modifier.padding(12.dp),
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
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.creators, key = Creator::id) { creator ->
                CreatorRow(
                    creator = creator,
                    avatarUrl = state.creatorAvatars[creator.id],
                    selected = creator.id in state.selectedCreatorIds,
                    onSelectedChange = { viewModel.setCreatorSelected(creator.id, it) },
                    onOpenChannel = {
                        creatorYouTubeChannelUrl(creator)?.let(uriHandler::openUri)
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
    onSelectedChange: (Boolean) -> Unit,
    onOpenChannel: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onSelectedChange(!selected) },
                onLongClickLabel = "Otwórz kanał w YouTube",
                onLongClick = onOpenChannel,
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProfileImage(
                url = avatarUrl,
                creatorName = creator.name,
                modifier = Modifier.size(48.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp, end = 8.dp),
            ) {
                Text(creator.name, fontWeight = FontWeight.SemiBold)
                Text(
                    text = if (creator.sources.size == 1) {
                        "1 źródło YouTube"
                    } else {
                        "${creator.sources.size} źródła YouTube"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Checkbox(checked = selected, onCheckedChange = null)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryScreen(state: AppUiState, viewModel: AppViewModel) {
    val uriHandler = LocalUriHandler.current
    var rangeMenuOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val shouldLoadMore by remember(state.history.size, state.historyHasMore) {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            state.historyHasMore &&
                (state.history.isEmpty() || lastVisible >= state.history.lastIndex - 3)
        }
    }

    LaunchedEffect(shouldLoadMore, state.isLoadingHistory, state.history.size) {
        if (shouldLoadMore && !state.isLoadingHistory) viewModel.loadMoreHistory()
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Od najnowszych", fontWeight = FontWeight.SemiBold)
            Box {
                OutlinedButton(onClick = { rangeMenuOpen = true }) {
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
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HistoryFilter.entries.forEach { filter ->
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
        }

        if (state.history.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (state.isLoadingHistory) {
                    CircularProgressIndicator()
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
                        if (state.selectedCreatorIds.isEmpty()) {
                            "Zaznacz co najmniej jednego twórcę. Historia pokazuje tylko " +
                                "aktualnie wybrane kanały."
                        } else if (state.settings.historyFilters.isEmpty()) {
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
                Card(
                    modifier = Modifier.fillMaxWidth().clickable {
                        youtubeWatchUrl(item.videoId)?.let(uriHandler::openUri)
                    },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
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
                                .padding(start = 12.dp),
                        ) {
                            Text(item.creatorName, fontWeight = FontWeight.SemiBold)
                            Text(
                                item.title,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    "${kindLabel(item.kind)} · " +
                                        formatTime(item.publishedAtMillis),
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
            if (state.historyHasMore) {
                item(key = "history-loading") {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(72.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (state.isLoadingHistory) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp))
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
                            Text(error, color = MaterialTheme.colorScheme.error)
                            OutlinedButton(onClick = viewModel::retryHistoryLoading) {
                                Text("Spróbuj ponownie")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationsScreen(state: AppUiState) {
    val uriHandler = LocalUriHandler.current

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
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                "Najnowsze powiadomienia",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        items(state.notifications, key = HistoryItem::videoId) { item ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable {
                    youtubeWatchUrl(item.videoId)?.let(uriHandler::openUri)
                },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
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
                            .padding(start = 12.dp),
                    ) {
                        Text(item.creatorName, fontWeight = FontWeight.SemiBold)
                        Text(
                            item.title,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "${kindLabel(item.kind)} · " +
                                    formatTime(item.publishedAtMillis),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    state: AppUiState,
    viewModel: AppViewModel,
    exactAlarmAccessGranted: Boolean,
    batteryOptimizationIgnored: Boolean,
    requestExactAlarmAccess: () -> Unit,
    openBatteryOptimizationSettings: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val uriHandler = LocalUriHandler.current
    val intervals = listOf(15, 30, 60, 120, 180, 360, 720, 1440)
    var intervalMenuOpen by remember { mutableStateOf(false) }
    var clearConfirmationVisible by rememberSaveable { mutableStateOf(false) }
    var thirdPartyNoticesVisible by rememberSaveable { mutableStateOf(false) }
    val thirdPartyNotices = remember(resources) {
        resources.openRawResource(R.raw.third_party_notices)
            .bufferedReader()
            .use { it.readText() }
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
                        .verticalScroll(rememberScrollState()),
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
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ThemeSettings(state, viewModel)
        }
        item {
            FastHistorySettings(state, viewModel)
        }
        item {
            ExactAlarmSettings(
                exactAlarmAccessGranted = exactAlarmAccessGranted,
                batteryOptimizationIgnored = batteryOptimizationIgnored,
                requestExactAlarmAccess = requestExactAlarmAccess,
                openBatteryOptimizationSettings = openBatteryOptimizationSettings,
            )
        }
        item {
            Text("Częstotliwość", style = MaterialTheme.typography.titleMedium)
            Box {
                OutlinedButton(onClick = { intervalMenuOpen = true }) {
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
                Text("Godzina codziennego sprawdzenia", style = MaterialTheme.typography.titleMedium)
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
                    Text("Aktualizacje", fontWeight = FontWeight.SemiBold)
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
                                modifier = Modifier.size(18.dp),
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
                        is UpdateUiState.Error -> Text(
                            "Nie udało się sprawdzić aktualizacji: ${updateState.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        is UpdateUiState.Available -> {
                            Text("Dostępna wersja: ${updateState.update.version}")
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
                                Text("Pobierz i przygotuj aktualizację")
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
                                    modifier = Modifier.size(18.dp),
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
                            "zanim rozpocznie się pobieranie. Aplikacja sprawdza do 6 źródeł " +
                            "równocześnie i ponawia rozległą awarię najwyżej dwa razy. " +
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
            PrivacyDnsNote()
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
private fun PrivacyDnsNote() {
    Card(Modifier.fillMaxWidth()) {
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
        }
    }
}

@Composable
private fun FastHistorySettings(state: AppUiState, viewModel: AppViewModel) {
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
            Text("Szybka historia", style = MaterialTheme.typography.titleMedium)
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
                        modifier = Modifier.size(18.dp),
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
                    uriHandler.openUri("https://youtu.be/EPeDTRNKAVo")
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
            Text("Wygląd", style = MaterialTheme.typography.titleMedium)
            Text("Motyw aplikacji", fontWeight = FontWeight.SemiBold)
            Box {
                OutlinedButton(onClick = { themeMenuOpen = true }) {
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
            Text("Kolor akcentu", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
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
        )
    }
}

@Composable
private fun ExactAlarmSettings(
    exactAlarmAccessGranted: Boolean,
    batteryOptimizationIgnored: Boolean,
    requestExactAlarmAccess: () -> Unit,
    openBatteryOptimizationSettings: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Działanie w tle", style = MaterialTheme.typography.titleMedium)
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
        modifier = Modifier.fillMaxWidth(),
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
            onCheckedChange = onCheckedChange,
            enabled = enabled,
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

private fun formatClock(hour: Int, minute: Int): String =
    String.format(Locale.ROOT, "%02d:%02d", hour, minute)

private val HISTORY_RANGES = listOf(7, 14, 21, 30, 60)

private val DATE_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
    .withZone(ZoneId.systemDefault())

private fun formatTime(epochMillis: Long): String =
    DATE_FORMATTER.format(Instant.ofEpochMilli(epochMillis))

private fun youtubeWatchUrl(videoId: String): String? =
    videoId.takeIf(YOUTUBE_VIDEO_ID::matches)
        ?.let { "https://www.youtube.com/watch?v=$it" }

private val YOUTUBE_VIDEO_ID = Regex("""[A-Za-z0-9_-]{11}""")

internal fun creatorYouTubeChannelUrl(creator: Creator): String? {
    val channel = creator.sources.firstOrNull { it.type == SourceType.CHANNEL }
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
