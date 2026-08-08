package pl.lewicowyt.notifier.worker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.ZonedDateTime
import pl.lewicowyt.notifier.data.AppSettings
import pl.lewicowyt.notifier.data.PreferencesRepository
import pl.lewicowyt.notifier.data.hasEnabledContentForSelectedCreators
import pl.lewicowyt.notifier.diagnostics.DiagnosticCategory
import pl.lewicowyt.notifier.diagnostics.DiagnosticLevel
import pl.lewicowyt.notifier.diagnostics.DiagnosticLogStore
import pl.lewicowyt.notifier.diagnostics.DiagnosticReasonCode
import pl.lewicowyt.notifier.diagnostics.DiagnosticSyncRun

internal enum class AlarmScheduleCause {
    APP_START,
    SETTINGS_CHANGED,
    ALARM_FIRED,
    SYSTEM_BOOT,
    CLOCK_OR_TIMEZONE_CHANGED,
    EXACT_ALARM_ACCESS_CHANGED,
    RETRY,
    DND,
}

internal data class SchedulerDiagnosticSnapshot(
    val regularPresent: Int,
    val regularExpected: Int,
    val missingSlots: List<Int>,
    val nextAlarmAtMillis: Long?,
    val retryPresent: Boolean,
    val watchdogPresent: Boolean,
    val dndProbePresent: Boolean,
    val intervalMinutes: Int?,
) {
    val regularHealthy: Boolean get() = regularPresent == regularExpected

    fun fields(): Map<String, Any?> = mapOf(
        "regular" to "$regularPresent/$regularExpected",
        "retry" to retryPresent,
        "watchdog" to watchdogPresent,
        "dndProbe" to dndProbePresent,
        "next" to nextAlarmAtMillis,
        "intervalMin" to intervalMinutes,
        "status" to if (regularHealthy) "OK" else "BROKEN",
        "missingSlots" to missingSlots.takeIf(List<Int>::isNotEmpty)?.joinToString(","),
    )
}

internal data class WatchdogDiagnosticState(
    val syncId: String?,
    val startedAtMillis: Long,
    val lastStage: String,
    val serviceRunning: Boolean,
    val wakeLockHeld: Boolean,
)

/**
 * Jedyny harmonogram automatycznej synchronizacji.
 *
 * AlarmManager przechowuje kolejkę 15 alarmów RTC_WAKEUP poza procesem
 * aplikacji. Po każdym wywołaniu odbiornik uzupełnia pełną kolejkę, dzięki
 * czemu harmonogram nie wymaga stale działającego procesu aplikacji.
 */
