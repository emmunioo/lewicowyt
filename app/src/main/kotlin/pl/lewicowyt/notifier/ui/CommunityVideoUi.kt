package pl.lewicowyt.notifier.ui

import android.view.SurfaceView
import android.net.ConnectivityManager
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.compose.runtime.collectAsState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.lewicowyt.notifier.audio.CommunityAudioPlayer
import pl.lewicowyt.notifier.audio.CommunityMediaCache
import pl.lewicowyt.notifier.video.MediaPrechecks
import pl.lewicowyt.notifier.community.CommunityVideo
import pl.lewicowyt.notifier.i18n.tr
import pl.lewicowyt.notifier.video.VideoPolicy
import pl.lewicowyt.notifier.video.VideoSupport

/** Przycisk filmu na karcie pozycji społeczności; film otwiera się w osobnym oknie odtwarzacza. */
@Composable
internal fun CommunityVideoRow(video: CommunityVideo, title: String) {
    var open by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val label = video.title ?: title
    val v = video.hevc ?: video.avc
    // C-17: rozmiar przed pobraniem — większy z wariantów (który zostanie wybrany, wiadomo dopiero po sprawdzeniu dekodera)
    val maxBytes = listOfNotNull(video.hevc?.bytes, video.avc?.bytes).maxOrNull()?.takeIf { it > 0 }
    val details = listOfNotNull(
        video.durationMs?.let { formatClock(it) },
        v?.height?.let { "${it}p" },
        maxBytes?.let { "do " + formatMegabytes(it) },
    ).joinToString(" · ")
    FilledTonalButton(
        onClick = {
            // sieć taryfowa (dane komórkowe) i duży plik — najpierw pytamy
            val metered = context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true
            // D-09: brak deklaracji rozmiaru = traktujemy jak duży plik (pytamy); deklaracja jest wiążąca przy pobieraniu
            if (metered && (maxBytes == null || maxBytes > CONFIRM_METERED_BYTES)) confirm = true else open = true
        },
        modifier = Modifier.semantics { contentDescription = "Obejrzyj film: $label" },
    ) {
        Text("🎬 " + tr("Obejrzyj film") + if (details.isNotEmpty()) " ($details)" else "")
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(tr("Pobrać film?")) },
            text = {
                Text(
                    tr("Jesteś na danych komórkowych (sieć taryfowa).") + " " +
                        (maxBytes?.let { tr("Film zajmie") + " " + formatMegabytes(it) + "." } ?: tr("Serwer nie podał rozmiaru filmu (najwyżej 200 MB).")),
                )
            },
            confirmButton = { TextButton(onClick = { confirm = false; open = true }) { Text(tr("Pobierz")) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(tr("Anuluj")) } },
        )
    }
    if (open) VideoPlayerDialog(video, label) { open = false }
}

private const val CONFIRM_METERED_BYTES = 50L * 1024 * 1024

private fun formatMegabytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb < 10) String.format(java.util.Locale.ROOT, "%.1f MB", mb).replace('.', ',') else "${mb.toLong()} MB"
}

private sealed interface VideoLoad {
    data class Loading(val message: String) : VideoLoad
    data class Failed(val message: String) : VideoLoad
    data class Ready(val file: File, val url: String, val width: Int, val height: Int) : VideoLoad
}

/**
 * Pełnoekranowy odtwarzacz: wybór wariantu (sprzętowy H.265 albo H.264), pobranie prywatną ścieżką,
 * POMIAR pliku (kodek, kl/s) i dopiero wtedy odtwarzanie. Film powyżej 30 kl/s nie zostanie odtworzony.
 */
@Composable
private fun VideoPlayerDialog(video: CommunityVideo, title: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    // zamknięcie okna (przycisk albo wstecz) zatrzymuje film — w tle gra tylko, gdy wychodzisz z aplikacji
    val onClose = {
        val url = CommunityAudioPlayer.state.value.url
        if (url != null && (url == video.hevc?.url || url == video.avc?.url)) CommunityAudioPlayer.stop()
        onDismiss()
    }
    var load by remember { mutableStateOf<VideoLoad>(VideoLoad.Loading("Wybieram wersję filmu…")) }

    LaunchedEffect(video) {
        // C-22e: MediaCodecList potrafi przyciąć UI — poza wątkiem głównym
        val choice = withContext(Dispatchers.Default) { VideoSupport.choose(video) }
        if (choice == null) {
            load = VideoLoad.Failed(
                if (video.avc == null) "Ten telefon nie ma sprzętowego dekodera H.265, a serwer nie dał wersji H.264."
                else "Ten telefon nie potrafi odtworzyć tego filmu.",
            )
            return@LaunchedEffect
        }
        load = VideoLoad.Loading(if (choice.mime == VideoSupport.MIME_HEVC) "Pobieram film (H.265)…" else "Pobieram film (H.264)…")
        // kodek, kl/s i dźwięk sprawdzane ze spisu (moov) na początku pliku — zły film przerwany przed pobraniem obrazu
        val precheck = MediaPrechecks.mp4Video(if (choice.mime == VideoSupport.MIME_HEVC) "hevc" else "avc")
        val file = when (val r = CommunityMediaCache.fetch(context, choice.variant.url, CommunityMediaCache.Kind.VIDEO, precheck, choice.variant.bytes)) {
            is CommunityMediaCache.Fetch.Ok -> r.file
            is CommunityMediaCache.Fetch.Rejected -> { load = VideoLoad.Failed(r.reason); return@LaunchedEffect }
            CommunityMediaCache.Fetch.Failed -> {
                load = VideoLoad.Failed("Nie udało się pobrać filmu (sieć, Tor niegotowy albo plik za duży).")
                return@LaunchedEffect
            }
        }
        val probe = withContext(Dispatchers.IO) { VideoSupport.probe(file) }
        load = when {
            probe == null -> VideoLoad.Failed("Nie da się odczytać tego filmu.")
            !probe.mime.equals(choice.mime, ignoreCase = true) ->
                VideoLoad.Failed("Plik ma inny kodek, niż zadeklarował serwer — nie odtwarzam.")
            !VideoPolicy.isAllowedVideoSoundtrack(probe.audioMime) ->
                VideoLoad.Failed(VideoPolicy.notOpusMessage(probe.audioMime))
            !VideoPolicy.isAllowedFps(probe.fps) ->
                VideoLoad.Failed(probe.fps?.let { VideoPolicy.tooManyFpsMessage(it) } ?: "Nie da się sprawdzić liczby klatek — nie odtwarzam.")
            else -> VideoLoad.Ready(file, choice.variant.url, probe.width, probe.height)
        }
        if (load is VideoLoad.Failed) CommunityMediaCache.discard(context, choice.variant.url, CommunityMediaCache.Kind.VIDEO)
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(Color.Black).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Color.White, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 2)
                TextButton(onClick = onClose) { Text(tr("Zamknij")) }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when (val l = load) {
                    is VideoLoad.Loading -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text(l.message, color = Color.White, modifier = Modifier.padding(top = 8.dp))
                    }
                    is VideoLoad.Failed -> Text(l.message, color = Color.White, modifier = Modifier.padding(16.dp))
                    is VideoLoad.Ready -> VideoSurfaceWithControls(l, title)
                }
            }
        }
    }
}

