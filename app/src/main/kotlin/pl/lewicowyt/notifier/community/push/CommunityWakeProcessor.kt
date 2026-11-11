package pl.lewicowyt.notifier.community.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalTime
import kotlin.random.asKotlinRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import pl.lewicowyt.notifier.AppGraph
import pl.lewicowyt.notifier.network.PrivacySettings
import pl.lewicowyt.notifier.R
import pl.lewicowyt.notifier.community.CommunityContentFilter
import pl.lewicowyt.notifier.community.CommunityUpdateKind
import pl.lewicowyt.notifier.community.CommunityUpdatePolicy
import pl.lewicowyt.notifier.images.CommunityImageLoader
import pl.lewicowyt.notifier.messaging.NewVideoWorker
import pl.lewicowyt.notifier.messaging.ServerNotificationHandler
import pl.lewicowyt.notifier.model.VideoKind
import pl.lewicowyt.notifier.notifications.ActiveHours
import pl.lewicowyt.notifier.notifications.WearNotifications

/**
 * Obsługa JEDNEGO budzika od serwera społeczności — wspólna dla wszystkich dróg dostarczenia:
 *  - UnifiedPush (dystrybutor wbudowany LEP i zewnętrzny, np. ntfy): SZYBKA ŚCIEŻKA [drainInbox] — budzik zapisany
 *    trwale w [PushInbox] obsługujemy od razu w procesie, bez czekania na WorkManager/JobScheduler (od Androida 16
 *    zadania podlegają limitom czasu nawet przy działającej usłudze pierwszoplanowej, na Androidzie 8–11 praca nie
 *    jest przyspieszona, a praca z warunkiem sieci czekała w Doze na okno serwisowe);
 *  - FCM / „Godziny powiadomień" / siatka bezpieczeństwa skrzynki: [CommunityPushWorker] (trwała praca).
 *
 * Kroki (bez zmian względem dawnego workera — kolejność i opóźnienia są częścią ochrony przed korelacją):
 * identyfikacja nadawcy po E2E → ochrona przed powtórką → film serwera głównego (powiadomienie OD RAZU, bez sieci)
 * → losowe 0,5–5 s → odświeżenie WYŁĄCZNIE nadawcy → powiadomienie o nowych wpisach → rotacja tożsamości nadawcy.
 */
internal object CommunityWakeProcessor {
    private const val TAG = "CommunityWake"

