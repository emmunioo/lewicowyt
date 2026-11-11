package pl.lewicowyt.notifier.notifications

import java.time.Instant
import java.time.ZoneId
import pl.lewicowyt.notifier.data.AppSettings

/**
 * godziny, w których apka pokazuje powiadomienia. poza oknem czekają (w skrzynce/
 * Historii jako nieoznaczone), a po otwarciu okna przychodzą JEDNYM
 * zbiorczym powiadomieniem (R-20, ActiveHoursReleaseWorker z forceSummary).
 *
 * czysta logika czasu, bez Androida – łatwa do testów.
 */
object ActiveHours {

    /** czy [nowMinutes] (minuty od północy, lokalnie) wpada w okno */
    fun isWithin(settings: AppSettings, nowMinutes: Int): Boolean {
        if (!settings.activeHoursEnabled) return true
        val start = settings.activeHoursStartMinutes
        val end = settings.activeHoursEndMinutes
        if (start == end) return true // pełna doba = brak ograniczenia
        return if (start < end) {
            nowMinutes >= start && nowMinutes < end
        } else {
            // okno przez północ (np. 22:00–06:00)
            nowMinutes >= start || nowMinutes < end
        }
    }

    /**
     * ile ms do najbliższego otwarcia okna. 0, gdy już jesteśmy w oknie albo
     * funkcja off – wtedy dostarczamy od razu.
     */
    fun millisUntilNextOpen(settings: AppSettings, nowMillis: Long): Long {
        if (!settings.activeHoursEnabled) return 0L
        val zone = ZoneId.systemDefault()
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val nowMinutes = now.hour * 60 + now.minute
        if (isWithin(settings, nowMinutes)) return 0L
        val startHour = settings.activeHoursStartMinutes / 60
        val startMinute = settings.activeHoursStartMinutes % 60
        var open = now.toLocalDate().atTime(startHour, startMinute).atZone(zone)
        if (!open.toInstant().isAfter(now.toInstant())) {
            open = open.plusDays(1)
        }
        return (open.toInstant().toEpochMilli() - nowMillis).coerceAtLeast(0L)
    }
}
