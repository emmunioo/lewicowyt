package pl.lewicowyt.notifier.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.lewicowyt.notifier.data.AppSettings
import pl.lewicowyt.notifier.data.PreferencesRepository

class SyncScheduler(
    private val context: Context,
    private val preferences: PreferencesRepository,
) {
    suspend fun ensureScheduled() {
        WorkManager.getInstance(context).cancelAllWorkByTag(LEGACY_DAILY_WORK_TAG)
        // Zwykłe otwarcie aplikacji nie może anulować workera, który właśnie
        // sprawdza źródła w tle.
        scheduleInternal(preferences.current(), replaceDailySchedule = false)
    }

    suspend fun rescheduleAfterSystemClockChange() {
        WorkManager.getInstance(context).cancelAllWorkByTag(LEGACY_DAILY_WORK_TAG)
        // Zmiana czasu lub strefy wymaga ponownego obliczenia lokalnej godziny.
        scheduleInternal(preferences.current(), replaceDailySchedule = true)
    }

    fun schedule(settings: AppSettings) {
        scheduleInternal(settings, replaceDailySchedule = true)
    }

    suspend fun cancelScheduledAndWait() = withContext(Dispatchers.IO) {
        val workManager = WorkManager.getInstance(context)
        listOf(
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME),
            workManager.cancelUniqueWork(DAILY_UNIQUE_WORK_NAME),
            workManager.cancelAllWorkByTag(SYNC_WORK_TAG),
            workManager.cancelAllWorkByTag(LEGACY_DAILY_WORK_TAG),
        ).forEach { operation ->
            operation.result.get(CANCELLATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
    }

    suspend fun scheduleNextDailyIfNeeded() {
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
    }

    private fun scheduleInternal(
        settings: AppSettings,
        replaceDailySchedule: Boolean,
    ) {
        val workManager = WorkManager.getInstance(context)
        if (settings.selectedCreatorIds.isEmpty()) {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            workManager.cancelUniqueWork(DAILY_UNIQUE_WORK_NAME)
            workManager.cancelAllWorkByTag(LEGACY_DAILY_WORK_TAG)
            return
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (settings.allowMobileData) NetworkType.CONNECTED else NetworkType.UNMETERED,
            )
            .setRequiresBatteryNotLow(settings.requireBatteryNotLow)
            .build()

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
        const val SYNC_WORK_TAG = "youtube_background_sync"
        const val DAILY_WORK_TAG = "youtube_daily_background_sync_v2"
        const val LEGACY_DAILY_WORK_TAG = "youtube_daily_background_sync"
        const val DAILY_INTERVAL_MINUTES = 1440
        const val RETRY_BACKOFF_MINUTES = 15L
        const val CANCELLATION_TIMEOUT_SECONDS = 30L

        private fun buildConstraints(settings: AppSettings): Constraints =
            Constraints.Builder()
                .setRequiredNetworkType(
                    if (settings.allowMobileData) {
                        NetworkType.CONNECTED
                    } else {
                        NetworkType.UNMETERED
                    },
                )
                .setRequiresBatteryNotLow(settings.requireBatteryNotLow)
                .build()
    }
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