class SyncScheduler(
    private val context: Context,
    private val preferences: PreferencesRepository,
) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    suspend fun ensureScheduled() {
        val settings = preferences.current()
        val signatureMatches = scheduleState.getString(SCHEDULE_SIGNATURE_KEY, null) ==
            scheduleSignature(settings)
        val snapshot = diagnosticSnapshot()
        if (signatureMatches && snapshot.regularHealthy) return
        if (!snapshot.regularHealthy) {
            logQueue(
                snapshot = snapshot,
                cause = AlarmScheduleCause.APP_START,
                reason = DiagnosticReasonCode.ALARM_QUEUE_INCOMPLETE,
                rebuild = true,
            )
        }
        schedule(settings, AlarmScheduleCause.APP_START)
    }

    internal suspend fun rescheduleAfterSystemClockChange(cause: AlarmScheduleCause) {
        schedule(preferences.current(), cause)
    }

    internal fun schedule(
        settings: AppSettings,
        cause: AlarmScheduleCause = AlarmScheduleCause.SETTINGS_CHANGED,
        syncId: String? = null,
    ) {
        if (!settings.hasEnabledContentForSelectedCreators() || !hasExactAlarmAccess()) {
            cancelAlarm()
            val reason = if (!hasExactAlarmAccess()) {
                DiagnosticReasonCode.EXACT_ALARM_PERMISSION_MISSING
            } else if (settings.selectedCreatorIds.isEmpty()) {
                DiagnosticReasonCode.NO_SELECTED_CREATORS
            } else {
                DiagnosticReasonCode.NO_ENABLED_CONTENT_TYPES
            }
            DiagnosticLogStore.event(
                DiagnosticCategory.SCHEDULER,
                DiagnosticLevel.WARNING,
                "ALARM_QUEUE_DISABLED",
                syncId,
                reason,
                fields = mapOf("cause" to cause.name),
                text = "Nie utworzono harmonogramu automatycznej synchronizacji",
            )
            return
        }
        cancelRegularAlarms()
        cancelRetryAlarm()
        try {
            val runs = nextAlarmRuns(
                now = ZonedDateTime.now(),
                intervalMinutes = settings.intervalMinutes,
                dailyHour = settings.dailyHour,
                dailyMinute = settings.dailyMinute,
                count = REGULAR_ALARM_QUEUE_SIZE,
            )
            val stateEditor = scheduleState.edit().clearRegularAlarmTimes()
            runs.forEachIndexed { slot, trigger ->
                val triggerAtMillis = trigger.toInstant().toEpochMilli()
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    regularAlarmPendingIntent(slot, create = true)!!,
                )
                stateEditor.putLong(regularAlarmTimeKey(slot), triggerAtMillis)
            }
            stateEditor
                .putString(SCHEDULE_SIGNATURE_KEY, scheduleSignature(settings))
                .putInt(SCHEDULE_INTERVAL_KEY, settings.intervalMinutes)
                .apply()
            logQueue(diagnosticSnapshot(), cause, syncId = syncId, rebuild = true)
        } catch (error: SecurityException) {
            cancelAlarm()
            DiagnosticLogStore.event(
                DiagnosticCategory.SCHEDULER,
                DiagnosticLevel.ERROR,
                "ALARM_QUEUE_ERROR",
                syncId,
                DiagnosticReasonCode.EXACT_ALARM_PERMISSION_MISSING,
                fields = mapOf("cause" to cause.name, "type" to error.javaClass.simpleName),
                text = "Android odrzucił planowanie dokładnych alarmów",
            )
        }
    }

    /**
     * Receiver wywołuje tę metodę przed rozpoczęciem pracy sieciowej. Nawet
     * zabicie procesu podczas synchronizacji pozostawia 15 przyszłych terminów.
     */
    fun scheduleNext(settings: AppSettings, syncId: String?) {
        schedule(settings, AlarmScheduleCause.ALARM_FIRED, syncId)
    }

    /**
     * Rozległa awaria albo brak dozwolonej sieci dodaje jeden krótki alarm retry
     * niezależny od 15 zwykłych terminów. Stały PendingIntent retry gwarantuje,
     * że aktywne jest zawsze najwyżej jedno takie ponowienie.
     */
    fun scheduleRetry(
        settings: AppSettings,
        retryAttempt: Int,
        retryOf: String? = null,
        now: ZonedDateTime = ZonedDateTime.now(),
    ): Boolean {
        if (
            retryAttempt !in 1..MAX_RETRY_ATTEMPTS ||
            !settings.hasEnabledContentForSelectedCreators() ||
            !hasExactAlarmAccess()
        ) {
            return false
        }
        try {
            val triggerAtMillis = nextRetryAlarmRun(now).toInstant().toEpochMilli()
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                retryPendingIntent(retryAttempt, retryOf, create = true)!!,
            )
            scheduleState.edit().putLong(RETRY_TIME_KEY, triggerAtMillis).apply()
            DiagnosticLogStore.event(
                DiagnosticCategory.SCHEDULER,
                DiagnosticLevel.INFO,
                "RETRY_SCHEDULED",
                retryOf,
                DiagnosticReasonCode.RETRY_SCHEDULED,
                fields = mapOf("attempt" to retryAttempt, "at" to triggerAtMillis),
            )
        } catch (error: SecurityException) {
            cancelRetryAlarm()
            DiagnosticLogStore.error("AlarmRetry", "RETRY_SCHEDULE_ERROR", error)
            return false
        }
        return true
    }

    internal fun scheduleWatchdog(run: DiagnosticSyncRun) {
        if (!hasExactAlarmAccess()) return
        try {
            val triggerAtMillis = System.currentTimeMillis() + WATCHDOG_DELAY_MILLIS
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                watchdogPendingIntent(create = true)!!,
            )
            scheduleState.edit()
                .putLong(WATCHDOG_TIME_KEY, triggerAtMillis)
                .putString(WATCHDOG_SYNC_ID_KEY, run.syncId)
                .putLong(WATCHDOG_STARTED_AT_KEY, System.currentTimeMillis())
                .putString(WATCHDOG_LAST_STAGE_KEY, run.lastStage())
                .putBoolean(WATCHDOG_SERVICE_RUNNING_KEY, false)
                .putBoolean(WATCHDOG_WAKELOCK_HELD_KEY, false)
                .apply()
            run.event(
                "WATCHDOG_SCHEDULED",
                category = DiagnosticCategory.SCHEDULER,
                fields = mapOf("at" to triggerAtMillis),
            )
        } catch (error: SecurityException) {
            cancelWatchdog(run.syncId)
            run.event(
                "WATCHDOG_SCHEDULE_ERROR",
                category = DiagnosticCategory.SCHEDULER,
                level = DiagnosticLevel.ERROR,
                reason = DiagnosticReasonCode.EXACT_ALARM_PERMISSION_MISSING,
                fields = mapOf("type" to error.javaClass.simpleName),
            )
        }
    }

    fun updateWatchdogRuntime(
        syncId: String,
        stage: String,
        serviceRunning: Boolean,
        wakeLockHeld: Boolean,
    ) {
        if (scheduleState.getString(WATCHDOG_SYNC_ID_KEY, null) != syncId) return
        scheduleState.edit()
            .putString(WATCHDOG_LAST_STAGE_KEY, stage.take(48))
            .putBoolean(WATCHDOG_SERVICE_RUNNING_KEY, serviceRunning)
            .putBoolean(WATCHDOG_WAKELOCK_HELD_KEY, wakeLockHeld)
            .apply()
    }

    internal fun watchdogDiagnosticState(): WatchdogDiagnosticState = WatchdogDiagnosticState(
        syncId = scheduleState.getString(WATCHDOG_SYNC_ID_KEY, null),
        startedAtMillis = scheduleState.getLong(WATCHDOG_STARTED_AT_KEY, 0L),
        lastStage = scheduleState.getString(WATCHDOG_LAST_STAGE_KEY, "UNKNOWN") ?: "UNKNOWN",
        serviceRunning = scheduleState.getBoolean(WATCHDOG_SERVICE_RUNNING_KEY, false),
        wakeLockHeld = scheduleState.getBoolean(WATCHDOG_WAKELOCK_HELD_KEY, false),
    )

    fun cancelWatchdog(syncId: String? = null) {
        val existing = watchdogPendingIntent(create = false)
        existing?.let(alarmManager::cancel)
        scheduleState.edit()
            .remove(WATCHDOG_TIME_KEY)
            .remove(WATCHDOG_SYNC_ID_KEY)
            .remove(WATCHDOG_STARTED_AT_KEY)
            .remove(WATCHDOG_LAST_STAGE_KEY)
            .remove(WATCHDOG_SERVICE_RUNNING_KEY)
            .remove(WATCHDOG_WAKELOCK_HELD_KEY)
            .apply()
        if (existing != null) {
            DiagnosticLogStore.event(
                DiagnosticCategory.SCHEDULER,
                DiagnosticLevel.INFO,
                "WATCHDOG_CANCELLED",
                syncId,
            )
        }
    }

    fun scheduleDndProbe() {
        if (!hasExactAlarmAccess()) return
        try {
            val triggerAtMillis = System.currentTimeMillis() + DND_PROBE_DELAY_MILLIS
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                dndProbePendingIntent(create = true)!!,
            )
            scheduleState.edit()
                .putLong(DND_PROBE_TIME_KEY, triggerAtMillis)
                .apply()
            DiagnosticLogStore.event(
                DiagnosticCategory.SCHEDULER,
                DiagnosticLevel.INFO,
                "DND_PROBE_SCHEDULED",
                reason = DiagnosticReasonCode.DND_DEFERRED,
                fields = mapOf("at" to triggerAtMillis),
            )
        } catch (error: SecurityException) {
            DiagnosticLogStore.event(
                DiagnosticCategory.SCHEDULER,
                DiagnosticLevel.ERROR,
                "DND_PROBE_ERROR",
                reason = DiagnosticReasonCode.EXACT_ALARM_PERMISSION_MISSING,
                fields = mapOf("type" to error.javaClass.simpleName),
            )
            cancelDndProbe()
        }
    }

    fun scheduleDndCatchup() {
        if (!hasExactAlarmAccess()) return
        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + DND_CATCHUP_DELAY_MILLIS,
                dndProbePendingIntent(create = true)!!,
            )
            scheduleState.edit()
                .putLong(DND_PROBE_TIME_KEY, System.currentTimeMillis() + DND_CATCHUP_DELAY_MILLIS)
                .apply()
            DiagnosticLogStore.event(
                DiagnosticCategory.SCHEDULER,
                DiagnosticLevel.INFO,
                "DND_CATCHUP_SCHEDULED",
            )
        } catch (_: SecurityException) {
            // Stan dostępu pokaże ekran ustawień; zwykły harmonogram pozostaje.
        }
    }

    fun cancelDndProbe() {
        val existing = dndProbePendingIntent(create = false)
        existing?.let(alarmManager::cancel)
        scheduleState.edit().remove(DND_PROBE_TIME_KEY).apply()
        if (existing != null) {
            DiagnosticLogStore.event(
                DiagnosticCategory.SCHEDULER,
                DiagnosticLevel.INFO,
                "DND_PROBE_CANCELLED",
            )
        }
    }

    suspend fun cancelScheduledAndWait() {
        cancelAlarm()
    }

    fun hasExactAlarmAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()

    private fun cancelAlarm() {
        cancelRegularAlarms()
        cancelRetryAlarm()
        cancelWatchdog()
        cancelDndProbe()
        scheduleState.edit().remove(SCHEDULE_SIGNATURE_KEY).apply()
    }

    private fun cancelRegularAlarms() {
        repeat(REGULAR_ALARM_QUEUE_SIZE) { slot ->
            regularAlarmPendingIntent(slot, create = false)?.let(alarmManager::cancel)
        }
        scheduleState.edit().clearRegularAlarmTimes().apply()
    }

    private fun cancelRetryAlarm() {
        retryPendingIntent(retryAttempt = 0, retryOf = null, create = false)
            ?.let(alarmManager::cancel)
        scheduleState.edit().remove(RETRY_TIME_KEY).apply()
    }

    private fun hasCompleteRegularAlarmQueue(): Boolean =
        (0 until REGULAR_ALARM_QUEUE_SIZE).all { slot ->
            regularAlarmPendingIntent(slot, create = false) != null
        }

    private fun regularAlarmPendingIntent(slot: Int, create: Boolean): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            REGULAR_ALARM_REQUEST_CODE_BASE + slot,
            Intent(context, ReliableSyncAlarmReceiver::class.java)
                .setAction(ACTION_SYNC_ALARM)
                .putExtra(EXTRA_RETRY_ATTEMPT, 0)
                .putExtra(EXTRA_ALARM_SLOT, slot),
            (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE) or
                PendingIntent.FLAG_IMMUTABLE,
        )

    private fun retryPendingIntent(
        retryAttempt: Int,
        retryOf: String?,
        create: Boolean,
    ): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            RETRY_REQUEST_CODE,
            Intent(context, ReliableSyncAlarmReceiver::class.java)
                .setAction(ACTION_SYNC_RETRY)
                .putExtra(EXTRA_RETRY_ATTEMPT, retryAttempt)
                .putExtra(EXTRA_RETRY_OF_SYNC_ID, retryOf),
            (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE) or
                PendingIntent.FLAG_IMMUTABLE,
        )

    private fun watchdogPendingIntent(create: Boolean): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            WATCHDOG_REQUEST_CODE,
            Intent(context, ReliableSyncAlarmReceiver::class.java)
                .setAction(ACTION_SYNC_WATCHDOG),
            (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE) or
                PendingIntent.FLAG_IMMUTABLE,
        )

    private fun dndProbePendingIntent(create: Boolean): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            DND_PROBE_REQUEST_CODE,
            Intent(context, ReliableSyncAlarmReceiver::class.java)
                .setAction(ACTION_DND_PROBE),
            (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE) or
                PendingIntent.FLAG_IMMUTABLE,
        )

    internal fun diagnosticSnapshot(): SchedulerDiagnosticSnapshot {
        val now = System.currentTimeMillis()
        val missing = (0 until REGULAR_ALARM_QUEUE_SIZE).filter { slot ->
            regularAlarmPendingIntent(slot, create = false) == null ||
                scheduleState.getLong(regularAlarmTimeKey(slot), 0L) < now
        }
        val next = (0 until REGULAR_ALARM_QUEUE_SIZE)
            .map { slot -> scheduleState.getLong(regularAlarmTimeKey(slot), 0L) }
            .filter { it >= now }
            .minOrNull()
        return SchedulerDiagnosticSnapshot(
            regularPresent = REGULAR_ALARM_QUEUE_SIZE - missing.size,
            regularExpected = REGULAR_ALARM_QUEUE_SIZE,
            missingSlots = missing,
            nextAlarmAtMillis = next,
            retryPresent = retryPendingIntent(0, null, create = false) != null &&
                scheduleState.getLong(RETRY_TIME_KEY, 0L) >= now,
            watchdogPresent = watchdogPendingIntent(create = false) != null &&
                scheduleState.getLong(WATCHDOG_TIME_KEY, 0L) >= now,
            dndProbePresent = dndProbePendingIntent(create = false) != null &&
                scheduleState.getLong(DND_PROBE_TIME_KEY, 0L) >= now,
            intervalMinutes = scheduleState.getInt(SCHEDULE_INTERVAL_KEY, -1)
                .takeIf { it > 0 },
        )
    }

    internal fun logDiagnosticSnapshot(
        snapshot: SchedulerDiagnosticSnapshot,
        syncId: String?,
        cause: String,
    ) {
        DiagnosticLogStore.event(
            DiagnosticCategory.SCHEDULER,
            if (snapshot.regularHealthy) DiagnosticLevel.INFO else DiagnosticLevel.WARNING,
            "SCHEDULERS",
            syncId,
            reason = if (snapshot.regularHealthy) null else
                DiagnosticReasonCode.ALARM_QUEUE_INCOMPLETE,
            fields = snapshot.fields() + ("cause" to cause),
        )
    }

    private fun logQueue(
        snapshot: SchedulerDiagnosticSnapshot,
        cause: AlarmScheduleCause,
        syncId: String? = null,
        reason: DiagnosticReasonCode? = null,
        rebuild: Boolean,
    ) {
        DiagnosticLogStore.event(
            DiagnosticCategory.SCHEDULER,
            if (snapshot.regularHealthy) DiagnosticLevel.INFO else DiagnosticLevel.WARNING,
            "ALARM_QUEUE",
            syncId,
            reason,
            fields = snapshot.fields() + mapOf("cause" to cause.name, "rebuild" to rebuild),
        )
        DiagnosticLogStore.event(
            DiagnosticCategory.SCHEDULER,
            DiagnosticLevel.INFO,
            "SCHEDULERS",
            syncId,
            fields = snapshot.fields(),
        )
    }

    companion object {
        const val ACTION_SYNC_ALARM =
            "pl.lewicowyt.notifier.action.SYNC_ALARM"
        const val ACTION_SYNC_RETRY =
            "pl.lewicowyt.notifier.action.SYNC_RETRY"
        const val ACTION_SYNC_WATCHDOG =
            "pl.lewicowyt.notifier.action.SYNC_WATCHDOG"
        const val ACTION_DND_PROBE =
            "pl.lewicowyt.notifier.action.DND_PROBE"
        const val EXTRA_RETRY_ATTEMPT = "sync_alarm_retry_attempt"
        const val EXTRA_ALARM_SLOT = "sync_alarm_slot"
        const val EXTRA_RETRY_OF_SYNC_ID = "retry_of_sync_id"
        const val DAILY_INTERVAL_MINUTES = 1440
        const val MAX_RETRY_ATTEMPTS = 2
        const val RETRY_DELAY_MINUTES = 15L
        const val REGULAR_ALARM_QUEUE_SIZE = 15
        private const val REGULAR_ALARM_REQUEST_CODE_BASE = 7_101
        private const val WATCHDOG_REQUEST_CODE = 7_201
        private const val DND_PROBE_REQUEST_CODE = 7_202
        private const val RETRY_REQUEST_CODE = 7_203
        private const val SCHEDULE_STATE_NAME = "exact_alarm_schedule"
        private const val SCHEDULE_SIGNATURE_KEY = "signature"
        private const val SCHEDULE_INTERVAL_KEY = "interval_minutes"
        private const val REGULAR_TIME_KEY_PREFIX = "regular_time_"
        private const val RETRY_TIME_KEY = "retry_time"
        private const val WATCHDOG_TIME_KEY = "watchdog_time"
        private const val WATCHDOG_SYNC_ID_KEY = "watchdog_sync_id"
        private const val WATCHDOG_STARTED_AT_KEY = "watchdog_started_at"
        private const val WATCHDOG_LAST_STAGE_KEY = "watchdog_last_stage"
        private const val WATCHDOG_SERVICE_RUNNING_KEY = "watchdog_service_running"
        private const val WATCHDOG_WAKELOCK_HELD_KEY = "watchdog_wakelock_held"
        private const val DND_PROBE_TIME_KEY = "dnd_probe_time"
        val WATCHDOG_DELAY_MILLIS = java.util.concurrent.TimeUnit.MINUTES.toMillis(10)
        val DND_PROBE_DELAY_MILLIS = java.util.concurrent.TimeUnit.MINUTES.toMillis(15)
        const val DND_CATCHUP_DELAY_MILLIS = 1_000L
    }

    private val scheduleState = context.getSharedPreferences(
        SCHEDULE_STATE_NAME,
        Context.MODE_PRIVATE,
    )

    private fun scheduleSignature(settings: AppSettings): String =
        "${settings.intervalMinutes}:${settings.dailyHour}:${settings.dailyMinute}:" +
            settings.selectedCreatorIds.sorted().joinToString(",")

    private fun regularAlarmTimeKey(slot: Int): String = "$REGULAR_TIME_KEY_PREFIX$slot"

    private fun android.content.SharedPreferences.Editor.clearRegularAlarmTimes() = apply {
        repeat(REGULAR_ALARM_QUEUE_SIZE) { remove(regularAlarmTimeKey(it)) }
    }
}

