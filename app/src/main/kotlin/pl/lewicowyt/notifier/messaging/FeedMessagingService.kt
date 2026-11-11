package pl.lewicowyt.notifier.messaging

import android.content.Intent
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import pl.lewicowyt.notifier.AppGraph
import pl.lewicowyt.notifier.community.push.CommunityPushWorker
import pl.lewicowyt.notifier.community.push.CommunityPushManager
import pl.lewicowyt.notifier.community.push.PushSettings

/**
 * Odbiór AWARYJNEGO push przez Google (FCM) — tylko budziki serwerów społeczności, które same deklarują
 * `push.fcm` (własny projekt Firebase), i tylko przy włączonym „Awaryjnym push przez Google".
 *
 * Serwer domyślny NIE używa FCM: dawny broadcast do tematu "feed" projektu domyślnego został usunięty
 * (serwer i aplikacja) — powiadomienia o filmach przychodzą wyłącznie przez UnifiedPush. Wiadomości
 * spoza formatu budzika (np. stare {c,v} z tematu "feed") są ignorowane.
 *
 * onMessageReceived ma krótkie okno, więc nic tu nie robimy po sieci – wrzucamy trwałe zadanie do
 * WorkManagera ([CommunityPushWorker]), które przeżyje ubicie procesu.
 */
class FeedMessagingService : FirebaseMessagingService() {

    /**
     * N-01: usuwamy pola „notification message" (gcm.n.* / gcm.notification.*) ZANIM obsłuży je SDK Firebase.
     * Inaczej przy aplikacji w tle SDK sam zbudowałby powiadomienie i SAM POBRAŁ obrazek z adresu podanego przez
     * serwer — z PRAWDZIWEGO IP, poza Torem i poza filtrami aplikacji (ujawnienie subskrybenta). Po usunięciu
     * tych pól wiadomość staje się czysto „data" i trafia normalnie do onMessageReceived, gdzie my decydujemy.
     * (handleIntent jest publiczne, ale nieoficjalne — przy aktualizacji Firebase sprawdzić, czy nadal działa.)
     */
    override fun handleIntent(intent: Intent) {
        runCatching {
            intent.extras?.keySet()
                ?.filter { it.startsWith("gcm.n.") || it.startsWith("gcm.notification.") }
                ?.toList()
                ?.forEach(intent::removeExtra)
        }
        super.handleIntent(intent)
    }

    /**
     * #7: token FCM projektu DOMYŚLNEGO został odświeżony. Projekt domyślny nie służy już do niczego (serwer
     * domyślny nie używa FCM), więc przy wyłączonym awaryjnym FCM kasujemy token (nie trzymamy żywego adresu
     * kontaktu z Google), a przy włączonym tylko upewniamy się, że dawna subskrypcja „feed" jest zdjęta.
     * Tokeny serwerów społeczności to osobne FirebaseApp (op_*) i rotuje je worker po każdym budziku.
     */
    override fun onNewToken(token: String) {
        runCatching {
            AppGraph.initialize(applicationContext)
            if (PushSettings.fcmFallbackEnabled(applicationContext)) {
                CommunityPushManager.retireDefaultFeedTopic(applicationContext)
            } else {
                FirebaseMessaging.getInstance().deleteToken()
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        runCatching {
            AppGraph.initialize(applicationContext)
            val data = message.data
            // Budziki społeczności przychodzą z WŁASNYCH projektów Firebase serwerów (osobne FirebaseApp),
            // ale trafiają do tej samej usługi. Rozpoznajemy je po ładunku: zaszyfrowany "e" ALBO dowolne "t".
            // R-10: honorujemy KAŻDE "t" (nie tylko "1") — w tym decoy {"t":"decoy"} (protokół l.370-375), żeby
            // decoy wyzwolił pełną procedurę (rotację) i był nieodróżnialny od prawdziwego budzika.
            if (data.containsKey("e") || data.containsKey("t")) {
                // C-01: jawny budzik {"t":…} bez E2E — nadawcę ustalamy po projekcie FCM (gdy jednoznaczny)
                CommunityPushWorker.enqueue(
                    applicationContext, CommunityPushWorker.ACTION_REFRESH, data["e"],
                    senderHint = CommunityPushManager.serverForFcmSender(applicationContext, message.senderId),
                )
                return
            }
            // Nic innego nie przyjmujemy: serwer domyślny nie wysyła przez FCM, a bezpośrednie {c,v} od obcego
            // projektu (zna token1 z rejestracji) nie może udawać serwera głównego (F-14, A-46).
        }
    }
}