    /** Zakres obsługi budzików poza usługą (odbiornik zewnętrznego dystrybutora) — przeżywa pojedynczy broadcast. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * @param blob ciało budzika (szyfrogram E2E albo jawny mały sygnał); null = samo przejrzenie lokalnej bazy
     *   (opóźnione „Godziny powiadomień").
     * @param instance instancja zewnętrznego dystrybutora UnifiedPush (R-10: nadawca budzika bez E2E).
     * @param senderHint serwer, z którego kanału przyszedł budzik (temat LEP / projekt FCM) — C-01.
     * @param skipReplayCheck true WYŁĄCZNIE dla wpisu [PushInbox] odzyskanego po śmierci procesu w trakcie obsługi:
     *   ochrona przed powtórką mogła go już oznaczyć, zanim proces padł — bez tego budzik by przepadł.
     */
    suspend fun process(
        context: Context,
        blob: String?,
        instance: String?,
        senderHint: String?,
        skipReplayCheck: Boolean = false,
    ) {
        AppGraph.initialize(context)
        // Identyfikacja nadawcy: przy zaszyfrowanym budziku (wspólny token1 rodziny operatora)
        // działamy TYLKO gdy któryś SUBSKRYBOWANY serwer potrafi go odszyfrować. Budzik od serwera,
        // którego user nie subskrybuje (żaden klucz nie pasuje) → nic nie robimy (bez odświeżania).
        // plaintext budzika po odszyfrowaniu (JSON) — z niego bierzemy opcjonalną miniaturę.
        var payload: String? = null
        var senderId: String? = null
        if (!blob.isNullOrBlank()) {
            val serverIds = CommunityPushRegistry.servers(context).keys
            // T-03: nadawca i tekst jawny z JEDNEGO odszyfrowania (dawniej identify + drugie decrypt)
            val identified = CommunityE2E.identifyAndDecrypt(context, serverIds, blob)
            senderId = identified?.first
            // A-29 / B-09: ochrona przed powtórzeniem (replay) dotyczy TYLKO budzików E2E (szyfrogram jest
            // losowy, więc identyczny blob = powtórka) i jest kluczowana nadawcą. Jawne budziki bez E2E są
            // z natury identyczne (np. „odśwież”) — wcześniej drugi taki budzik (także od INNEGO serwera)
            // był w oknie po cichu gubiony.
            if (senderId != null) {
                val fresh = markBlobFresh(context, "$senderId|$blob")
                if (!fresh && !skipReplayCheck) return
            }
            // tylko blob, który uda się ODSZYFROWAĆ, daje plaintext (z niego miniatura/film)
            payload = identified?.second
            // T-13: świeżość z samego ładunku (pole `ts` dopisuje serwer) — budzik starszy niż okno to powtórka albo
            // bardzo spóźnione dostarczenie; odrzucamy go niezależnie od stanu rotacji klucza. Brak pola = jak dotąd.
            if (identified != null && isWakeStale(payload, System.currentTimeMillis(), if (skipReplayCheck) MAX_INBOX_AGE_MS else MAX_WAKE_AGE_MS)) return
        }
        // R-10: budzik bez E2E (UnifiedPush, protokół l.410) — nadawcę ustalamy po INSTANCJI UP (osobna per
        // serwer), więc odświeżymy właściwy serwer bez deszyfrowania. Gdy i tego brak (np. decoy FCM, protokół
        // l.375) — senderId zostaje null, ale NIE porzucamy budzika: niżej i tak zrobimy rotację (pełna
        // procedura, by decoy był nieodróżnialny od prawdziwego budzika).
        if (senderId == null) {
            instance?.takeIf { it.isNotBlank() }?.let { inst ->
                senderId = CommunityPushRegistry.serverForInstance(context, inst)
            }
        }
        // C-01: jawny budzik z tematu LEP (tryb wbudowany) albo z projektu FCM jednego serwera — nadawcę zna
        // kanał, którym przyszedł. Używamy go tylko, gdy E2E i instancja nic nie dały, i tylko dla serwera,
        // który wciąż jest zarejestrowany.
        if (senderId == null) {
            senderHint?.takeIf { it in CommunityPushRegistry.servers(context).keys }?.let { senderId = it }
        }
        // miniatura DOSTARCZONA W ŁADUNKU E2E (base64) — pokazujemy ją bez żadnego pobrania z sieci,
        // więc serwer nie widzi IP „kto właśnie otworzył powiadomienie" (zero korelacji obrazek↔push).
        val thumbnail = decodeThumbnail(payload)

        // Serwer GŁÓWNY działa jak serwer domyślny: jeśli ładunek niesie FILM, a nadawcą jest serwer
        // wskazany przez usera jako główny — wpuszczamy go w GŁÓWNY potok powiadomień (Historia +
        // powiadomienie o nowym filmie), z metadanymi/miniaturą od serwera (albo dociągnięciem z RSS).
        // To dzieje się PRZED losowym opóźnieniem niżej — powiadomienie o filmie pojawia się od razu.
        runCatching { maybeDeliverPrimaryVideo(context, senderId, payload, thumbnail) }

        val repo = AppGraph.communityRepository
        // M-14: odświeżamy TYLKO nadawcę budzika (gdy znany z E2E), a nie wszystkie serwery. Inaczej budzik
        // serwera A natychmiast wywoływałby ruch do serwera B (clearnet: z tego samego IP), co pozwala A i B
        // skorelować subskrybenta — kanał boczny niweczący per-serwerową niepowiązywalność. Pozostałe serwery
        // odświeżają się na własnym harmonogramie (ekran „Społeczność"/okresowe odświeżanie), niezależnie od pusha.
        // Mały losowy jitter przed pobraniem rozbija też korelację „dostarczenie budzika → fetch" dla obserwatora.
        // Odświeżamy po KAŻDYM rozpoznanym budziku (film, wpis i szum) — dzięki temu rodzaje budzików są dla
        // obserwatora sieci nieodróżnialne (R-10); pominięcie go po filmie zdradzałoby, który budzik był filmem.
        senderId?.let { id ->
            val sender = repo.contactableServers().firstOrNull { it.serverId == id }
            if (sender != null) {
                delay(JITTER_RNG.nextLong(500L, 5_001L))
                runCatching { repo.refresh(sender) } // aktualizuje bazę nadawcy (SHA-256), respektuje usunięcia
            }
        }
        // R-10: nadawca nieznany (decoy FCM, albo budzik bez E2E i bez znanej instancji) — NIE odświeżamy
        // wszystkich serwerów (kanał boczny M-14/R-05). Rotacja tożsamości i tak zajdzie niżej, więc decoy jest
        // nieodróżnialny od prawdziwego budzika na poziomie ruchu re-rejestracji.

        // po odświeżeniu czytamy z LOKALNEJ bazy istotne obce pozycje i decydujemy o powiadomieniu.
        // M-8: powiadamiamy TYLKO o NOWYCH pozycjach (nie powtarzamy tych samych przy każdym pushu) —
        // trzymamy zbiór już pokazanych kluczy w prefs. (restoredState zwraca manifest tylko po akceptacji
        // regulaminu, więc respektujemy też zgodę.)
        // A-31: „Godziny powiadomień" obowiązują też powiadomienia społeczności. Poza oknem NIC nie pokazujemy
        // i NIE oznaczamy jako pokazane — dowieziemy je po otwarciu okna (opóźnione skanowanie lokalnej bazy).
        val settingsNow = runCatching { AppGraph.preferences.current() }.getOrNull()
        val nowMinutes = LocalTime.now().let { it.hour * 60 + it.minute }
        if (settingsNow != null && !ActiveHours.isWithin(settingsNow, nowMinutes)) {
            CommunityPushWorker.enqueueActiveHoursRelease(
                context,
                ActiveHours.millisUntilNextOpen(settingsNow, System.currentTimeMillis()),
            )
        } else {
            // A-32: odczyt-modyfikacja-zapis zbioru pokazanych pod blokadą (równoległe obsługi w jednym procesie).
            SEEN_LOCK.withLock { notifyFresh(context, thumbnail) }
        }
        // M-04: rotację tożsamości zlecamy jako OSOBNĄ, unikalną pracę (nie trzymamy tu obsługi przez 1–15 s).
        // R-05: rotujemy TYLKO nadawcę (senderId). Gdy nadawca nieznany (decoy/plaintext bez instancji),
        // nie rotujemy nic — nie wysyłamy re-rejestracji do serwerów, które nic nie przysłały (anty-korelacja).
        // Losowe opóźnienie 1–15 s (jako initialDelay) nadal rozbija korelację „powiadomienie → nowa rejestracja".
        // T-12: serwery w zwykłym internecie widzą te same adresy IP przy starej i nowej rejestracji, więc rotacja po
        // KAŻDYM budziku kosztuje energię i ruch bez realnego zysku — rotujemy najwyżej raz na ROTATE_MIN_INTERVAL_MS.
        // Serwery .onion (Tor ukrywa IP) rotują jak dotąd po każdym budziku.
        if (shouldRotateNow(context, senderId)) CommunityPushWorker.enqueueRotate(context, senderId)
    }

