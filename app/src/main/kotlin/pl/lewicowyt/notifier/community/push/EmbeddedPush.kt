package pl.lewicowyt.notifier.community.push

import android.content.Context
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import pl.lewicowyt.notifier.community.COMMUNITY_USER_AGENT
import pl.lewicowyt.notifier.network.PrivacyHttpClient

/**
 * Wbudowany dystrybutor UnifiedPush (protokół LEP). Dla serwera trzyma losowy, nieprzezroczysty `topic`:
 *  - PUBLIKACJA: endpoint `${base}/lep/pub/${topic}` — to adres, który apka rejestruje u serwera (serwer
 *    POST-uje tam zaszyfrowany budzik; identyczny mechanizm co zewnętrzny UnifiedPush, więc postRegister bez zmian).
 *  - ODBIÓR: [EmbeddedPushService] (instant) albo [EmbeddedPushDrainWorker] (oszczędny) odpytuje
 *    `${base}/lep/sub/${topic}?after=${cursor}` ([poll]); budziki trafiają do trwałej [PushInbox], a stamtąd od
 *    razu do [CommunityWakeProcessor] (deszyfracja E2E, powiadomienie) — bez kolejki WorkManagera.
 *
 * Relay/serwer widzi tylko szum (E2E) i nieprzezroczysty topic. Stan trzymamy w SharedPreferences.
 */
object EmbeddedPush {
    private const val PREFS = "embedded_push"
    private const val MAX_RESPONSE_BYTES = 1024L * 1024L
    private val RNG = SecureRandom()

    data class Sub(val serverId: String, val topic: String, val base: String, val cursor: Long)

