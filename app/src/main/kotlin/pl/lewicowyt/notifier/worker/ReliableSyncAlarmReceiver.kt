package pl.lewicowyt.notifier.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import pl.lewicowyt.notifier.AppGraph
import pl.lewicowyt.notifier.data.BackgroundMode

class ReliableSyncAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SyncScheduler.ACTION_RELIABLE_SYNC_ALARM) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val appContext = context.applicationContext
                AppGraph.initialize(appContext)
                val settings = AppGraph.preferences.current()

                // Alarm jest jednorazowy. Następny termin zapisujemy przed
                // uruchomieniem sieci, aby awaria procesu nie przerwała nadzoru.
                AppGraph.scheduler.scheduleReliableWatchdog(settings)

                if (
                    settings.backgroundMode != BackgroundMode.RELIABLE ||
                    settings.selectedCreatorIds.isEmpty() ||
                    !AppGraph.scheduler.hasExactAlarmAccess() ||
                    AppGraph.syncEngine.isSyncInProgress() ||
                    !currentSyncNetworkAccess(appContext)
                        .allowsSync(settings.allowMobileData) ||
                    !shouldRunReliableAlarm(
                        nowMillis = System.currentTimeMillis(),
                        lastSuccessfulSyncMillis = settings.lastCompletedSyncAtMillis,
                        intervalMinutes = settings.intervalMinutes,
                    )
                ) {
                    return@launch
                }

                ContextCompat.startForegroundService(
                    appContext,
                    Intent(appContext, ReliableSyncService::class.java),
                )
            } catch (_: Exception) {
                // Następny alarm został już zaplanowany. Otwarcie aplikacji
                // również odtworzy harmonogram, jeśli system go usunie.
            } finally {
                pendingResult.finish()
            }
        }
    }
}