internal fun nextAlarmRuns(
    now: ZonedDateTime,
    intervalMinutes: Int,
    dailyHour: Int,
    dailyMinute: Int,
    count: Int,
): List<ZonedDateTime> {
    if (count <= 0) return emptyList()
    val first = nextAlarmRun(now, intervalMinutes, dailyHour, dailyMinute)
    return if (intervalMinutes == SyncScheduler.DAILY_INTERVAL_MINUTES) {
        List(count) { offset -> first.plusDays(offset.toLong()) }
    } else {
        val interval = intervalMinutes.coerceAtLeast(15).toLong()
        List(count) { offset -> first.plusMinutes(interval * offset.toLong()) }
    }
}

internal fun nextAlarmRun(
    now: ZonedDateTime,
    intervalMinutes: Int,
    dailyHour: Int,
    dailyMinute: Int,
): ZonedDateTime =
    if (intervalMinutes == SyncScheduler.DAILY_INTERVAL_MINUTES) {
        nextDailyRun(now, dailyHour, dailyMinute)
    } else {
        now.plusMinutes(intervalMinutes.coerceAtLeast(15).toLong())
    }

internal fun nextRetryAlarmRun(now: ZonedDateTime): ZonedDateTime =
    now.plusMinutes(SyncScheduler.RETRY_DELAY_MINUTES)

internal fun nextDailyRun(
    now: ZonedDateTime,
    hour: Int,
    minute: Int,
): ZonedDateTime {
    var next = now
        .withHour(hour.coerceIn(0, 23))
        .withMinute(minute.coerceIn(0, 59))
        .withSecond(0)
        .withNano(0)
    if (!next.isAfter(now)) next = next.plusDays(1)
    return next
}
