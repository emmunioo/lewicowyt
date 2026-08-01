package pl.lewicowyt.notifier.images

import android.content.Context
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import pl.lewicowyt.notifier.AppLog
import pl.lewicowyt.notifier.data.LocalDatabase
import pl.lewicowyt.notifier.model.Creator
import pl.lewicowyt.notifier.network.YouTubeSourceResolver

/** Weryfikuje awatary najwyżej raz na tydzień, a nieudane próby ponawia następnego dnia. */
class CreatorAvatarUpdater(
    context: Context,
    private val database: LocalDatabase,
    private val resolver: YouTubeSourceResolver,
) {
    private val appContext = context.applicationContext

    suspend fun refreshDue(
        creators: List<Creator>,
        nowMillis: Long = System.currentTimeMillis(),
    ) = withContext(Dispatchers.IO) {
        val dueIds = database.creatorAvatarIdsDue(
            creatorIds = creators.map(Creator::id),
            checkedBeforeMillis = nowMillis - CHECK_INTERVAL_MILLIS,
            attemptedBeforeMillis = nowMillis - FAILURE_RETRY_MILLIS,
        )
        if (dueIds.isEmpty()) return@withContext
        val semaphore = Semaphore(MAX_PARALLEL_AVATAR_CHECKS)
        coroutineScope {
            creators.filter { it.id in dueIds }.map { creator ->
                async {
                    semaphore.withPermit {
                        refreshOne(creator, nowMillis)
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun refreshOne(creator: Creator, nowMillis: Long) {
        database.markCreatorAvatarAttempted(creator.id, nowMillis)
        try {
            val freshUrl = resolver.resolveFreshCreatorAvatar(creator) ?: return
            val downloaded = JxlImageCache.downloadValidatedAvatar(appContext, freshUrl)
                ?: return
            val stored = database.getCreatorAvatarMetadata(creator.id)
            if (stored?.sha256.equals(downloaded.sha256, ignoreCase = true)) {
                database.markCreatorAvatarChecked(creator.id, nowMillis)
                return
            }
            if (!JxlImageCache.cacheDownloaded(appContext, freshUrl, downloaded.bytes)) return
            database.saveVerifiedCreatorAvatar(
                creatorId = creator.id,
                avatarUrl = freshUrl,
                sha256 = downloaded.sha256,
                checkedAtMillis = nowMillis,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AppLog.warning(
                "CreatorAvatarUpdater",
                "Nie udało się sprawdzić awatara: ${creator.id}",
                error,
            )
        }
    }

    private companion object {
        val CHECK_INTERVAL_MILLIS = TimeUnit.DAYS.toMillis(7)
        val FAILURE_RETRY_MILLIS = TimeUnit.DAYS.toMillis(1)
        const val MAX_PARALLEL_AVATAR_CHECKS = 3
    }
}
