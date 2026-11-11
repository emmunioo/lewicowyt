package pl.lewicowyt.notifier.audio

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import pl.lewicowyt.notifier.MainActivity

/**
 * Odtwarzanie dźwięku z serwerów społeczności W TLE: usługa pierwszoplanowa typu „mediaPlayback" z sesją
 * multimediów (powiadomienie z przyciskami, ekran blokady, słuchawki, Bluetooth). Odtwarzacz to ExoPlayer —
 * Opus dekoduje systemowy MediaCodec (Android 5+), więc bez dodatkowych bibliotek natywnych.
 * Pliki są zawsze LOKALNE (pobrane wcześniej przez [CommunityAudioCache]) — odtwarzacz nie łączy się z siecią.
 */
class AudioPlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            // fokus audio: przy rozmowie/innej muzyce pauza; odłączenie słuchawek → pauza (nie gramy na głośnik)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            // plik lokalny: wystarczy blokada CPU, Wi-Fi nie jest potrzebne (ekran wyłączony = dalej gra)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        // C-22d: film/muzyka przy krótkiej utracie fokusu (np. nawigacja) się ścisza; mowa (nagranie) pauzuje
        player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                val type = if (tracks.containsType(C.TRACK_TYPE_VIDEO)) C.AUDIO_CONTENT_TYPE_MOVIE else C.AUDIO_CONTENT_TYPE_SPEECH
                if (player.audioAttributes.contentType != type) {
                    player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(type).build(), true)
                }
            }
        })
        session = MediaSession.Builder(this, player)
            .setCallback(TrustedControllersOnly(packageName))
            // dotknięcie powiadomienia odtwarzacza / ekranu blokady otwiera aplikację na zakładce Społeczność
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    SESSION_ACTIVITY_REQUEST,
                    Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra(MainActivity.EXTRA_OPEN_COMMUNITY, true)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    // zamknięcie aplikacji z listy ostatnich: gdy nic nie gra, kończymy usługę (gdy gra — gra dalej w tle)
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    /**
     * Sterować odtwarzaczem mogą tylko: ta aplikacja oraz zaufane elementy systemu (powiadomienie, ekran
     * blokady, sterowanie z zegarka/auta przez system). Obca aplikacja nie podejrzy tytułu ani nie przejmie
     * odtwarzania — usługa musi być eksportowana, ale sesja odrzuca nieznanych klientów.
     */
    private companion object {
        const val SESSION_ACTIVITY_REQUEST = 4712
    }

    private class TrustedControllersOnly(private val ownPackage: String) : MediaSession.Callback {
        @OptIn(UnstableApi::class)
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
            if (controller.packageName == ownPackage || controller.isTrusted) {
                MediaSession.ConnectionResult.AcceptedResultBuilder(session).build()
            } else {
                MediaSession.ConnectionResult.reject()
            }
    }
}
