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
import pl.lewicowyt.notifier.data.BackgroundMode

class ReliableSyncService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var syncJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        AppGraph.initialize(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
            try {
                val settings = AppGraph.preferences.current()
                if (
                    settings.backgroundMode == BackgroundMode.RELIABLE &&
                    settings.selectedCreatorIds.isNotEmpty() &&
                    currentSyncNetworkAccess(this@ReliableSyncService)
                        .allowsSync(settings.allowMobileData)
                ) {
                    val completed = withTimeoutOrNull(SYNC_TIMEOUT_MILLIS) {
                        AppGraph.syncEngine.sync()
                    }
                    if (completed == null) {
                        recordFailure("Synchronizacja niezawodna przekroczyła limit czasu")
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                recordFailure(
                    "Błąd synchronizacji niezawodnej: " +
                        (error.message ?: error.javaClass.simpleName),
                )
            } finally {
                try {
                    // Alarm awaryjny może wyprzedzić opóźniony WorkManager.
                    // Pełne przeliczenie zastępuje stary termin dzienny,
                    // zamiast pozostawiać w kolejce drugą synchronizację.
                    AppGraph.scheduler.schedule(AppGraph.preferences.current())
                } catch (_: Exception) {
                    // Alarm odbiornika jest już ustawiony jako zabezpieczenie.
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
            "Sprawdzanie w tle",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Widoczne tylko podczas awaryjnego sprawdzania nowych materiałów"
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
