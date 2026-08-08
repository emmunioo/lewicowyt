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
 * Receiver wykonuje wyłącznie krótki zapis kolejki alarmów systemowych.
 */
class ScheduleChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in SUPPORTED_ACTIONS) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                AppGraph.initialize(context.applicationContext)
                val cause = when (intent.action) {
                    Intent.ACTION_BOOT_COMPLETED -> AlarmScheduleCause.SYSTEM_BOOT
                    Intent.ACTION_TIME_CHANGED,
                    Intent.ACTION_TIMEZONE_CHANGED,
                    -> AlarmScheduleCause.CLOCK_OR_TIMEZONE_CHANGED
                    else -> AlarmScheduleCause.EXACT_ALARM_ACCESS_CHANGED
                }
                AppGraph.scheduler.rescheduleAfterSystemClockChange(cause)
            } catch (error: Exception) {
                pl.lewicowyt.notifier.diagnostics.DiagnosticLogStore.event(
                    pl.lewicowyt.notifier.diagnostics.DiagnosticCategory.SCHEDULER,
                    pl.lewicowyt.notifier.diagnostics.DiagnosticLevel.ERROR,
                    "ALARM_QUEUE_ERROR",
                    reason = pl.lewicowyt.notifier.diagnostics.DiagnosticReasonCode.SCHEDULE_ERROR,
                    fields = mapOf("type" to error.javaClass.simpleName),
                    text = "Nie udało się odtworzyć harmonogramu po zdarzeniu systemowym",
                )
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
