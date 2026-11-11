package pl.lewicowyt.notifier.community.push

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import java.security.SecureRandom
import kotlin.random.asKotlinRandom
import pl.lewicowyt.notifier.AppGraph

/**
 * OPÓŹNIONE wyrejestrowanie STAREJ tożsamości dostarczania po zmianie tożsamości Tor.
 *
 * Nowa rejestracja idzie od razu (żeby push dalej działał), a wyrejestrowanie starej odpalamy w
 * LOSOWYM momencie od 1 s do 5 min później — dzięki temu z perspektywy serwera „stary token zniknął"
 * i „nowy token się pojawił" nie są ze sobą skorelowane w czasie (utrudnia powiązanie starej i nowej
 * tożsamości). Robota w WorkManager, więc przeżywa ubicie procesu; wymaga tylko sieci.
 */
class CommunityUnregisterWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        AppGraph.initialize(applicationContext)
        val pending = CommunityPushRegistry.takePendingUnregister(applicationContext)
        if (pending.isEmpty()) return Result.success()
        // Izolacja obwodu: dla celów .onion wymuś ŚWIEŻĄ tożsamość Tor tuż przed wysyłką, żeby
        // wyrejestrowanie starego NIE dzieliło obwodu/wyjścia z wcześniejszą rejestracją nowego
        // (inaczej serwer mógłby skleić oba po źródle mimo losowego opóźnienia).
        if (pending.any { CommunityTorClient.isOnion(it.url) }) {
            runCatching { EmbeddedTor.newIdentity(applicationContext) }
            // KLUCZOWE (F-15): po NEWNYM eksmituj pulę połączeń Tora, żeby wyrejestrowanie starej tożsamości
            // NIE pojechało tym samym, utrzymanym keep-alive gniazdem TCP co rejestracja nowej (inaczej
            // serwer sklei je po źródle mimo losowego opóźnienia). Nowe żądanie otworzy świeży obwód.
            runCatching { CommunityTorClient.get(applicationContext)?.connectionPool?.evictAll() }
        }
        // A-28b: kolejka jest zdejmowana atomowo (dwa workery nie wyślą tego samego), ale wpisy, których nie
        // udało się dostarczyć (sieć/Tor/5xx), wracają do kolejki i praca jest ponawiana z backoffem — wcześniej
        // porażka = trwała utrata wyrejestrowania (token/endpoint zostawał u serwera).
        val failed = runCatching { CommunityPushManager.sendUnregistrations(applicationContext, pending) }
            .getOrDefault(pending)
        if (failed.isNotEmpty() && runAttemptCount < MAX_ATTEMPTS) {
            CommunityPushRegistry.addPendingUnregister(applicationContext, failed)
            return Result.retry()
        }
        return Result.success()
    }

    companion object {
        // losowy rozrzut opóźnień ma odcinać korelację czasową (kto kiedy się rejestruje) — z SecureRandom, nie przewidywalnego PRNG
        private val JITTER_RNG = SecureRandom().asKotlinRandom()

        private const val MIN_DELAY_MS = 1_000L          // 1 sekunda
        private const val MAX_DELAY_MS = 5L * 60 * 1_000 // 5 minut
        private const val MAX_ATTEMPTS = 8

        /** Planuje wyrejestrowanie starej tożsamości na losowy moment z przedziału 1 s – 5 min. */
        fun schedule(context: Context) {
            val delayMs = JITTER_RNG.nextLong(MIN_DELAY_MS, MAX_DELAY_MS + 1)
            val request = OneTimeWorkRequestBuilder<CommunityUnregisterWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            // KEEP (nie REPLACE): kolejka jest kumulatywna i opróżniana w całości, więc gdy worker już
            // czeka, zostawiamy go — odpali w swoim pierwotnym losowym terminie i wyśle też wpisy dołożone
            // później. REPLACE przy częstych rotacjach odkładałby wyrejestrowanie w nieskończoność (F-15).
            WorkManager.getInstance(context).enqueueUniqueWork(
                "community-unregister-old",
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