    private fun shouldRotateNow(context: Context, senderId: String?): Boolean {
        if (senderId.isNullOrBlank()) return true
        val register = CommunityPushRegistry.servers(context)[senderId]?.register ?: return true
        // rejestracja przez Tor (opcja T-10) ukrywa IP także u zwykłych serwerów — rotacja ma wtedy pełny sens
        if (CommunityTorClient.isOnion(register) || PrivacySettings.registerViaTor(context)) return true
        val prefs = context.getSharedPreferences("community_push_rotation", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(senderId, 0L) < ROTATE_MIN_INTERVAL_MS) return false
        prefs.edit().putLong(senderId, now).apply()
        return true
    }

    /**
     * SZYBKA ŚCIEŻKA wbudowanego dystrybutora: obsługuje od razu wszystkie budziki czekające w [PushInbox]
     * (każdy we własnej korutynie — budzik serwera A nie czeka na losowe opóźnienie i odświeżenie serwera B).
     * Wpis znika ze skrzynki dopiero PO obsłudze; gdy proces zginie w trakcie, następne wywołanie (usługa, worker
     * oszczędny, start aplikacji) obsłuży go ponownie. Wołający trzyma blokadę wybudzenia (usługa) albo jest pracą
     * WorkManagera (ten trzyma ją sam), więc obsługa nie utknie w uśpionym CPU w trybie Doze.
     */
    suspend fun drainInbox(context: Context) = coroutineScope {
        val inbox = PushInbox.of(context)
        for (entry in inbox.claim()) {
            launch {
                try {
                    process(
                        context,
                        blob = entry.blob,
                        instance = null,
                        senderHint = entry.senderHint,
                        skipReplayCheck = entry.recovered,
                    )
                    inbox.complete(entry)
                } catch (cancelled: CancellationException) {
                    inbox.release(entry) // usługa zatrzymana w trakcie — wpis wróci przy następnej obsłudze
                    throw cancelled
                } catch (error: Exception) {
                    // Błąd samej obsługi (nie sieci — ta jest best-effort wewnątrz) nie zniknie przy ponowieniu.
                    // Usuwamy wpis, żeby uszkodzony budzik nie był przetwarzany bez końca.
                    Log.w(TAG, "obsługa budzika: ${error.javaClass.simpleName}: ${error.message}")
                    inbox.complete(entry)
                }
            }
        }
    }

