package pl.lewicowyt.notifier.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import pl.lewicowyt.notifier.AppGraph
import pl.lewicowyt.notifier.data.BackgroundMode

/**
 * Energooszczędne zabezpieczenie zwykłego WorkManagera.
 *
 * Alarm jest celowo niedokładny i uruchamia się rzadziej od podstawowego
 * harmonogramu. Nie pobiera danych samodzielnie: zapisuje tylko jedno unikalne
 * zadanie WorkManagera, które nadal czeka na dozwoloną sieć i stan baterii.
 */
class BalancedSyncWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SyncScheduler.ACTION_BALANCED_SYNC_WATCHDOG) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                AppGraph.initialize(context.applicationContext)
                val settings = AppGraph.preferences.current()

                // Alarm jest jednorazowy, więc odnawiamy go przed dalszą pracą.
                AppGraph.scheduler.scheduleBalancedWatchdog(settings)

                if (
                    settings.backgroundMode == BackgroundMode.BALANCED &&
                    settings.selectedCreatorIds.isNotEmpty() &&
                    !AppGraph.syncEngine.isSyncInProgress()
                ) {
                    AppGraph.scheduler.enqueueBalancedCatchUpIfOverdue(settings)
                }
            } catch (_: Exception) {
                // Otwarcie aplikacji lub odbiornik BOOT_COMPLETED odtworzy alarm.
            } finally {
                pendingResult.finish()
            }
        }
    }
}
