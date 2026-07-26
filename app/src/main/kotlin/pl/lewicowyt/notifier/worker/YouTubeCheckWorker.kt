package pl.lewicowyt.notifier.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import pl.lewicowyt.notifier.AppGraph
import pl.lewicowyt.notifier.data.BackgroundMode
import pl.lewicowyt.notifier.model.SyncOutcome

class YouTubeCheckWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        AppGraph.initialize(applicationContext)
        if (inputData.getBoolean(SyncScheduler.INPUT_BALANCED_CATCH_UP, false)) {
            val settings = AppGraph.preferences.current()
            if (
                settings.backgroundMode != BackgroundMode.BALANCED ||
                settings.selectedCreatorIds.isEmpty() ||
                AppGraph.syncEngine.isSyncInProgress() ||
                !shouldRunBalancedCatchUp(
                    now = ZonedDateTime.now(),
                    lastCompletedSyncMillis = settings.lastCompletedSyncAtMillis,
                    intervalMinutes = settings.intervalMinutes,
                    dailyHour = settings.dailyHour,
                    dailyMinute = settings.dailyMinute,
                )
            ) {
                return Result.success()
            }
        }
        val outcome = try {
            withTimeoutOrNull(BACKGROUND_SYNC_TIMEOUT_MILLIS) {
                AppGraph.syncEngine.sync()
            } ?: return retryOrFinish("Synchronizacja w tle przekroczyła limit czasu")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return retryOrFinish(
                "Błąd synchronizacji w tle: " +
                    (error.message ?: error.javaClass.simpleName),
            )
        }
        return if (shouldRetryBackgroundSync(outcome, runAttemptCount)) {
            Result.retry()
        } else {
            finishSuccessfulRun()
        }
    }

    private suspend fun retryOrFinish(message: String): Result {
        try {
            AppGraph.preferences.updateLastSync(
                timestamp = System.currentTimeMillis(),
                summary = message,
                completed = false,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Błąd zapisu statusu nie może zablokować ponowienia właściwej pracy.
        }
        return if (runAttemptCount < MAX_ADDITIONAL_RETRIES) {
            Result.retry()
        } else {
            finishSuccessfulRun()
        }
    }

    private suspend fun finishSuccessfulRun(): Result {
        // Następny dzień jest dopinany do jednego stałego unikalnego łańcucha
        // dopiero po terminalnej próbie. Retry nie może utworzyć kolejnego zadania.
        try {
            AppGraph.scheduler.scheduleAfterBackgroundSync()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Aplikacja odtworzy brakujący harmonogram przy następnym uruchomieniu.
        }
        return Result.success()
    }

    private companion object {
        const val MAX_ADDITIONAL_RETRIES = 2
        val BACKGROUND_SYNC_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(8)
    }
}

internal fun shouldRetryBackgroundSync(
    outcome: SyncOutcome,
    runAttemptCount: Int,
    maxAdditionalRetries: Int = 2,
): Boolean {
    if (outcome.errors.isEmpty() || runAttemptCount >= maxAdditionalRetries) return false

    // Pojedyncza niedostępna strona nie może ponownie uruchamiać całej,
    // kosztownej synchronizacji kilkudziesięciu poprawnie sprawdzonych źródeł.
    // Ponawiamy wyłącznie awarię całkowitą albo co najmniej połowy prób.
    val failedSources = outcome.errors.size
    val attemptedSources = outcome.checkedSources + failedSources
    return outcome.checkedSources == 0 ||
        failedSources.toLong() * 2L >= attemptedSources.toLong()
}
