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

class ReliableSyncAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SyncScheduler.ACTION_SYNC_ALARM) return
        val retryAttempt = intent.getIntExtra(SyncScheduler.EXTRA_RETRY_ATTEMPT, 0)
            .coerceIn(0, SyncScheduler.MAX_RETRY_ATTEMPTS)
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            var retrySettings: pl.lewicowyt.notifier.data.AppSettings? = null
            try {
                val appContext = context.applicationContext
                AppGraph.initialize(appContext)
                val settings = AppGraph.preferences.current()
                retrySettings = settings

                if (
                    settings.selectedCreatorIds.isEmpty() ||
                    !AppGraph.scheduler.hasExactAlarmAccess()
                ) {
                    return@launch
                }

                // Alarm jest jednorazowy. Następny zwykły termin zapisujemy
                // przed uruchomieniem sieci, aby awaria procesu nie przerwała
                // całego harmonogramu.
                AppGraph.scheduler.scheduleNext(settings)
                if (AppGraph.syncEngine.isSyncInProgress()) return@launch

                if (
                    !currentSyncNetworkAccess(appContext)
                        .allowsSync(settings.allowMobileData)
                ) {
                    AppGraph.scheduler.scheduleRetry(
                        settings = settings,
                        retryAttempt = retryAttempt + 1,
                    )
                    return@launch
                }

                ContextCompat.startForegroundService(
                    appContext,
                    Intent(appContext, ReliableSyncService::class.java)
                        .putExtra(SyncScheduler.EXTRA_RETRY_ATTEMPT, retryAttempt),
                )
            } catch (_: Exception) {
                try {
                    retrySettings?.let {
                        AppGraph.scheduler.scheduleRetry(
                            settings = it,
                            retryAttempt = retryAttempt + 1,
                        )
                    }
                } catch (_: Exception) {
                    // Następny zwykły termin został zapisany przed próbą startu.
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
