package pl.lewicowyt.notifier.worker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.lewicowyt.notifier.data.AppSettings
import pl.lewicowyt.notifier.data.BackgroundMode
import pl.lewicowyt.notifier.data.PreferencesRepository

class SyncScheduler(
    private val context: Context,
    private val preferences: PreferencesRepository,
) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    suspend fun ensureScheduled() {
        WorkManager.getInstance(context).cancelAllWorkByTag(LEGACY_DAILY_WORK_TAG)
        // Zwykłe otwarcie aplikacji nie może anulować workera, który właśnie
        // sprawdza źródła w tle.
        val settings = preferences.current()
        scheduleInternal(settings, replaceDailySchedule = false)
        enqueueBalancedCatchUpIfOverdue(settings)
    }

    suspend fun rescheduleAfterSystemClockChange() {
        WorkManager.getInstance(context).cancelAllWorkByTag(LEGACY_DAILY_WORK_TAG)
        // Zmiana czasu lub strefy wymaga ponownego obliczenia lokalnej godziny.
        val settings = preferences.current()
        scheduleInternal(settings, replaceDailySchedule = true)
        enqueueBalancedCatchUpIfOverdue(settings)
    }

    fun schedule(settings: AppSettings) {
        scheduleInternal(settings, replaceDailySchedule = true)
        enqueueBalancedCatchUpIfOverdue(settings)
    }

    suspend fun cancelScheduledAndWait() = withContext(Dispatchers.IO) {
        val workManager = WorkManager.getInstance(context)
        listOf(
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME),
            workManager.cancelUniqueWork(DAILY_UNIQUE_WORK_NAME),
            workManager.cancelUniqueWork(BALANCED_CATCH_UP_WORK_NAME),
            workManager.cancelAllWorkByTag(SYNC_WORK_TAG),
            workManager.cancelAllWorkByTag(LEGACY_DAILY_WORK_TAG),
        ).forEach { operation ->
            operation.result.get(CANCELLATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        cancelBalancedWatchdog()
        cancelReliableWatchdog()
    }

    suspend fun scheduleAfterBackgroundSync() {
        val settings = preferences.current()
        if (
            settings.intervalMinutes == DAILY_INTERVAL_MINUTES &&
            settings.selectedCreatorIds.isNotEmpty()
        ) {
            enqueueDaily(
                workManager = WorkManager.getInstance(context),
                settings = settings,
                policy = ExistingWorkPolicy.APPEND_OR_REPLACE,
            )
        }
        scheduleBalancedWatchdog(settings)
        scheduleReliableWatchdog(settings)
    }

    /**
     * Rzadki, niedokładny alarm nie zastępuje WorkManagera. Daje mu tylko drugą
     * szansę, gdy producent telefonu zbyt długo nie otworzył zwykłego okna pracy.
     * Nie wymaga dostępu „Alarmy i przypomnienia” i nadal podlega ograniczeniom
     * sieci oraz baterii wybranym przez użytkownika.
     */
    fun scheduleBalancedWatchdog(settings: AppSettings) {
        if (
            settings.backgroundMode != BackgroundMode.BALANCED ||
            settings.selectedCreatorIds.isEmpty()
        ) {
            cancelBalancedWatchdog()
            return
        }

        val nextRun = nextBalancedWatchdogRun(
            now = ZonedDateTime.now(),
            intervalMinutes = settings.intervalMinutes,
            dailyHour = settings.dailyHour,
            dailyMinute = settings.dailyMinute,
        )
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            nextRun.toInstant().toEpochMilli(),
            balancedAlarmPendingIntent(),
        )
    }

    fun cancelBalancedWatchdog() {
        alarmManager.cancel(balancedAlarmPendingIntent())
    }

    /**
     * Dodaje najwyżej jedno zaległe sprawdzenie. Worker przed właściwym pobraniem
     * ponownie sprawdzi świeżość danych, więc normalne zadanie okresowe może je
     * bezpiecznie wyprzedzić bez podwójnego transferu.
     */
    fun enqueueBalancedCatchUpIfOverdue(
        settings: AppSettings,
        now: ZonedDateTime = ZonedDateTime.now(),
    ): Boolean {
        if (
            settings.backgroundMode != BackgroundMode.BALANCED ||
            settings.selectedCreatorIds.isEmpty() ||
            !shouldRunBalancedCatchUp(
                now = now,
                lastCompletedSyncMillis = settings.lastCompletedSyncAtMillis,
                intervalMinutes = settings.intervalMinutes,
                dailyHour = settings.dailyHour,
                dailyMinute = settings.dailyMinute,
            )
        ) {
            return false
        }

        val requestBuilder = OneTimeWorkRequestBuilder<YouTubeCheckWorker>()
            .setInputData(workDataOf(INPUT_BALANCED_CATCH_UP to true))
            .setConstraints(buildConstraints(settings))
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                RETRY_BACKOFF_MINUTES,
                TimeUnit.MINUTES,
            )
            .addTag(SYNC_WORK_TAG)
            .addTag(BALANCED_CATCH_UP_WORK_TAG)
        if (
            shouldExpediteBalancedCatchUp(
                nowMillis = now.toInstant().toEpochMilli(),
                lastCompletedSyncMillis = settings.lastCompletedSyncAtMillis,
                intervalMinutes = settings.intervalMinutes,
            )
        ) {
            requestBuilder.setExpedited(
                OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST,
            )
        }
        val request = requestBuilder.build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            BALANCED_CATCH_UP_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
        return true
    }

    fun hasExactAlarmAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()

    fun scheduleReliableWatchdog(settings: AppSettings) {
        if (
            settings.backgroundMode != BackgroundMode.RELIABLE ||
            settings.selectedCreatorIds.isEmpty() ||
            !hasExactAlarmAccess()
        ) {
            cancelReliableWatchdog()
            return
        }

        val nextRun = nextReliableAlarmRun(
            now = ZonedDateTime.now(),
            intervalMinutes = settings.intervalMinutes,
            dailyHour = settings.dailyHour,
            dailyMinute = settings.dailyMinute,
        )
        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                nextRun.toInstant().toEpochMilli(),
                reliableAlarmPendingIntent(),
            )
        } catch (_: SecurityException) {
            // Dostęp może zostać odebrany między sprawdzeniem a zaplanowaniem.
            cancelReliableWatchdog()
        }
    }

    fun cancelReliableWatchdog() {
        alarmManager.cancel(reliableAlarmPendingIntent())
    }

    private fun scheduleInternal(
        settings: AppSettings,
        replaceDailySchedule: Boolean,
    ) {
        val workManager = WorkManager.getInstance(context)
        if (settings.selectedCreatorIds.isEmpty()) {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            workManager.cancelUniqueWork(DAILY_UNIQUE_WORK_NAME)
            workManager.cancelUniqueWork(BALANCED_CATCH_UP_WORK_NAME)
            workManager.cancelAllWorkByTag(LEGACY_DAILY_WORK_TAG)
            cancelBalancedWatchdog()
            cancelReliableWatchdog()
            return
        }

        val constraints = buildConstraints(settings)
        scheduleBalancedWatchdog(settings)
        scheduleReliableWatchdog(settings)
        if (settings.backgroundMode != BackgroundMode.BALANCED) {
            workManager.cancelUniqueWork(BALANCED_CATCH_UP_WORK_NAME)
        }

        if (settings.intervalMinutes == DAILY_INTERVAL_MINUTES) {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            enqueueDaily(
                workManager = workManager,
                settings = settings,
                policy = if (replaceDailySchedule) {
                    ExistingWorkPolicy.REPLACE
                } else {
                    ExistingWorkPolicy.KEEP
                },
                constraints = constraints,
            )
            return
        }

        workManager.cancelUniqueWork(DAILY_UNIQUE_WORK_NAME)
        workManager.cancelAllWorkByTag(LEGACY_DAILY_WORK_TAG)
        val request = PeriodicWorkRequestBuilder<YouTubeCheckWorker>(
            settings.intervalMinutes.coerceAtLeast(15).toLong(),
            TimeUnit.MINUTES,
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                RETRY_BACKOFF_MINUTES,
                TimeUnit.MINUTES,
            )
            .addTag(SYNC_WORK_TAG)
            .build()
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    private fun reliableAlarmPendingIntent(): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            RELIABLE_ALARM_REQUEST_CODE,
            Intent(context, ReliableSyncAlarmReceiver::class.java)
                .setAction(ACTION_RELIABLE_SYNC_ALARM),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun balancedAlarmPendingIntent(): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            BALANCED_ALARM_REQUEST_CODE,
            Intent(context, BalancedSyncWatchdogReceiver::class.java)
                .setAction(ACTION_BALANCED_SYNC_WATCHDOG),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun enqueueDaily(
        workManager: WorkManager,
        settings: AppSettings,
        policy: ExistingWorkPolicy,
        constraints: Constraints = buildConstraints(settings),
    ) {
        val now = ZonedDateTime.now()
        val next = nextDailyRun(now, settings.dailyHour, settings.dailyMinute)
        val request = OneTimeWorkRequestBuilder<YouTubeCheckWorker>()
            .setInitialDelay(
                Duration.between(now, next).toMillis().coerceAtLeast(1_000L),
                TimeUnit.MILLISECONDS,
            )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                RETRY_BACKOFF_MINUTES,
                TimeUnit.MINUTES,
            )
            .addTag(SYNC_WORK_TAG)
            .addTag(DAILY_WORK_TAG)
            .build()
        workManager.enqueueUniqueWork(
            DAILY_UNIQUE_WORK_NAME,
            policy,
            request,
        )
    }

    companion object {
        const val UNIQUE_WORK_NAME = "youtube_periodic_check"
        const val DAILY_UNIQUE_WORK_NAME = "youtube_daily_check"
        const val BALANCED_CATCH_UP_WORK_NAME = "youtube_balanced_catch_up"
        const val SYNC_WORK_TAG = "youtube_background_sync"
        const val DAILY_WORK_TAG = "youtube_daily_background_sync_v2"
        const val BALANCED_CATCH_UP_WORK_TAG = "youtube_balanced_catch_up_v1"
        const val LEGACY_DAILY_WORK_TAG = "youtube_daily_background_sync"
        const val INPUT_BALANCED_CATCH_UP = "balanced_catch_up"
        const val ACTION_BALANCED_SYNC_WATCHDOG =
            "pl.lewicowyt.notifier.action.BALANCED_SYNC_WATCHDOG"
        const val ACTION_RELIABLE_SYNC_ALARM =
            "pl.lewicowyt.notifier.action.RELIABLE_SYNC_ALARM"
        const val DAILY_INTERVAL_MINUTES = 1440
        const val RETRY_BACKOFF_MINUTES = 15L
        const val CANCELLATION_TIMEOUT_SECONDS = 30L
        private const val BALANCED_ALARM_REQUEST_CODE = 7_100
        private const val RELIABLE_ALARM_REQUEST_CODE = 7_101

        private fun buildConstraints(settings: AppSettings): Constraints =
            Constraints.Builder()
                .setRequiredNetworkType(
                    if (settings.allowMobileData) {
                        NetworkType.CONNECTED
                    } else {
                        NetworkType.UNMETERED
                    },
                )
                .setRequiresBatteryNotLow(
                    settings.backgroundMode == BackgroundMode.BALANCED &&
                        settings.requireBatteryNotLow,
                )
                .build()
    }
}

