package pl.lewicowyt.notifier.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import pl.lewicowyt.notifier.audio.CommunityAudioPlayer
import pl.lewicowyt.notifier.community.CommunityAudio
import pl.lewicowyt.notifier.i18n.tr

/**
 * Odtwarzacz dźwięku (Opus) na karcie pozycji społeczności. Gra natywnie w aplikacji — także w tle,
 * przy wygaszonym ekranie (powiadomienie z przyciskami) — z pętlą i prędkością do 3×.
 * Plik pobieramy dopiero po naciśnięciu ▶ (prywatna ścieżka, Tor dla .onion).
 */
@Composable
internal fun CommunityAudioRow(audio: CommunityAudio, title: String, artist: String?) {
    val context = LocalContext.current
    val state by CommunityAudioPlayer.state.collectAsState()
    val current = state.url == audio.url
    val label = audio.title ?: title
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val playing = current && state.playing
            FilledTonalButton(
                onClick = { CommunityAudioPlayer.toggle(context, audio, label, artist) },
                modifier = Modifier.semantics {
                    contentDescription = if (playing) "Pauza: $label" else "Odtwórz dźwięk: $label"
                },
            ) {
                if (current && state.loading) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(if (playing) "⏸" else "▶")
                }
            }
            Column(Modifier.weight(1f)) {
                Text("🎧 " + label, style = MaterialTheme.typography.labelLarge, maxLines = 2)
                val total = if (current && state.durationMs > 0) state.durationMs else audio.durationMs ?: 0L
                val time = if (current) "${formatClock(state.positionMs)} / ${formatClock(total)}" else formatClock(total)
                if (total > 0 || current) {
                    Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (current) {
                TextButton(onClick = { CommunityAudioPlayer.stop() }) { Text(tr("Zatrzymaj")) }
            }
        }
        if (current) {
            state.error?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            val duration = state.durationMs.coerceAtLeast(1L).toFloat()
            // przeciąganie suwaka nie skacze przy każdym ruchu — przewijamy dopiero po puszczeniu
            var dragging by remember { mutableStateOf(false) }
            var dragValue by remember { mutableFloatStateOf(0f) }
            Slider(
                value = if (dragging) dragValue else (state.positionMs / duration).coerceIn(0f, 1f),
                onValueChange = { dragging = true; dragValue = it },
                onValueChangeFinished = {
                    CommunityAudioPlayer.seekTo((dragValue * duration).toLong())
                    dragging = false
                },
                modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = "Pozycja odtwarzania"
                    stateDescription = "${formatClock(state.positionMs)} z ${formatClock(state.durationMs)}"
                },
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = state.loop,
                    onClick = { CommunityAudioPlayer.setLoop(!state.loop) },
                    label = { Text("🔁 " + tr("Pętla")) },
                    modifier = Modifier.semantics { stateDescription = if (state.loop) "włączona" else "wyłączona" },
                )
                CommunityAudioPlayer.SPEEDS.forEach { speed ->
                    FilterChip(
                        selected = kotlin.math.abs(state.speed - speed) < 0.01f,
                        onClick = { CommunityAudioPlayer.setSpeed(speed) },
                        label = { Text(formatSpeed(speed)) },
                        modifier = Modifier.semantics { contentDescription = "Prędkość ${formatSpeed(speed)}" },
                    )
                }
            }
        }
    }
}

private fun formatSpeed(speed: Float): String =
    (if (speed % 1f == 0f) speed.toInt().toString() else speed.toString().replace('.', ',')) + "×"

internal fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
