package pl.lewicowyt.notifier.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import pl.lewicowyt.notifier.AppGraph
import pl.lewicowyt.notifier.data.AppSettings
import pl.lewicowyt.notifier.data.hasEnabledContentForSelectedCreators
import pl.lewicowyt.notifier.diagnostics.DiagnosticCategory
import pl.lewicowyt.notifier.diagnostics.DiagnosticLevel
import pl.lewicowyt.notifier.diagnostics.DiagnosticLogStore
import pl.lewicowyt.notifier.diagnostics.DiagnosticReasonCode
import pl.lewicowyt.notifier.diagnostics.DiagnosticSyncRun
import pl.lewicowyt.notifier.diagnostics.DiagnosticSyncTrigger

class ReliableSyncAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action !in SUPPORTED_ACTIONS) return
        val retryAttempt = intent.getIntExtra(SyncScheduler.EXTRA_RETRY_ATTEMPT, 0)
            .coerceIn(0, SyncScheduler.MAX_RETRY_ATTEMPTS)
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            var settings: AppSettings? = null
            var run: DiagnosticSyncRun? = null
            try {
                val appContext = context.applicationContext
                AppGraph.initialize(appContext)
                val retryOf = intent.getStringExtra(SyncScheduler.EXTRA_RETRY_OF_SYNC_ID)
                if (action == SyncScheduler.ACTION_SYNC_WATCHDOG) {
                    val watchdog = AppGraph.scheduler.watchdogDiagnosticState()
                    DiagnosticLogStore.event(
                        DiagnosticCategory.SCHEDULER,
                        DiagnosticLevel.ERROR,
                        "WATCHDOG_FIRED",
                        syncId = watchdog.syncId,
                        reason = DiagnosticReasonCode.WATCHDOG_TIMEOUT,
                        fields = mapOf(
                            "elapsedMs" to (System.currentTimeMillis() - watchdog.startedAtMillis)
                                .coerceAtLeast(0L),
                            "lastStage" to watchdog.lastStage,
                            "serviceRunning" to watchdog.serviceRunning,
                            "wakeLockHeld" to watchdog.wakeLockHeld,
                        ),
                    )
                    run = DiagnosticSyncRun.create(
                        DiagnosticSyncTrigger.WATCHDOG,
                        retryOf = watchdog.syncId,
                    )
                } else {
                    run = DiagnosticSyncRun.create(
                        trigger = when (action) {
                            SyncScheduler.ACTION_SYNC_RETRY -> DiagnosticSyncTrigger.RETRY
                            SyncScheduler.ACTION_DND_PROBE -> DiagnosticSyncTrigger.DND_CATCHUP
                            else -> DiagnosticSyncTrigger.EXACT_ALARM
                        },
                        retryOf = retryOf,
                    )
                }
                run.start()
                settings = AppGraph.preferences.current()
                AppGraph.diagnostics.writeSnapshot("AUTO_SYNC_START", run)

                if (!settings.hasEnabledContentForSelectedCreators()) {
                    run.event(
                        "SYNC_SKIPPED",
                        level = DiagnosticLevel.WARNING,
                        reason = if (settings.selectedCreatorIds.isEmpty()) {
                            DiagnosticReasonCode.NO_SELECTED_CREATORS
                        } else {
                            DiagnosticReasonCode.NO_ENABLED_CONTENT_TYPES
                        },
                        text = "Brak aktywnych źródeł do sprawdzenia",
                    )
                    run.finish(mapOf("result" to "SKIPPED"))
                    return@launch
                }
                if (!AppGraph.scheduler.hasExactAlarmAccess()) {
                    run.event(
                        "SYNC_SKIPPED",
                        level = DiagnosticLevel.WARNING,
                        reason = DiagnosticReasonCode.EXACT_ALARM_PERMISSION_MISSING,
                        text = "Android nie zezwala na dokładne alarmy",
                    )
                    run.finish(mapOf("result" to "SKIPPED"))
                    return@launch
                }

                if (action == SyncScheduler.ACTION_SYNC_ALARM) {
                    AppGraph.scheduler.scheduleNext(settings, run.syncId)
                }

                if (isNotificationInterruptionSuppressed(appContext)) {
                    if (action != SyncScheduler.ACTION_DND_PROBE) {
                        AppGraph.preferences.recordDeferredDndSync()
                    }
                    run.event(
                        "SYNC_DEFERRED",
                        category = DiagnosticCategory.SCHEDULER,
                        reason = DiagnosticReasonCode.DND_ACTIVE,
                        text = "Tryb Nie przeszkadzać jest aktywny",
                    )
                    AppGraph.scheduler.cancelWatchdog(run.syncId)
                    AppGraph.scheduler.scheduleDndProbe()
                    run.finish(mapOf("result" to "DEFERRED"))
                    return@launch
                }
                AppGraph.scheduler.cancelDndProbe()
                if (AppGraph.syncEngine.isSyncInProgress()) {
                    run.event(
                        "SYNC_SKIPPED",
                        level = DiagnosticLevel.WARNING,
                        reason = DiagnosticReasonCode.SYNC_ALREADY_RUNNING,
                        text = "Inna synchronizacja nadal trwa",
                    )
                    run.finish(mapOf("result" to "SKIPPED"))
                    return@launch
                }

                val network = currentSyncNetworkAccess(appContext)
                if (!network.allowsSync(settings.allowMobileData)) {
                    val reason = if (network == SyncNetworkAccess.UNAVAILABLE) {
                        DiagnosticReasonCode.NO_NETWORK
                    } else {
                        DiagnosticReasonCode.CELLULAR_DISABLED
                    }
                    val scheduled = AppGraph.scheduler.scheduleRetry(
                        settings = settings,
                        retryAttempt = retryAttempt + 1,
                        retryOf = run.syncId,
                    )
                    run.event(
                        "SYNC_DEFERRED",
                        category = DiagnosticCategory.NETWORK,
                        level = DiagnosticLevel.WARNING,
                        reason = reason,
                        fields = mapOf("retryScheduled" to scheduled),
                        text = "Brak dozwolonej sieci do synchronizacji",
                    )
                    run.finish(mapOf("result" to "DEFERRED"))
                    return@launch
                }

                val deferredDue = settings.deferredDndSyncAtMillis > 0L
                AppGraph.scheduler.scheduleWatchdog(run)
                run.event("FGS_START_REQUEST", category = DiagnosticCategory.SCHEDULER)
                try {
                    ReliableSyncService.start(
                        context = appContext,
                        retryAttempt = retryAttempt,
                        deliverSystemNotifications = deferredDue ||
                            action != SyncScheduler.ACTION_DND_PROBE,
                        clearDeferredDndSyncOnSuccess = deferredDue,
                        syncId = run.syncId,
                        trigger = run.trigger,
                        retryOf = run.retryOf,
                    )
                } catch (error: Exception) {
                    AppGraph.scheduler.cancelWatchdog(run.syncId)
                    val scheduled = AppGraph.scheduler.scheduleRetry(
                        settings,
                        retryAttempt + 1,
                        retryOf = run.syncId,
                    )
                    run.event(
                        "FGS_START_FAILED",
                        category = DiagnosticCategory.SCHEDULER,
                        level = DiagnosticLevel.ERROR,
                        reason = DiagnosticReasonCode.FGS_START_FAILED,
                        fields = mapOf(
                            "type" to error.javaClass.simpleName,
                            "retryScheduled" to scheduled,
                            "watchdogCancelled" to true,
                        ),
                    )
                    run.finish(mapOf("result" to "ERROR"))
                }
            } catch (error: Exception) {
                run?.event(
                    "RECEIVER_ERROR",
                    category = DiagnosticCategory.SCHEDULER,
                    level = DiagnosticLevel.ERROR,
                    reason = DiagnosticReasonCode.SCHEDULE_ERROR,
                    fields = mapOf("type" to error.javaClass.simpleName),
                )
                try {
                    AppGraph.scheduler.cancelWatchdog(run?.syncId)
                    settings?.let {
                        AppGraph.scheduler.scheduleRetry(
                            settings = it,
                            retryAttempt = retryAttempt + 1,
                            retryOf = run?.syncId,
                        )
                    }
                } catch (_: Exception) {
                    // Następny zwykły termin został zapisany przed próbą startu.
                }
                run?.finish(mapOf("result" to "ERROR"))
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        val SUPPORTED_ACTIONS = setOf(
            SyncScheduler.ACTION_SYNC_ALARM,
            SyncScheduler.ACTION_SYNC_RETRY,
            SyncScheduler.ACTION_SYNC_WATCHDOG,
            SyncScheduler.ACTION_DND_PROBE,
        )
    }
}