    private suspend fun notifyFresh(context: Context, thumbnail: Bitmap?) {
        val repo = AppGraph.communityRepository
        val seenPrefs = context.getSharedPreferences("community_notified", Context.MODE_PRIVATE)
        val seen = seenPrefs.getStringSet(KEY_SEEN, emptySet()).orEmpty().toMutableSet()
        val freshKeys = ArrayList<String>()
        // C-04: klucze wszystkich istotnych pozycji obecnych TERAZ w bazie przeglądanych serwerów — zbiór
        // „pokazanych" przycinamy do nich (a nie losowo), więc nic, co wciąż jest w bazie, nie wraca jako nowość.
        val currentKeys = HashSet<String>()
        val scannedServers = HashSet<String>()
        // #5: powiadamiamy o KAŻDEJ istotnej (SIGNIFICANT) nowości, nie tylko o obcych pozycjach:
        // obce pozycje, posty, artykuły/newslettery i memy. O tym, czy sekcja/twórca wolno powiadamiać,
        // nadal decyduje APKA (CommunityContentFilter) — serwer tylko kataloguje platformę/autora.
        val toNotify = buildList {
            for (record in repo.storedServers()) {
                if (!record.enabled) continue
                val manifest = repo.restoredState(record).manifest ?: continue
                val serverName = record.displayName ?: manifest.name
                scannedServers += record.serverId
                fun consider(
                    platform: String,
                    creator: String?,
                    type: String?,
                    update: CommunityUpdateKind,
                    title: String?,
                    dedup: String,
                    notifyImageUrl: String?,
                    imageQuality: Int?,
                ) {
                    if (update != CommunityUpdateKind.SIGNIFICANT) return
                    // C-04: pozycja bez URL — kluczem skrót tekstu (nie cały tekst: prefs nie ważą megabajtów);
                    // stary klucz (pełny tekst) nadal uznajemy za „pokazane", żeby aktualizacja nie powtórzyła powiadomień.
                    val legacy = record.serverId + "|" + dedup.ifBlank { title ?: "" }
                    val key = if (dedup.isNotBlank()) legacy else record.serverId + "|#" + sha256Short(title.orEmpty())
                    currentKeys += key
                    if (key in seen || legacy in seen) { currentKeys += legacy; return } // już pokazane — nie powtarzaj
                    freshKeys += key
                    // D-05: wyciszona pozycja też jest „rozpatrzona" — po odciszeniu nie przyjdzie seria zaległych
                    if (!CommunityUpdatePolicy.shouldNotify(update, CommunityContentFilter.shouldNotify(record, platform, creator, type))) return
                    add(Pending(title ?: serverName, serverName, notifyImageUrl, imageQuality, key))
                }
                manifest.foreignItems.forEach {
                    consider(
                        it.platform, it.author, it.type, it.update,
                        it.title ?: it.text, it.url.orEmpty(), it.notifyImageUrl, it.imageQuality,
                    )
                }
                manifest.posts.forEach {
                    // posty społeczności to kanał YouTube danego twórcy — platforma "youtube" (jak w logu ruchu)
                    consider(
                        "youtube", it.author, it.type, it.update,
                        it.text, it.url.orEmpty(), it.notifyImageUrl, it.imageQuality,
                    )
                }
                manifest.articles.forEach {
                    // C-03: te same klucze co drzewo wyciszeń: media / <źródło> / newsletter|artykuł
                    consider(
                        CommunityContentFilter.PLATFORM_MEDIA, it.source, CommunityContentFilter.articleTypeLabel(it.kind), it.update,
                        it.title, it.url.orEmpty(), it.notifyImageUrl, it.imageQuality,
                    )
                }
                manifest.memes.forEach {
                    consider(
                        CommunityContentFilter.PLATFORM_MEME, it.source, CommunityContentFilter.TYPE_MEME, it.update,
                        it.title ?: it.source, it.url ?: it.imageUrl, it.notifyImageUrl, it.imageQuality,
                    )
                }
            }
        }
        if (toNotify.isNotEmpty()) {
            // Miniatura: pierwszeństwo ma obraz z ładunku E2E (zero sieci, zero korelacji „kto otworzył").
            // R-06: miniaturę z notifyImageUrl dociągamy TYLKO gdy user zostawił włączone „Pobieraj obrazy”
            // ORAZ „Obrazy do powiadomień”. Inaczej budzik nie generuje GET-u (serwer nie skoreluje budzika z
            // pobraniem). Czytamy z preferences.current() (świeże), nie ze statycznego pola (L-41/R-06).
            val settings = runCatching { AppGraph.preferences.current() }.getOrNull()
            val mayFetchThumb = settings?.let { it.communityDownloadImages && it.communityDownloadNotificationImages } ?: false
            val effectiveThumb = thumbnail ?: toNotify.singleOrNull()?.notifyImageUrl
                ?.takeIf { mayFetchThumb }
                ?.let { url -> runCatching { CommunityImageLoader.load(context, url, toNotify.first().imageQuality) }.getOrNull() }
            notify(context, toNotify, effectiveThumb, settings?.notifyOnWatch ?: true)
        }
        // C-04: zbiór = pokazane klucze pozycji, które WCIĄŻ są w bazie (dla przeglądanych serwerów) + świeże;
        // klucze serwerów chwilowo wyłączonych zostają (po włączeniu nie powtórzą powiadomień). Pozycje usunięte
        // z bazy wypadają same, więc zbiór nie rośnie bez końca i nie trzeba losowo wyrzucać.
        val kept = seen.filterTo(HashSet()) { k -> k.substringBefore('|') !in scannedServers || k in currentKeys }
        val next = kept + freshKeys
        if (next != seen) seenPrefs.edit().putStringSet(KEY_SEEN, next).commit()
    }