internal fun nextBalancedWatchdogRun(
    now: ZonedDateTime,
    intervalMinutes: Int,
    dailyHour: Int,
    dailyMinute: Int,
): ZonedDateTime {
    if (intervalMinutes == SyncScheduler.DAILY_INTERVAL_MINUTES) {
        return nextDailyRun(now, dailyHour, dailyMinute)
            .plusMinutes(BALANCED_DAILY_WATCHDOG_GRACE_MINUTES)
    }
    return now.plusMinutes(balancedWatchdogDelayMinutes(intervalMinutes))
}

internal fun balancedWatchdogDelayMinutes(intervalMinutes: Int): Long =
    (intervalMinutes.coerceAtLeast(15).toLong() * 2L)
        .coerceIn(
            BALANCED_MIN_WATCHDOG_DELAY_MINUTES,
            BALANCED_MAX_WATCHDOG_DELAY_MINUTES,
        )

internal fun shouldRunBalancedCatchUp(
    now: ZonedDateTime,
    lastCompletedSyncMillis: Long,
    intervalMinutes: Int,
    dailyHour: Int,
    dailyMinute: Int,
): Boolean {
    if (lastCompletedSyncMillis <= 0L) return true
    val lastCompleted = java.time.Instant.ofEpochMilli(lastCompletedSyncMillis)
    if (lastCompleted.isAfter(now.toInstant())) return false

    val oldestFreshCompletion = if (intervalMinutes == SyncScheduler.DAILY_INTERVAL_MINUTES) {
        var latestDue = now
            .withHour(dailyHour.coerceIn(0, 23))
            .withMinute(dailyMinute.coerceIn(0, 59))
            .withSecond(0)
            .withNano(0)
            .plusMinutes(BALANCED_CATCH_UP_GRACE_MINUTES)
        if (latestDue.isAfter(now)) latestDue = latestDue.minusDays(1)
        latestDue.toInstant()
    } else {
        now.minusMinutes(
            intervalMinutes.coerceAtLeast(15).toLong() +
                BALANCED_CATCH_UP_GRACE_MINUTES,
        ).toInstant()
    }
    return lastCompleted.isBefore(oldestFreshCompletion)
}

