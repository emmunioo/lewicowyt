package pl.lewicowyt.notifier.worker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.ZonedDateTime
import pl.lewicowyt.notifier.data.AppSettings
import pl.lewicowyt.notifier.data.PreferencesRepository

/**
 * Jedyny harmonogram automatycznej synchronizacji.
 *
 * AlarmManager przechowuje jednorazowy alarm RTC_WAKEUP poza procesem
 * aplikacji. Po każdym wywołaniu odbiornik zapisuje kolejny termin, dzięki
 * czemu harmonogram nie wymaga stale działającego procesu aplikacji.
 */
class SyncScheduler(
    private val context: Context,
    private val preferences: PreferencesRepository,
) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    suspend fun ensureScheduled() {
        schedule(preferences.current())
    }

    suspend fun rescheduleAfterSystemClockChange() {
        schedule(preferences.current())
    }

    fun schedule(settings: AppSettings) {
        if (settings.selectedCreatorIds.isEmpty() || !hasExactAlarmAccess()) {
            cancelAlarm()
            return
        }
        scheduleAt(
            triggerAtMillis = nextAlarmRun(
                now = ZonedDateTime.now(),
                intervalMinutes = settings.intervalMinutes,
                dailyHour = settings.dailyHour,
                dailyMinute = settings.dailyMinute,
            ).toInstant().toEpochMilli(),
            retryAttempt = 0,
        )
    }

    /**
     * Receiver wywołuje tę metodę przed rozpoczęciem pracy sieciowej. Nawet
     * zabicie procesu podczas synchronizacji nie usuwa wtedy następnego terminu.
     */
    fun scheduleNext(settings: AppSettings) {
        schedule(settings)
    }

    /**
     * Rozległa awaria albo brak dozwolonej sieci może zastąpić zwykły następny
     * termin krótkim retry. Ten sam PendingIntent gwarantuje, że aktywny jest
     * zawsze najwyżej jeden alarm.
     */
    fun scheduleRetry(
        settings: AppSettings,
        retryAttempt: Int,
        now: ZonedDateTime = ZonedDateTime.now(),
    ): Boolean {
        if (
            retryAttempt !in 1..MAX_RETRY_ATTEMPTS ||
            settings.selectedCreatorIds.isEmpty() ||
            !hasExactAlarmAccess()
        ) {
            return false
        }
        scheduleAt(
            triggerAtMillis = nextRetryAlarmRun(now).toInstant().toEpochMilli(),
            retryAttempt = retryAttempt,
        )
        return true
    }

    suspend fun cancelScheduledAndWait() {
        cancelAlarm()
    }

    fun hasExactAlarmAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()

    private fun scheduleAt(triggerAtMillis: Long, retryAttempt: Int) {
        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                alarmPendingIntent(retryAttempt),
            )
        } catch (_: SecurityException) {
            cancelAlarm()
        }
    }

    private fun cancelAlarm() {
        alarmManager.cancel(alarmPendingIntent(retryAttempt = 0))
    }

    private fun alarmPendingIntent(retryAttempt: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            Intent(context, ReliableSyncAlarmReceiver::class.java)
                .setAction(ACTION_SYNC_ALARM)
                .putExtra(EXTRA_RETRY_ATTEMPT, retryAttempt),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        const val ACTION_SYNC_ALARM =
            "pl.lewicowyt.notifier.action.SYNC_ALARM"
        const val EXTRA_RETRY_ATTEMPT = "sync_alarm_retry_attempt"
        const val DAILY_INTERVAL_MINUTES = 1440
        const val MAX_RETRY_ATTEMPTS = 2
        const val RETRY_DELAY_MINUTES = 15L
        private const val ALARM_REQUEST_CODE = 7_101
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
