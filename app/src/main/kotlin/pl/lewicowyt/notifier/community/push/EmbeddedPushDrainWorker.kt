package pl.lewicowyt.notifier.community.push

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Tryb OSZCZĘDNY wbudowanego UnifiedPush: zamiast trzymać stały long-poll, co ~15 min drenuje LEP
 * (`wait=0` → natychmiastowy zwrot zaległych budzików). Bez stałego paska i baterii w tle; powiadomienia
 * z opóźnieniem do interwału. Budziki trafiają do [PushInbox] i są obsługiwane od razu w tej pracy
 * ([CommunityWakeProcessor.drainInbox]; WorkManager trzyma blokadę wybudzenia do jej końca).
 * W trybie natychmiastowym ta sama praca jest siatką bezpieczeństwa: podnosi usługę i obsługuje budziki,
 * które zostały w skrzynce po zabitym procesie.
 */
class EmbeddedPushDrainWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        if (PushSettings.mode(context) != PushSettings.Mode.EMBEDDED) return Result.success()
        val subs = EmbeddedPush.subscriptions(context)
        if (subs.isEmpty()) return Result.success()
        // B-06: w trybie natychmiastowym worker jest siatką bezpieczeństwa — gdy usługa działa, nic nie robi;
        // gdy nie działa (restart telefonu, aktualizacja, zabita przez system), próbuje ją podnieść i drenuje sam.
        if (PushSettings.embedded(context) == PushSettings.Embedded.INSTANT) {
            if (EmbeddedPushService.running) return Result.success()
            EmbeddedPushService.start(context)
        }
        // budziki, które zostały w skrzynce po zabitym procesie — zanim pobierzemy nowe
        CommunityWakeProcessor.drainInbox(context)
        // B-10: tematy .onion wymagają gotowego Tora — bez czekania poll zwracał od razu błąd.
        val torReady = if (subs.any { CommunityTorClient.isOnion(it.base) }) {
            runCatching { CommunityTorClient.prepare(context) }.getOrDefault(false)
        } else {
            true
        }
        for (sub in subs) {
            if (!torReady && CommunityTorClient.isOnion(sub.base)) continue
            runCatching { EmbeddedPush.poll(context, sub, hold = false) }
        }
        CommunityWakeProcessor.drainInbox(context)
        return Result.success()
    }

    companion object {
        private const val NAME = "embedded-push-drain"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<EmbeddedPushDrainWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
