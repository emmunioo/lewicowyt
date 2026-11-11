package pl.lewicowyt.notifier.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.lewicowyt.notifier.network.YouTubePageClassifier

/**
 * Wykrywa streamy z historii, które stały się PRYWATNE (wcześniej publiczne). Sprawdza dopiero
 * **dobę po REALNYM starcie** (`liveBroadcastDetails.startTimestamp`), więc najpierw zapisuje ten
 * start. Jedno zapytanie sieciowe [YouTubePageClassifier.streamStatus] daje naraz start i status
 * prywatności. Ograniczone do kilku pobrań na przebieg; wynik trzyma tabela `stream_privacy`.
 */
class StreamPrivacyChecker(
    private val database: LocalDatabase,
    private val classifier: YouTubePageClassifier,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    /** Zwraca true, gdy pojawił się nowy prywatny (UI powinno odświeżyć badge/filtr). */
    suspend fun run(): Boolean = withContext(Dispatchers.IO) {
        val now = nowMillis()
        var fetches = 0
        var attempts = 0
        var changed = false
        // A-36: kandydaci w LOSOWEJ kolejności, a limit liczy tylko UDANE sprawdzenia (+ twardy limit prób) —
        // dawniej te same pierwsze wiersze (ORDER BY published DESC), które nie dawały statusu, zjadały limit
        // przy każdym przebiegu i reszta nigdy nie była sprawdzana.
        for (s in database.streamsForPrivacyCheck().shuffled()) {
            if (fetches >= MAX_FETCHES_PER_RUN || attempts >= MAX_ATTEMPTS_PER_RUN) break
            if (s.isPrivate || s.checkedMs != 0L) continue // już rozstrzygnięte

            val dueByStart = s.streamStartMs != null && now >= s.streamStartMs + CHECK_DELAY_MS
            val needStart = s.streamStartMs == null
            if (!dueByStart && !needStart) continue // start znany, ale doba jeszcze nie minęła

            attempts++
            val status = classifier.streamStatus(s.videoId) ?: continue
            fetches++

            if (s.streamStartMs == null && status.actualStartMs != null) {
                database.setStreamStart(s.videoId, status.actualStartMs)
            }
            // odniesienie do „doby": realny start (świeży lub zapisany), a w ostateczności publikacja
            val startRef = status.actualStartMs ?: s.streamStartMs ?: s.publishedMs
            if (now >= startRef + CHECK_DELAY_MS) {
                database.markStreamPrivacy(s.videoId, status.isPrivate, now)
                if (status.isPrivate) changed = true
            }
        }
        changed
    }

    private companion object {
        const val CHECK_DELAY_MS = 24L * 60L * 60L * 1000L // doba po starcie
        const val MAX_FETCHES_PER_RUN = 8
        const val MAX_ATTEMPTS_PER_RUN = 24 // A-36: twardy limit prób (także nieudanych) na przebieg
    }
}
