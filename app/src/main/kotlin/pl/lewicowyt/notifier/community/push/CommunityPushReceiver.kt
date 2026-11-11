package pl.lewicowyt.notifier.community.push

import android.content.Context
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.unifiedpush.android.connector.MessagingReceiver

/**
 * Odbiornik ZEWNĘTRZNEGO dystrybutora UnifiedPush (np. ntfy). Budzik obsługujemy od razu (szybka ścieżka przez
 * [PushInbox]); rejestrację endpointu i rotację przejmuje [CommunityPushWorker]. Wątku odbiornika nie blokujemy.
 */
class CommunityPushReceiver : MessagingReceiver() {

    override fun onNewEndpoint(context: Context, endpoint: String, instance: String) {
        // #8 (M-1): endpoint należy do KONKRETNEJ instancji = konkretnego serwera. Zapisujemy go pod tą
        // instancją (nie wspólnie), a worker ACTION_REGISTER wyśle każdemu serwerowi tylko jego własny adres.
        CommunityPushManager.saveEndpoint(context, instance, endpoint)
        CommunityPushWorker.enqueue(context, CommunityPushWorker.ACTION_REGISTER, instance = instance)
    }

    override fun onMessage(context: Context, message: ByteArray, instance: String) {
        // treści z pusha nie ufamy — to tylko budzik; świeży manifest apka dociąga sama po HTTPS.
        val blob = runCatching { String(message).takeIf { it.isNotBlank() } }.getOrNull()
        // R-10: instancja UnifiedPush należy do JEDNEGO serwera — to on jest nadawcą (także budzika bez E2E).
        val server = CommunityPushRegistry.serverForInstance(context, instance)
        // SZYBKA ŚCIEŻKA (jak w dystrybutorze wbudowanym): budzik trwale do skrzynki i obsługa OD RAZU, w czasie
        // trwania tego broadcastu (system trzyma blokadę wybudzenia do finish()). Praca WorkManagera miała warunek
        // „sieć dostępna", więc w Doze nawet powiadomienie o filmie — które sieci nie potrzebuje (miniatura jest
        // w ładunku E2E) — czekało na okno serwisowe. Praca zostaje jako siatka bezpieczeństwa.
        if (blob != null && server != null && PushInbox.of(context).put(server, blob)) {
            CommunityPushWorker.enqueueInboxDrain(context, PushInbox.idOf(server, blob))
            val pending = goAsync()
            val app = context.applicationContext
            val work = CommunityWakeProcessor.scope.launch { CommunityWakeProcessor.drainInbox(app) }
            // Broadcast kończymy po obsłudze albo najpóźniej po RECEIVER_BUDGET_MS (< 10 s — limit broadcastu
            // pierwszoplanowego). Obsługi NIE przerywamy: dokończy się w procesie, a gdyby proces zginął, wpis
            // ze skrzynki obsłuży praca-siatka.
            CommunityWakeProcessor.scope.launch {
                try {
                    withTimeoutOrNull(RECEIVER_BUDGET_MS) { work.join() }
                } finally {
                    pending.finish()
                }
            }
            return
        }
        // nieznana instancja / pusty budzik / nieudany zapis — dawna, trwała ścieżka przez WorkManager
        CommunityPushWorker.enqueue(context, CommunityPushWorker.ACTION_REFRESH, blob, instance)
    }

    override fun onRegistrationFailed(context: Context, instance: String) {
        // brak pusha → zostaje fallback-polling; nic nie robimy
    }

    override fun onUnregistered(context: Context, instance: String) {
        // tylko ta instancja straciła rejestrację — czyścimy JEJ endpoint, inne serwery zostają nietknięte
        CommunityPushRegistry.forgetInstance(context, instance)
    }

    private companion object {
        const val RECEIVER_BUDGET_MS = 9_000L
    }
}