    private fun p(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** origin (scheme://host:port) z adresu rejestracji — LEP serwujemy na tym samym hoście co serwer. */
    fun originOf(url: String): String? {
        val u = url.toHttpUrlOrNull() ?: return null
        return "${u.scheme}://${u.host}:${u.port}"
    }

    /** Zapewnia topic dla serwera i zwraca endpoint PUBLIKACJI do zarejestrowania u serwera. */
    fun pubEndpoint(c: Context, serverId: String, base: String): String {
        val prefs = p(c)
        val topic = prefs.getString("topic_$serverId", null) ?: newTopic()
        prefs.edit().putString("topic_$serverId", topic).putString("base_$serverId", base).apply()
        return "$base/lep/pub/$topic"
    }

    /** Wynik rotacji: stary endpoint (do wyrejestrowania) i nowy (do rejestracji) + tematy (do zatwierdzenia/cofnięcia). */
    data class Rotation(
        val serverId: String,
        val oldEndpoint: String?,
        val newEndpoint: String,
        val oldTopic: String? = null,
        val newTopic: String? = null,
        val oldBase: String? = null,
    )

    /**
     * Rotuje temat serwera: nowy, nieskorelowalny `topic` (zeruje kursor). Niepowiązywalność w czasie —
     * serwer/relay nie łączy nowej subskrypcji ze starą. Wołane po KAŻDYM budziku (wraz z rotacją klucza E2E
     * i re-rejestracją nowego endpointu w CommunityPushManager). Zwraca stary i nowy endpoint PUBLIKACJI.
     */
    fun rotateTopic(c: Context, serverId: String, base: String): Rotation {
        val prefs = p(c)
        val oldTopic = prefs.getString("topic_$serverId", null)
        val oldEndpoint = oldTopic?.let { "$base/lep/pub/$it" }
        val oldBase = prefs.getString("base_$serverId", null)
        val topic = newTopic()
        // A-22: kursor jest per TEMAT. Nowy temat startuje od zera (brak klucza). A-28: kursor STAREGO tematu
        // kasujemy dopiero w [finishRotation] (po 2xx z serwera) — przy porażce [revertRotation] wraca do niego.
        prefs.edit()
            .putString("topic_$serverId", topic)
            .putString("base_$serverId", base)
            .commit()
        return Rotation(serverId, oldEndpoint, "$base/lep/pub/$topic", oldTopic, topic, oldBase)
    }

    /**
     * A-28: rotacja potwierdzona przez serwer — sprzątamy kursor starego tematu i przerywamy long-poll, który
     * wciąż wisi na starym temacie (pętla usługi zrobi jeszcze jedno pobranie `wait=0` ze starego tematu, żeby
     * nie zgubić budzika wysłanego tam tuż przed rotacją, i od razu przejdzie na nowy).
     */
    fun finishRotation(c: Context, rot: Rotation) {
        rot.oldTopic?.let { p(c).edit().remove("cursor_$it").apply() }
        interrupt(rot.serverId)
    }

    /** A-28: serwer nie przyjął nowego endpointu — wracamy do starego tematu (wciąż zarejestrowanego u serwera). */
    fun revertRotation(c: Context, rot: Rotation) {
        val prefs = p(c)
        if (prefs.getString("topic_${rot.serverId}", null) != rot.newTopic) return // ktoś już zmienił temat
        prefs.edit().apply {
            if (rot.oldTopic != null) putString("topic_${rot.serverId}", rot.oldTopic) else remove("topic_${rot.serverId}")
            if (rot.oldBase != null) putString("base_${rot.serverId}", rot.oldBase) else remove("base_${rot.serverId}")
            rot.newTopic?.let { remove("cursor_$it") }
        }.commit()
        interrupt(rot.serverId) // pętla mogła już odpytywać (nieznany serwerowi) nowy temat — wraca do starego od razu
    }

    /** Wszystkie aktywne subskrypcje wbudowane (do long-polla). */
    fun subscriptions(c: Context): List<Sub> {
        val prefs = p(c)
        return prefs.all.keys
            .filter { it.startsWith("topic_") }
            .mapNotNull { key ->
                val serverId = key.removePrefix("topic_")
                val topic = prefs.getString(key, null) ?: return@mapNotNull null
                val base = prefs.getString("base_$serverId", null) ?: return@mapNotNull null
                Sub(serverId, topic, base, prefs.getLong("cursor_$topic", 0L))
            }
    }

    /** B-07: aktualny endpoint PUBLIKACJI serwera (do wyrejestrowania) — null, gdy serwer nie ma tematu. */
    fun currentPubEndpoint(c: Context, serverId: String): String? {
        val prefs = p(c)
        val topic = prefs.getString("topic_$serverId", null) ?: return null
        val base = prefs.getString("base_$serverId", null) ?: return null
        return "$base/lep/pub/$topic"
    }

    /** Baza LEP zapisana dla serwera (relay albo host serwera) — do rotacji. */
    fun baseOf(c: Context, serverId: String): String? = p(c).getString("base_$serverId", null)

    /** A-22: kursor jest per TEMAT (`cursor_<topic>`), nie per serwer — rotacja tematu zeruje go naturalnie. */
    fun cursor(c: Context, topic: String): Long = p(c).getLong("cursor_$topic", 0L)

    fun setCursor(c: Context, topic: String, value: Long) {
        p(c).edit().putLong("cursor_$topic", value).apply()
    }

    /** Usuwa temat serwera (po wyrejestrowaniu/zdjęciu z rejestru) wraz z jego kursorem. */
    fun forget(c: Context, serverId: String) {
        val prefs = p(c)
        val topic = prefs.getString("topic_$serverId", null)
        prefs.edit()
            .remove("topic_$serverId")
            .remove("base_$serverId")
            .apply { topic?.let { remove("cursor_$it") } }
            .apply()
    }

    /** Zostawia tematy TYLKO dla podanych serwerów, resztę kasuje (porządkuje po sync). */
    fun retainOnly(c: Context, serverIds: Set<String>) {
        val prefs = p(c)
        val toForget = prefs.all.keys
            .filter { it.startsWith("topic_") }
            .map { it.removePrefix("topic_") }
            .filter { it !in serverIds }
        if (toForget.isNotEmpty()) toForget.forEach { forget(c, it) }
    }

    fun clearAll(c: Context) = p(c).edit().clear().apply()

    /**
     * Dostosowuje odbiór budzików do trybu: INSTANT → usługa pierwszoplanowa (long-poll), SAVER → worker co ~15 min.
     * Gdy brak subskrypcji albo tryb push = EXTERNAL → wszystko zatrzymane.
     */
    fun applyRunState(c: Context) {
        val active = PushSettings.mode(c) == PushSettings.Mode.EMBEDDED && subscriptions(c).isNotEmpty()
        if (!active) {
            EmbeddedPushService.stop(c)
            EmbeddedPushDrainWorker.cancel(c)
            return
        }
        when (PushSettings.embedded(c)) {
            PushSettings.Embedded.INSTANT -> {
                // B-06: worker zostaje jako siatka bezpieczeństwa — WorkManager przeżywa restart telefonu i
                // aktualizację apki, gdy usługi nie podniósł PushBootReceiver (np. zabita przez system lub
                // producenta telefonu). Worker próbuje ją podnieść, a gdy się nie da (od Androida 12 start
                // usługi pierwszoplanowej z tła bez wyjątku jest zabroniony) — sam drenuje LEP.
                EmbeddedPushDrainWorker.schedule(c)
                EmbeddedPushService.start(c)
            }
            PushSettings.Embedded.SAVER -> {
                EmbeddedPushService.stop(c)
                EmbeddedPushDrainWorker.schedule(c)
            }
        }
    }

    /** Całkowite wyłączenie wbudowanego odbioru (np. przy przełączeniu na zewnętrzny dystrybutor). */
    fun stopAll(c: Context) {
        EmbeddedPushService.stop(c)
        EmbeddedPushDrainWorker.cancel(c)
    }

    private fun newTopic(): String {
        val bytes = ByteArray(16)
        RNG.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) } // 32 znaki hex — pasuje do LEP TOPIC_RE
    }

