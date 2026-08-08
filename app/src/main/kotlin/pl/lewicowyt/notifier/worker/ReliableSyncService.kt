package pl.lewicowyt.notifier.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import pl.lewicowyt.notifier.AppGraph
import pl.lewicowyt.notifier.MainActivity
import pl.lewicowyt.notifier.R
import pl.lewicowyt.notifier.data.AppSettings
import pl.lewicowyt.notifier.data.hasEnabledContentForSelectedCreators
import pl.lewicowyt.notifier.diagnostics.DiagnosticCategory
import pl.lewicowyt.notifier.diagnostics.DiagnosticLevel
import pl.lewicowyt.notifier.diagnostics.DiagnosticReasonCode
import pl.lewicowyt.notifier.diagnostics.DiagnosticSyncRun
import pl.lewicowyt.notifier.diagnostics.DiagnosticSyncTrigger
import pl.lewicowyt.notifier.model.SyncOutcome

class ReliableSyncService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var syncJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        AppGraph.initialize(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val retryAttempt = intent?.getIntExtra(SyncScheduler.EXTRA_RETRY_ATTEMPT, 0)
            ?.coerceIn(0, SyncScheduler.MAX_RETRY_ATTEMPTS) ?: 0
        val trigger = intent?.getStringExtra(EXTRA_SYNC_TRIGGER)
            ?.let { runCatching { DiagnosticSyncTrigger.valueOf(it) }.getOrNull() }
            ?: DiagnosticSyncTrigger.REDELIVERED_FGS
        val wakeLockState = AtomicBoolean(false)
        val run = DiagnosticSyncRun.resume(
            syncId = intent?.getStringExtra(EXTRA_SYNC_ID).orEmpty(),
            trigger = trigger,
            retryOf = intent?.getStringExtra(EXTRA_RETRY_OF),
            stageSink = { stage ->
                AppGraph.scheduler.updateWatchdogRuntime(
                    syncId = intent?.getStringExtra(EXTRA_SYNC_ID).orEmpty(),
                    stage = stage,
                    serviceRunning = true,
                    wakeLockHeld = wakeLockState.get(),
                )
            },
        )
        if (trigger == DiagnosticSyncTrigger.REDELIVERED_FGS) run.start()
        try {
            ServiceCompat.startForeground(
                this,
                FOREGROUND_NOTIFICATION_ID,
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("lewicowYT")
                    .setContentText("Sprawdzanie nowych materiałów…")
                    .setContentIntent(
                        PendingIntent.getActivity(
                            this,
                            0,
                            Intent(this, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        ),
                    )
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOnlyAlertOnce(true)
                    .setOngoing(true)
                    .build(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else 0,
            )
            run.event("FGS_STARTED", category = DiagnosticCategory.SCHEDULER)
        } catch (error: Exception) {
            run.event(
                "FGS_START_FAILED",
                category = DiagnosticCategory.SCHEDULER,
                level = DiagnosticLevel.ERROR,
                reason = DiagnosticReasonCode.FGS_START_FAILED,
                fields = mapOf("type" to error.javaClass.simpleName),
            )
            AppGraph.scheduler.cancelWatchdog(run.syncId)
            serviceScope.launch {
                val scheduled = runCatching {
                    AppGraph.scheduler.scheduleRetry(
                        settings = AppGraph.preferences.current(),
                        retryAttempt = retryAttempt + 1,
                        retryOf = run.syncId,
                    )
                }.getOrDefault(false)
                run.event(
                    "FGS_START_RECOVERY",
                    category = DiagnosticCategory.SCHEDULER,
                    reason = if (scheduled) DiagnosticReasonCode.RETRY_SCHEDULED else null,
                    fields = mapOf(
                        "retryScheduled" to scheduled,
                        "watchdogCancelled" to true,
                    ),
                )
                run.finish(mapOf("result" to "FGS_START_FAILED"))
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }

        if (syncJob?.isActive == true) {
            run.event(
                "SYNC_SKIPPED",
                reason = DiagnosticReasonCode.SYNC_ALREADY_RUNNING,
            )
            run.finish(mapOf("result" to "SKIPPED"))
            return START_REDELIVER_INTENT
        }
        val deliverSystemNotifications = intent?.getBooleanExtra(
            EXTRA_DELIVER_SYSTEM_NOTIFICATIONS,
            true,
        ) ?: true
        val clearDeferredDndSyncOnSuccess = intent?.getBooleanExtra(
            EXTRA_CLEAR_DEFERRED_DND_SYNC,
            false,
        ) ?: false
        syncJob = serviceScope.launch {
            var settings: AppSettings? = null
            var retryNeeded = false
            var wakeLock: PowerManager.WakeLock? = null
            try {
                wakeLock = try {
                    getSystemService(PowerManager::class.java)
                        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:youtube-sync")
                        .apply {
                            setReferenceCounted(false)
                            acquire(WAKE_LOCK_TIMEOUT_MILLIS)
                        }
                } catch (error: Exception) {
                    run.event(
                        "WAKELOCK_ERROR",
                        category = DiagnosticCategory.SCHEDULER,
                        level = DiagnosticLevel.ERROR,
                        reason = DiagnosticReasonCode.WAKELOCK_FAILURE,
                        fields = mapOf("type" to error.javaClass.simpleName),
                    )
                    throw error
                }
                wakeLockState.set(wakeLock.isHeld)
                AppGraph.scheduler.updateWatchdogRuntime(
                    run.syncId,
                    run.lastStage(),
                    serviceRunning = true,
                    wakeLockHeld = wakeLock.isHeld,
                )
                run.event("WAKELOCK_ACQUIRED", category = DiagnosticCategory.SCHEDULER)

                val currentSettings = AppGraph.preferences.current()
                settings = currentSettings
                val network = currentSyncNetworkAccess(this@ReliableSyncService)
                if (
                    currentSettings.hasEnabledContentForSelectedCreators() &&
                    AppGraph.scheduler.hasExactAlarmAccess() &&
                    network.allowsSync(currentSettings.allowMobileData)
                ) {
                    val outcome = withTimeoutOrNull(SYNC_TIMEOUT_MILLIS) {
                        AppGraph.syncEngine.sync(deliverSystemNotifications, run)
                    }
                    if (outcome == null) {
                        run.event(
                            "SYNC_TIMEOUT",
                            level = DiagnosticLevel.ERROR,
                            reason = DiagnosticReasonCode.NETWORK_TIMEOUT,
                            fields = mapOf("timeoutMs" to SYNC_TIMEOUT_MILLIS),
                        )
                        recordFailure("Automatyczna synchronizacja przekroczyła limit czasu")
                        retryNeeded = true
                    } else {
                        retryNeeded = shouldRetryAlarmSync(outcome, retryAttempt)
                        if (clearDeferredDndSyncOnSuccess) {
                            AppGraph.preferences.clearDeferredDndSync()
                        }
                    }
                    AppGraph.backgroundUpdateCoordinator.checkAfterYouTubeSync(currentSettings)
                } else {
                    val reason = when {
                        !currentSettings.hasEnabledContentForSelectedCreators() ->
                            DiagnosticReasonCode.NO_ENABLED_CONTENT_TYPES
                        !AppGraph.scheduler.hasExactAlarmAccess() ->
                            DiagnosticReasonCode.EXACT_ALARM_PERMISSION_MISSING
                        network == SyncNetworkAccess.UNAVAILABLE -> DiagnosticReasonCode.NO_NETWORK
                        else -> DiagnosticReasonCode.CELLULAR_DISABLED
                    }
                    run.event("SYNC_SKIPPED", level = DiagnosticLevel.WARNING, reason = reason)
                    retryNeeded = reason == DiagnosticReasonCode.NO_NETWORK ||
                        reason == DiagnosticReasonCode.CELLULAR_DISABLED
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                retryNeeded = true
                run.event(
                    "SYNC_ERROR",
                    level = DiagnosticLevel.ERROR,
                    reason = DiagnosticReasonCode.DELIVERY_FAILED,
                    fields = mapOf("type" to error.javaClass.simpleName),
                )
                recordFailure(
                    "Błąd automatycznej synchronizacji: " +
                        (error.message ?: error.javaClass.simpleName),
                )
                AppGraph.diagnostics.writeSnapshot("SIGNIFICANT_ERROR", run)
            } finally {
                AppGraph.scheduler.cancelWatchdog(run.syncId)
                if (retryNeeded) {
                    val scheduled = runCatching {
                        settings?.let {
                            AppGraph.scheduler.scheduleRetry(
                                settings = it,
                                retryAttempt = retryAttempt + 1,
                                retryOf = run.syncId,
                            )
                        } ?: false
                    }.getOrDefault(false)
                    run.event(
                        "RETRY_DECISION",
                        category = DiagnosticCategory.SCHEDULER,
                        reason = if (scheduled) DiagnosticReasonCode.RETRY_SCHEDULED else null,
                        fields = mapOf("scheduled" to scheduled, "attempt" to retryAttempt + 1),
                    )
                }
                if (wakeLock?.isHeld == true) {
                    wakeLock.release()
                    wakeLockState.set(false)
                    run.event("WAKELOCK_RELEASED", category = DiagnosticCategory.SCHEDULER)
                } else if (wakeLock != null) {
                    run.event(
                        "WAKELOCK_TIMEOUT",
                        category = DiagnosticCategory.SCHEDULER,
                        level = DiagnosticLevel.WARNING,
                        reason = DiagnosticReasonCode.WAKELOCK_FAILURE,
                    )
                }
                run.event("FGS_STOP", category = DiagnosticCategory.SCHEDULER)
                run.finish(mapOf("retryScheduled" to retryNeeded))
                ServiceCompat.stopForeground(
                    this@ReliableSyncService,
                    ServiceCompat.STOP_FOREGROUND_REMOVE,
                )
                stopSelf()
            }
        }
        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun recordFailure(message: String) {
        try {
            AppGraph.preferences.updateLastSync(
                timestamp = System.currentTimeMillis(),
                summary = message,
                completed = false,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Sprzątanie usługi ma pierwszeństwo przed wpisem DataStore.
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Automatyczne sprawdzanie w tle",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Widoczne podczas pobierania nowych materiałów po alarmie"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "reliable_background_sync"
        const val FOREGROUND_NOTIFICATION_ID = 0x4C5954
        val SYNC_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(8)
        val WAKE_LOCK_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(9)
        const val EXTRA_DELIVER_SYSTEM_NOTIFICATIONS = "deliver_system_notifications"
        const val EXTRA_CLEAR_DEFERRED_DND_SYNC = "clear_deferred_dnd_sync"
        const val EXTRA_SYNC_ID = "diagnostic_sync_id"
        const val EXTRA_SYNC_TRIGGER = "diagnostic_sync_trigger"
        const val EXTRA_RETRY_OF = "diagnostic_retry_of"

        internal fun start(
            context: android.content.Context,
            retryAttempt: Int,
            deliverSystemNotifications: Boolean,
            clearDeferredDndSyncOnSuccess: Boolean,
            syncId: String,
            trigger: DiagnosticSyncTrigger,
            retryOf: String?,
        ) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ReliableSyncService::class.java)
                    .putExtra(SyncScheduler.EXTRA_RETRY_ATTEMPT, retryAttempt)
                    .putExtra(EXTRA_DELIVER_SYSTEM_NOTIFICATIONS, deliverSystemNotifications)
                    .putExtra(EXTRA_CLEAR_DEFERRED_DND_SYNC, clearDeferredDndSyncOnSuccess)
                    .putExtra(EXTRA_SYNC_ID, syncId)
                    .putExtra(EXTRA_SYNC_TRIGGER, trigger.name)
                    .putExtra(EXTRA_RETRY_OF, retryOf),
            )
        }

        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, ReliableSyncService::class.java))
        }
    }
}

internal fun shouldRetryAlarmSync(
    outcome: SyncOutcome,
    retryAttempt: Int,
    maxRetries: Int = SyncScheduler.MAX_RETRY_ATTEMPTS,
): Boolean {
    if (outcome.errors.isEmpty() || retryAttempt >= maxRetries) return false
    val failedSources = outcome.errors.size
    val attemptedSources = outcome.checkedSources + failedSources
    return outcome.checkedSources == 0 ||
        failedSources.toLong() * 2L >= attemptedSources.toLong()
}