internal fun shouldExpediteBalancedCatchUp(
    nowMillis: Long,
    lastCompletedSyncMillis: Long,
    intervalMinutes: Int,
): Boolean {
    if (lastCompletedSyncMillis <= 0L) return true
    val elapsed = nowMillis - lastCompletedSyncMillis
    if (elapsed < 0L) return false
    val twoIntervals = TimeUnit.MINUTES.toMillis(
        intervalMinutes.coerceAtLeast(15).toLong() * 2L,
    )
    val largeDelayThreshold = maxOf(
        TimeUnit.HOURS.toMillis(BALANCED_EXPEDITED_MIN_DELAY_HOURS),
        twoIntervals,
    )
    return elapsed >= largeDelayThreshold
}

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

internal fun nextReliableAlarmRun(
    now: ZonedDateTime,
    intervalMinutes: Int,
    dailyHour: Int,
    dailyMinute: Int,
): ZonedDateTime {
    if (intervalMinutes == SyncScheduler.DAILY_INTERVAL_MINUTES) {
        var next = now
            .withHour(dailyHour.coerceIn(0, 23))
            .withMinute(dailyMinute.coerceIn(0, 59))
            .withSecond(0)
            .withNano(0)
            .plusMinutes(RELIABLE_ALARM_GRACE_MINUTES)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return next
    }
    return now.plusMinutes(
        intervalMinutes.coerceAtLeast(15).toLong() + RELIABLE_ALARM_GRACE_MINUTES,
    )
}

internal fun shouldRunReliableAlarm(
    nowMillis: Long,
    lastSuccessfulSyncMillis: Long,
    intervalMinutes: Int,
): Boolean {
    if (lastSuccessfulSyncMillis <= 0L) return true
    val elapsed = nowMillis - lastSuccessfulSyncMillis
    if (elapsed < 0L) return false
    val expectedInterval = TimeUnit.MINUTES.toMillis(
        intervalMinutes.coerceAtLeast(15).toLong(),
    )
    return elapsed >= expectedInterval
}

private const val RELIABLE_ALARM_GRACE_MINUTES = 10L
private const val BALANCED_CATCH_UP_GRACE_MINUTES = 10L
private const val BALANCED_DAILY_WATCHDOG_GRACE_MINUTES = 60L
private const val BALANCED_MIN_WATCHDOG_DELAY_MINUTES = 60L
private const val BALANCED_MAX_WATCHDOG_DELAY_MINUTES = 12L * 60L
private const val BALANCED_EXPEDITED_MIN_DELAY_HOURS = 6L
