package pl.lewicowyt.notifier.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.util.concurrent.TimeUnit
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
        val retryAttempt = intent
            ?.getIntExtra(SyncScheduler.EXTRA_RETRY_ATTEMPT, 0)
            ?.coerceIn(0, SyncScheduler.MAX_RETRY_ATTEMPTS)
            ?: 0
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
            } else {
                0
            },
        )

        if (syncJob?.isActive == true) return START_NOT_STICKY
        syncJob = serviceScope.launch {
            var settings: AppSettings? = null
            var retryNeeded = false
            try {
                val currentSettings = AppGraph.preferences.current()
                settings = currentSettings
                if (
                    currentSettings.selectedCreatorIds.isNotEmpty() &&
                    AppGraph.scheduler.hasExactAlarmAccess() &&
                    currentSyncNetworkAccess(this@ReliableSyncService)
                        .allowsSync(currentSettings.allowMobileData)
                ) {
                    val outcome = withTimeoutOrNull(SYNC_TIMEOUT_MILLIS) {
                        AppGraph.syncEngine.sync()
                    }
                    if (outcome == null) {
                        recordFailure("Automatyczna synchronizacja przekroczyła limit czasu")
                        retryNeeded = true
                    } else {
                        retryNeeded = shouldRetryAlarmSync(outcome, retryAttempt)
                    }
                    // Kontrola wydania współdzieli wybudzenie i dostęp do sieci
                    // ze sprawdzaniem YouTube. Wewnętrzny znacznik czasu blokuje
                    // kolejne zapytanie przez co najmniej dwie godziny.
                    AppGraph.backgroundUpdateCoordinator
                        .checkAfterYouTubeSync(currentSettings)
                } else if (
                    currentSettings.selectedCreatorIds.isNotEmpty() &&
                    AppGraph.scheduler.hasExactAlarmAccess()
                ) {
                    retryNeeded = true
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                retryNeeded = true
                recordFailure(
                    "Błąd automatycznej synchronizacji: " +
                        (error.message ?: error.javaClass.simpleName),
                )
            } finally {
                if (retryNeeded) {
                    try {
                        settings?.let {
                            AppGraph.scheduler.scheduleRetry(
                                settings = it,
                                retryAttempt = retryAttempt + 1,
                            )
                        }
                    } catch (_: Exception) {
                        // Receiver zapisał już kolejny zwykły termin.
                    }
                }
                ServiceCompat.stopForeground(
                    this@ReliableSyncService,
                    ServiceCompat.STOP_FOREGROUND_REMOVE,
                )
                stopSelf()
            }
        }
        return START_NOT_STICKY
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
            // Brak zapisu diagnostycznego nie może zatrzymać sprzątania usługi.
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

    private companion object {
        const val CHANNEL_ID = "reliable_background_sync"
        const val FOREGROUND_NOTIFICATION_ID = 0x4C5954
        val SYNC_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(8)
    }
}

internal fun shouldRetryAlarmSync(
    outcome: SyncOutcome,
    retryAttempt: Int,
    maxRetries: Int = SyncScheduler.MAX_RETRY_ATTEMPTS,
): Boolean {
    if (outcome.errors.isEmpty() || retryAttempt >= maxRetries) return false

    // Pojedyncza niedostępna strona nie może ponownie uruchamiać całej,
    // kosztownej synchronizacji kilkudziesięciu poprawnych źródeł.
    val failedSources = outcome.errors.size
    val attemptedSources = outcome.checkedSources + failedSources
    return outcome.checkedSources == 0 ||
        failedSources.toLong() * 2L >= attemptedSources.toLong()
}
