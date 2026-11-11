@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package pl.lewicowyt.notifier.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
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
import pl.lewicowyt.notifier.links.isSafeExternalHttpsUrl
import pl.lewicowyt.notifier.AppGraph
import pl.lewicowyt.notifier.R
import pl.lewicowyt.notifier.community.CommunityAdPolicy
import pl.lewicowyt.notifier.community.CommunityArticle
import pl.lewicowyt.notifier.community.CommunityArticleKind
import pl.lewicowyt.notifier.community.CommunityCapability
import pl.lewicowyt.notifier.community.CommunityConsentState
import pl.lewicowyt.notifier.community.CommunityContentFilter
import pl.lewicowyt.notifier.community.AppTerms
import pl.lewicowyt.notifier.network.PrivacySettings
import pl.lewicowyt.notifier.community.CommunityDefaults
import pl.lewicowyt.notifier.community.CommunityTerms
import androidx.compose.ui.graphics.Color
import pl.lewicowyt.notifier.community.CommunityTransport
import pl.lewicowyt.notifier.community.communityTransportOf
import pl.lewicowyt.notifier.community.push.EmbeddedPushService
import pl.lewicowyt.notifier.community.push.PushSettings
import pl.lewicowyt.notifier.community.CommunityCreator
import pl.lewicowyt.notifier.community.CommunityContentCodec
import pl.lewicowyt.notifier.community.CommunityMeme
import pl.lewicowyt.notifier.images.CommunityImageExporter
import pl.lewicowyt.notifier.images.CommunityImageLoader
import pl.lewicowyt.notifier.community.communityItemId
import pl.lewicowyt.notifier.i18n.Pronoun
import pl.lewicowyt.notifier.i18n.Translations
import pl.lewicowyt.notifier.i18n.tr
import pl.lewicowyt.notifier.community.CommunityNotificationPolicy
import pl.lewicowyt.notifier.community.CommunityUpdateKind
import pl.lewicowyt.notifier.community.DEFAULT_COMMUNITY_PORT
import pl.lewicowyt.notifier.community.CommunityLinkItem
import pl.lewicowyt.notifier.community.CommunityLinkKind
import pl.lewicowyt.notifier.community.CommunityManifest
import pl.lewicowyt.notifier.community.CommunityForeignItem
import pl.lewicowyt.notifier.community.CommunityPost
import pl.lewicowyt.notifier.community.CommunityServerState
import pl.lewicowyt.notifier.community.CommunityTermsPrompt
import pl.lewicowyt.notifier.community.CommunityPrimaryHealth
import pl.lewicowyt.notifier.community.CommunityUiState
import pl.lewicowyt.notifier.data.DEFAULT_ACCENT_COLOR_ARGB
import pl.lewicowyt.notifier.data.AppSettings
import pl.lewicowyt.notifier.data.MAX_YOUTUBE_API_KEY_CHARS
import pl.lewicowyt.notifier.data.ThemeMode
import pl.lewicowyt.notifier.data.YouTubeLinkTarget
import pl.lewicowyt.notifier.data.isHistoryEnabledFor
import pl.lewicowyt.notifier.data.isNotificationEnabledFor
import pl.lewicowyt.notifier.diagnostics.DiagnosticLogState
import pl.lewicowyt.notifier.diagnostics.DiagnosticLogStore
import pl.lewicowyt.notifier.diagnostics.DiagnosticCategory
import pl.lewicowyt.notifier.diagnostics.DiagnosticLevel
import pl.lewicowyt.notifier.model.Creator
import pl.lewicowyt.notifier.model.DescriptionAvailability
import pl.lewicowyt.notifier.model.HistoryFilter
import pl.lewicowyt.notifier.model.HistoryItem
import pl.lewicowyt.notifier.model.MaterialStatusBadge
import pl.lewicowyt.notifier.model.SourceType
import pl.lewicowyt.notifier.model.VideoKind
import pl.lewicowyt.notifier.model.displayKindAt
import pl.lewicowyt.notifier.model.statusBadgesAt
import pl.lewicowyt.notifier.updates.UpdatePolicy

