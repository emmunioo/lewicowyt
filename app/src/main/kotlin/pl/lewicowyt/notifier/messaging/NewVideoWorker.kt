package pl.lewicowyt.notifier.messaging

import android.content.Context
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import pl.lewicowyt.notifier.AppGraph

/**
 * trwała obsługa powiadomienia z serwera (przetrwa ubicie procesu i reboot).
 * budzik UnifiedPush (CommunityPushWorker) wrzuca tu zadanie, gdy filmu nie ma jeszcze w playliście;
 * robotę – z ponawianiem playlisty co minutę
 * do 15 razy – wykonuje WorkManager.
 */
class NewVideoWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        AppGraph.initialize(applicationContext)
        val channelId = inputData.getString(KEY_CHANNEL)
        val videoId = inputData.getString(KEY_VIDEO)
        if (channelId.isNullOrBlank() || videoId.isNullOrBlank()) return Result.success()
        val attempt = inputData.getInt(KEY_ATTEMPT, 0)

        // A-47: anulowanie przepuszczamy (WorkManager sam ponowi), a nieoczekiwany błąd (np. SQLite/IO) daje
        // Result.retry() z backoffem zamiast „sukcesu" z cicho utraconym powiadomieniem.
        val result = try {
            ServerNotificationHandler.handle(channelId, videoId, attempt)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (error: Exception) {
            // G-05c: ślad w dzienniku — po MAX_RUN_ATTEMPTS powiadomienie przepada, a wcześniej nie było wpisu
            pl.lewicowyt.notifier.diagnostics.DiagnosticLogStore.event(
                pl.lewicowyt.notifier.diagnostics.DiagnosticCategory.SYNC,
                pl.lewicowyt.notifier.diagnostics.DiagnosticLevel.WARNING,
                "NEW_VIDEO_WORKER_FAILED",
                fields = mapOf(
                    "type" to error.javaClass.simpleName,
                    "runAttempt" to runAttemptCount,
                    "gaveUp" to (runAttemptCount >= MAX_RUN_ATTEMPTS),
                ),
            )
            return if (runAttemptCount < MAX_RUN_ATTEMPTS) Result.retry() else Result.success()
        }

        if (result == ServerNotificationHandler.HandleResult.RETRY_PLAYLIST) {
            // filmu jeszcze nie ma w playliście – ponów za minutę (nowy Worker)
            enqueue(applicationContext, channelId, videoId, attempt + 1, delayMinutes = 1L)
        }
        return Result.success()
    }

    companion object {
        private const val KEY_CHANNEL = "c"
        private const val KEY_VIDEO = "v"
        private const val KEY_ATTEMPT = "attempt"
        private const val MAX_RUN_ATTEMPTS = 5 // A-47

        fun enqueue(
            context: Context,
            channelId: String,
            videoId: String,
            attempt: Int = 0,
            delayMinutes: Long = 0L,
        ) {
            val request = OneTimeWorkRequestBuilder<NewVideoWorker>()
                .setInputData(
                    workDataOf(
                        KEY_CHANNEL to channelId,
                        KEY_VIDEO to videoId,
                        KEY_ATTEMPT to attempt,
                    ),
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .apply {
                    if (delayMinutes > 0L) {
                        // ponowienie playlisty może poczekać minutę (expedited +
                        // initialDelay się wykluczają w WorkManagerze)
                        setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                    } else {
                        // push „na teraz": odpal od razu, z pominięciem odraczania
                        // w Doze/App Standby. po wyczerpaniu limitu expedited spada
                        // do zwykłego zadania (nie gubi się).
                        // N-02: expedited TYLKO od API 31 — niżej getForegroundInfo() (domyślnie rzuca) wywracał
                        // zadanie przed doWork(), przez co powiadomienia o filmach nie działały na Androidzie 8–11.
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                        }
                    }
                }
                .build()
            // unikalne per videoId: duplikat pusha albo kolejna próba zastępuje
            // poprzednie zadanie, zamiast mnożyć równoległe łańcuchy
            WorkManager.getInstance(context).enqueueUniqueWork(
                "new-video-$videoId",
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
