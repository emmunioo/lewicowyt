package pl.lewicowyt.notifier.community.push

import android.content.Context
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.random.asKotlinRandom
import pl.lewicowyt.notifier.AppGraph

/**
 * Trwałe prace push serwerów społeczności: (1) budzik z ZEWNĘTRZNEGO dystrybutora UnifiedPush / FCM (obsługę
 * deleguje do [CommunityWakeProcessor]), (2) rejestracja endpointu w serwerze, (3) rotacja tożsamości nadawcy,
 * (4) „Nowa tożsamość", (5) opóźnione powiadomienia po otwarciu „Godzin powiadomień". Budziki WBUDOWANEGO
 * dystrybutora omijają WorkManagera (szybka ścieżka: [PushInbox] → [CommunityWakeProcessor.drainInbox]).
 */
class CommunityPushWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        AppGraph.initialize(applicationContext)
        val action = inputData.getString(KEY_ACTION) ?: ACTION_REFRESH

        if (action == ACTION_REGISTER) {
            // C-02: nowy endpoint jednej INSTANCJI (np. po rotacji serwera A) zgłaszamy TYLKO jej serwerowi —
            // rejestracja u wszystkich naraz pozwalałaby B skorelować „rejestracja tuż po budziku A".
            val inst = inputData.getString(KEY_INSTANCE)?.takeIf { it.isNotBlank() }
            if (inst != null) {
                val ok = CommunityPushRegistry.serverForInstance(applicationContext, inst)
                    ?.let { CommunityPushManager.registerEndpointWithServer(applicationContext, it) } ?: true
                // D-04: nieudana rejestracja nowego adresu (serwer/Tor niedostępny) — ponawiamy z backoffem
                if (!ok) return if (runAttemptCount < MAX_ROTATE_ATTEMPTS) Result.retry() else Result.failure()
            } else {
                CommunityPushManager.registerEndpointWithAllServers(applicationContext)
            }
            return Result.success()
        }

        if (action == ACTION_DRAIN_INBOX) {
            // siatka bezpieczeństwa szybkiej ścieżki: budziki, których obsługa w odbiorniku nie skończyła się
            // (proces zabity) — zwykle skrzynka jest już pusta i praca kończy się od razu
            CommunityWakeProcessor.drainInbox(applicationContext)
            return Result.success()
        }

        if (action == ACTION_ROTATE) {
            return doRotate(inputData.getString(KEY_SENDER))
        }

        if (action == ACTION_ROTATE_IDENTITY) {
            // 1. migawka STAREJ tożsamości dostarczania PRZED rotacją (po rotacji stary token FCM znika)
            val pending = CommunityPushManager.snapshotForUnregister(applicationContext)
            // 2. wyczyść SUPERCOOKIE sterowane przez serwer: zapamiętane adresy/alternatywy (community_endpoints)
            //    oraz zaległą kolejkę wyrejestrowań z poprzednich cykli (F-02). Nowe trafią niżej.
            runCatching {
                applicationContext.getSharedPreferences("community_endpoints", Context.MODE_PRIVATE).edit().clear().apply()
            }
            runCatching { CommunityPushRegistry.takePendingUnregister(applicationContext) }
            // 3. NOWA tożsamość Tor (nowe obwody) — kolejny ruch .onion pójdzie inną trasą
            runCatching { EmbeddedTor.newIdentity(applicationContext) }
            // Q-03: po NEWNYM eksmituj pulę połączeń Tora (jak F-15 w CommunityUnregisterWorker) — inaczej
            // rejestracja NOWEJ tożsamości mogła pojechać utrzymanym keep-alive gniazdem (tym samym obwodem),
            // którym wcześniej szła rejestracja starej, i serwer .onion skleiłby obie.
            runCatching { CommunityTorClient.get(applicationContext)?.connectionPool?.evictAll() }
            // 4. nowy Firebase Installation ID (żeby stary i nowy token nie dzieliły prefiksu FID)
            runCatching { CommunityPushManager.resetFirebaseInstallations(applicationContext) }
            // 5. rotacja klucza E2E + token1 + rejestracja NOWEJ tożsamości OD RAZU (rotateAndReregister rotuje E2E)
            runCatching { CommunityPushManager.rotateAndReregister(applicationContext) }
            // Q-04: wyrejestrowujemy tylko adresy, które PO rotacji nie są już bieżące. Przy nieudanej rotacji
            // (revertRotation) stary temat LEP zostaje aktywny — jego wyrejestrowanie odcinało push serwera.
            val stillActive = runCatching { CommunityPushManager.snapshotForUnregister(applicationContext) }
                .getOrDefault(emptyList()).toSet()
            val stale = pending.filterNot { it in stillActive }
            // 6. STARĄ tożsamość wyrejestruj w LOSOWYM momencie 1 s–5 min później (rozspójnienie w czasie)
            if (stale.isNotEmpty()) {
                CommunityPushRegistry.addPendingUnregister(applicationContext, stale)
                CommunityUnregisterWorker.schedule(applicationContext)
            }
            return Result.success()
        }

        // Obsługa budzika (odszyfrowanie, film serwera głównego, odświeżenie nadawcy, powiadomienie, rotacja)
        // jest wspólna dla tej pracy (zewnętrzny dystrybutor UnifiedPush, FCM, opóźnione „Godziny powiadomień")
        // i dla szybkiej ścieżki wbudowanego dystrybutora (PushInbox → CommunityWakeProcessor, bez WorkManagera).
        val blob = inputData.getString(KEY_BLOB) ?: readBlobFile(applicationContext, inputData.getString(KEY_BLOB_FILE))
        CommunityWakeProcessor.process(
            applicationContext,
            blob = blob,
            instance = inputData.getString(KEY_INSTANCE),
            senderHint = inputData.getString(KEY_SENDER_HINT),
        )
        return Result.success()
    }

    private suspend fun doRotate(senderId: String?): Result {
        // R-05: rotacja (token1/endpoint + klucz E2E) TYLKO dla nadawcy budzika. Null = nieznany nadawca
        // (np. decoy) → nic nie rotujemy.
        if (senderId != null) {
            // A-28: rotacja dwufazowa — nowy klucz/temat zatwierdzany dopiero po 2xx z serwera. Porażka (sieć,
            // Tor niegotowy, kod != 2xx) = stara tożsamość zostaje ważna, a praca jest ponawiana z backoffem.
            val ok = runCatching {
                CommunityPushManager.rotateAndReregister(applicationContext, onlyServerId = senderId)
            }.getOrDefault(false)
            if (!ok && runAttemptCount < MAX_ROTATE_ATTEMPTS) return Result.retry()
        }
        return Result.success()
    }

    companion object {
        // losowy rozrzut opóźnień ma odcinać korelację czasową (kto kiedy się rejestruje) — z SecureRandom, nie przewidywalnego PRNG
        private val JITTER_RNG = SecureRandom().asKotlinRandom()

        private const val KEY_ACTION = "action"
        private const val KEY_BLOB = "blob"
        private const val KEY_INSTANCE = "instance" // R-10: instancja UnifiedPush → nadawca bez E2E
        private const val KEY_SENDER = "sender" // R-05: nadawca budzika — rotujemy TYLKO jego
        private const val KEY_SENDER_HINT = "sender_hint" // C-01: serwer, z którego kanału (temat LEP / projekt FCM) przyszedł budzik
        const val ACTION_REFRESH = "refresh"
        const val ACTION_REGISTER = "register"
        const val ACTION_ROTATE = "rotate"
        const val ACTION_ROTATE_IDENTITY = "rotate_identity"
        private const val ACTION_DRAIN_INBOX = "drain_inbox"
        // siatka startuje z opóźnieniem — szybka ścieżka zwykle kończy obsługę w kilka sekund
        private const val INBOX_SAFETY_NET_DELAY_MS = 30_000L
        private const val KEY_BLOB_FILE = "blob_file" // A-30: blob za duży na inputData → plik w noBackupFilesDir
        internal const val MAX_FILE_BLOB_CHARS = 64 * 1024 // budzik E2E z miniaturą mieści się z zapasem
        private const val MAX_ROTATE_ATTEMPTS = 5

        private fun blobDir(context: Context) = File(context.noBackupFilesDir, "push_blobs")

        private fun readBlobFile(context: Context, name: String?): String? {
            if (name.isNullOrBlank() || !name.matches(Regex("^[a-z0-9]{1,64}$"))) return null
            val f = File(blobDir(context), name)
            return runCatching { f.readText().takeIf { it.length <= MAX_FILE_BLOB_CHARS } }
                .getOrNull()
                .also { runCatching { f.delete() } }
        }

        /** A-31: opóźnione skanowanie lokalnej bazy po otwarciu „Godzin powiadomień" (bez sieci do serwerów). */
        internal fun enqueueActiveHoursRelease(context: Context, delayMillis: Long) {
            runCatching {
                val request = OneTimeWorkRequestBuilder<CommunityPushWorker>()
                    .setInputData(workDataOf(KEY_ACTION to ACTION_REFRESH))
                    .setInitialDelay(delayMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
                    .build()
                WorkManager.getInstance(context)
                    .enqueueUniqueWork("community-push-active-hours", ExistingWorkPolicy.REPLACE, request)
            }
        }

        // WorkManager.Data ma limit ~10 KB; blob większy niż to i tak jest podejrzany (FCM tnie do ~4 KB).
        // Ucinamy PRZED workDataOf, żeby nie rzucił wyjątku i nie wywrócił odbiornika pusha (F-13).
        private const val MAX_BLOB_CHARS = 9_000

        /**
         * blob = zaszyfrowany ładunek budzika (do identyfikacji nadawcy); null przy rejestracji/plain.
         * instance = instancja UnifiedPush, z której przyszedł budzik (R-10: pozwala ustalić nadawcę bez E2E).
         */
        fun enqueue(
            context: Context,
            action: String = ACTION_REFRESH,
            blob: String? = null,
            instance: String? = null,
            senderHint: String? = null,
        ) {
            // A-30: blob ≤ 9000 znaków idzie w inputData; większy (do 64 KB) — plikiem; jeszcze większy pomijamy
            // (budzik i tak tylko wyzwala odświeżenie) — nigdy nie wywracamy odbiornika.
            val safeBlob = blob?.takeIf { it.length <= MAX_BLOB_CHARS }
            val blobFile = if (safeBlob == null && blob != null && blob.length <= MAX_FILE_BLOB_CHARS) {
                runCatching {
                    val name = MessageDigest.getInstance("SHA-256").digest(blob.toByteArray())
                        .joinToString("") { "%02x".format(it) }.take(40)
                    blobDir(context).mkdirs()
                    // B-90: pliki po pracach anulowanych przed odczytem — sprzątamy starsze niż doba
                    val staleBefore = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
                    blobDir(context).listFiles().orEmpty()
                        .filter { it.isFile && it.lastModified() < staleBefore }
                        .forEach { it.delete() }
                    File(blobDir(context), name).writeText(blob)
                    name
                }.getOrNull()
            } else {
                null
            }
            runCatching {
                val request = OneTimeWorkRequestBuilder<CommunityPushWorker>()
                    .setInputData(
                        workDataOf(
                            KEY_ACTION to action,
                            KEY_BLOB to safeBlob,
                            KEY_BLOB_FILE to blobFile,
                            KEY_INSTANCE to instance,
                            KEY_SENDER_HINT to senderHint,
                        ),
                    )
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .apply {
                        // N-02: expedited TYLKO od Androida 12 (API 31). Niżej WorkManager odpala je jako usługę
                        // pierwszoplanową i woła getForegroundInfo() (domyślnie rzuca IllegalStateException),
                        // przez co CAŁA obsługa push padała przed doWork() na Androidzie 8–11.
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                        }
                    }
                    .build()
                // M-04: ODŚWIEŻANIE ma nazwę unikalną PER BLOB — dwa różne budziki (różny ładunek) tworzą dwie
                // osobne prace i OBA się wykonają (żaden blob/film nie ginie). Identyczny duplikat w krótkim
                // oknie jest deduplikowany przez KEEP. Pozostałe akcje mają jedną nazwę (koalescencja).
                // D-02: klucz łączy INSTANCJĘ (UnifiedPush — osobna per serwer) ORAZ skrót ładunku (FCM/E2E) —
                // dwa serwery UP z identycznym plaintextem `{"t":"1"}` mają różne nazwy, a dwa różne budziki
                // jednego serwera też (żaden nie zostanie zjedzony przez KEEP).
                val refreshKey = refreshWorkKey(instance, senderHint, safeBlob, blobFile)
                val uniqueName = when (action) {
                    ACTION_REFRESH -> "community-push-refresh-$refreshKey"
                    // C-02: rejestracja per instancja — nowe endpointy dwóch serwerów nie zlewają się w jedną pracę
                    ACTION_REGISTER -> "community-push-register" + (instance?.takeIf { it.isNotBlank() }?.let { "-" + it.hashCode().toUInt().toString(36) } ?: "")
                    else -> "community-push-$action"
                }
                WorkManager.getInstance(context).enqueueUniqueWork(
                    uniqueName,
                    ExistingWorkPolicy.KEEP,
                    request,
                )
            }
        }

        /**
         * D-02: nazwa pracy odświeżenia = KANAŁ (instancja UP / serwer z tematu LEP) + SKRÓT ŁADUNKU. Dwa różne
         * budziki tego samego serwera (np. dwa filmy z miniaturami, blob w pliku) dają dwie prace — KEEP gubi tylko
         * identyczne duplikaty. Wcześniej klucz bywał stały dla serwera i kolejne budziki przepadały.
         */
        internal fun refreshWorkKey(instance: String?, senderHint: String?, safeBlob: String?, blobFile: String?): String {
            val base = instance?.takeIf { it.isNotBlank() } ?: senderHint?.takeIf { it.isNotBlank() } ?: ""
            val payload = safeBlob?.let { b ->
                MessageDigest.getInstance("SHA-256").digest(b.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
            } ?: blobFile ?: "noblob"
            return (if (base.isEmpty()) "" else base.hashCode().toUInt().toString(36) + "-") + payload
        }

        /**
         * M-04: rotacja tożsamości jako OSOBNA, unikalna praca z losowym opóźnieniem 1–15 s (initialDelay).
         * KEEP: burst kolejnych pushy zlewa się w jedną zaplanowaną rotację (nie głodzimy jej, nie robimy jej
         * N razy). Oddzielenie od odświeżania sprawia, że rotacja nie blokuje przetwarzania kolejnych blobów.
         */
        /**
         * Siatka bezpieczeństwa szybkiej ścieżki (odbiornik zewnętrznego dystrybutora): gdyby proces zginął w trakcie
         * obsługi budzika [PushInbox], ta praca obsłuży go ponownie. Bez warunku sieci — powiadomienie o filmie
         * z miniaturą w ładunku E2E sieci nie potrzebuje. Unikalna per budzik (KEEP: duplikat nie dubluje pracy).
         */
        fun enqueueInboxDrain(context: Context, entryId: String) {
            runCatching {
                val request = OneTimeWorkRequestBuilder<CommunityPushWorker>()
                    .setInputData(workDataOf(KEY_ACTION to ACTION_DRAIN_INBOX))
                    .setInitialDelay(INBOX_SAFETY_NET_DELAY_MS, TimeUnit.MILLISECONDS)
                    .build()
                WorkManager.getInstance(context)
                    .enqueueUniqueWork("community-push-inbox-${entryId.take(16)}", ExistingWorkPolicy.KEEP, request)
            }
        }

        fun enqueueRotate(context: Context, senderId: String?) {
            if (senderId.isNullOrBlank()) return // R-05: brak nadawcy (decoy) → nic nie rotujemy
            runCatching {
                val request = OneTimeWorkRequestBuilder<CommunityPushWorker>()
                    .setInputData(workDataOf(KEY_ACTION to ACTION_ROTATE, KEY_SENDER to senderId))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInitialDelay(JITTER_RNG.nextLong(1_000L, 15_001L), TimeUnit.MILLISECONDS)
                    .build()
                // R-05: unikalna praca PER NADAWCA — rotacje różnych serwerów się nie zlewają (KEEP tylko w obrębie
                // tego samego nadawcy, by burst jego budzików dał jedną rotację).
                WorkManager.getInstance(context).enqueueUniqueWork(
                    "community-push-rotate-" + senderId.hashCode().toUInt().toString(36),
                    ExistingWorkPolicy.KEEP,
                    request,
                )
            }
        }
    }
}