private enum class Screen(val title: String) {
    CREATORS("Twórcy"),
    // Historia i dawne Powiadomienia są jedną zakładką z przełącznikiem „Wszystkie / 🔔 Powiadomienia"
    HISTORY("Historia"),
    COMMUNITY("Społeczność"),
    SETTINGS("Ustawienia"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LewicowYTApp(
    viewModel: AppViewModel,
    whatsNewVisible: Boolean = false,
    acknowledgeWhatsNew: () -> Unit = {},
    batteryOptimizationIgnored: Boolean = true,
    openBatteryOptimizationSettings: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val whatsNewUriHandler = LocalUriHandler.current
    val youtubeLinks = remember { AppGraph.youtubeLinks }
    val openYouTubeLink: (String) -> Unit = { url ->
        youtubeLinks.open(
            url = url,
            target = state.settings.youtubeLinkTarget,
            otherAppPackage = state.settings.otherYouTubeAppPackage,
        )
    }
    val community by viewModel.communityUiState.collectAsStateWithLifecycle()
    // otwieranie linków społeczności wg deklarowanych platform (twitter/twitch/…): jeśli host pasuje do
    // platformy i user ustawił dla niej preferencję → otwórz zgodnie z nią; inaczej domyślnie przeglądarka.
    val communityLinkPlatforms by viewModel.communityLinkPlatforms.collectAsStateWithLifecycle()
    val openCommunityLink: (String) -> Unit = openLink@{ url ->
        // C-22i: ta sama kontrola niezależnie od preferencji platformy (https, bez userinfo; od D-16 dowolny port)
        if (!isSafeExternalHttpsUrl(url)) return@openLink
        val host = runCatching { java.net.URI(url).host?.lowercase()?.removePrefix("www.") }.getOrNull()
        val platform = host?.let { h ->
            communityLinkPlatforms.firstOrNull { p -> p.hosts.any { h == it || h.endsWith(".$it") } }
        }
        val pref = platform?.let { state.settings.communityLinkPrefs[it.id] }
        if (pref != null) youtubeLinks.openExternal(url, pref.target, pref.packageName)
        else whatsNewUriHandler.openUri(url)
    }
    // zakładka Społeczności jest warunkowa: pokazujemy ją tylko, gdy jakiś serwer coś wnosi
    val visibleScreens = Screen.entries.filter { it != Screen.COMMUNITY || community.tabVisible }
    var selectedScreenName by rememberSaveable { mutableStateOf(Screen.CREATORS.name) }
    var batteryOptimizationNoticeVisible by remember { mutableStateOf(false) }
    var communityManagerOpen by rememberSaveable { mutableStateOf(false) }
    val screen = visibleScreens.firstOrNull { it.name == selectedScreenName } ?: Screen.CREATORS

    // podzakładka Ustawień: pełnoekranowy menedżer serwerów społeczności (nie zaśmieca Ustawień)
    if (communityManagerOpen) {
        CommunityServersManagerScreen(viewModel = viewModel, onBack = { communityManagerOpen = false })
        return
    }

    LaunchedEffect(screen) {
        // Historia odświeża się TYLKO gestem (pull-to-refresh) — nie wymuszamy przy wejściu.
        // Widok 🔔 odświeża się przy wejściu w niego (AppViewModel.setHistoryView). Opuszczenie zakładki
        // w widoku 🔔 oznacza powiadomienia jako widziane (licznik na przełączniku znika).
        if (screen != Screen.HISTORY && state.historyView == HistoryView.NOTIFICATIONS) {
            viewModel.markNotificationsSeen()
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
            selectedScreenName = Screen.HISTORY.name // widok 🔔 ustawia AppViewModel.openNotifications
        }
    }
    val communityNavigationRequest by viewModel.communityNavigationRequest.collectAsStateWithLifecycle()
    LaunchedEffect(communityNavigationRequest) {
        if (communityNavigationRequest > 0L) {
            communityManagerOpen = false // ekran zarządzania serwerami zasłaniałby zakładkę
            selectedScreenName = Screen.COMMUNITY.name
        }
    }

    community.pendingTerms?.let { prompt ->
        CommunityTermsDialog(
            prompt = prompt,
            onAccept = viewModel::acceptCommunityTerms,
            onDecline = viewModel::declineCommunityTerms,
            onDismiss = viewModel::dismissCommunityTermsPrompt,
        )
    }

    // pierwsze uruchomienie: regulamin APLIKACJI (zasłona). Do akceptacji aplikacja nie łączy się z serwerem domyślnym.
    // Gdy użytkownik wybrał już inny serwer główny, serwer domyślny i tak nie jest używany — zasłony nie pokazujemy.
    val appTermsAccepted by viewModel.appTermsAccepted.collectAsStateWithLifecycle()
    val defaultServerActive = community.primaryServerId.isEmpty() || CommunityDefaults.isDefault(community.primaryServerId)
    if (!appTermsAccepted && defaultServerActive) {
        DefaultServerTermsOverlay(
            title = "Regulamin aplikacji lewicowYT",
            intro = "Przed pierwszym użyciem zapoznaj się z regulaminem. Akceptując go, zezwalasz aplikacji na łączenie się z " +
                "serwerem domyślnym — do tego czasu aplikacja się z nim nie łączy. Serwer domyślny możesz zastąpić własnym.",
            versionLabel = AppTerms.VERSION,
            text = AppTerms.TEXT,
            acceptLabel = "Akceptuję regulamin",
            onAccept = viewModel::acceptAppTerms,
            onChooseOtherServer = { communityManagerOpen = true },
        )
    } else {
        // T-11: zmieniony regulamin serwera domyślnego — zasłona na całym ekranie do czasu decyzji
        community.defaultServerTermsToAccept?.let { terms ->
            DefaultServerTermsOverlay(
                title = "Zmieniony regulamin serwera domyślnego",
                intro = "Serwer domyślny zmienił swój regulamin. Do czasu Twojej decyzji nie wysyła do Ciebie powiadomień " +
                    "o nowych filmach. Zaakceptuj nowy regulamin albo wybierz własny serwer główny — wtedy serwer " +
                    "domyślny zostanie wyłączony.",
                versionLabel = terms.versionLabel,
                text = terms.text,
                acceptLabel = "Akceptuję nowy regulamin",
                onAccept = viewModel::acceptDefaultServerTerms,
                onChooseOtherServer = { communityManagerOpen = true },
            )
        }
    }

    // „Co nowego” i podpowiedź o baterii czekają, aż użytkownik rozstrzygnie zasłonę z regulaminem (najpierw regulamin, potem nowości)
    val termsOverlayShowing = (!appTermsAccepted && defaultServerActive) || community.defaultServerTermsToAccept != null
    if (!termsOverlayShowing && whatsNewVisible) {
        WhatsNewDialog(
            onDismiss = acknowledgeWhatsNew,
            onOpenProjectPage = { whatsNewUriHandler.openUri("https://emmunioo.github.io/lewicowyt") },
        )
    } else if (!termsOverlayShowing && batteryOptimizationNoticeVisible) {
        AlertDialog(
            onDismissRequest = { batteryOptimizationNoticeVisible = false },
            title = { Text("Zalecane działanie bez ograniczeń") },
            text = {
                Text(
                    "Aby powiadomienia docierały możliwie niezawodnie również przy " +
                        "wyłączonym ekranie, ustaw dla lewicowYT użycie baterii " +
                        "„Bez ograniczeń”. Niektóre telefony inaczej opóźniają " +
                        "powiadomienia nawet o kilka godzin. Może to nieznacznie " +
                        "zwiększyć zużycie energii.\n\nPo kliknięciu poniżej otworzą " +
                        "się ustawienia aplikacji — wybierz „Bateria”, a następnie " +
                        "„Bez ograniczeń”.",
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
                    visibleScreens.forEach { item ->
                        TextButton(
                            onClick = { selectedScreenName = item.name },
                            modifier = Modifier.semantics {
                                role = Role.Tab // TalkBack: „karta, 2 z 4"
                                selected = item == screen
                                // „🔔3" czytane dosłownie brzmi źle — dla czytnika pełne zdanie
                                if (item == Screen.HISTORY && state.unseenNotificationCount > 0) {
                                    contentDescription =
                                        "${pl.lewicowyt.notifier.i18n.Translations.tr(item.title)}, nowe powiadomienia: ${state.unseenNotificationCount}"
                                }
                                stateDescription = if (item == screen) {
                                    "Wybrana karta"
                                } else {
                                    "Karta niewybrana"
                                }
                            },
                        ) {
                            Text(
                                // nowe powiadomienia widać już na pasku zakładek: „Historia · 🔔3"
                                text = if (item == Screen.HISTORY && state.unseenNotificationCount > 0) {
                                    "${tr(item.title)} · 🔔${state.unseenNotificationCount}"
                                } else {
                                    tr(item.title)
                                },
                                fontWeight = if (item == screen) {
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
                Screen.COMMUNITY -> CommunityScreen(
                    community = community,
                    openYouTubeLink = openYouTubeLink,
                    openCommunityLink = openCommunityLink,
                    onToggleFavorite = viewModel::toggleCommunityFavorite,
                    onRefresh = viewModel::refreshCommunityServers,
                    onSaveMeme = viewModel::saveMeme,
                    imageRetentionDays = state.settings.communityImageRetentionDays,
                )
                Screen.SETTINGS -> SettingsScreen(
                    state = state,
                    viewModel = viewModel,
                    openYouTubeLink = openYouTubeLink,
                    onOpenCommunityManager = { communityManagerOpen = true },
                )
            }
        }
    }
}

/**
 * Baner ostrzegający, że serwer społeczności wskazany jako „serwer główny" (zastępujący serwer domyślny)
 * po kilku sprawdzeniach daje inne wyniki niż YouTube RSS (to samo źródło, co serwer domyślny). Pokazuje
 * DOKŁADNIE, co się różni: brak/nadmiar/duplikaty twórców lub filmów, wysokie opóźnienia, brak odpowiedzi.
 */
@Composable
private fun PrimaryHealthBanner(health: CommunityPrimaryHealth) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                tr("Serwer główny może nie działać poprawnie"),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                tr(
                    "Ten serwer zastępuje serwer domyślny, ale po kilku sprawdzeniach jego wyniki różnią się " +
                        "od YouTube. Co dokładnie się różni:",
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            health.differences.forEach { diff ->
                Text("• ${diff.detail}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * warunkowa zakładka „Społeczność": scalona oś czasu treści z serwerów, na które user się
 * zgodził — od najnowszych, z filtrem po serwerze. dodawanie serwerów i akceptacja regulaminów
 * są w Ustawieniach. treść przygotował serwer, apka tylko ją pokazuje (zero logowania/scrapowania).
 * linki YT idą przez launcher; obce (https) otwiera przeglądarka.
 */
@Composable
private fun CommunityScreen(
    community: CommunityUiState,
    openYouTubeLink: (String) -> Unit,
    openCommunityLink: (String) -> Unit,
    onToggleFavorite: (String, String, Boolean) -> Unit,
    onRefresh: () -> Unit,
    onSaveMeme: (String, String) -> Unit,
    imageRetentionDays: Int,
) {
    val uriHandler = LocalUriHandler.current
    // zapis mema wymaga uprawnienia do zapisu tylko na Androidzie < 10; wyżej działa bez pytania.
    // Trzymamy ostatnie żądanie, żeby po przyznaniu uprawnienia dokończyć zapis.
    val context = LocalContext.current
    var pendingSave by remember { mutableStateOf<Pair<String, String>?>(null) }
    val storagePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val req = pendingSave
        pendingSave = null
        if (granted && req != null) onSaveMeme(req.first, req.second)
    }
    val saveMeme: (String, String) -> Unit = save@{ url, name ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            onSaveMeme(url, name)
        } else {
            pendingSave = url to name
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
    // odświeżanie TYLKO gestem (pull-to-refresh) — nie wymuszamy go przy wejściu na zakładkę.
    // Świeżość w tle zapewnia odświeżanie po starcie aplikacji + push + godzinny fallback.
    // wbudowany serwer domyślny jest ukryty — nie ma go w filtrze serwerów zakładki
    // T-10: liczone raz na zmianę listy serwerów (dawniej przy każdym przerysowaniu: nowa lista i głębokie
    // porównywanie pełnych stanów serwerów w kluczach remember niżej)
    val active = remember(community.servers) {
        community.servers.filter { it.contributesToTab && !CommunityDefaults.isDefault(it.record.serverId) }
    }
    var selectedServerId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedCategory by rememberSaveable { mutableStateOf<CommunityFeedCategory?>(null) }

    // wybrany serwer mógł zniknąć (usunięty/wyłączony) → wróć do „Wszystkie"
    val activeIds = active.map { it.record.serverId }.toSet()
    val effectiveServerId = selectedServerId?.takeIf { it in activeIds }
    val fullFeed = remember(active, effectiveServerId) { buildCommunityFeed(active, effectiveServerId) }
    // kategorie faktycznie obecne w feedzie (po filtrze serwera) — tylko takie pokazujemy jako filtr typu
    val presentCategories = remember(fullFeed) {
        CommunityFeedCategory.entries.filter { cat -> fullFeed.any { it.category == cat } }
    }
    // wybrana kategoria mogła zniknąć → wróć do „Wszystkie"
    val effectiveCategory = selectedCategory?.takeIf { it in presentCategories }
    val feed = remember(fullFeed, effectiveCategory) {
        if (effectiveCategory == null) fullFeed else fullFeed.filter { it.category == effectiveCategory }
    }
    // D-17 (A-49): ulubione ze WSZYSTKICH serwerów — także wyłączonych — żeby retencja nie kasowała ich obrazów.
    // E-07: retencja po samym kluczu deduplikacji (itemId == dedupKey) — ulubiony zapisany pod serwerem B
    // zostaje chroniony także wtedy, gdy zwycięzcą deduplikacji jest dziś serwer A (jak gwiazdka, A-55)
    val favoriteDedupKeys = remember(community.servers) {
        community.servers.flatMap { it.favoriteItemIds }.toSet()
    }

    // Ładowanie „jak w przeglądarce": z WYPRZEDZENIEM wczytujemy obrazy pozycji tuż pod widocznym obszarem,
    // żeby były gotowe, zanim do nich doscrollujesz (zamiast czekać, aż wejdą na ekran). Prefetch jest
    // nieblokujący, dedup-owany i ograniczony limitem równoległości loadera (nie zalewa sieci/Tora).
    val listState = rememberLazyListState()
    val prefetchContext = LocalContext.current
    LaunchedEffect(listState, feed) {
        if (feed.isEmpty()) return@LaunchedEffect
        val keyToIndex = feed.withIndex().associate { (i, e) -> feedListKey(e) to i }
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo
                .mapNotNull { (it.key as? String)?.let(keyToIndex::get) }
                .maxOrNull() ?: -1
        }.collect { lastVisibleFeedIdx ->
            if (lastVisibleFeedIdx < 0) return@collect
            val end = (lastVisibleFeedIdx + COMMUNITY_PREFETCH_AHEAD).coerceAtMost(feed.lastIndex)
            for (i in (lastVisibleFeedIdx + 1)..end) prefetchFeedImages(prefetchContext, feed[i])
        }
    }

    // RETENCJA: globalnie (po dacie, niezależnie od wybranego serwera/filtra) trzymamy na dysku obrazy 30
    // NAJNOWSZYCH pozycji, ulubionych oraz wszystkich z okna „Obrazy postów: N dni” (B-22), żeby po
    // wyłączeniu i włączeniu apki nie pobierać ich od nowa. Resztę
    // kasujemy, by cache nie puchł. Liczymy z PEŁNEJ listy wszystkich serwerów (nie z przefiltrowanego feedu).
    val globalFeed = remember(active) { buildCommunityFeed(active, null) }
    // A-49: ulubione chronimy ze WSZYSTKICH serwerów (także wyłączonych), nie tylko z tych w zakładce
    val allServersFeed = remember(community.servers) { buildCommunityFeed(community.servers, null, forRetention = true) }
    LaunchedEffect(globalFeed, allServersFeed, favoriteDedupKeys, imageRetentionDays) {
        if (globalFeed.isEmpty()) return@LaunchedEffect
        val withImages = globalFeed.filter { feedImageUrls(it).isNotEmpty() }
        val newest = withImages.sortedByDescending { it.sortMillis }.take(COMMUNITY_RETAIN_POSTS)
        val favored = allServersFeed.filter { it.dedupKey in favoriteDedupKeys }
        // B-22: obrazy z okna „Obrazy postów: N dni” (ustawienie użytkownika) też zostają — wcześniej
        // retencja trzymała tylko 30 najnowszych memów/postów i kasowała resztę przy KAŻDEJ zmianie feedu.
        val windowStart = if (imageRetentionDays > 0) {
            System.currentTimeMillis() - imageRetentionDays.toLong() * 24L * 60L * 60L * 1000L
        } else {
            Long.MAX_VALUE
        }
        val inWindow = allServersFeed.filter { it.sortMillis >= windowStart }
        // A-49: awatary autorów (ten sam cache) też chronimy — inaczej kasowane przy każdej zmianie feedu i
        // pobierane od nowa (transfer + powtórny kontakt z hostem obrazów / Torem). Są małe.
        val avatars = allServersFeed.mapNotNull { (it as? CommunityFeedItem.PostItem)?.post?.authorImageUrl }
        val keep = ((newest + favored + inWindow).flatMap { feedImageUrls(it) } + avatars).toHashSet()
        newest.forEach { prefetchFeedImages(prefetchContext, it) } // najnowsze gotowe od ręki po starcie
        CommunityImageLoader.retainImages(prefetchContext, keep)
    }

    PullToRefreshBox(
        isRefreshing = community.isRefreshing,
        onRefresh = onRefresh,
        // TalkBack: przeciągnięcie w dół to dla czytnika przewijanie — „Odśwież" jest w menu akcji
        modifier = Modifier.fillMaxSize().semantics {
            customActions = listOf(CustomAccessibilityAction("Odśwież treści społeczności") { onRefresh(); true })
        },
    ) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(tr("Społeczność"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        }
        item {
            Text(
                tr(
                    "Treści z serwerów, na które wyraził{g:eś|aś|oś} zgodę — od najnowszych. Serwery dodajesz " +
                        "i regulaminy akceptujesz w Ustawieniach.",
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        // baner weryfikacji serwera głównego: gdy serwer zastępujący serwer domyślny rozjeżdża się z RSS
        community.primaryHealth?.let { health ->
            item { PrimaryHealthBanner(health) }
        }
        // filtr serwera — osobny wiersz; widoczny, gdy jest jakikolwiek serwer wnoszący treść
        // (przy jednym to „Wszystkie" + ten jeden serwer; przy wielu realnie rozdziela źródła).
        if (active.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        tr("Serwer:"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilterChip(
                        selected = effectiveServerId == null,
                        onClick = { selectedServerId = null },
                        label = { Text(tr("Wszystkie")) },
                    )
                    active.forEach { server ->
                        FilterChip(
                            selected = effectiveServerId == server.record.serverId,
                            onClick = { selectedServerId = server.record.serverId },
                            label = { Text(server.manifest?.name ?: server.record.endpoint) },
                        )
                    }
                }
            }
        }
        // filtr typu treści (memy / artykuły / posty / …) — widoczny zawsze, gdy jest jakakolwiek
        // treść (odkrywalność); przy jednym typie to po prostu „Wszystko" + ten jeden typ.
        if (presentCategories.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        tr("Typ:"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilterChip(
                        selected = effectiveCategory == null,
                        onClick = { selectedCategory = null },
                        label = { Text(tr("Wszystko")) },
                    )
                    presentCategories.forEach { category ->
                        FilterChip(
                            selected = effectiveCategory == category,
                            onClick = { selectedCategory = category },
                            label = { Text(tr(category.label)) },
                        )
                    }
                }
            }
        }
        if (feed.isEmpty()) {
            item {
                Text(
                    tr("Brak treści do pokazania."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(feed, key = { feedListKey(it) }) { entry ->
                // A-55: gwiazdka niezależna od tego, który serwer „wygrał" dedup — ulubione zapisane pod innym
                // serwerem tej samej treści też się liczą; odznaczenie zdejmuje je ze WSZYSTKICH serwerów.
                val favoredOn = active.filter { entry.dedupKey in it.favoriteItemIds }.map { it.record.serverId }
                val favorite = favoredOn.isNotEmpty()
                CommunityFeedRow(
                    entry = entry,
                    favorite = favorite,
                    onToggleFavorite = {
                        if (favorite) {
                            favoredOn.forEach { onToggleFavorite(it, entry.dedupKey, false) }
                        } else {
                            onToggleFavorite(entry.serverId, entry.dedupKey, true)
                        }
                    },
                    openYouTubeLink = openYouTubeLink,
                    openInBrowser = openCommunityLink,
                    onSaveMeme = saveMeme,
                )
            }
        }
    }
    }
}

/** scalona pozycja osi czasu; sortMillis=0 (np. twórcy bez daty) ląduje na końcu. */
/** kategoria pozycji feedu — do filtra typu w zakładce Społeczność. */
private enum class CommunityFeedCategory(val label: String) {
    CREATORS("Twórcy"),
    PLATFORMS("Platformy"),
    POSTS("Posty"),
    ARTICLES("Artykuły"),
    MEMES("Memy"),
    LINKS("Linki"),
}

private sealed interface CommunityFeedItem {
    val serverName: String
    val serverId: String
    val sortMillis: Long
    val category: CommunityFeedCategory

    /** kanoniczny klucz do wykrywania tej samej treści z RÓŻNYCH serwerów (= itemId w magazynie). */
    val dedupKey: String

    /** nazwy pozostałych serwerów, które dostarczyły ten sam materiał (do adnotacji „także z"). */
    val alsoFrom: List<String>
    fun withAlsoFrom(names: List<String>): CommunityFeedItem

    data class CreatorItem(
        override val serverName: String,
        override val serverId: String,
        val creator: CommunityCreator,
        override val alsoFrom: List<String> = emptyList(),
    ) : CommunityFeedItem {
        override val sortMillis: Long get() = 0L
        override val category get() = CommunityFeedCategory.CREATORS
        // twórca (kanał) to trwały byt – klucz po adresie kanału (kolejność jak w CommunityContentCodec)
        override val dedupKey: String
            get() = communityItemId(creator.channelUrl, "youtube", creator.name, creator.id)
        override fun withAlsoFrom(names: List<String>) = copy(alsoFrom = names)
    }
    data class ForeignItem(
        override val serverName: String,
        override val serverId: String,
        val item: CommunityForeignItem,
        override val alsoFrom: List<String> = emptyList(),
    ) : CommunityFeedItem {
        override val sortMillis: Long get() = item.publishedAtMillis ?: 0L
        override val category get() = CommunityFeedCategory.PLATFORMS
        override val dedupKey: String
            get() = communityItemId(item.url, item.platform, item.author, item.title ?: item.text)
        override fun withAlsoFrom(names: List<String>) = copy(alsoFrom = names)
    }
    data class PostItem(
        override val serverName: String,
        override val serverId: String,
        val post: CommunityPost,
        override val alsoFrom: List<String> = emptyList(),
    ) : CommunityFeedItem {
        override val sortMillis: Long get() = post.publishedAtMillis ?: 0L
        override val category get() = CommunityFeedCategory.POSTS
        override val dedupKey: String
            get() = communityItemId(post.url, "youtube", post.author, post.text)
        override fun withAlsoFrom(names: List<String>) = copy(alsoFrom = names)
    }
    data class LinkItem(
        override val serverName: String,
        override val serverId: String,
        val item: CommunityLinkItem,
        override val alsoFrom: List<String> = emptyList(),
    ) : CommunityFeedItem {
        override val sortMillis: Long get() = item.publishedAtMillis ?: 0L
        override val category get() = CommunityFeedCategory.LINKS
        override val dedupKey: String
            get() = communityItemId(item.url, item.kind.name, null, item.title ?: item.text)
        override fun withAlsoFrom(names: List<String>) = copy(alsoFrom = names)
    }
    data class ArticleItem(
        override val serverName: String,
        override val serverId: String,
        val article: CommunityArticle,
        override val alsoFrom: List<String> = emptyList(),
    ) : CommunityFeedItem {
        override val sortMillis: Long get() = article.publishedAtMillis ?: 0L
        override val category get() = CommunityFeedCategory.ARTICLES
        override val dedupKey: String
            get() = communityItemId(article.url, "media", article.source, article.title)
        override fun withAlsoFrom(names: List<String>) = copy(alsoFrom = names)
    }
    data class MemeItem(
        override val serverName: String,
        override val serverId: String,
        val meme: CommunityMeme,
        override val alsoFrom: List<String> = emptyList(),
    ) : CommunityFeedItem {
        override val sortMillis: Long get() = meme.publishedAtMillis ?: 0L
        override val category get() = CommunityFeedCategory.MEMES
        override val dedupKey: String
            get() = communityItemId(meme.url ?: meme.imageUrl, "meme", meme.source, meme.title)
        override fun withAlsoFrom(names: List<String>) = copy(alsoFrom = names)
    }
}

/**
 * Scalanie duplikatów tej samej treści z różnych serwerów. Grupujemy po kanonicznym kluczu,
 * z każdej grupy zostawiamy JEDEN wpis: z PIERWSZEGO serwera na liście (A-55 — obcy serwer nie podmieni
 * cudzej pozycji datą z przyszłości). Pozostałe serwery lądują w [CommunityFeedItem.alsoFrom].
 * Zachowujemy kolejność wejścia, żeby nie tasować feedu przed finalnym sortowaniem.
 */
// ile pozycji PONIŻEJ widocznego obszaru wczytywać z wyprzedzeniem (prefetch). 10 = spory bufor „jak w
// przeglądarce" — kolejne posty są gotowe, zanim do nich doscrollujesz.
private const val COMMUNITY_PREFETCH_AHEAD = 10

// ile NAJNOWSZYCH postów trzymać na dysku między uruchomieniami apki (globalnie po dacie, niezależnie od
// serwera). Ich obrazy nie są kasowane, więc po ponownym włączeniu nie pobieramy ich od nowa.
private const val COMMUNITY_RETAIN_POSTS = 30

/** Obrazy pozycji feedu do prefetchu (główny obraz; dla mema to pierwsze zdjęcie karuzeli). */
private fun prefetchFeedImages(context: Context, entry: CommunityFeedItem) {
    when (entry) {
        is CommunityFeedItem.MemeItem ->
            CommunityImageLoader.prefetch(context, entry.meme.imageUrl, entry.meme.imageQuality)
        is CommunityFeedItem.PostItem ->
            entry.post.imageUrl?.let { CommunityImageLoader.prefetch(context, it, entry.post.imageQuality) }
        is CommunityFeedItem.ForeignItem ->
            entry.item.imageUrl?.let { CommunityImageLoader.prefetch(context, it, entry.item.imageQuality) }
        is CommunityFeedItem.ArticleItem ->
            entry.article.imageUrl?.let { CommunityImageLoader.prefetch(context, it, entry.article.imageQuality) }
        is CommunityFeedItem.LinkItem ->
            entry.item.imageUrl?.let { CommunityImageLoader.prefetch(context, it, entry.item.imageQuality) }
        is CommunityFeedItem.CreatorItem -> Unit
    }
}

/**
 * WSZYSTKIE adresy obrazów pozycji feedu (mem = całą karuzelę) — do retencji „trzymaj na dysku".
 * B-22: także artykuły, wpisy z innych platform i reklamy/konkursy — ich karty pokazują obrazy, a wcześniej
 * retencja kasowała je przy każdej zmianie feedu (ponowne pobieranie, kolejne kontakty z hostem / Torem).
 */
private fun feedImageUrls(entry: CommunityFeedItem): List<String> = when (entry) {
    is CommunityFeedItem.MemeItem -> entry.meme.images.map { it.imageUrl }
    is CommunityFeedItem.PostItem -> listOfNotNull(entry.post.imageUrl)
    is CommunityFeedItem.ForeignItem -> listOfNotNull(entry.item.imageUrl)
    is CommunityFeedItem.ArticleItem -> listOfNotNull(entry.article.imageUrl)
    is CommunityFeedItem.LinkItem -> listOfNotNull(entry.item.imageUrl)
    is CommunityFeedItem.CreatorItem -> emptyList()
}

private fun dedupFeed(items: List<CommunityFeedItem>): List<CommunityFeedItem> {
    val groups = LinkedHashMap<String, MutableList<CommunityFeedItem>>()
    items.forEach { groups.getOrPut(it.dedupKey) { ArrayList() }.add(it) }
    return groups.values.map { group ->
        // A-55: zwycięzcą grupy jest pozycja z PIERWSZEGO serwera na liście (stała kolejność dodania), a nie ta
        // z najnowszą datą — obcy serwer nie podmieni cudzego posta, podając jego URL z datą z przyszłości.
        val primary = group.first()
        val others = group.asSequence()
            .filter { it !== primary }
            .map { it.serverName }
            .filter { it != primary.serverName }
            .distinct()
            .toList()
        if (others.isEmpty()) primary else primary.withAlsoFrom(others)
    }
}

private fun buildCommunityFeed(
    active: List<CommunityServerState>,
    selectedServerId: String?,
    // D-17: do retencji obrazów liczymy WSZYSTKIE pozycje (także z wyłączonych serwerów i wyciszonych gałęzi)
    forRetention: Boolean = false,
): List<CommunityFeedItem> {
    val chosen = if (selectedServerId == null) {
        active
    } else {
        active.filter { it.record.serverId == selectedServerId }
    }
    val regular = ArrayList<CommunityFeedItem>()
    val ads = ArrayList<CommunityFeedItem.LinkItem>()
    chosen.forEach { server ->
        val manifest = server.manifest ?: return@forEach
        val name = manifest.name
        val id = server.record.serverId
        // pokazujemy tylko to, czego user nie wyłączył dla WYŚWIETLANIA (platforma / twórca / typ)
        fun visible(platform: String, creator: String?, type: String? = null) =
            forRetention || CommunityContentFilter.shouldDisplay(server.record, platform, creator, type)

        if (CommunityCapability.COMMUNITY_CREATORS in manifest.capabilities) {
            manifest.creators
                .filter { visible(CommunityNotificationPolicy.PLATFORM_YOUTUBE, it.id) }
                .forEach { regular += CommunityFeedItem.CreatorItem(name, id, it) }
        }
        if (CommunityCapability.FOREIGN_PLATFORM_ITEMS in manifest.capabilities) {
            manifest.foreignItems
                .filter { visible(it.platform, it.author, it.type) }
                .forEach { regular += CommunityFeedItem.ForeignItem(name, id, it) }
        }
        if (CommunityCapability.YOUTUBE_COMMUNITY_POSTS in manifest.capabilities) {
            manifest.posts
                .filter { visible(CommunityNotificationPolicy.PLATFORM_YOUTUBE, it.author, it.type) }
                .forEach { regular += CommunityFeedItem.PostItem(name, id, it) }
        }
        if (CommunityCapability.CONTESTS in manifest.capabilities && visible("contests", null)) {
            manifest.contests.forEach { regular += CommunityFeedItem.LinkItem(name, id, it) }
        }
        if (CommunityCapability.MEDIA_PUBLICATIONS in manifest.capabilities) {
            manifest.articles
                .filter { visible(CommunityContentFilter.PLATFORM_MEDIA, it.source, communityArticleTypeLabel(it.kind)) }
                .forEach { regular += CommunityFeedItem.ArticleItem(name, id, it) }
        }
        if (CommunityCapability.MEMES in manifest.capabilities) {
            manifest.memes
                .filter { visible(CommunityContentFilter.PLATFORM_MEME, it.source, CommunityContentFilter.TYPE_MEME) }
                .forEach { regular += CommunityFeedItem.MemeItem(name, id, it) }
        }
        // reklamy: osobny przełącznik + oś wyświetlania
        if (CommunityCapability.ADS in manifest.capabilities && server.record.adsEnabled && visible("ads", null)) {
            manifest.ads.forEach { ads += CommunityFeedItem.LinkItem(name, id, it) }
        }
    }
    // scalamy zdublowaną treść z różnych serwerów, dopiero potem liczymy limity/sortowanie
    val dedupedRegular = dedupFeed(regular)
    val dedupedAds = dedupFeed(ads).filterIsInstance<CommunityFeedItem.LinkItem>()
    // twardy limit: max 1 reklama na 20 zwykłych pozycji – nadmiar obcinamy (bierzemy najnowsze)
    val allowedAds = CommunityAdPolicy.allowedAdCount(dedupedRegular.size)
    val keptAds = dedupedAds.sortedByDescending { it.sortMillis }.take(allowedAds)
    // N-06: po połączeniu list (zwykłe + reklamy) gwarantujemy unikalność klucza listy — inaczej dwie pozycje
    // z tym samym (kategoria|serwer|adres) wywróciłyby LazyColumn („Key already used").
    return (dedupedRegular + keptAds).distinctBy { feedListKey(it) }.sortedByDescending { it.sortMillis }
}

/** N-06: klucz pozycji listy — z KATEGORIĄ, żeby reklama i zwykła pozycja z tym samym adresem nie kolidowały. */
private fun feedListKey(entry: CommunityFeedItem): String =
    "${entry.category}|${entry.serverId}|${entry.dedupKey}"

@Composable
private fun CommunityFeedRow(
    entry: CommunityFeedItem,
    favorite: Boolean,
    onToggleFavorite: () -> Unit,
    openYouTubeLink: (String) -> Unit,
    openInBrowser: (String) -> Unit,
    onSaveMeme: (String, String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    entry.serverName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // ta sama treść przyszła też z innych serwerów – pokazujemy je zamiast dublować karty
                if (entry.alsoFrom.isNotEmpty()) {
                    Text(
                        "także z: " + entry.alsoFrom.joinToString(", "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // ulubione: przypięty post nie zostanie usunięty z bazy retencją ani aktualizacją
            TextButton(onClick = onToggleFavorite) {
                Text(if (favorite) tr("★ w ulubionych") else tr("☆ do ulubionych"), style = MaterialTheme.typography.labelSmall)
            }
        }
        when (entry) {
            is CommunityFeedItem.CreatorItem -> CommunityCreatorCard(entry.creator, onOpen = openYouTubeLink)
            is CommunityFeedItem.ForeignItem -> CommunityForeignItemCard(entry.item, onOpen = openInBrowser)
            is CommunityFeedItem.PostItem -> CommunityPostCard(entry.post, onOpen = openYouTubeLink)
            is CommunityFeedItem.LinkItem -> CommunityLinkCard(entry.item, onOpen = openInBrowser)
            is CommunityFeedItem.ArticleItem -> CommunityArticleCard(entry.article, onOpen = openInBrowser)
            is CommunityFeedItem.MemeItem -> CommunityMemeCard(entry.meme, onOpen = openInBrowser, onSave = onSaveMeme)
        }
    }
}

@Composable
private fun CommunityMemeCard(
    meme: CommunityMeme,
    onOpen: (String) -> Unit,
    onSave: (String, String) -> Unit,
) {
    // podpowiedź do nazwy pliku: podpis → źródło → „mem"
    val baseName = meme.title ?: meme.source ?: "mem"
    // tap na karcie otwiera oryginał (gdy jest url); długie przytrzymanie SAMEGO obrazu → zapis do galerii
    val cardModifier = if (meme.url != null) {
        Modifier.fillMaxWidth().clickable(onClickLabel = tr("Otwórz")) { onOpen(meme.url) }
    } else {
        Modifier.fillMaxWidth()
    }
    Card(cardModifier) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                buildString {
                    append("Mem")
                    meme.source?.let { append(" · ").append(it) }
                },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CommunityUpdateBadge(meme.update)
            meme.title?.let {
                Text(it, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            if (meme.images.size > 1) {
                // KARUZELA IG: wiele zdjęć w jednym poście — przesuwane palcem w bok (jak na Instagramie),
                // nie wszystkie naraz. Przytrzymanie zapisuje AKTUALNIE widoczne zdjęcie.
                // PROPORCJE kontenera ustala NAJWIĘKSZE zdjęcie posta (po powierzchni w px) — żeby
                // przesuwanie nie zmieniało rozmiaru karty i żeby to największe mieściło się idealnie.
                // Reszta (inne proporcje) jest pokazywana w całości (Fit), a wolne boki wypełnia rozmyte
                // tło z tego samego zdjęcia (patrz CommunityCarouselSlide). Wymiary bierzemy z już
                // zdekodowanych bitmap — ta sama droga, którą apka i tak skaluje obrazy do GUI.
                // Zakres proporcji przycinamy do rozsądnego (0.75–1.91), by karta nie była absurdalnie wysoka.
                val bitmaps = meme.images.map { rememberCommunityBitmap(it.imageUrl, it.imageQuality) }
                val aspect = bitmaps.mapNotNull { it.value }
                    .maxByOrNull { it.width.toLong() * it.height.toLong() }
                    ?.let { it.width.toFloat() / it.height.toFloat() }
                    ?.coerceIn(0.75f, 1.91f) ?: 1f
                val pagerState = rememberPagerState(pageCount = { meme.images.size })
                val pagerScope = rememberCoroutineScope()
                // TalkBack: przesuwanie palcem w bok jest dla czytnika gestem nawigacji, więc karuzela ma
                // własne akcje „Następne/Poprzednie zdjęcie" (menu akcji czytnika) i ogłasza numer zdjęcia
                val pagerA11y = Modifier.fillMaxWidth().semantics {
                    stateDescription = "Zdjęcie ${pagerState.currentPage + 1} z ${meme.images.size}"
                    customActions = listOf(
                        CustomAccessibilityAction("Następne zdjęcie") {
                            if (pagerState.currentPage < meme.images.lastIndex) {
                                pagerScope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                                true
                            } else {
                                false
                            }
                        },
                        CustomAccessibilityAction("Poprzednie zdjęcie") {
                            if (pagerState.currentPage > 0) {
                                pagerScope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                                true
                            } else {
                                false
                            }
                        },
                    )
                }
                HorizontalPager(state = pagerState, modifier = pagerA11y) { page ->
                    val img = meme.images[page]
                    CommunityCarouselSlide(
                        description = "Mem, zdjęcie ${page + 1} z ${meme.images.size}" +
                            (meme.title?.let { ": $it" } ?: ""),
                        bitmap = bitmaps[page].value,
                        modifier = Modifier.fillMaxWidth().aspectRatio(aspect)
                            .clip(RoundedCornerShape(8.dp))
                            .combinedClickable(
                                onClickLabel = if (meme.url != null) tr("Otwórz") else null,
                                onClick = { meme.url?.let(onOpen) },
                                onLongClickLabel = tr("Zapisz obraz"),
                                onLongClick = { onSave(img.imageUrl, baseName + "-" + (page + 1)) },
                            ),
                    )
                }
                Text(
                    "${pagerState.currentPage + 1}/${meme.images.size} · " +
                        tr("przesuń palcem w bok; przytrzymaj, aby zapisać bieżące zdjęcie"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                CommunityFeedImage(
                    meme.imageUrl,
                    Modifier.fillMaxWidth().heightIn(max = 360.dp).clip(RoundedCornerShape(8.dp))
                        .combinedClickable(
                            onClickLabel = if (meme.url != null) tr("Otwórz") else null,
                            onClick = { meme.url?.let(onOpen) },
                            onLongClickLabel = tr("Zapisz obraz"),
                            onLongClick = { onSave(meme.imageUrl, baseName) },
                        ),
                    jxlQuality = meme.imageQuality,
                    description = "Mem" + (meme.title?.let { ": $it" } ?: ""),
                )
                Text(
                    tr("Przytrzymaj obraz, aby zapisać w Galerii."),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            meme.video?.let { CommunityVideoRow(it, meme.title ?: "Mem") }
            meme.audio?.let { CommunityAudioRow(it, meme.title ?: "Mem", meme.source) }
        }
    }
}

// otwiera pozycję dotknięciem LUB przytrzymaniem; gdy brak URL, karta nie jest klikalna
private fun Modifier.communityOpenable(url: String?, onOpen: (String) -> Unit): Modifier =
    if (url == null) {
        this
    } else {
        combinedClickable(
            onClickLabel = "Otwórz",
            onClick = { onOpen(url) },
            onLongClickLabel = "Otwórz bezpośrednio",
            onLongClick = { onOpen(url) },
        )
    }

/** reklama/konkurs – wyłącznie odnośnik do strony WWW, z wyraźną etykietą. */
@Composable
private fun CommunityLinkCard(item: CommunityLinkItem, onOpen: (String) -> Unit) {
    val label = when (item.kind) {
        CommunityLinkKind.AD -> "Reklama"
        CommunityLinkKind.CONTEST -> if (item.thirdParty) "Konkurs (strona trzecia)" else "Konkurs"
    }
    Card(Modifier.fillMaxWidth().communityOpenable(item.url, onOpen)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CommunityUpdateBadge(item.update)
            item.title?.let {
                Text(it, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            item.text?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            CommunityFeedImage(
                item.imageUrl,
                Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(RoundedCornerShape(8.dp)),
                jxlQuality = item.imageQuality,
            )
        }
    }
}

/** publikacja medialna / newsletter – odnośnik do artykułu, ze źródłem i zajawką. */
@Composable
private fun CommunityArticleCard(article: CommunityArticle, onOpen: (String) -> Unit) {
    val label = when (article.kind) {
        CommunityArticleKind.NEWSLETTER -> "Newsletter · ${article.source}"
        CommunityArticleKind.ARTICLE -> "Artykuł · ${article.source}"
    }
    Card(Modifier.fillMaxWidth().communityOpenable(article.url, onOpen)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                buildString {
                    append(label)
                    article.author?.let { append(" · ").append(it) }
                },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CommunityUpdateBadge(article.update)
            Text(article.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            article.text?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            CommunityFeedImage(
                article.imageUrl,
                Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(RoundedCornerShape(8.dp)),
                jxlQuality = article.imageQuality,
            )
            article.video?.let { CommunityVideoRow(it, article.title) }
            article.audio?.let { CommunityAudioRow(it, article.title, article.source) }
        }
    }
}

/** mała etykieta „zaktualizowano" dla pozycji ponownie wysłanej przez serwer z tym samym id. */
@Composable
private fun CommunityUpdateBadge(update: CommunityUpdateKind) {
    val label = when (update) {
        CommunityUpdateKind.SIGNIFICANT -> "✎ zaktualizowano"
        CommunityUpdateKind.MINOR -> "✎ drobna aktualizacja"
        CommunityUpdateKind.NONE -> return
    }
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun CommunityCreatorCard(creator: CommunityCreator, onOpen: (String) -> Unit) {
    Card(Modifier.fillMaxWidth().communityOpenable(creator.channelUrl, onOpen)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                creator.name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun CommunityForeignItemCard(item: CommunityForeignItem, onOpen: (String) -> Unit) {
    Card(Modifier.fillMaxWidth().communityOpenable(item.url, onOpen)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                buildString {
                    append(item.platform)
                    item.author?.let { append(" · ").append(it) }
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CommunityUpdateBadge(item.update)
            item.title?.let {
                Text(it, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            item.text?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            CommunityFeedImage(
                item.imageUrl,
                Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(RoundedCornerShape(8.dp)),
                jxlQuality = item.imageQuality,
            )
            item.video?.let { CommunityVideoRow(it, item.title ?: item.text?.take(80) ?: item.platform) }
            item.audio?.let { CommunityAudioRow(it, item.title ?: item.text?.take(80) ?: item.platform, item.author) }
        }
    }
}

@Composable
private fun CommunityPostCard(post: CommunityPost, onOpen: (String) -> Unit) {
    Card(Modifier.fillMaxWidth().communityOpenable(post.url, onOpen)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val meta = buildString {
                post.author?.let { append(it) }
                post.type?.let {
                    if (isNotEmpty()) append(" · ")
                    append(it)
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (post.authorImageUrl != null) {
                    CommunityAvatar(post.authorImageUrl, post.author.orEmpty(), Modifier.size(28.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    if (meta.isNotEmpty()) {
                        Text(meta, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                    CommunityUpdateBadge(post.update)
                }
            }
            Text(post.text, style = MaterialTheme.typography.bodyMedium)
            CommunityFeedImage(
                post.imageUrl,
                Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(RoundedCornerShape(8.dp)),
                jxlQuality = post.imageQuality,
            )
            post.video?.let { CommunityVideoRow(it, post.text.take(80)) }
            post.audio?.let { CommunityAudioRow(it, post.text.take(80), post.author) }
        }
    }
}

/** Karta „Język / tłumaczenie": wgranie własnego tłumaczenia (podmiana), eksport szablonu, powrót do polskiego. */
@Composable
private fun TranslationSettingsCard() {
    val context = LocalContext.current
    val importScope = rememberCoroutineScope()
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // L-9: import (odczyt z limitem + parsowanie do 8 MB) na wątku IO, żeby nie blokować UI (ANR).
            importScope.launch {
                val result = withContext(Dispatchers.IO) { Translations.importFrom(context, uri) }
                result
                    .onSuccess { Toast.makeText(context, "Wczytano tłumaczenie ($it napisów).", Toast.LENGTH_SHORT).show() }
                    .onFailure { Toast.makeText(context, "Nie wczytano: ${it.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            Translations.exportTemplate(context, uri)
                .onSuccess { Toast.makeText(context, "Zapisano szablon ($it napisów).", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, "Nie zapisano: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                tr("Język / tłumaczenie"),
                modifier = Modifier.semantics { heading() },
                fontWeight = FontWeight.SemiBold,
            )
            Text("${tr("Aktualny")}: ${Translations.languageLabel}", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) }) {
                    Text(tr("Wczytaj tłumaczenie…"))
                }
                if (Translations.active) {
                    OutlinedButton(onClick = { Translations.clear(context) }) { Text(tr("Przywróć polski")) }
                }
            }
            OutlinedButton(onClick = { exportLauncher.launch("lewicowyt-tlumaczenie.json") }) {
                Text(tr("Zapisz szablon do tłumaczenia"))
            }
            Text(
                "Wgraj plik JSON z tłumaczeniem (klucz = polski tekst, wartość = tłumaczenie). " +
                    "Brakujące teksty zostają po polsku. „Zapisz szablon” tworzy plik ze wszystkimi " +
                    "napisami widzianymi dotąd w aplikacji — im więcej ekranów odwiedzisz, tym pełniejszy.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** wiersz w Ustawieniach: otwiera osobny ekran menedżera (nie zaśmieca Ustawień przy 2000 serwerów). */
@Composable
private fun CommunityServersSettingsRow(viewModel: AppViewModel, onOpen: () -> Unit) {
    val community by viewModel.communityUiState.collectAsStateWithLifecycle()
    Card(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Zarządzaj serwerami") { onOpen() }) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Serwery społeczności",
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    community.servers.count { !CommunityDefaults.isDefault(it.record.serverId) }.let { added ->
                        if (added == 0) "Dodaj i zarządzaj serwerami społeczności."
                        else "Dodane serwery: $added. Dotknij, aby zarządzać."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("›", style = MaterialTheme.typography.titleLarge, modifier = Modifier.clearAndSetSemantics { }) // strzałka dekoracyjna
        }
    }
}

/** Pełnoekranowy menedżer serwerów społeczności (podzakładka Ustawień). */
/** Podsekcja „Ustawienia treści" w „Serwery społeczności" (wejście zębatką w prawym górnym rogu). */
@Composable
private fun CommunityLinkPlatformsCard(state: AppUiState, viewModel: AppViewModel) {
    val platforms by viewModel.communityLinkPlatforms.collectAsStateWithLifecycle()
    val youtubeLinks = remember { AppGraph.youtubeLinks }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Otwieranie linków platform",
                modifier = Modifier.semantics { heading() },
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Serwery mogą deklarować platformy (np. Twitter/X, Twitch). Dla każdej wybierasz, gdzie " +
                    "otwierać jej linki — dokładnie jak dla YouTube. Domyślnie: przeglądarka.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (platforms.isEmpty()) {
                Text(
                    "Żaden z dodanych serwerów nie zadeklarował jeszcze platform.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                platforms.forEach { platform ->
                    CommunityLinkPlatformRow(
                        platform = platform,
                        pref = state.settings.communityLinkPrefs[platform.id],
                        youtubeLinks = youtubeLinks,
                        onSet = { target, pkg -> viewModel.setCommunityLinkPref(platform.id, target, pkg) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CommunityLinkPlatformRow(
    platform: pl.lewicowyt.notifier.community.CommunityLinkPlatform,
    pref: pl.lewicowyt.notifier.data.CommunityLinkPref?,
    youtubeLinks: pl.lewicowyt.notifier.links.YouTubeLinkLauncher,
    onSet: (YouTubeLinkTarget, String?) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var appPickerOpen by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf(emptyList<pl.lewicowyt.notifier.links.ExternalAppOption>()) }
    var loading by remember { mutableStateOf(false) }
    val target = pref?.target ?: YouTubeLinkTarget.SYSTEM_DEFAULT
    // dla platform pomijamy opcję „YouTube" (dotyczy tylko serwera domyślnego)
    val options = remember { YouTubeLinkTarget.entries.filter { it != YouTubeLinkTarget.YOUTUBE } }

    LaunchedEffect(appPickerOpen) {
        if (appPickerOpen) {
            loading = true
            apps = withContext(Dispatchers.Default) { youtubeLinks.launchableApplications() }
            loading = false
        }
    }
    if (appPickerOpen) {
        AlertDialog(
            onDismissRequest = { appPickerOpen = false },
            title = { Text("Wybierz aplikację dla „${platform.name}”") },
            text = {
                when {
                    loading -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    apps.isEmpty() -> Text("Nie znaleziono aplikacji do wyboru.")
                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                        items(apps, key = { it.packageName }) { app ->
                            TextButton(onClick = { onSet(YouTubeLinkTarget.OTHER_APP, app.packageName); appPickerOpen = false }, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(app.label, fontWeight = FontWeight.SemiBold)
                                    Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { appPickerOpen = false }) { Text("Anuluj") } },
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(platform.name, fontWeight = FontWeight.Medium)
        Box {
            OutlinedButton(onClick = { menuOpen = true }) { Text(youtubeLinkTargetLabel(target)) }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                options.forEach { opt ->
                    DropdownMenuItem(
                        text = { Text(youtubeLinkTargetLabel(opt)) },
                        onClick = {
                            menuOpen = false
                            if (opt == YouTubeLinkTarget.OTHER_APP) appPickerOpen = true
                            else onSet(opt, null)
                        },
                    )
                }
            }
        }
        if (target == YouTubeLinkTarget.OTHER_APP) {
            val label = remember(pref?.packageName) { youtubeLinks.applicationLabel(pref?.packageName) }
            Text(
                "Wybrano: ${label ?: pref?.packageName ?: "brak"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CommunityPushSettingsCard(viewModel: AppViewModel) {
    val context = LocalContext.current
    val embedded = remember { mutableStateOf(viewModel.pushMode() == PushSettings.Mode.EMBEDDED) }
    val instant = remember { mutableStateOf(viewModel.embeddedMode() == PushSettings.Embedded.INSTANT) }
    val fcm = remember { mutableStateOf(viewModel.isFcmFallbackEnabled()) }
    val nonPrivateTor = remember { mutableStateOf(viewModel.isNonPrivateTorEnabled()) }
    val registerViaTor = remember { mutableStateOf(viewModel.isRegisterViaTorEnabled()) }
    val strictDns = remember { mutableStateOf(viewModel.isStrictDnsEnabled()) }
    Card(Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Powiadomienia push",
                modifier = Modifier.semantics { heading() },
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "To serwery społeczności dostarczają powiadomienia — głównym torem jest UnifiedPush (bez Google Play " +
                    "i bez FCM). Serwer domyślny dostarcza budziki wyłącznie przez UnifiedPush; on i jego relay działają " +
                    "w Google Cloud Run: operator hostingu widzi " +
                    "adres IP i czas połączeń, ale nie treść budzików (szyfrowanie end-to-end). Chcesz tego uniknąć — " +
                    "ustaw jako główny własny serwer społeczności, także .onion przez Tor.",
                style = MaterialTheme.typography.bodySmall,
            )
            SwitchSetting(
                title = "Wbudowany dystrybutor (bez osobnej apki)",
                checked = embedded.value,
                onCheckedChange = {
                    embedded.value = it
                    viewModel.setPushMode(if (it) PushSettings.Mode.EMBEDDED else PushSettings.Mode.EXTERNAL)
                },
            )
            Text(
                "Włączone (domyślnie): powiadomienia odbiera sama lewicowYT — bez Google Play i bez instalowania ntfy. " +
                    "Wyłącz, jeśli wolisz zewnętrzny dystrybutor UnifiedPush (np. ntfy); wtedy moduł wbudowany jest " +
                    "całkowicie wyłączony.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (embedded.value) {
                SwitchSetting(
                    title = "Powiadomienia natychmiastowe",
                    checked = instant.value,
                    onCheckedChange = {
                        instant.value = it
                        viewModel.setEmbeddedMode(
                            if (it) PushSettings.Embedded.INSTANT else PushSettings.Embedded.SAVER,
                        )
                    },
                )
                Text(
                    if (instant.value) {
                        "Włączone: budziki od serwerów społeczności przychodzą w ~1 s, także przy wygaszonym " +
                            "ekranie i po restarcie telefonu. Android wymaga wtedy stałego, cichego powiadomienia " +
                            "„Odbiór powiadomień społeczności\" — możesz je ukryć, odbiór działa dalej."
                    } else {
                        "Wyłączone (oszczędny): bez stałego powiadomienia i baterii w tle — apka sprawdza " +
                            "serwery co ~15 minut (powiadomienia z opóźnieniem)."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (instant.value) {
                    OutlinedButton(
                        onClick = {
                            runCatching { context.startActivity(EmbeddedPushService.hideNotificationIntent(context)) }
                        },
                    ) {
                        Text("Ukryj stałe powiadomienie")
                    }
                    Text(
                        "Otwiera ustawienia kanału „Odbiór powiadomień w tle\" — wyłącz go jednym przełącznikiem. " +
                            "Powiadomienia o nowych treściach przychodzą dalej normalnie.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            SwitchSetting(
                title = "Awaryjny push przez Google (FCM)",
                checked = fcm.value,
                onCheckedChange = {
                    fcm.value = it
                    viewModel.setFcmFallbackEnabled(it)
                    // „Nieprywatny Tor" ma sens tylko przy FCM — wyłączenie FCM wyłącza też CGNAT
                    if (!it && nonPrivateTor.value) {
                        nonPrivateTor.value = false
                        viewModel.setNonPrivateTorEnabled(false)
                    }
                },
            )
            Text(
                "Domyślnie wyłączone — apka nie używa push Google (FCM) ani usług Google Play. Dotyczy tylko serwerów " +
                    "społeczności, które same oferują push przez Google — serwer domyślny działa wyłącznie przez " +
                    "UnifiedPush. Włącz tylko awaryjnie, gdy UnifiedPush u takiego serwera nie działa; wtedy jego " +
                    "powiadomienia idą przez serwery Google (Google widzi adres IP i to, że subskrybujesz ten serwer).",
                style = MaterialTheme.typography.bodySmall,
            )
            SwitchSetting(
                title = "Nieprywatny Tor (omijanie CGNAT)",
                checked = nonPrivateTor.value && fcm.value,
                enabled = fcm.value,
                onCheckedChange = {
                    nonPrivateTor.value = it
                    viewModel.setNonPrivateTorEnabled(it)
                },
            )
            Text(
                "Wymaga włączonego awaryjnego FCM (bez niego nie da się włączyć). Pozwala serwerowi .onion, który " +
                    "wysyła powiadomienia przez FCM, użyć go — Tor służy wtedy tylko do omijania CGNAT / ukrycia IP " +
                    "serwera, a Google widzi, że subskrybujesz ten serwer. Serwery .onion bez FCM są w pełni prywatne " +
                    "niezależnie od tej opcji.",
                style = MaterialTheme.typography.bodySmall,
            )
            SwitchSetting(
                title = "Rejestruj push przez Tor (także zwykłe serwery)",
                checked = registerViaTor.value,
                onCheckedChange = {
                    registerViaTor.value = it
                    viewModel.setRegisterViaTorEnabled(it)
                },
            )
            Text(
                "Rejestracja i wyrejestrowanie adresu dostarczania idą wtedy przez wbudowany Tor także do serwerów w " +
                    "zwykłym internecie (w tym domyślnego) — operator nie połączy starej i nowej rejestracji po adresie IP. " +
                    "Gdy Tor nie jest gotowy, rejestracja czeka. Samo odbieranie budzików (długie połączenie z " +
                    "pośrednikiem) nadal idzie bezpośrednio i zużywa mniej baterii.",
                style = MaterialTheme.typography.bodySmall,
            )
            SwitchSetting(
                title = "Tylko szyfrowany DNS",
                checked = strictDns.value,
                onCheckedChange = {
                    strictDns.value = it
                    viewModel.setStrictDnsEnabled(it)
                },
            )
            Text(
                "Domyślnie aplikacja pyta o nazwy hostów szyfrowany DNS (AdGuard), a gdy ten jest zablokowany, na 5 minut " +
                    "przechodzi na DNS systemowy. Po włączeniu tej opcji nigdy nie wysyła jawnych zapytań DNS — przy " +
                    "zablokowanym szyfrowanym DNS połączenia zwyczajnie się nie uda. Nazwy sieci lokalnej (.local) są " +
                    "zawsze rozwiązywane przez system.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun CommunityContentSettingsScreen(
    state: AppUiState,
    viewModel: AppViewModel,
    onBack: () -> Unit,
) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Wstecz" }) { Text("‹ Wstecz", modifier = Modifier.clearAndSetSemantics { }) }
                Text(
                    "Ustawienia treści",
                    modifier = Modifier.padding(start = 4.dp).semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            HorizontalDivider()
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { CommunityPushSettingsCard(viewModel) }
                item { CommunityLinkPlatformsCard(state, viewModel) }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "Obrazy społeczności",
                                modifier = Modifier.semantics { heading() },
                                fontWeight = FontWeight.SemiBold,
                            )
                            SwitchSetting(
                                title = "Pobieraj obrazy",
                                checked = state.settings.communityDownloadImages,
                                onCheckedChange = viewModel::setCommunityDownloadImages,
                            )
                            SwitchSetting(
                                title = "Obrazy do powiadomień",
                                checked = state.settings.communityDownloadNotificationImages,
                                onCheckedChange = viewModel::setCommunityDownloadNotificationImages,
                            )
                            SwitchSetting(
                                title = "Pobieraj animacje WebP",
                                checked = state.settings.communityDownloadWebpAnimations,
                                onCheckedChange = viewModel::setCommunityDownloadWebpAnimations,
                            )
                            Text(
                                "Wyłączenie animacji WebP oszczędza transfer i pamięć (pobieranie jest " +
                                    "przerywane zaraz po nagłówku).",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            SwitchSetting(
                                title = "Kompresuj ciężkie obrazy",
                                checked = state.settings.communityRecompressImages,
                                onCheckedChange = viewModel::setCommunityRecompressImages,
                            )
                            Text(
                                "Gdy serwer przyśle za duży obraz, aplikacja sama zmniejszy go do " +
                                    "formatu JXL o akceptowalnej jakości, żeby oszczędzić miejsce. " +
                                    "Obrazy zostają na dysku przez okres z „Zapamiętywania treści” (i zawsze ulubione), więc nie pobierają się ponownie.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            val maxKb = state.settings.communityMaxJxlKb
                            Text(
                                if (maxKb <= 0) "Limit rozmiaru JXL: bez limitu" else "Limit rozmiaru JXL: $maxKb KB",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Slider(
                                value = maxKb.toFloat(),
                                onValueChange = { viewModel.setCommunityMaxJxlKb((it.toInt() / 50) * 50) },
                                valueRange = 0f..2000f,
                                steps = 39,
                                modifier = Modifier.semantics {
                                    contentDescription = "Limit rozmiaru JXL"
                                    stateDescription = if (maxKb <= 0) "bez limitu" else "$maxKb kilobajtów"
                                },
                            )
                            Text(
                                "Powyżej limitu aplikacja stopniowo dokompresowuje JXL (jakość, potem " +
                                    "75% i 50% rozdzielczości). Wymaga jakości podanej przez serwer w " +
                                    "metadanych — bez niej takie zdjęcie jest pomijane.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            HorizontalDivider(Modifier.padding(vertical = 4.dp))
                            Text(
                                "Masowa konwersja zapisanych zdjęć do JXL",
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "Przekonwertuje wszystkie już zapisane obrazy społeczności (webp/png/jpg/avif) " +
                                    "do JXL według powyższych ustawień — z limitem rozmiaru stosuje tę samą " +
                                    "drabinkę co przy pobieraniu (jakość, potem 75% i 50% rozdzielczości). " +
                                    "Już-JXL mieszczące się w limicie są pomijane, animowane WebP zostają bez " +
                                    "zmian, a plik jest podmieniany tylko wtedy, gdy JXL jest mniejszy (albo " +
                                    "oryginał przekracza limit).",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            val conv by viewModel.communityJxlConversion.collectAsStateWithLifecycle()
                            Button(
                                onClick = { viewModel.convertCommunityImagesToJxl() },
                                enabled = conv?.running != true,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(if (conv?.running == true) "Konwertowanie…" else "Przekonwertuj zapisane zdjęcia do JXL")
                            }
                            conv?.let { c ->
                                if (c.running || c.total > 0) {
                                    val pct = if (c.total > 0) c.done * 100 / c.total else 100
                                    LinearProgressIndicator(
                                        progress = { if (c.total > 0) c.done.toFloat() / c.total else 1f },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Text(
                                        "${c.done} / ${c.total} ($pct%)" + if (!c.running) " — gotowe" else "",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
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
                                "Zapis memów (przytrzymanie obrazu)",
                                modifier = Modifier.semantics { heading() },
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "W zakładce Społeczność przytrzymaj obraz mema, aby zapisać go do Galerii. " +
                                    "Poniżej wybierasz format zapisu oraz — dla formatów stratnych — jakość i " +
                                    "moc kompresji.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            val format = runCatching {
                                CommunityImageExporter.Format.valueOf(state.settings.communityMemeSaveFormat)
                            }.getOrDefault(CommunityImageExporter.Format.JXL) // A-38
                            val formatLabel = mapOf(
                                CommunityImageExporter.Format.ORIGINAL to "Oryginał",
                                CommunityImageExporter.Format.JXL to "JXL",
                                CommunityImageExporter.Format.WEBP to "WEBP",
                                CommunityImageExporter.Format.PNG to "PNG",
                                CommunityImageExporter.Format.JPG to "JPG (MozJPEG)",
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                CommunityImageExporter.Format.entries.forEach { f ->
                                    FilterChip(
                                        selected = format == f,
                                        onClick = { viewModel.setCommunityMemeSaveFormat(f) },
                                        label = { Text(formatLabel[f] ?: f.name) },
                                    )
                                }
                            }
                            val lossy = format != CommunityImageExporter.Format.ORIGINAL &&
                                format != CommunityImageExporter.Format.PNG
                            val hasEffort = format == CommunityImageExporter.Format.JXL ||
                                format == CommunityImageExporter.Format.JPG
                            if (format == CommunityImageExporter.Format.ORIGINAL) {
                                Text(
                                    "Oryginał zapisuje plik bajt-w-bajt w kontenerze przysłanym przez serwer " +
                                        "(JXL/AVIF/WEBP…), bez rekompresji.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (lossy) {
                                val quality = state.settings.communityMemeSaveQuality
                                Text("Jakość: $quality", style = MaterialTheme.typography.bodyMedium)
                                Slider(
                                    value = quality.toFloat(),
                                    onValueChange = { viewModel.setCommunityMemeSaveQuality(it.toInt()) },
                                    valueRange = 1f..100f,
                                    modifier = Modifier.semantics {
                                        contentDescription = "Jakość zapisu memów"
                                        stateDescription = "$quality"
                                    },
                                )
                            }
                            if (hasEffort) {
                                val effort = state.settings.communityMemeSaveEffort
                                Text("Moc kompresji: $effort", style = MaterialTheme.typography.bodyMedium)
                                Slider(
                                    value = effort.toFloat(),
                                    onValueChange = { viewModel.setCommunityMemeSaveEffort(it.toInt()) },
                                    valueRange = 0f..100f,
                                    modifier = Modifier.semantics {
                                        contentDescription = "Moc kompresji zapisu memów"
                                        stateDescription = "$effort"
                                    },
                                )
                                Text(
                                    "Wyższa moc = mniejszy plik przy tej samej jakości, ale wolniejsze kodowanie " +
                                        "(JXL, MozJPEG).",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
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
                                "Zapamiętywanie treści społeczności",
                                modifier = Modifier.semantics { heading() },
                                fontWeight = FontWeight.SemiBold,
                            )
                            val textDays = state.settings.communityTextRetentionDays
                            val imageDays = state.settings.communityImageRetentionDays
                            // M-09: podczas przeciągania zmieniamy TYLKO lokalny podgląd; zapis i (destrukcyjny)
                            // prune odpalamy raz, na onValueChangeFinished — inaczej każdy krok suwaka kasował treść.
                            var textDraft by remember(textDays) { mutableStateOf(textDays.toFloat()) }
                            var imageDraft by remember(imageDays) { mutableStateOf(imageDays.toFloat()) }
                            Text(
                                if (textDraft.toInt() <= 0) "Tekst postów: nie zapisuj" else "Tekst postów: ${textDraft.toInt()} dni wstecz",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Slider(
                                value = textDraft,
                                onValueChange = { textDraft = it },
                                onValueChangeFinished = { viewModel.setCommunityTextRetentionDays(textDraft.toInt()) },
                                valueRange = 0f..365f,
                                modifier = Modifier.semantics {
                                    contentDescription = "Jak długo trzymać tekst postów"
                                    stateDescription = if (textDraft.toInt() <= 0) "nie zapisuj" else "${textDraft.toInt()} dni"
                                },
                            )
                            Text(
                                if (imageDraft.toInt() <= 0) "Obrazy postów: nie zapisuj" else "Obrazy postów: ${imageDraft.toInt()} dni wstecz",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Slider(
                                value = imageDraft,
                                onValueChange = { imageDraft = it },
                                onValueChangeFinished = { viewModel.setCommunityImageRetentionDays(imageDraft.toInt()) },
                                valueRange = 0f..365f,
                                modifier = Modifier.semantics {
                                    contentDescription = "Jak długo trzymać obrazy postów"
                                    stateDescription = if (imageDraft.toInt() <= 0) "nie zapisuj" else "${imageDraft.toInt()} dni"
                                },
                            )
                            Text(
                                "Osobno dla tekstu i obrazów — baza może trzymać sam tekst posta bez " +
                                    "zdjęcia. Posty dodane do ulubionych nie są usuwane.",
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
private fun CommunityServersManagerScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val community by viewModel.communityUiState.collectAsStateWithLifecycle()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var endpoint by rememberSaveable { mutableStateOf("") }
    var showContentSettings by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    community.message?.let { message ->
        LaunchedEffect(message) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.clearCommunityMessage()
        }
    }
    community.pendingTerms?.let { prompt ->
        CommunityTermsDialog(
            prompt = prompt,
            onAccept = viewModel::acceptCommunityTerms,
            onDecline = viewModel::declineCommunityTerms,
            onDismiss = viewModel::dismissCommunityTermsPrompt,
        )
    }

    // podsekcja „ustawienia treści" — wejście zębatką w prawym górnym rogu tego ekranu
    if (showContentSettings) {
        BackHandler { showContentSettings = false }
        CommunityContentSettingsScreen(state = state, viewModel = viewModel, onBack = { showContentSettings = false })
        return
    }
    BackHandler(onBack = onBack)

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Wstecz" }) { Text("‹ Wstecz", modifier = Modifier.clearAndSetSemantics { }) }
                Text(
                    "Serwery społeczności",
                    modifier = Modifier.padding(start = 4.dp).weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                )
                IconButton(
                    onClick = { showContentSettings = true },
                    modifier = Modifier.semantics { contentDescription = "Ustawienia treści serwerów" },
                ) {
                    Text("⚙", style = MaterialTheme.typography.titleLarge, modifier = Modifier.clearAndSetSemantics { })
                }
            }
            HorizontalDivider()
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(
                        "Dodaj serwer, którego adres masz od zaufanego źródła. lewicowYT pobiera od niego " +
                            "treści i nie wysyła mu Twoich danych — poza pushem, gdy masz dla serwera włączone " +
                            "„Powiadomienia” (wtedy trafia tam tylko ulotny adres dostarczenia i Twój klucz " +
                            "publiczny). Wystarczy sam adres " +
                            "(np. serwer.przyklad.pl albo 192.168.1.10) — schemat, port $DEFAULT_COMMUNITY_PORT i /manifest.json dopiszemy.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    OutlinedTextField(
                        value = endpoint,
                        onValueChange = { endpoint = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Adres serwera (np. serwer.przyklad.pl)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { viewModel.addCommunityServer(endpoint); endpoint = "" },
                                enabled = !community.isBusy && endpoint.isNotBlank(),
                            ) {
                                if (community.isBusy) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Text("Dodaj serwer")
                                }
                            }
                            if (community.servers.isNotEmpty()) {
                                OutlinedButton(
                                    onClick = { viewModel.refreshCommunityServers() },
                                    enabled = !community.isBusy,
                                ) { Text("Odśwież") }
                            }
                        }
                        if (community.servers.isNotEmpty()) {
                            OutlinedButton(
                                onClick = { viewModel.reloadCommunityDatabase() },
                                enabled = !community.isBusy,
                            ) { Text("Przeładuj bazę (SHA-256)") }
                            Text(
                                "„Przeładuj bazę” pobiera treść od nowa ze wszystkich serwerów i " +
                                    "sprawdza odciskiem SHA-256, co realnie się zmieniło (ulubione zostają).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (community.servers.isNotEmpty()) {
                    item {
                        val primaryName = community.servers
                            .firstOrNull { it.record.serverId == community.primaryServerId }
                            ?.let { it.manifest?.name ?: it.record.endpoint }
                            ?: "Domyślny (oficjalny)"
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "Serwer główny: $primaryName",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            // B-11: przycisk tylko gdy głównym jest INNY serwer; przywracamy jawnie SERVER_ID
                            if (!CommunityDefaults.isDefault(community.primaryServerId)) {
                                OutlinedButton(onClick = { viewModel.setPrimaryServer(CommunityDefaults.SERVER_ID) }) {
                                    Text("Przywróć domyślny (oficjalny)")
                                }
                            }
                        }
                    }
                }
                if (community.servers.isNotEmpty()) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    "Tożsamość w sieci Tor",
                                    modifier = Modifier.semantics { heading() },
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "Nadaje nową tożsamość: zmienia obwody Tor ORAZ klucz szyfrujący, " +
                                        "przez co serwer widzi świeżego, niepowiązywalnego subskrybenta. " +
                                        "Stary identyfikator wyrejestruje się w LOSOWYM momencie (do 5 minut, " +
                                        "przez odrębny obwód), żeby nie dało się powiązać starej tożsamości z nową.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                OutlinedButton(
                                    onClick = { viewModel.rotateTorIdentity() },
                                    enabled = !community.isBusy,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Zmień tożsamość Tor (nowy identyfikator)") }
                            }
                        }
                    }
                }
                // wbudowany serwer domyślny jest ukryty: nie da się go tu wyłączyć ani usunąć — zastępuje go
                // tylko wybór innego serwera głównego (powrót: „Przywróć domyślny" nad listą)
                items(community.servers.filterNot { CommunityDefaults.isDefault(it.record.serverId) }, key = { it.record.serverId }) { server ->
                    CommunityServerManagerCard(
                        server = server,
                        isPrimary = server.record.serverId == community.primaryServerId,
                        viewModel = viewModel,
                    )
                }
            }
        }
    }
}

private data class CommunityCreatorNode(val id: String, val label: String, val types: List<String>)
private data class CommunityPlatformNode(
    val platform: String,
    val label: String,
    val creators: List<CommunityCreatorNode>,
)

// zbiera etykiety twórców + ich typy (id twórcy = lowercase); potem robi z tego węzły
private class CreatorTypeCollector {
    val labels = LinkedHashMap<String, String>()
    val types = LinkedHashMap<String, LinkedHashSet<String>>()
    fun add(rawAuthor: String, rawType: String?) {
        val key = rawAuthor.trim().lowercase()
        if (key.isEmpty()) return
        labels.putIfAbsent(key, rawAuthor.trim())
        rawType?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let {
            types.getOrPut(key) { LinkedHashSet() }.add(it)
        }
    }
    fun nodes(): List<CommunityCreatorNode> =
        labels.map { CommunityCreatorNode(it.key, it.value, types[it.key]?.toList().orEmpty()) }
}

/** typ pozycji medialnej jako stabilny klucz/etykieta – używany i w filtrze feedu, i w drzewie wyciszeń. */
private fun communityArticleTypeLabel(kind: CommunityArticleKind): String = CommunityContentFilter.articleTypeLabel(kind)

/** buduje drzewo platforma → twórca → typ z tego, co serwer realnie oferuje (reklamy mają własny przełącznik). */
private fun communityPlatformNodes(manifest: CommunityManifest?): List<CommunityPlatformNode> {
    manifest ?: return emptyList()
    val caps = manifest.capabilities
    val nodes = ArrayList<CommunityPlatformNode>()

    if (CommunityCapability.COMMUNITY_CREATORS in caps || CommunityCapability.YOUTUBE_COMMUNITY_POSTS in caps) {
        val yt = CreatorTypeCollector()
        if (CommunityCapability.COMMUNITY_CREATORS in caps) {
            manifest.creators.forEach { yt.labels.putIfAbsent(it.id.trim().lowercase(), it.name) }
        }
        if (CommunityCapability.YOUTUBE_COMMUNITY_POSTS in caps) {
            manifest.posts.forEach { p -> p.author?.let { yt.add(it, p.type) } }
        }
        nodes += CommunityPlatformNode(CommunityNotificationPolicy.PLATFORM_YOUTUBE, "YouTube", yt.nodes())
    }
    if (CommunityCapability.FOREIGN_PLATFORM_ITEMS in caps) {
        val byPlatform = LinkedHashMap<String, CreatorTypeCollector>()
        manifest.foreignItems.forEach { item ->
            val pk = item.platform.trim().lowercase()
            if (pk.isEmpty()) return@forEach
            item.author?.let { byPlatform.getOrPut(pk) { CreatorTypeCollector() }.add(it, item.type) }
        }
        byPlatform.forEach { (pk, collector) -> nodes += CommunityPlatformNode(pk, pk, collector.nodes()) }
    }
    if (CommunityCapability.MEDIA_PUBLICATIONS in caps) {
        val media = CreatorTypeCollector()
        manifest.articles.forEach { a -> media.add(a.source, communityArticleTypeLabel(a.kind)) }
        nodes += CommunityPlatformNode(CommunityContentFilter.PLATFORM_MEDIA, "Media / artykuły", media.nodes())
    }
    if (CommunityCapability.MEMES in caps) {
        val memes = CreatorTypeCollector()
        manifest.memes.forEach { m -> m.source?.let { memes.add(it, CommunityContentFilter.TYPE_MEME) } }
        nodes += CommunityPlatformNode(CommunityContentCodec.PLATFORM_MEME, "Memy", memes.nodes())
    }
    if (CommunityCapability.CONTESTS in caps) {
        nodes += CommunityPlatformNode("contests", "Konkursy", emptyList())
    }
    return nodes
}

/** dwa chipy: 🔔 powiadomienia i 👁 wyświetlanie (każdy z własnym stanem/aktywnością). */
@Composable
private fun CommunityMuteChips(
    notifyOn: Boolean,
    notifyEnabled: Boolean,
    displayOn: Boolean,
    displayEnabled: Boolean,
    onNotify: () -> Unit,
    onDisplay: () -> Unit,
    // TalkBack: czego dotyczą przełączniki (platforma / twórca / typ) — same emoji nic nie mówią
    subject: String = "",
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
            selected = notifyOn,
            enabled = notifyEnabled,
            onClick = onNotify,
            label = { Text(if (notifyOn) "🔔" else "🔕", modifier = Modifier.clearAndSetSemantics { }) },
            modifier = Modifier.semantics {
                contentDescription = "Powiadomienia: $subject"
                stateDescription = if (notifyOn) "włączone" else "wyciszone"
            },
        )
        FilterChip(
            selected = displayOn,
            enabled = displayEnabled,
            onClick = onDisplay,
            label = { Text(if (displayOn) "👁" else "🚫", modifier = Modifier.clearAndSetSemantics { }) },
            modifier = Modifier.semantics {
                contentDescription = "Pokazuj w zakładce: $subject"
                stateDescription = if (displayOn) "pokazywane" else "ukryte"
            },
        )
    }
}

@Composable
private fun CommunityServerManagerCard(
    server: CommunityServerState,
    isPrimary: Boolean,
    viewModel: AppViewModel,
) {
    val serverId = server.record.serverId
    val platforms = remember(server.manifest) { communityPlatformNodes(server.manifest) }
    val accepted = server.consentState == CommunityConsentState.ACCEPTED
    val eligibleForPrimary = server.record.enabled && server.record.canBePrimary && accepted
    var expanded by rememberSaveable(serverId) { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                        .clickable(onClickLabel = if (expanded) "Zwiń ustawienia serwera" else "Rozwiń ustawienia serwera") { expanded = !expanded }
                        .semantics { stateDescription = if (expanded) "Rozwinięte" else "Zwinięte" },
                ) {
                    Text(
                        server.manifest?.name ?: server.record.displayName ?: server.record.endpoint,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        server.record.endpoint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(
                    checked = server.record.enabled,
                    onCheckedChange = { viewModel.setCommunityServerEnabled(serverId, it) },
                )
            }

            Text(communityStatusLine(server), style = MaterialTheme.typography.bodyMedium)
            if (isPrimary) {
                Text(
                    "★ Serwer główny",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (expanded) {
                if (accepted && server.record.enabled) {
                    // Serwer .onion, który wysyła push przez FCM, wymaga trybu „Nieprywatny Tor". Bez niego
                    // powiadomień tego serwera nie da się włączyć — blokujemy przełącznik i wyjaśniamy dlaczego.
                    val pushNeedsNonPrivateTor =
                        (server.record.push ?: server.manifest?.push)?.requiresNonPrivateTor == true &&
                            communityTransportOf(server.record.endpoint) == CommunityTransport.ONION
                    // A-50: syncFromServers rejestruje taki serwer TYLKO gdy włączone są OBA: „Nieprywatny Tor"
                    // i awaryjny FCM — inaczej przełącznik pokazywałby ON, a push by nie działał.
                    val notifyBlocked = pushNeedsNonPrivateTor &&
                        !(viewModel.isNonPrivateTorEnabled() && viewModel.isFcmFallbackEnabled())
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Powiadomienia (całość)", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = server.record.notifyEnabled && !notifyBlocked,
                            enabled = !notifyBlocked,
                            onCheckedChange = { viewModel.setCommunityServerNotifyEnabled(serverId, it) },
                        )
                    }
                    if (notifyBlocked) {
                        Text(
                            "Ten serwer (.onion) wysyła powiadomienia przez Google i wymaga dwóch opcji w " +
                                "Ustawieniach powiadomień push: „Awaryjny push przez Google (FCM)” oraz „Nieprywatny " +
                                "Tor”. Włącz obie, aby móc włączyć powiadomienia tego serwera.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (server.manifest?.capabilities?.contains(CommunityCapability.ADS) == true) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Reklamy (wspierasz serwer)", style = MaterialTheme.typography.bodyMedium)
                            Switch(
                                checked = server.record.adsEnabled,
                                onCheckedChange = { viewModel.setCommunityServerAdsEnabled(serverId, it) },
                            )
                        }
                    }
                    if (platforms.isNotEmpty()) {
                        Text(
                            "🔔 powiadomienia · 👁 pokazuj w zakładce (możesz wyłączyć całą platformę albo twórcę)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        platforms.forEach { node ->
                            val pNotify = !CommunityContentFilter.isMuted(server.record.notifyMuted, node.platform, null)
                            val pDisplay = !CommunityContentFilter.isMuted(server.record.displayMuted, node.platform, null)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(node.label, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                                CommunityMuteChips(
                                    notifyOn = pNotify,
                                    notifyEnabled = true,
                                    displayOn = pDisplay,
                                    displayEnabled = true,
                                    onNotify = { viewModel.toggleCommunityNotify(serverId, node.platform) },
                                    onDisplay = { viewModel.toggleCommunityDisplay(serverId, node.platform) },
                                    subject = node.label,
                                )
                            }
                            node.creators.forEach { creator ->
                                val cNotify = !CommunityContentFilter.isMuted(server.record.notifyMuted, node.platform, creator.id)
                                val cDisplay = !CommunityContentFilter.isMuted(server.record.displayMuted, node.platform, creator.id)
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        creator.label,
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    CommunityMuteChips(
                                        notifyOn = cNotify,
                                        notifyEnabled = pNotify,
                                        displayOn = cDisplay,
                                        displayEnabled = pDisplay,
                                        onNotify = { viewModel.toggleCommunityNotify(serverId, node.platform, creator.id) },
                                        onDisplay = { viewModel.toggleCommunityDisplay(serverId, node.platform, creator.id) },
                                        subject = "${node.label}, ${creator.label}",
                                    )
                                }
                                // trzeci poziom: typy treści u twórcy (np. post / stream / video)
                                creator.types.forEach { type ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(start = 32.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            type,
                                            modifier = Modifier.weight(1f),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        CommunityMuteChips(
                                            notifyOn = !CommunityContentFilter.isMuted(server.record.notifyMuted, node.platform, creator.id, type),
                                            notifyEnabled = cNotify,
                                            displayOn = !CommunityContentFilter.isMuted(server.record.displayMuted, node.platform, creator.id, type),
                                            displayEnabled = cDisplay,
                                            onNotify = { viewModel.toggleCommunityNotify(serverId, node.platform, creator.id, type) },
                                            onDisplay = { viewModel.toggleCommunityDisplay(serverId, node.platform, creator.id, type) },
                                            subject = "${creator.label}, $type",
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // R-09: pokazujemy przycisk, gdy jest regulamin do przejrzenia — ze świeżego manifestu ALBO
                    // z bazy (reviewableTerms), żeby po restarcie przy zmienionym regulaminie nie było ślepego
                    // zaułka (dotąd warunek wymagał manifestu w pamięci).
                    if (!accepted && (server.manifest != null || server.reviewableTerms != null)) {
                        Button(onClick = { viewModel.reviewCommunityTerms(serverId) }) {
                            Text("Przejrzyj regulamin")
                        }
                    }
                    if (eligibleForPrimary && !isPrimary) {
                        OutlinedButton(onClick = { viewModel.setPrimaryServer(serverId) }) {
                            Text("Ustaw jako główny")
                        }
                    }
                    if (!CommunityDefaults.isDefault(serverId)) {
                        OutlinedButton(onClick = { viewModel.removeCommunityServer(serverId) }) { Text("Usuń") }
                    } else {
                        Text(
                            "Serwer domyślny (wbudowany) — nieusuwalny",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * T-11: pełnoekranowa zasłona — ekran pod spodem jest widoczny, ale wyszarzony i NIEAKTYWNY (okno dialogowe przejmuje
 * dotyk), a nad nim stoi nowy regulamin serwera domyślnego. Wyjścia są dwa: zaakceptować albo wybrać inny serwer główny
 * (wtedy serwer domyślny zostaje wyłączony i nie trzeba nic akceptować). Przycisk „wstecz” zasłony nie zamyka.
 */
@Composable
private fun DefaultServerTermsOverlay(
    title: String,
    intro: String,
    versionLabel: String,
    text: String,
    acceptLabel: String,
    onAccept: () -> Unit,
    onChooseOtherServer: () -> Unit,
) {
    val chunks = remember(text) { termsChunks(text) }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = {},
        properties = androidx.compose.ui.window.DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color(0xB3808080)).padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        title,
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        intro,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("Wersja: $versionLabel", style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(chunks) { chunk -> Text(chunk, style = MaterialTheme.typography.bodyMedium) }
                    }
                    HorizontalDivider()
                    Button(onClick = onAccept, modifier = Modifier.fillMaxWidth()) { Text(acceptLabel) }
                    OutlinedButton(onClick = onChooseOtherServer, modifier = Modifier.fillMaxWidth()) {
                        Text("Wybierz inny serwer główny")
                    }
                }
            }
        }
    }
}

@Composable
private fun CommunityTermsDialog(
    prompt: CommunityTermsPrompt,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onDismiss: () -> Unit,
) {
    // A-10: regulamin jest NIELIMITOWANY (R-22), więc nie renderujemy go jednym `Text` (do ~1 M znaków = zamrożenie
    // UI) — dzielimy na kawałki po akapitach (≤ 2000 znaków) i pokazujemy leniwie w przewijanej liście.
    val chunks = remember(prompt.terms.text) { termsChunks(prompt.terms.text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Regulamin serwera „${prompt.serverName}”") },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 440.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                Text(
                    "Ten regulamin ustala serwer społeczności, nie lewicowYT. Twoja decyzja zapisuje się " +
                        "tylko na tym telefonie — samej decyzji nie wysyłamy. Po akceptacji, gdy masz dla tego " +
                        "serwera włączone „Powiadomienia”, do serwera trafi tylko ulotny adres dostarczenia i " +
                        "Twój klucz publiczny (nic więcej); wyłączenie „Powiadomień” to wyrejestrowuje.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                }
                item { Text("Wersja: ${prompt.terms.versionLabel}", style = MaterialTheme.typography.bodySmall) }
                item { HorizontalDivider() }
                items(chunks) { chunk -> Text(chunk, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = { Button(onClick = onAccept) { Text("Akceptuję") } },
        dismissButton = { TextButton(onClick = onDecline) { Text("Odrzucam") } },
    )
}

/** A-10: dzieli długi regulamin na kawałki ≤ [TERMS_CHUNK_CHARS] znaków, preferując granice akapitów/linii. */
private fun termsChunks(text: String): List<String> {
    val out = ArrayList<String>()
    var start = 0
    while (start < text.length) {
        var end = (start + TERMS_CHUNK_CHARS).coerceAtMost(text.length)
        if (end < text.length) {
            val nl = text.lastIndexOf('\n', end)
            if (nl > start + TERMS_CHUNK_CHARS / 2) end = nl + 1
        }
        out += text.substring(start, end).trimEnd('\n')
        start = end
    }
    return out
}

private const val TERMS_CHUNK_CHARS = 2_000

private fun communityStatusLine(server: CommunityServerState): String {
    if (!server.record.enabled) return "Wyłączony."
    return when (server.consentState) {
        CommunityConsentState.ACCEPTED ->
            if (server.contributesToTab) {
                "Połączony — dane pojawiają się w tej zakładce."
            } else {
                "Zgoda zapisana. Serwer nic teraz nie udostępnia."
            }
        CommunityConsentState.DECLINED -> "Odmówiono regulaminu — brak połączenia."
        CommunityConsentState.CONSENT_REQUIRED -> "Wymaga akceptacji regulaminu."
        null -> server.error?.let { "Nie udało się pobrać: $it" } ?: "Nieznany stan — odśwież."
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
    // połączona zakładka: „Wszystkie" (pełna Historia, stronicowana) albo „🔔 Powiadomienia" (14 dni, bez stron)
    val notificationsView = state.historyView == HistoryView.NOTIFICATIONS
    val visibleItems = if (notificationsView) state.notificationsInView else state.history
    // LazyColumn trzyma pozycję po kluczu dawnego pierwszego elementu, więc nowe powiadomienia
    // dopisane na górze (i nagłówek „Nowe od ostatniej wizyty”) lądowały nad ekranem. Wejście w 🔔
    // zawsze pokazuje górę listy; nowa pozycja na górze też, o ile user nie przewinął w dół.
    LaunchedEffect(notificationsView) {
        if (notificationsView) listState.scrollToItem(0)
    }
    val topNotificationKey = if (notificationsView) visibleItems.firstOrNull()?.videoId else null
    LaunchedEffect(topNotificationKey) {
        if (topNotificationKey != null && listState.firstVisibleItemIndex <= 2) listState.scrollToItem(0)
    }
    val availableHistoryFilters = HistoryFilter.entries.filter {
        it in state.settings.globalHistoryTypes
    }
    val activeHistoryFilters = state.settings.historyFilters
        .intersect(state.settings.globalHistoryTypes)
    val shouldLoadMore by remember(
        state.history.size,
        state.historyHasMore,
        activeHistoryFilters,
        notificationsView,
    ) {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            !notificationsView && activeHistoryFilters.isNotEmpty() && state.historyHasMore &&
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
                // zakres czasu dotyczy tylko „Wszystkich" — w 🔔 nie może chować świeżych powiadomień
                if (!notificationsView) Box {
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
        // przełącznik widoku: „Wszystkie" / „🔔 Powiadomienia" (z licznikiem nowych od ostatniej wizyty)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = !notificationsView,
                onClick = { viewModel.setHistoryView(HistoryView.ALL) },
                label = { Text(tr("Wszystkie")) },
            )
            FilterChip(
                selected = notificationsView,
                onClick = { viewModel.setHistoryView(HistoryView.NOTIFICATIONS) },
                label = {
                    Text(
                        if (state.unseenNotificationCount > 0) {
                            "🔔 ${tr("Powiadomienia")} · ${state.unseenNotificationCount}"
                        } else {
                            "🔔 ${tr("Powiadomienia")}"
                        },
                    )
                },
                modifier = Modifier.semantics {
                    contentDescription = "Powiadomienia, nowe: ${state.unseenNotificationCount}"
                },
            )
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
            if (!notificationsView) {
                OutlinedButton(onClick = { olderSearchVisible = true }) {
                    Text("Znajdź starszy")
                }
            }
        }

        if (notificationsView && visibleItems.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (state.historySearchQuery.isNotBlank() || state.favoritesOnly) {
                        "Brak powiadomień pasujących do filtrów."
                    } else {
                        "Brak powiadomień z ostatnich 14 dni. Każdy materiał, o którym przyszło " +
                            "powiadomienie, pojawi się tutaj od najnowszego."
                    },
                )
            }
            return@Column
        }

        if (!notificationsView && state.history.isEmpty()) {
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

        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            // B-90: gest odświeżania = synchronizacja (ten sam stan isRefreshing, co wskaźnik)
            onRefresh = viewModel::syncNow,
            // TalkBack: alternatywa dla gestu przeciągnięcia (menu akcji czytnika)
            modifier = Modifier.fillMaxSize().semantics {
                customActions = listOf(CustomAccessibilityAction("Sprawdź nowe materiały") { viewModel.syncNow(); true })
            },
        ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val card: @Composable (HistoryItem) -> Unit = { item ->
                MaterialHistoryCard(
                    item = item,
                    isDescriptionDownloadActive = state.isLoadingDescriptions,
                    isPrivate = item.videoId in state.privateVideoIds,
                    // w „Wszystkich" 🔔 pokazuje, o czym przyszło powiadomienie (w widoku 🔔 to oczywiste)
                    showNotified = !notificationsView && item.notified,
                    onOpen = {
                        youtubeWatchUrl(item.videoId)?.let(openYouTubeLink)
                    },
                    onFavoriteChange = { viewModel.setFavorite(item.videoId, it) },
                )
            }
            if (notificationsView) {
                // separator „Nowe od ostatniej wizyty" — granica to poprzednie otwarcie widoku 🔔
                val (fresh, older) = visibleItems.partition {
                    it.detectedAtMillis > state.notificationsSeparatorAtMillis
                }
                if (fresh.isNotEmpty()) {
                    item(key = "notifications-new") { NotificationSectionLabel("Nowe od ostatniej wizyty") }
                    items(fresh, key = HistoryItem::videoId) { card(it) }
                    if (older.isNotEmpty()) {
                        item(key = "notifications-older") { NotificationSectionLabel("Wcześniejsze") }
                    }
                }
                items(older, key = HistoryItem::videoId) { card(it) }
                return@LazyColumn
            }
            items(state.history, key = HistoryItem::videoId) { card(it) }
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

/** nagłówek sekcji w widoku 🔔 („Nowe od ostatniej wizyty" / „Wcześniejsze"). */
@Composable
private fun NotificationSectionLabel(text: String) {
    Text(
        tr(text),
        modifier = Modifier.padding(top = 4.dp).semantics { heading() },
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun MaterialHistoryCard(
    item: HistoryItem,
    isDescriptionDownloadActive: Boolean,
    isPrivate: Boolean,
    showNotified: Boolean = false,
    onOpen: () -> Unit,
    onFavoriteChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val nowMillis = System.currentTimeMillis()
    val displayKind = item.displayKindAt(nowMillis)
    val statusBadges = buildList {
        // stream, który stał się prywatny — oznaczamy emotką jak inne statusy
        if (isPrivate) add(Triple(MaterialStatusBadge.PRIVATE, "🔒", "Stream jest teraz prywatny"))
        item.statusBadgesAt(nowMillis).forEach { badge ->
            val (icon, label) = when (badge) {
                MaterialStatusBadge.SCHEDULED -> "🗓️" to "Zaplanowana transmisja"
                MaterialStatusBadge.DESCRIPTION -> "📓" to "Opis materiału został pobrany"
                MaterialStatusBadge.MEMBERS_ONLY -> "💵" to "Materiał tylko dla wspierających"
                MaterialStatusBadge.PRIVATE -> "🔒" to "Stream jest teraz prywatny"
            }
            add(Triple(badge, icon, label))
        }
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
                if (showNotified) {
                    StatusBadgeText(icon = "🔔", label = "Przyszło powiadomienie o tym materiale", spinning = false)
                }
                statusBadges.forEach { (badge, icon, label) ->
                    StatusBadgeText(
                        icon = icon,
                        label = label,
                        // zaplanowany stream to jedyna odznaka, której stan może
                        // się jeszcze ruszyć w trakcie pobierania opisów
                        // (potwierdzenie/wygaśnięcie po terminie) – więc tylko ona kręci się animacją
                        spinning = isDescriptionDownloadActive &&
                            badge == MaterialStatusBadge.SCHEDULED,
                    )
                }
            }
        }
    }
}

/**
 * odznaka statusu materiału. przy [spinning] ikona kręci się płynnie w kółko –
 * sygnał, że w tle lecą opisy, bez odrywania uwagi (obejmuje tylko „🗓️", patrz wywołanie).
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
            // stała prędkość (LinearEasing), bez rozpędzania/hamowania co obrót –
            // brak „zacięcia" na złączeniu pętli, płynnie jak zwykły spinner
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
    openYouTubeLink: (String) -> Unit,
    onOpenCommunityManager: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val uriHandler = LocalUriHandler.current
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
                    "Usunięte zostaną lokalna historia (także widok 🔔 Powiadomienia) oraz " +
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
            TranslationSettingsCard()
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
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SwitchSetting(
                    title = "Zezwól na wykorzystywanie danych komórkowych",
                    checked = state.settings.allowMobileData,
                    onCheckedChange = viewModel::setAllowMobileData,
                )
                Text(
                    "Dotyczy ręcznego odświeżania Historii i pobierania opisów. " +
                        "Powiadomienia o nowych materiałach dostarcza serwer.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SwitchSetting(
                    title = "Miniaturki YouTube w JXL",
                    checked = state.settings.youtubeThumbnailsAsJxl,
                    onCheckedChange = viewModel::setYoutubeThumbnailsAsJxl,
                )
                Text(
                    "Wyłączone (domyślnie): miniaturki pobierane jako WebP i trzymane bez zmian — mniejszy " +
                        "transfer niż JPG, zero dodatkowego obciążenia. Włączone: apka przekodowuje je do JXL — " +
                        "zajmują mniej miejsca na dysku, ale kosztem pracy procesora (kompresja i dekodowanie).",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item {
            ActiveHoursSettings(state, viewModel)
        }
        item {
            CommunityServersSettingsRow(viewModel = viewModel, onOpen = onOpenCommunityManager)
        }
        // „Obrazy społeczności" i „Zapamiętywanie treści społeczności" przeniesione do podsekcji:
        // Serwery społeczności → zębatka (CommunityContentSettingsScreen).
        // Wariant `play`: aktualizacje dostarcza wyłącznie Google Play (zasady Google Play) — bez samoaktualizatora.
        if (!BuildConfig.SELF_UPDATE) item {
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
                        "Zainstalowana wersja: ${BuildConfig.VERSION_NAME}. Aktualizacje dostarcza Google Play.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        if (BuildConfig.SELF_UPDATE) item {
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
                        "Po włączeniu aplikacja pobiera nowe APK w tle (sprawdza przy starcie, " +
                            "najwyżej co 2 godziny). Instalacja nie otwiera przeglądarki, ale " +
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
                        // A-51: bez instrukcji dla dewelopera (użytkownik nic tu nie zmieni)
                        UpdateUiState.NotConfigured -> Text(
                            "Ta kompilacja aplikacji nie ma włączonego sprawdzania aktualizacji.",
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
                                    "Autor oznaczył to wydanie jako poprawkę bezpieczeństwa — " +
                                        "ta aktualizacja jest obowiązkowa.",
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
                        // A-57: tekst zgodny z kodem (M-11) — usunięcie wydania z GitHuba NICZEGO nie wymusza;
                        // obowiązkowa jest tylko aktualizacja z jawnym markerem w opisie wydania.
                        "Aktualizacja jest obowiązkowa tylko wtedy, gdy autor oznaczy nowe wydanie " +
                            "jako poprawkę bezpieczeństwa (znacznik [security-mandatory] w opisie " +
                            "wydania) — wtedy aplikacja pobierze je niezależnie od ustawienia " +
                            "automatycznych aktualizacji i poprosi o instalację. Usunięcie wydania " +
                            "z GitHuba niczego nie wymusza. Każda aktualizacja musi być podpisana " +
                            "tym samym kluczem i mieć wyższy versionCode.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item {
            OutlinedButton(onClick = { clearConfirmationVisible = true }) {
                Text("Wyczyść historię na stan początkowy")
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
                // E-05: liczba zdarzeń w tle — dekodowanie kilku MB dziennika nie może blokować UI
                val diagnosticEventCount by produceState(-1, diagnosticState) {
                    value = withContext(Dispatchers.IO) { DiagnosticLogStore.eventCount() }
                }
                val watchTestContext = LocalContext.current
                DiagnosticLogSettings(
                    state = diagnosticState.copy(eventCount = diagnosticEventCount),
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
                    onTrafficChange = { on ->
                        DiagnosticLogStore.setTrafficEnabled(on)
                        diagnosticState = DiagnosticLogStore.state()
                        diagnosticMessage = if (on) "Zapisuję ruch sieciowy." else "Zatrzymano zapis ruchu sieciowego."
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
                    onWatchTest = {
                        val sent = viewModel.sendWatchTestNotification()
                        Toast.makeText(
                            watchTestContext,
                            if (sent) "Wysłano powiadomienie testowe." else "Powiadomienia lewicowYT są wyłączone w ustawieniach telefonu.",
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                    onHide = {
                        DiagnosticLogStore.hide()
                        diagnosticState = DiagnosticLogStore.state()
                        diagnosticMessage = null
                        dnsTapCount = 0
                    },
                )
            }
            item {
                // UKRYTE (tylko po odblokowaniu diagnostyki): zaufanie do certyfikatów użytkownika dla pracy przez proxy z
                // inspekcją TLS. Domyślnie wyłączone; uzasadnienie i zakres: network/UserCertificateTrust.kt (nota dla audytorów).
                val certContext = LocalContext.current
                var trustUserCerts by remember { mutableStateOf(PrivacySettings.trustUserCertificates(certContext)) }
                Card(Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SwitchSetting(
                            title = "Ufaj certyfikatom użytkownika (proxy)",
                            checked = trustUserCerts,
                            onCheckedChange = {
                                trustUserCerts = it
                                PrivacySettings.setTrustUserCertificates(certContext, it)
                                DiagnosticLogStore.event(
                                    DiagnosticCategory.NETWORK,
                                    DiagnosticLevel.WARNING,
                                    "USER_CA_TRUST_CHANGED",
                                    fields = mapOf("enabled" to it),
                                )
                            },
                        )
                        Text(
                            "Pozwala aplikacji działać przez proxy z inspekcją TLS (np. Burp, mitmproxy, sieć firmowa), której " +
                                "certyfikat CA zainstalowałeś w telefonie. UWAGA: gdy włączone, każdy zainstalowany przez Ciebie CA " +
                                "może podsłuchać połączenia aplikacji (treści serwerów społeczności, strony YouTube, GitHub). " +
                                "Nie dotyczy szyfrowanego DNS ani Tora. Pozostaw wyłączone, jeśli nie debugujesz ruchu.",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (trustUserCerts) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
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
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { thirdPartyNoticesVisible = true }, modifier = Modifier.weight(1f, fill = false)) {
                    Text("Licencje komponentów zewnętrznych")
                }
                Spacer(Modifier.weight(1f))
                CandleLink()
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
            // bez clickable/ripple: pierwsze cztery tapnięcia są niewidoczne,
            // karta nie zdradza ukrytej funkcji w semantyce UI
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
    onTrafficChange: (Boolean) -> Unit,
    onExport: () -> Unit,
    onClear: () -> Unit,
    onSnapshot: () -> Unit,
    onCheckDatabase: () -> Unit,
    onWatchTest: () -> Unit,
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
            SwitchSetting(
                title = "Zapisuj ruch sieciowy",
                checked = state.trafficEnabled,
                onCheckedChange = onTrafficChange,
                enabled = state.enabled,
            )
            Text(
                "Każde zapytanie HTTP aplikacji: metoda, adres bez parametrów, kod odpowiedzi, bajty i czas. " +
                    "Nazwy serwerów społeczności są zastępowane skrótem (ten sam serwer = ten sam skrót). " +
                    "Osobny dziennik do 8 MB, eksportowany razem z diagnostyką. Wymaga włączonej diagnostyki.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Dziennik zapisuje uruchomienia synchronizacji, jej wynik, " +
                    "problemy harmonogramu, niedostępną sieć oraz krótkie błędy. " +
                    "Dla opisów zapisuje źródło API/Web, czas oraz rozmiar przed i po " +
                    "kompresji. Przy lokalnym wyszukiwaniu zapisuje tylko długość zapytania, jego " +
                    "skrót oraz identyfikatory pokazanych filmów (bez treści i tytułów). Adresy " +
                    ".onion i IP w komunikatach błędów są ukrywane. " +
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
                    "zdarzenia: ${if (state.eventCount < 0) "liczę…" else state.eventCount}",
                style = MaterialTheme.typography.labelSmall,
            )
            Button(
                onClick = onExport,
                enabled = state.eventCount > 0 || (state.eventCount < 0 && state.storedBytes > 0),
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
            // Q-06: Mi Fitness / Zepp dopisują aplikację do swojej listy dopiero po pierwszym jej powiadomieniu
            OutlinedButton(onClick = onWatchTest) { Text("Wyślij powiadomienie testowe (zegarek)") }
            Text(
                "Opaska Xiaomi (Mi Fitness), Amazfit (Zepp) albo inna nie widzi lewicowYT na liście aplikacji? Te " +
                    "aplikacje dopisują ją dopiero, gdy zobaczą od niej powiadomienie. Wyślij testowe, a potem w Mi " +
                    "Fitness: Urządzenie → Powiadomienia i połączenia → Powiadomienia aplikacji → włącz lewicowYT.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onClear,
                    enabled = state.eventCount != 0 && state.storedBytes > 0,
                ) {
                    Text("Wyczyść")
                }
                TextButton(onClick = onHide) {
                    Text("Wyłącz, usuń dziennik i ukryj")
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
                    YouTubeLinkTarget.entries
                        .forEach { target ->
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
    // sekret nie idzie do SavedState/Bundle i po zapisaniu jest czyszczony
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
    var pronounMenuOpen by remember { mutableStateOf(false) }

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
            Text(tr("Zaimki"), fontWeight = FontWeight.SemiBold)
            Box {
                OutlinedButton(
                    onClick = { pronounMenuOpen = true },
                    modifier = Modifier.semantics {
                        contentDescription = "Zaimki"
                        stateDescription = state.settings.pronoun.label
                    },
                ) {
                    Text(state.settings.pronoun.label)
                }
                DropdownMenu(
                    expanded = pronounMenuOpen,
                    onDismissRequest = { pronounMenuOpen = false },
                ) {
                    Pronoun.entries.forEach { p ->
                        DropdownMenuItem(
                            text = { Text(p.label) },
                            onClick = {
                                pronounMenuOpen = false
                                viewModel.setPronoun(p)
                            },
                        )
                    }
                }
            }
            Text(
                tr("Aplikacja dobiera formy rodzajowe (np. „wyraziłeś/aś/oś zgodę”) do wybranych zaimków. " +
                    "Działa też z tłumaczeniami użytkowników — warianty rodzajowe zachowują tłumacze."),
                style = MaterialTheme.typography.bodySmall,
            )
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

@Composable
private fun ActiveHoursSettings(state: AppUiState, viewModel: AppViewModel) {
    val settings = state.settings
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Godziny powiadomień",
                modifier = Modifier.semantics { heading() },
                fontWeight = FontWeight.SemiBold,
            )
            SwitchSetting(
                title = "Powiadamiaj tylko w wybranych godzinach",
                checked = settings.activeHoursEnabled,
                onCheckedChange = viewModel::setActiveHoursEnabled,
            )
            if (settings.activeHoursEnabled) {
                ActiveHoursTimeRow(
                    label = "Od",
                    minutes = settings.activeHoursStartMinutes,
                ) { hour, minute ->
                    viewModel.setActiveHours(hour * 60 + minute, settings.activeHoursEndMinutes)
                }
                ActiveHoursTimeRow(
                    label = "Do",
                    minutes = settings.activeHoursEndMinutes,
                ) { hour, minute ->
                    viewModel.setActiveHours(settings.activeHoursStartMinutes, hour * 60 + minute)
                }
                Text(
                    "Poza tymi godzinami powiadomienia czekają i pojawiają się po " +
                        "otwarciu okna jako jedno zbiorcze powiadomienie.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // Powiadomienia na zegarek nie mają przełącznika w aplikacji: o tym, czy pokazać je na zegarku, decyduje
            // aplikacja producenta zegarka (Mi Fitness, Zepp, Wear OS…). Przycisk testowy (Q-06) jest w ukrytej
            // Diagnostyce — patrz DiagnosticLogSettings.
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActiveHoursTimeRow(
    label: String,
    minutes: Int,
    onPicked: (hour: Int, minute: Int) -> Unit,
) {
    val hour = minutes / 60
    val minute = minutes % 60
    // A-52: dialog w Compose ze stanem w rememberSaveable — przeżywa obrót ekranu/zmianę konfiguracji
    // (dawny android.app.TimePickerDialog.show() z onClick wyciekał okno i gubił wybór godziny).
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    if (pickerOpen) {
        val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickerOpen = false },
            title = { Text(label) },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    pickerOpen = false
                    onPicked(state.hour, state.minute)
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickerOpen = false }) { Text("Anuluj") } },
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "Zmień godzinę") { pickerOpen = true }
            .semantics(mergeDescendants = true) {
                stateDescription = "%02d:%02d".format(hour, minute)
            }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label)
        Text("%02d:%02d".format(hour, minute), fontWeight = FontWeight.Medium)
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
    YouTubeLinkTarget.ALWAYS_ASK -> "Zapytaj i zapamiętaj wybór"
    YouTubeLinkTarget.YOUTUBE -> "YouTube"
    YouTubeLinkTarget.BROWSER -> "Przeglądarka"
    YouTubeLinkTarget.OTHER_APP -> "Dowolna inna aplikacja"
}

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