    /** Wynik jednego odpytania tematu LEP ([poll]). */
    sealed interface Poll {
        /** Serwer odpowiedział; [received] budzików zapisanych w [PushInbox], [cursor] = nowy kursor tematu. */
        data class Ok(val cursor: Long, val received: Int) : Poll

        /** Błąd sieci/serwera albo nieudany zapis budzika — pętla odczeka (backoff) i spróbuje ponownie. */
        data object Failed : Poll

        /** Long-poll przerwany CELOWO ([interrupt]: rotacja tematu, nowa sieć) — to nie awaria, bez backoffu. */
        data object Interrupted : Poll
    }

    // long-polle w toku (serwer → wywołanie) — [interrupt] przerywa je natychmiast
    private val activeCalls = ConcurrentHashMap<String, Call>()

    /**
     * Sygnał „nie czekaj, odpytaj od razu" dla pętli usługi (licznik — budzi WSZYSTKIE pętle, także te czekające
     * w backoffie). Podbijany przez [interrupt] i [interruptAll].
     */
    internal val wakeUps = MutableStateFlow(0L)

    /**
     * Przerywa trwający long-poll serwera, żeby pętla od razu przeszła na AKTUALNY temat. Wołane po zatwierdzeniu
     * (albo cofnięciu) rotacji tematu: bez tego stary long-poll trzymał pętlę do ~50 s, a budziki wysłane już na
     * nowy temat czekały na jego koniec.
     */
    fun interrupt(serverId: String) {
        wakeUps.update { it + 1 }
        activeCalls[serverId]?.cancel()
    }

    /** Budzi pętle czekające w backoffie (np. sieć wróciła) bez przerywania trwających long-polli. */
    fun wakeAll() {
        wakeUps.update { it + 1 }
    }

    /** Aktualny temat LEP serwera (null = serwer bez subskrypcji wbudowanej). */
    fun currentTopic(c: Context, serverId: String): String? = p(c).getString("topic_$serverId", null)

    /**
     * Przerywa WSZYSTKIE long-polle — po zmianie sieci (np. Wi-Fi → LTE). Połączenie zestawione w starej sieci
     * jest martwe, ale bez tego czekałoby na timeout odczytu (70 s), zanim pętla połączyłaby się w nowej.
     */
    fun interruptAll() {
        wakeUps.update { it + 1 }
        activeCalls.values.forEach { it.cancel() }
    }

    // klienci pochodni (timeouty long-polla / drenażu) — newBuilder() dzieli pulę połączeń i dispatcher z bazowym,
    // ale budowanie przy KAŻDYM odpytaniu było zbędne; odtwarzamy tylko po zmianie klienta bazowego (np. Tor)
    private class Derived(val base: OkHttpClient, val hold: OkHttpClient, val drain: OkHttpClient)
    @Volatile private var derived: List<Derived> = emptyList()

