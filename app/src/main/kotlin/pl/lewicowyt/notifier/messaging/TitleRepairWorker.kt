package pl.lewicowyt.notifier.messaging

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import pl.lewicowyt.notifier.AppGraph

/**
 * Budzik push potrafi wyprzedzić RSS YouTube o minuty — wtedy film trafia do Historii/Powiadomień z tytułem
 * zastępczym „Nowy materiał". Ta praca dociąga prawdziwy tytuł (i datę) z RSS później: po 10 min, 1 h i 6 h.
 * Przetrwa ubicie procesu (WorkManager).
 */
class TitleRepairWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        AppGraph.initialize(applicationContext)
        val channelId = inputData.getString(KEY_CHANNEL)
        val videoId = inputData.getString(KEY_VIDEO)
        val creatorId = inputData.getString(KEY_CREATOR)
        if (channelId.isNullOrBlank() || videoId.isNullOrBlank() || creatorId.isNullOrBlank()) return Result.success()
        val attempt = inputData.getInt(KEY_ATTEMPT, 0)
        val repaired = try {
            ServerNotificationHandler.repairTitle(channelId, videoId, creatorId, attempt)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) {
            false
        }
        if (!repaired && attempt + 1 < DELAYS_MINUTES.size) {
            schedule(applicationContext, channelId, videoId, creatorId, attempt + 1)
        }
        return Result.success()
    }

    companion object {
        private const val KEY_CHANNEL = "c"
        private const val KEY_VIDEO = "v"
        private const val KEY_CREATOR = "creator"
        private const val KEY_ATTEMPT = "attempt"
        private val DELAYS_MINUTES = longArrayOf(10L, 60L, 360L)

        fun schedule(context: Context, channelId: String, videoId: String, creatorId: String, attempt: Int = 0) {
            val request = OneTimeWorkRequestBuilder<TitleRepairWorker>()
                .setInputData(
                    workDataOf(KEY_CHANNEL to channelId, KEY_VIDEO to videoId, KEY_CREATOR to creatorId, KEY_ATTEMPT to attempt),
                )
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(DELAYS_MINUTES[attempt.coerceIn(0, DELAYS_MINUTES.lastIndex)], TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("title-repair-$videoId", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
