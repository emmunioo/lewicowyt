package pl.lewicowyt.notifier.audio

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import pl.lewicowyt.notifier.community.CommunityAudio
import pl.lewicowyt.notifier.video.VideoPolicy
import pl.lewicowyt.notifier.video.VideoSupport

/**
 * Sterowanie odtwarzaniem z interfejsu: jeden wspólny odtwarzacz dla całej aplikacji (w [AudioPlaybackService]),
 * do którego UI łączy się przez MediaController. Stan (co gra, pozycja, prędkość, pętla) wystawiamy jako
 * StateFlow — karty w zakładce Społeczność tylko go rysują.
 */
object CommunityAudioPlayer {
    /** prędkości do wyboru; 3× zgodnie z wymaganiem (ExoPlayer zachowuje wysokość głosu — Sonic). */
    val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

    data class State(
        val url: String? = null,
        val title: String? = null,
        val loading: Boolean = false,
        val playing: Boolean = false,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val speed: Float = 1f,
        val loop: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: MediaController? = null
    private var ticker: Job? = null
    private var loadJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish(player)
        override fun onPlayerError(error: PlaybackException) {
            _state.update { it.copy(playing = false, loading = false, error = "Nie da się odtworzyć tego pliku") }
        }
    }

    /** Przycisk ▶/⏸ na karcie: ten sam dźwięk = pauza/wznów, inny = pobierz (jeśli trzeba) i graj. */
    fun toggle(context: Context, audio: CommunityAudio, title: String, artist: String?) {
        // D-08: ten sam dźwięk właśnie się pobiera — ponowne dotknięcie nie zaczyna pobierania od zera
        if (_state.value.url == audio.url && _state.value.loading) return
        val c = controller
        if (_state.value.url == audio.url && c != null && c.mediaItemCount > 0 && !_state.value.loading) {
            if (c.isPlaying) c.pause() else {
                // C-16: po błędzie odtwarzacz jest w IDLE — bez prepare() ▶ nic by nie zrobił
                if (c.playbackState == Player.STATE_IDLE) c.prepare()
                if (c.playbackState == Player.STATE_ENDED) c.seekTo(0)
                c.play()
            }
            return
        }
        loadJob?.cancel()
        val keep = _state.value
        _state.value = State(url = audio.url, title = title, loading = true, speed = keep.speed, loop = keep.loop,
            durationMs = audio.durationMs ?: 0L)
        val app = context.applicationContext
        loadJob = scope.launch {
            // format sprawdzany w trakcie pobierania: nie-Opus przerwany po pierwszych bajtach
            val file = when (val r = CommunityAudioCache.fetch(app, audio.url)) {
                is CommunityMediaCache.Fetch.Ok -> r.file
                is CommunityMediaCache.Fetch.Rejected -> {
                    _state.update { it.copy(loading = false, error = r.reason) }
                    return@launch
                }
                CommunityMediaCache.Fetch.Failed -> {
                    _state.update { it.copy(loading = false, error = "Nie udało się pobrać dźwięku (sieć albo Tor niegotowy)") }
                    return@launch
                }
            }
            // tylko Opus: sprawdzamy prawdziwy kodek ścieżki (np. Vorbis w .ogg albo AAC w .webm → odmowa)
            val mime = withContext(Dispatchers.IO) { VideoSupport.audioTrackMime(file) }
            if (!VideoPolicy.isAllowedAudioTrack(mime)) {
                CommunityMediaCache.discard(app, audio.url, CommunityMediaCache.Kind.AUDIO)
                _state.update { it.copy(loading = false, error = VideoPolicy.notOpusMessage(mime)) }
                return@launch
            }
            val ctl = connect(app) ?: run {
                _state.update { it.copy(loading = false, error = "Odtwarzacz niedostępny") }
                return@launch
            }
            val item = MediaItem.Builder()
                .setMediaId(audio.url)
                .setUri(Uri.fromFile(file))
                .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).build())
                .build()
            ctl.setMediaItem(item)
            ctl.repeatMode = if (_state.value.loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            ctl.setPlaybackSpeed(_state.value.speed)
            ctl.prepare()
            ctl.play()
            _state.update { it.copy(loading = false) }
        }
    }

    /**
     * Film przez tę samą usługę co dźwięk: powiadomienie z przyciskami, ekran blokady, gra dalej po wyjściu
     * z aplikacji. Plik jest już pobrany i sprawdzony (kodek, ≤ 30 kl/s). Zwraca kontroler do podpięcia obrazu.
     */
    suspend fun startVideo(context: Context, file: java.io.File, url: String, title: String): MediaController? {
        loadJob?.cancel()
        val ctl = connect(context.applicationContext) ?: return null
        val keep = _state.value
        _state.value = State(url = url, title = title, speed = keep.speed, loop = keep.loop)
        ctl.setMediaItem(
            MediaItem.Builder()
                .setMediaId(url)
                .setUri(Uri.fromFile(file))
                .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
                .build(),
        )
        ctl.trackSelectionParameters = ctl.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false).build()
        ctl.repeatMode = if (keep.loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        ctl.setPlaybackSpeed(keep.speed)
        ctl.prepare()
        ctl.play()
        return ctl
    }

    /**
     * Aplikacja w tle: film gra dalej, ale bez dekodowania obrazu (sam dźwięk) — mniej baterii i procesora,
     * co na słabym telefonie ma znaczenie. Po powrocie obraz wraca.
     */
    fun setVideoTrackEnabled(enabled: Boolean) {
        controller?.let {
            it.trackSelectionParameters = it.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !enabled).build()
        }
    }

    fun setSpeed(speed: Float) {
        val s = speed.coerceIn(0.5f, 3f)
        _state.update { it.copy(speed = s) }
        controller?.setPlaybackSpeed(s)
    }

    fun setLoop(loop: Boolean) {
        _state.update { it.copy(loop = loop) }
        controller?.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs.coerceAtLeast(0L))
        _state.update { it.copy(positionMs = positionMs.coerceAtLeast(0L)) }
    }

    fun stop() {
        loadJob?.cancel()
        controller?.run { stop(); clearMediaItems() }
        _state.update { State(speed = it.speed, loop = it.loop) }
    }

    private fun publish(player: Player) {
        _state.update {
            it.copy(
                playing = player.isPlaying,
                positionMs = player.currentPosition.coerceAtLeast(0L),
                durationMs = player.duration.takeIf { d -> d > 0 } ?: it.durationMs,
                speed = player.playbackParameters.speed,
                loop = player.repeatMode == Player.REPEAT_MODE_ONE,
                error = if (player.playerError != null) it.error else null,
            )
        }
        // pozycja do paska postępu odświeżana tylko, gdy coś gra (bez stałego budzenia CPU)
        if (player.isPlaying && ticker?.isActive != true) {
            ticker = scope.launch {
                while (isActive && controller?.isPlaying == true) {
                    controller?.let { c -> _state.update { s -> s.copy(positionMs = c.currentPosition.coerceAtLeast(0L)) } }
                    delay(500)
                }
            }
        }
    }

    private suspend fun connect(context: Context): MediaController? {
        controller?.takeIf { it.isConnected }?.let { return it }
        // C-16: rozłączony kontroler (ubita usługa) zwalniamy — inaczej wisiałby z podpiętym listenerem
        controller?.run { removeListener(listener); release() }
        controller = null
        val token = SessionToken(context, ComponentName(context, AudioPlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        val ctl: MediaController = suspendCancellableCoroutine<MediaController?> { cont ->
            future.addListener({
                cont.resume(runCatching { future.get() }.getOrNull())
            }, ContextCompat.getMainExecutor(context))
            cont.invokeOnCancellation { MediaController.releaseFuture(future) }
        } ?: return null
        ctl.addListener(listener)
        controller = ctl
        return ctl
    }
}
