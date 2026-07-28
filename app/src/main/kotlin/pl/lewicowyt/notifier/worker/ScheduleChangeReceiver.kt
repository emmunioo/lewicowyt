package pl.lewicowyt.notifier.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import pl.lewicowyt.notifier.AppGraph

/**
 * Przelicza trwały harmonogram po restarcie oraz po zmianie zegara lub strefy.
 * Receiver wykonuje wyłącznie krótki zapis następnego alarmu systemowego.
 */
class ScheduleChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in SUPPORTED_ACTIONS) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                AppGraph.initialize(context.applicationContext)
                AppGraph.scheduler.rescheduleAfterSystemClockChange()
            } catch (_: Exception) {
                // Kolejne uruchomienie aplikacji ponownie odtworzy harmonogram.
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            EXACT_ALARM_PERMISSION_ACTION,
        )
        const val EXACT_ALARM_PERMISSION_ACTION =
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
    }
}
