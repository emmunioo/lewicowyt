package pl.lewicowyt.notifier.diagnostics

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import pl.lewicowyt.notifier.BuildConfig
import pl.lewicowyt.notifier.data.CreatorCatalog
import pl.lewicowyt.notifier.data.LocalDatabase
import pl.lewicowyt.notifier.data.PreferencesRepository
import pl.lewicowyt.notifier.images.JxlImageCache
import pl.lewicowyt.notifier.worker.SyncScheduler
import pl.lewicowyt.notifier.worker.currentDiagnosticNetworkState

internal data class DiagnosticSnapshotResult(
    val databaseOk: Boolean,
    val alarmsPresent: Int,
    val alarmsExpected: Int,
    val fts5Available: Boolean,
)

/**
 * Tworzy wyłącznie tanią migawkę danych lokalnych i publicznych stanów systemu.
 * Nie uruchamia sieci ani synchronizacji i nie odczytuje identyfikatorów urządzenia.
 */
internal class DiagnosticSnapshotProvider(
    context: Context,
    private val catalog: CreatorCatalog,
    private val preferences: PreferencesRepository,
    private val database: LocalDatabase,
    private val scheduler: SyncScheduler,
) {
    private val appContext = context.applicationContext

    suspend fun writeSnapshot(
        origin: String,
        run: DiagnosticSyncRun? = null,
    ): DiagnosticSnapshotResult {
        if (!DiagnosticLogStore.isEnabled()) {
            return DiagnosticSnapshotResult(false, 0, SyncScheduler.REGULAR_ALARM_QUEUE_SIZE, false)
        }
        val syncId = run?.syncId
        DiagnosticLogStore.event(
            DiagnosticCategory.APP,
            DiagnosticLevel.INFO,
            "DIAGNOSTIC_SNAPSHOT_BEGIN",
            syncId = syncId,
            fields = mapOf("origin" to origin),
        )
        return try {
            val settings = preferences.current()
            val network = currentDiagnosticNetworkState(appContext)
            val notificationManager = appContext.getSystemService(NotificationManager::class.java)
            val policyAccess = notificationManager?.isNotificationPolicyAccessGranted == true
            val dnd = notificationManager?.currentInterruptionFilter
                ?.let { it != NotificationManager.INTERRUPTION_FILTER_ALL }
                ?: false
            val batteryUnrestricted = appContext.getSystemService(PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(appContext.packageName) == true
            val notificationsAllowed = NotificationManagerCompat.from(appContext)
                .areNotificationsEnabled() && (
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                        ContextCompat.checkSelfPermission(
                            appContext,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) == PackageManager.PERMISSION_GRANTED
                    )
            val exactAlarm = scheduler.hasExactAlarmAccess()
            val selected = catalog.creators.filter { it.id in settings.selectedCreatorIds }
            val schedulerState = scheduler.diagnosticSnapshot()
            val db = database.diagnosticState()
            val images = JxlImageCache.diagnosticState(appContext)

            DiagnosticLogStore.event(
                DiagnosticCategory.APP,
                DiagnosticLevel.INFO,
                "SYSTEM_STATE",
                syncId = syncId,
                fields = mapOf(
                    "version" to BuildConfig.VERSION_NAME,
                    "sdk" to Build.VERSION.SDK_INT,
                    "exactAlarm" to exactAlarm,
                    "notifications" to notificationsAllowed,
                    "notificationPolicy" to policyAccess,
                    "dnd" to dnd,
                    "batteryUnrestricted" to batteryUnrestricted,
                    "network" to network.type.name,
                    "networkAvailable" to network.available,
                    "networkMetered" to network.metered,
                    "allowMobile" to settings.allowMobileData,
                    "selectedCreators" to selected.size,
                    "activeSources" to selected.sumOf { it.sources.size },
                    "types" to settings.globalHistoryTypes.joinToString(",") { it.name },
                    "intervalMin" to settings.intervalMinutes,
                    "lastCompletedSync" to settings.lastCompletedSyncAtMillis,
                    "nextAlarm" to schedulerState.nextAlarmAtMillis,
                ),
            )
            scheduler.logDiagnosticSnapshot(schedulerState, syncId, "SNAPSHOT")
            DiagnosticLogStore.event(
                DiagnosticCategory.DATABASE,
                DiagnosticLevel.INFO,
                "DATABASE_STATE",
                syncId = syncId,
                fields = mapOf(
                    "engine" to "BUNDLED_SQLITE",
                    "sqlite" to db.sqliteVersion,
                    "userVersion" to db.userVersion,
                    "schema" to db.appSchemaVersion,
                    "journal" to db.journalMode,
                    "fts5" to db.fts5Available,
                ),
            )
            DiagnosticLogStore.event(
                DiagnosticCategory.IMAGE,
                DiagnosticLevel.INFO,
                "IMAGE_CACHE_STATE",
                syncId = syncId,
                fields = mapOf(
                    "files" to images.files,
                    "bytes" to images.bytes,
                    "conversions" to images.conversionsInProgress,
                ),
            )
            DiagnosticLogStore.updateExportSummary(
                listOf(
                    "SYSTEM | sdk=${Build.VERSION.SDK_INT} | exactAlarm=$exactAlarm | notifications=$notificationsAllowed | dnd=$dnd | network=${network.type.name}",
                    "SCHEDULERS | regular=${schedulerState.regularPresent}/${schedulerState.regularExpected} | retry=${schedulerState.retryPresent} | watchdog=${schedulerState.watchdogPresent} | dndProbe=${schedulerState.dndProbePresent}",
                    "DATABASE | engine=BUNDLED_SQLITE | sqlite=${db.sqliteVersion} | schema=${db.userVersion} | journal=${db.journalMode} | fts5=${db.fts5Available}",
                    "LAST_SYNC | completed=${settings.lastCompletedSyncAtMillis}",
                ),
            )
            DiagnosticSnapshotResult(
                databaseOk = true,
                alarmsPresent = schedulerState.regularPresent,
                alarmsExpected = schedulerState.regularExpected,
                fts5Available = db.fts5Available,
            )
        } catch (error: Exception) {
            DiagnosticLogStore.event(
                DiagnosticCategory.APP,
                DiagnosticLevel.ERROR,
                "DIAGNOSTIC_SNAPSHOT_ERROR",
                syncId = syncId,
                reason = DiagnosticReasonCode.DATABASE_ERROR,
                fields = mapOf("type" to error.javaClass.simpleName),
                text = "Nie udało się zapisać pełnej migawki diagnostycznej",
            )
            DiagnosticSnapshotResult(false, 0, SyncScheduler.REGULAR_ALARM_QUEUE_SIZE, false)
        } finally {
            DiagnosticLogStore.event(
                DiagnosticCategory.APP,
                DiagnosticLevel.INFO,
                "DIAGNOSTIC_SNAPSHOT_END",
                syncId = syncId,
                fields = mapOf("origin" to origin),
            )
        }
    }

    fun quickCheck(): Boolean {
        val result = runCatching { database.quickCheck() }
        val ok = result.getOrNull().equals("ok", ignoreCase = true)
        DiagnosticLogStore.event(
            DiagnosticCategory.DATABASE,
            if (ok) DiagnosticLevel.INFO else DiagnosticLevel.ERROR,
            "DB_QUICK_CHECK",
            reason = if (ok) null else DiagnosticReasonCode.DATABASE_ERROR,
            fields = mapOf(
                "result" to if (ok) "OK" else "PROBLEM",
                "type" to result.exceptionOrNull()?.javaClass?.simpleName,
            ),
            text = if (ok) "Baza danych: OK" else "Wykryto problem bazy danych",
        )
        return ok
    }
}
