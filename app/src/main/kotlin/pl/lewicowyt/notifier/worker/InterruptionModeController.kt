package pl.lewicowyt.notifier.worker

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import pl.lewicowyt.notifier.AppGraph
import pl.lewicowyt.notifier.data.hasEnabledContentForSelectedCreators
import pl.lewicowyt.notifier.diagnostics.DiagnosticCategory
import pl.lewicowyt.notifier.diagnostics.DiagnosticLogStore
import pl.lewicowyt.notifier.diagnostics.DiagnosticLevel
import pl.lewicowyt.notifier.diagnostics.DiagnosticReasonCode

/**
 * Obserwuje systemowy filtr „Nie przeszkadzać”. Producenckie tryby snu nie
 * mają wspólnego publicznego API, ale standardowy Tryb snu zwykle steruje tym
 * właśnie filtrem.
 */
class InterruptionModeController(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastSuppressed: Boolean? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action == NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED) {
                handleFilterChange()
            }
        }
    }

    fun start() {
        lastSuppressed = isNotificationInterruptionSuppressed(context)
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun handleFilterChange() {
        val suppressed = isNotificationInterruptionSuppressed(context)
        val wasSuppressed = lastSuppressed
        lastSuppressed = suppressed

        scope.launch {
            AppGraph.initialize(context)
            if (wasSuppressed == false && suppressed) {
                if (AppGraph.syncEngine.isSyncInProgress()) {
                    AppGraph.preferences.recordDeferredDndSync()
                }
                AppGraph.scheduler.cancelWatchdog()
                AppGraph.scheduler.scheduleDndProbe()
                ReliableSyncService.stop(context)
                DiagnosticLogStore.event(
                    DiagnosticCategory.SCHEDULER,
                    DiagnosticLevel.INFO,
                    "DND_ACTIVE",
                    reason = DiagnosticReasonCode.DND_ACTIVE,
                    text = "Włączono Nie przeszkadzać; przerwano pracę sieciową",
                )
                return@launch
            }
            if (wasSuppressed != true || suppressed) return@launch
            val settings = AppGraph.preferences.current()
            if (!settings.hasEnabledContentForSelectedCreators()) return@launch

            val deliveryDue = settings.deferredDndSyncAtMillis > 0L
            DiagnosticLogStore.event(
                DiagnosticCategory.SCHEDULER,
                DiagnosticLevel.INFO,
                "DND_CATCHUP_SCHEDULED",
                fields = mapOf("notificationDue" to deliveryDue),
                text = "Zakończono Nie przeszkadzać; zaplanowano natychmiastowe sprawdzenie",
            )
            // Dokładny alarm otrzymuje od Androida prawo do uruchomienia FGS
            // z tła; bezpośredni start usługi z broadcastu DND nie zawsze je ma.
            AppGraph.scheduler.scheduleDndCatchup()
        }
    }
}

fun hasNotificationPolicyAccess(context: Context): Boolean =
    context.getSystemService(NotificationManager::class.java)
        .isNotificationPolicyAccessGranted

fun isNotificationInterruptionSuppressed(context: Context): Boolean =
    when (
        context.getSystemService(NotificationManager::class.java)
            .currentInterruptionFilter
    ) {
        NotificationManager.INTERRUPTION_FILTER_PRIORITY,
        NotificationManager.INTERRUPTION_FILTER_ALARMS,
        NotificationManager.INTERRUPTION_FILTER_NONE -> true
        else -> false
    }
