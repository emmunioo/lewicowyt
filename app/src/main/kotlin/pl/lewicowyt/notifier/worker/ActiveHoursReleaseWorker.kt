package pl.lewicowyt.notifier.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.LocalTime
import java.util.concurrent.TimeUnit
import pl.lewicowyt.notifier.AppGraph
import pl.lewicowyt.notifier.data.AppSettings
import pl.lewicowyt.notifier.data.isNotificationEnabledFor
import pl.lewicowyt.notifier.notifications.ActiveHours
import pl.lewicowyt.notifier.notifications.toNotificationCandidate

/**
 * dowozi powiadomienia wstrzymane poza „godzinami powiadomień". odpala się na
 * otwarcie okna (albo od razu, gdy już w oknie / funkcja wyłączona). zbiera
 * wszystkie oczekujące wpisy skrzynki (notified=0 — czyli te, które jeszcze NIE
 * miały powiadomienia; ręczny sync oznacza swoje jako dostarczone) i wysyła je
 * JEDNYM zbiorczym powiadomieniem (forceSummary), niezależnie od liczby.
 * przetrwa ubicie procesu, bo to WorkManager.
 */
class ActiveHoursReleaseWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        AppGraph.initialize(applicationContext)
        val graph = AppGraph
        val settings = graph.preferences.current()

        val nowMinutes = LocalTime.now().let { it.hour * 60 + it.minute }
        if (!ActiveHours.isWithin(settings, nowMinutes)) {
            // okno wciąż zamknięte (np. user zmienił godziny) – przekładamy
            schedule(applicationContext, settings)
            return Result.success()
        }

        val creatorIds = settings.selectedCreatorIds
        if (creatorIds.isEmpty()) return Result.success()

        val (allowed, suppressed) = graph.database.pendingNotifications(creatorIds)
            .partition { settings.isNotificationEnabledFor(it.creatorId, it.kind) }
        // wpis wyłączonego rodzaju nie może czaić się ukryty i odpalić starego
        // powiadomienia po ponownym włączeniu opcji
        if (suppressed.isNotEmpty()) {
            graph.database.markVideosNotified(suppressed.map { it.videoId })
        }

        val candidates = allowed
            .map { it.toNotificationCandidate() }
            .distinctBy { it.entry.id }
        if (candidates.isEmpty()) return Result.success()

        // R-20: dowóz po oknie ciszy zawsze jednym ZBIORCZYM powiadomieniem (nie zalewamy
        // pojedynczymi przy <=3), i tylko wpisami, które jeszcze nie miały powiadomienia.
        val delivery = graph.notifications.notifyBatch(candidates, forceSummary = true) { candidate ->
            val latest = graph.preferences.current()
            candidate.creator.id in latest.selectedCreatorIds &&
                latest.isNotificationEnabledFor(candidate.creator.id, candidate.kind)
        }
        graph.database.markVideosNotified(delivery.deliveredVideoIds)
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "active-hours-release"

        /**
         * planuje dowóz wstrzymanych powiadomień na najbliższe otwarcie okna (albo
         * od razu, gdy już w oknie / funkcja off). REPLACE, więc zmiana godzin przez
         * usera aktualizuje termin, a wiele wstrzymanych nie mnoży zadań.
         */
        fun schedule(context: Context, settings: AppSettings) {
            val delayMillis = ActiveHours.millisUntilNextOpen(settings, System.currentTimeMillis())
            val request = OneTimeWorkRequestBuilder<ActiveHoursReleaseWorker>()
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                // C-19: bez wymogu sieci — wpisy są już w bazie; miniatury i tak ładujemy w miarę możliwości
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