    private fun sha256Short(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

    /**
     * Dekoduje miniaturę z ładunku E2E (pole `img`, base64). Limit rozmiaru chroni przed nadużyciem
     * (FCM i tak tnie data-payload ~4 KB). Zwraca null przy braku/błędzie — powiadomienie zostaje tekstowe.
     */
    private fun decodeThumbnail(payload: String?): Bitmap? {
        val b64 = runCatching { JSONObject(payload ?: return null).optString("img") }
            .getOrNull()?.takeIf { it.isNotBlank() && it.length <= MAX_THUMB_B64_CHARS } ?: return null
        return runCatching {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            // serwer sam decyduje o formacie miniatury (JXL/AVIF/WEBP/PNG/JPG) — dekoder obsługuje wszystkie
            CommunityImageLoader.decodePushThumbnail(bytes)
        }.getOrNull()
    }

    /**
     * Jeśli ładunek niesie FILM (`video`), a nadawcą jest wybrany serwer GŁÓWNY — dostarcza film przez ten
     * sam potok co serwer domyślny (Historia + powiadomienie), respektując wybranych twórców i ustawienia
     * powiadomień. Serwer może dołączyć tytuł/czas/rodzaj/miniaturę (apka nie dzwoni po YouTube) albo tylko
     * id (apka dociąga z RSS). Nadawca inny niż główny NIE może wstrzykiwać podstawowych powiadomień.
     * Zwraca true, gdy podjęto próbę dostarczenia filmu.
     */
    private suspend fun maybeDeliverPrimaryVideo(
        context: Context,
        senderId: String?,
        payload: String?,
        thumbnail: Bitmap?,
    ): Boolean {
        if (senderId.isNullOrBlank() || payload.isNullOrBlank()) return false
        // tylko serwer wskazany przez usera jako główny; inny serwer nie przejmie podstawowych powiadomień
        if (AppGraph.preferences.currentPrimaryServerId() != senderId) return false
        val video = runCatching { JSONObject(payload).optJSONObject("video") }.getOrNull() ?: return false
        val channelId = video.optString("c").takeIf { CHANNEL_ID_RE.matches(it) } ?: return false
        val videoId = video.optString("v").takeIf { VIDEO_ID_RE.matches(it) } ?: return false
        // L-14: tytuł i data pochodzą od serwera — ograniczamy długość i NIE pozwalamy na datę z przyszłości
        // (inaczej spreparowana data „przyklejałaby" pozycję na górze Historii).
        val title = video.optString("t").takeIf { it.isNotBlank() }?.take(300)
        val publishedAt = video.optLong("p", 0L).takeIf { it > 0L }?.coerceAtMost(System.currentTimeMillis())
        val kind = parseKind(video.optString("k"))
        val provided = ServerNotificationHandler.ProvidedVideo(title, publishedAt, kind, thumbnail)
        val result = runCatching {
            ServerNotificationHandler.handle(channelId, videoId, playlistAttempt = 0, provided = provided)
        }.getOrNull()
        // twórca „playlistowy": filmu jeszcze nie ma w playliście — ponawianie co minutę robi NewVideoWorker.
        // Dawniej robiła to tylko ścieżka FCM (temat "feed"); po jej usunięciu UnifiedPush gubiłby takie filmy.
        if (result == ServerNotificationHandler.HandleResult.RETRY_PLAYLIST) {
            NewVideoWorker.enqueue(context, channelId, videoId, attempt = 1, delayMinutes = 1L)
        }
        return true
    }

    private fun parseKind(raw: String?): VideoKind? = when (raw?.trim()?.lowercase()) {
        "video", "film" -> VideoKind.VIDEO
        "short", "shorts" -> VideoKind.SHORT
        "live" -> VideoKind.LIVE
        "upcoming", "scheduled" -> VideoKind.UPCOMING
        "stream_archive", "archive" -> VideoKind.STREAM_ARCHIVE
        else -> null
    }

    /** jedna oczekująca nowość do pokazania w powiadomieniu (tytuł + nazwa serwera + opcjonalna miniatura). */
    private data class Pending(
        val title: String,
        val serverName: String,
        val notifyImageUrl: String?,
        val imageQuality: Int?,
        val key: String,
    )

    private fun notify(context: Context, items: List<Pending>, thumbnail: Bitmap?, onWatch: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return // brak zgody na powiadomienia – cicho odświeżyliśmy bazę
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Aktualizacje społeczności", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Powiadomienia wypychane przez serwery społeczności (UnifiedPush)" },
            )
        }
        // M-8: akcja po dotknięciu — otwiera aplikację (ekran startowy). PendingIntent IMMUTABLE (bez
        // nadpisywania przez odbiorcę) zgodnie z wymogami Androida 12+.
        val contentIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(
                context, 0, it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        val first = items.first()
        // smartwatch: wpisy społeczności też na zegarek (gdy użytkownik tego nie wyłączył)

        val builder = WearNotifications.content(NotificationCompat.Builder(context, CHANNEL_ID), onWatch, contentIntent)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(first.serverName)
            .setContentText(first.title)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
        // miniatura (E2E albo dociągnięta z notifyImageUrl): przy jednej pozycji duży obraz; zawsze ikona z boku.
        if (thumbnail != null) {
            builder.setLargeIcon(thumbnail)
            if (items.size == 1) {
                builder.setStyle(
                    NotificationCompat.BigPictureStyle().bigPicture(thumbnail).bigLargeIcon(null as Bitmap?),
                )
            } else {
                builder.setStyle(NotificationCompat.BigTextStyle().bigText(items.joinToString("\n") { "• ${it.title}" }))
            }
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(items.joinToString("\n") { "• ${it.title}" }))
        }
        // A-32: ID per pozycja (zbiorcze = per pierwsza nowa) — kolejne powiadomienie nie nadpisuje poprzedniego
        val id = NOTIFICATION_ID_BASE + (first.key.hashCode() and 0xFFFF)
        NotificationManagerCompat.from(context).notify(id, builder.build())
    }

    /** A-29: true gdy blob jest NOWY (pierwszy raz w oknie [REPLAY_WINDOW_MS]); zapisuje skrót. */
    private fun markBlobFresh(context: Context, blob: String): Boolean = synchronized(REPLAY_LOCK) {
        val digest = MessageDigest.getInstance("SHA-256").digest(blob.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)
        val prefs = context.getSharedPreferences("community_push_replay", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val stale = prefs.all.filterValues { (it as? Long ?: 0L) < now - REPLAY_WINDOW_MS }.keys
        if (prefs.contains(digest) && prefs.getLong(digest, 0L) >= now - REPLAY_WINDOW_MS) return false
        prefs.edit().apply { stale.forEach { remove(it) } }.putLong(digest, now).commit()
        true
    }

    // losowy rozrzut opóźnień ma odcinać korelację czasową — z SecureRandom, nie przewidywalnego PRNG
    private val JITTER_RNG = SecureRandom().asKotlinRandom()
    private const val CHANNEL_ID = "community_push"
    private const val NOTIFICATION_ID_BASE = 0x1E1C0 // "lewic0"
    private const val KEY_SEEN = "notified_keys" // M-8: klucze już pokazanych pozycji (nie powtarzamy)
    // ~12 KB base64 (~9 KB obrazu) — z zapasem; realnie FCM utnie ładunek do ~4 KB
    private const val MAX_THUMB_B64_CHARS = 12_000
    private const val REPLAY_WINDOW_MS = 20L * 60 * 1000 // > okno wycofanego klucza E2E (15 min)
    private const val ROTATE_MIN_INTERVAL_MS = 6L * 60 * 60 * 1000 // T-12
    /** T-13: najstarszy dopuszczalny budzik (zapas na złe zegary i opóźnione dostarczenie przez pośrednika). */
    internal const val MAX_WAKE_AGE_MS = 3L * 60 * 60 * 1000
    /** Wpis ze skrzynki odzyskany po śmierci procesu może być starszy — do limitu skrzynki. */
    internal const val MAX_INBOX_AGE_MS = 26L * 60 * 60 * 1000
    private val SEEN_LOCK = Mutex()
    private val REPLAY_LOCK = Any()
    private val CHANNEL_ID_RE = Regex("^UC[A-Za-z0-9_-]{22}$")
    private val VIDEO_ID_RE = Regex("^[A-Za-z0-9_-]{11}$")
}

/**
 * T-13: czy budzik jest przeterminowany wg pola `ts` (ms od epoki) w ładunku. Bez pola (starszy serwer) albo z
 * nieprawidłowym — NIE jest przeterminowany. Czas z przyszłości (zegar telefonu spóźniony) też przyjmujemy:
 * odrzucamy tylko budziki jednoznacznie stare, żeby zły zegar nie wyłączył powiadomień.
 */
internal fun isWakeStale(plaintext: String?, nowMillis: Long, maxAgeMillis: Long): Boolean {
    val ts = runCatching { org.json.JSONObject(plaintext ?: return false).optLong("ts", 0L) }.getOrDefault(0L)
    return ts > 0L && nowMillis - ts > maxAgeMillis
}
