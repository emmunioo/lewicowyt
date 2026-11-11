package pl.lewicowyt.notifier.notifications

import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import pl.lewicowyt.notifier.R

/**
 * Powiadomienia na smartwatchu (Wear OS, Galaxy Watch i opaski, które przejmują powiadomienia telefonu).
 * Android sam przekazuje powiadomienia na sparowany zegarek — tu decydujemy, KTÓRE i w jakiej formie:
 *  - nowe materiały (filmy, społeczność): na zegarek, z przyciskiem „Otwórz na telefonie" (film i tak gra
 *    na telefonie, zegarek tylko informuje) i miniaturą jako tłem, jeśli jest;
 *  - powiadomienia techniczne (usługa w tle, aktualizacja, stan serwera): TYLKO na telefonie — na
 *    nadgarstku nic z nimi nie zrobisz, a byłyby tylko szumem;
 *  - nie ma przełącznika w aplikacji: zegarek włącza/wyłącza się w aplikacji producenta zegarka (Mi Fitness, Zepp…).
 * Nic nie wysyłamy sami do zegarka i nie dodajemy żadnej biblioteki Google — to zwykły mostek systemowy.
 */
object WearNotifications {
    /** nowy materiał: na zegarek (gdy włączone) z akcją otwarcia na telefonie; inaczej tylko lokalnie. */
    fun content(
        builder: NotificationCompat.Builder,
        onWatch: Boolean,
        openOnPhone: PendingIntent?,
    ): NotificationCompat.Builder {
        if (!onWatch) return builder.setLocalOnly(true)
        val extender = NotificationCompat.WearableExtender()
            // dotknięcie na zegarku otwiera aplikację/YouTube na TELEFONIE (zegarek nie ma czym tego odtworzyć)
            .setContentIntentAvailableOffline(false)
        if (openOnPhone != null) {
            extender.addAction(
                NotificationCompat.Action.Builder(R.drawable.ic_notification, "Otwórz na telefonie", openOnPhone).build(),
            )
        }
        return builder.extend(extender)
    }

    /** powiadomienie techniczne — nigdy na zegarek. */
    fun phoneOnly(builder: NotificationCompat.Builder): NotificationCompat.Builder = builder.setLocalOnly(true)
}