    private fun clientFor(base: OkHttpClient, hold: Boolean): OkHttpClient {
        val d = derived.firstOrNull { it.base === base } ?: synchronized(this) {
            derived.firstOrNull { it.base === base } ?: Derived(
                base,
                hold = base.newBuilder()
                    .readTimeout(HOLD_READ_TIMEOUT_S, TimeUnit.SECONDS)
                    .callTimeout(HOLD_CALL_TIMEOUT_S, TimeUnit.SECONDS)
                    .build(),
                drain = base.newBuilder()
                    .readTimeout(DRAIN_READ_TIMEOUT_S, TimeUnit.SECONDS)
                    .callTimeout(DRAIN_CALL_TIMEOUT_S, TimeUnit.SECONDS)
                    .build(),
            ).also { derived = (derived + it).takeLast(4) } // bazowy Tor/clearnet; stare po restarcie Tora wypadają
        }
        return if (hold) d.hold else d.drain
    }

    /**
     * Jedno odpytanie tematu LEP. [hold]=true → long-poll (serwer trzyma ~50 s; tryb natychmiastowy);
     * [hold]=false → drenaż natychmiastowy (`wait=0`; tryb oszczędny, ostatnie pobranie po rotacji).
     * Dla .onion idzie przez Tor (fail-closed: brak Tora → [Poll.Failed]).
     *
     * Kolejność gwarantuje, że budzik nie zginie: najpierw zapis do trwałej [PushInbox], DOPIERO potem kursor.
     * Obsługę (deszyfracja E2E, powiadomienie) robi wołający przez [CommunityWakeProcessor.drainInbox] — od razu,
     * w procesie, bez kolejki WorkManagera.
     */
    fun poll(c: Context, sub: Sub, hold: Boolean): Poll {
        val onion = CommunityTorClient.isOnion(sub.base)
        val base = if (onion) CommunityTorClient.get(c) ?: return Poll.Failed else PrivacyHttpClient.get(c)
        val url = "${sub.base}/lep/sub/${sub.topic}?after=${sub.cursor}" + if (hold) "" else "&wait=0"
        val req = Request.Builder().url(url).header("User-Agent", COMMUNITY_USER_AGENT).build()
        val call = clientFor(base, hold).newCall(req)
        if (hold) activeCalls[sub.serverId] = call
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) return Poll.Failed
                // A-21: limit odczytu — zepsuty/złośliwy relay nie wywoła OOM w procesie z usługą pierwszoplanową.
                // Legalnie: do 50 budzików × 16 KB (+ JSON) < 1 MiB.
                val source = resp.body.source()
                source.request(MAX_RESPONSE_BYTES + 1)
                if (source.buffer.size > MAX_RESPONSE_BYTES) return Poll.Failed
                val json = JSONObject(source.readUtf8())
                val inbox = PushInbox.of(c)
                var received = 0
                json.optJSONArray("msgs")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val data = arr.optJSONObject(i)?.optString("data").orEmpty()
                        if (data.isBlank()) continue
                        // C-01: temat należy do JEDNEGO serwera — to on jest nadawcą także budzika bez E2E.
                        // Nieudany zapis = kursor stoi, serwer odda budziki ponownie (już zapisane się nie zdublują).
                        if (!inbox.put(sub.serverId, data)) return Poll.Failed
                        received += 1
                    }
                }
                val newCursor = json.optLong("cursor", sub.cursor)
                // A-22 (CAS): zapisujemy kursor TEMATU tylko jeśli ten temat jest wciąż aktualny dla serwera —
                // stary long-poll kończący się po rotacji NIE nadpisze kursora (temat już porzucony).
                if (p(c).getString("topic_${sub.serverId}", null) == sub.topic) {
                    setCursor(c, sub.topic, newCursor)
                }
                return Poll.Ok(newCursor, received)
            }
        } catch (e: Exception) {
            return if (call.isCanceled()) Poll.Interrupted else Poll.Failed
        } finally {
            if (hold) activeCalls.remove(sub.serverId, call)
        }
    }

    // long-poll: serwer trzyma ~50 s → 70 s odczytu z zapasem na wolny Tor; drenaż: odpowiedź natychmiastowa
    private const val HOLD_READ_TIMEOUT_S = 70L
    private const val HOLD_CALL_TIMEOUT_S = 90L
    private const val DRAIN_READ_TIMEOUT_S = 20L
    private const val DRAIN_CALL_TIMEOUT_S = 30L
}