@Composable
private fun VideoSurfaceWithControls(ready: VideoLoad.Ready, title: String) {
    val context = LocalContext.current
    // film gra przez usługę w tle (jak dźwięk): powiadomienie, ekran blokady, gra dalej po wyjściu z aplikacji
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var surface by remember { mutableStateOf<SurfaceView?>(null) }
    LaunchedEffect(ready.url) {
        controller = CommunityAudioPlayer.startVideo(context, ready.file, ready.url, title)
    }
    LaunchedEffect(controller, surface) {
        val c = controller ?: return@LaunchedEffect
        surface?.let { c.setVideoSurfaceView(it) }
    }
    DisposableEffect(Unit) {
        onDispose { surface?.let { s -> controller?.clearVideoSurfaceView(s) } }
    }
    // w tle: dalej gra, ale bez dekodowania obrazu (sam dźwięk — mniej baterii); po powrocie obraz wraca
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> CommunityAudioPlayer.setVideoTrackEnabled(false)
                Lifecycle.Event.ON_START -> CommunityAudioPlayer.setVideoTrackEnabled(true)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val state by CommunityAudioPlayer.state.collectAsState()
    val mine = state.url == ready.url
    val playing = mine && state.playing
    val aspect = if (ready.width > 0 && ready.height > 0) ready.width.toFloat() / ready.height else 16f / 9f
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AndroidView(
            factory = { ctx -> SurfaceView(ctx).also { it.keepScreenOn = true; surface = it } },
            modifier = Modifier.fillMaxWidth().aspectRatio(aspect),
        )
        if (controller == null) {
            Text(tr("Uruchamiam odtwarzacz…"), color = Color.White)
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = {
                    val c = controller ?: return@FilledTonalButton
                    if (c.isPlaying) c.pause() else {
                        // D-10: po błędzie odtwarzacz jest w IDLE — bez prepare() ▶ nic by nie zrobił
                        if (c.playbackState == Player.STATE_IDLE) c.prepare()
                        if (c.playbackState == Player.STATE_ENDED) c.seekTo(0)
                        c.play()
                    }
                },
                modifier = Modifier.semantics { contentDescription = if (playing) "Pauza" else "Odtwórz" },
            ) { Text(if (playing) "⏸" else "▶") }
            Text("${formatClock(state.positionMs)} / ${formatClock(state.durationMs)}", color = Color.White)
        }
        state.error?.takeIf { mine }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        var dragging by remember { mutableStateOf(false) }
        var dragValue by remember { mutableFloatStateOf(0f) }
        val total = state.durationMs.coerceAtLeast(1L).toFloat()
        Slider(
            value = if (dragging) dragValue else (state.positionMs / total).coerceIn(0f, 1f),
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = { CommunityAudioPlayer.seekTo((dragValue * total).toLong()); dragging = false },
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = "Pozycja filmu"
                stateDescription = "${formatClock(state.positionMs)} z ${formatClock(state.durationMs)}"
            },
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(
                selected = state.loop,
                onClick = { CommunityAudioPlayer.setLoop(!state.loop) },
                label = { Text("🔁 " + tr("Pętla")) },
            )
            CommunityAudioPlayer.SPEEDS.forEach { s ->
                FilterChip(
                    selected = kotlin.math.abs(state.speed - s) < 0.01f,
                    onClick = { CommunityAudioPlayer.setSpeed(s) },
                    label = { Text((if (s % 1f == 0f) s.toInt().toString() else s.toString().replace('.', ',')) + "×") },
                )
            }
        }
        Text(
            tr("Po wyjściu z aplikacji film gra dalej jako dźwięk (z powiadomieniem); „Zamknij” zatrzymuje odtwarzanie."),
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
