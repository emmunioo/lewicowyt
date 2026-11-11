package pl.lewicowyt.notifier.community.push

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.unifiedpush.android.connector.UnifiedPush
import pl.lewicowyt.notifier.community.CommunityFcmConfig
import pl.lewicowyt.notifier.community.COMMUNITY_USER_AGENT
import pl.lewicowyt.notifier.community.CommunityTransport
import pl.lewicowyt.notifier.community.communityTransportOf
import pl.lewicowyt.notifier.community.CommunityConsentState
import pl.lewicowyt.notifier.community.CommunityServerState
import pl.lewicowyt.notifier.network.PrivacyHttpClient
import pl.lewicowyt.notifier.network.PrivacySettings

/**
 * Spina push z serwerami społeczności. Push = budzik; apka po obudzeniu sama dociąga manifest
 * i lokalnie filtruje. Transporty per serwer: FCM (własny projekt serwera → osobny FirebaseApp,
 * token per serwer) albo UnifiedPush (endpoint dystrybutora). Prywatność:
 *  - **Token 1 rotowany CO POWIADOMIENIE** — po każdym budziku apka bierze nowy adres dostarczenia
 *    (dla FCM: deleteToken → nowy token; UnifiedPush: nowy endpoint dystrybutora), więc stara i nowa
 *    rejestracja nie mają wspólnego identyfikatora. To JEDYNY token — nie ma osobnego pseudonimu.
 *  - **E2E** — apka rejestruje klucz publiczny (P-256), serwer szyfruje ładunek budzika; przy wspólnym
 *    token1 (rodzina serwerów operatora) to po E2E apka rozróżnia, który serwer przysłał budzik.
 * Sieć wykonujemy z wątku IO/workera. Każda ścieżka best-effort — błąd → cichy fallback.
 */
object CommunityPushManager {
    private const val TAG = "CommunityPush"
    private const val FEED_RETIRE_PREFS = "fcm_feed_topic"
    private const val KEY_FEED_RETIRED = "retired"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** Upewnia się, że wybrany jest dystrybutor UnifiedPush. Zwraca, czy jakiś jest dostępny. */
    private fun ensureDistributor(context: Context): Boolean {
        val distributors = runCatching { UnifiedPush.getDistributors(context) }.getOrDefault(emptyList())
        if (distributors.isEmpty()) return false
        val saved = runCatching { UnifiedPush.getAckDistributor(context) }.getOrNull()
        if (saved == null) runCatching { UnifiedPush.saveDistributor(context, distributors.first()) }
        return true
    }

    /**
     * #8 (M-1): rejestruje w dystrybutorze OSOBNĄ instancję danego serwera → osobny, nieskorelowalny
     * endpoint. Endpoint przyjdzie asynchronicznie do [CommunityPushReceiver.onNewEndpoint] z tą instancją.
     */
    private fun registerInstance(context: Context, instance: String) {
        runCatching { UnifiedPush.registerApp(context, instance) }
            .onFailure { Log.w(TAG, "registerApp($instance): ${it.message}") }
    }

    /**
     * B-07: endpointy UnifiedPush, pod którymi serwer może nas znać — temat LEP wbudowanego dystrybutora
     * oraz endpoint instancji zewnętrznego dystrybutora (po zmianie trybu mogą istnieć oba).
     */
    private fun unifiedPushEndpoints(context: Context, serverId: String): List<String> = listOfNotNull(
        EmbeddedPush.currentPubEndpoint(context, serverId),
        CommunityPushRegistry.endpointForServer(context, serverId),
    ).distinct()

    /**
     * R-03: wyrejestrowuje push JEDNEGO serwera (usunięcie/wyłączenie/odmowa regulaminu). Odkłada wysłanie
     * `unregister` starej tożsamości do opóźnionego workera (rozspójnienie w czasie), wyrejestrowuje instancję
     * UnifiedPush u dystrybutora i USUWA serwer z rejestru — dzięki czemu `rotateAndReregister` po budziku
     * innego serwera nie wyśle mu już nowej rejestracji. Nie tworzy tokenów FCM tylko po to, by je wycofać.
     */
    fun unregisterServer(context: Context, serverId: String) {
        val sp = CommunityPushRegistry.servers(context)[serverId]
        if (sp != null) {
            val url = sp.unregister
            if (url != null) {
                val pending = if (sp.fcm != null) {
                    listOfNotNull(
                        fcmTokenIfInitialized(context, sp.fcm)?.let { CommunityPushRegistry.Pending(url, token1 = it, endpoint = null) },
                    )
                } else {
                    // B-07: także temat LEP wbudowanego dystrybutora (tryb domyślny), nie tylko endpoint zewnętrzny
                    unifiedPushEndpoints(context, serverId)
                        .map { CommunityPushRegistry.Pending(url, token1 = null, endpoint = it) }
                }
                if (pending.isNotEmpty()) {
                    CommunityPushRegistry.addPendingUnregister(context, pending)
                    CommunityUnregisterWorker.schedule(context)
                }
            }
            // UnifiedPush: zdejmij instancję u dystrybutora (żeby przestał dostarczać budziki na stary endpoint)
            if (sp.fcm == null) {
                CommunityPushRegistry.instanceForServer(context, serverId)
                    ?.let { runCatching { UnifiedPush.unregisterApp(context, it) } }
            }
        }
        CommunityPushRegistry.removeServer(context, serverId)
        // B-07: przestań odpytywać temat LEP serwera (usługa/worker) i zatrzymaj odbiór, gdy był ostatni
        EmbeddedPush.forget(context, serverId)
        EmbeddedPush.applyRunState(context)
    }

    /** Endpoint UnifiedPush przyszedł dla konkretnej INSTANCJI serwera (#8) — zapamiętujemy go pod nią. */
    fun saveEndpoint(context: Context, instance: String, endpoint: String) =
        CommunityPushRegistry.setEndpointForInstance(context, instance, endpoint)

    /** C-02: nowy endpoint jednej instancji — zgłaszamy go TYLKO jej serwerowi (bez ruchu do pozostałych). */
    fun registerEndpointWithServer(context: Context, serverId: String): Boolean {
        val sp = CommunityPushRegistry.servers(context)[serverId] ?: return true // serwer usunięty — nie ponawiamy
        if (sp.fcm != null) return true
        val endpoint = CommunityPushRegistry.endpointForServer(context, serverId) ?: return true
        val ok = postRegister(context, serverId, sp.register, endpoint = endpoint, token1 = null)
        // D-04: stary adres (sprzed rotacji) wyrejestrowujemy DOPIERO, gdy serwer potwierdził nowy
        if (ok) releaseDeferredUnregister(context, serverId)
        return ok
    }

    private fun deferredPrefs(context: Context) =
        context.getSharedPreferences("community_push_deferred_unreg", Context.MODE_PRIVATE)

    /** D-04: stary endpoint czeka na potwierdzenie nowego (tryb zewnętrzny — nowy przychodzi asynchronicznie). */
    private fun deferUnregister(context: Context, serverId: String, url: String, endpoint: String) {
        deferredPrefs(context).edit().putString(serverId, JSONObject().put("u", url).put("e", endpoint).toString()).apply()
    }

    private fun releaseDeferredUnregister(context: Context, serverId: String) {
        val raw = deferredPrefs(context).getString(serverId, null) ?: return
        val o = runCatching { JSONObject(raw) }.getOrNull()
        deferredPrefs(context).edit().remove(serverId).apply()
        val url = o?.optString("u").orEmpty()
        val endpoint = o?.optString("e").orEmpty()
        if (url.isBlank() || endpoint.isBlank()) return
        CommunityPushRegistry.addPendingUnregister(context, listOf(CommunityPushRegistry.Pending(url, token1 = null, endpoint = endpoint)))
        CommunityUnregisterWorker.schedule(context)
    }

    /** C-01: serwer, do którego projektu FCM należy nadawca — tylko gdy jednoznaczny (wspólny projekt rodziny = null). */
    fun serverForFcmSender(context: Context, senderId: String?): String? {
        if (senderId.isNullOrBlank()) return null
        return CommunityPushRegistry.servers(context).filterValues { it.fcm?.senderId == senderId }.keys.singleOrNull()
    }

    /** Świeże endpointy UnifiedPush: zgłasza każdemu serwerowi bez własnego FCM JEGO WŁASNY endpoint. */
    fun registerEndpointWithAllServers(context: Context) {
        CommunityPushRegistry.servers(context).forEach { (serverId, sp) ->
            if (sp.fcm != null) return@forEach
            CommunityPushRegistry.endpointForServer(context, serverId)
                ?.let { postRegister(context, serverId, sp.register, endpoint = it, token1 = null) }
        }
    }

    /**
     * Po odświeżeniu manifestów rejestruje aplikację w każdym serwerze z push:
     * FCM (własny projekt) → token per serwer; inaczej → endpoint UnifiedPush (gdy dostępny).
     */
    fun syncFromServers(context: Context, servers: List<CommunityServerState>) {
        // H-2: FCM dociąga token z projektu Firebase PODANEGO PRZEZ SERWER, co kontaktuje Google z PRAWDZIWEGO
        // IP, POZA Torem. Dlatego rejestrujemy push dla serwera TYLKO gdy użytkownik zaakceptował jego regulamin
        // (consentState == ACCEPTED) — żaden niezaakceptowany/złośliwy serwer .onion nie wymusi rejestracji.
        // Dla serwerów .onion FCM jest dodatkowo bramkowany trybem „Nieprywatny Tor" (patrz niżej).
        val nonPrivateTor = NonPrivateTorSettings.isEnabled(context)
        // UnifiedPush jest GŁÓWNYM torem; FCM to tylko AWARYJNY fallback (domyślnie off — zero kontaktu z Google).
        val fcmFallback = PushSettings.fcmFallbackEnabled(context)
        val mode = PushSettings.mode(context)
        val fresh = LinkedHashMap<String, CommunityPushRegistry.ServerPush>()
        // dystrybutora wybieramy LENIWIE, ale ZANIM pierwszy raz zawołamy registerApp — inaczej (brak
        // zapisanego dystrybutora) rejestracja instancji nie wyprodukuje endpointu. null = jeszcze nie sprawdzano.
        var distributorReady: Boolean? = null
        for (s in servers) {
            val push = s.manifest?.push ?: continue
            if (!s.record.enabled) continue
            if (s.consentState != CommunityConsentState.ACCEPTED) continue // bez zgody na regulamin — nie rejestrujemy
            // R-04: push podąża za przełącznikiem „Powiadomienia" serwera. Gdy wyłączony — NIE rejestrujemy
            // (serwer nie dostaje adresu dostarczenia ani klucza, nie budzi telefonu). Wyrejestrowanie przy
            // samym wyłączeniu robi setCommunityServerNotifyEnabled → unregisterServer.
            if (!s.record.notifyEnabled) continue
            val serverId = s.record.serverId
            // H-03: serwer klasy ONION domyślnie NIE używa FCM — samo pobranie tokenu kontaktuje Google z
            // prawdziwego IP (poza Torem) i wiąże subskrybenta z kontem Google. Wyjątek: tryb „Nieprywatny Tor"
            // (user świadomie go włączył) ORAZ serwer, który tego wymaga (push.requiresNonPrivateTor) — wtedy
            // Tor służy tylko do omijania CGNAT / ukrycia IP serwera, a FCM wolno użyć. Inaczej UnifiedPush/pull.
            val isOnion = communityTransportOf(s.record.endpoint) == CommunityTransport.ONION
            val onionFcmAllowed = nonPrivateTor && push.requiresNonPrivateTor
            // FCM uzywalny tylko gdy user WŁĄCZYŁ fallback awaryjny ORAZ przechodzi brama .onion/Nieprywatny Tor.
            val fcmUsable = fcmFallback && (!isOnion || onionFcmAllowed)
            // Serwer .onion wymagający FCM (requiresNonPrivateTor), bez usable FCM nie dostaje pusha — zostaje
            // pull. Pominięcie = brak w `fresh` = zdjęcie z rejestru przy replaceServers.
            if (isOnion && push.requiresNonPrivateTor && !fcmUsable) continue
            val fcm = push.fcm?.takeIf { fcmUsable }
            // #7: próbujemy FCM, ale gdy token się nie uda (brak Play Services / błąd sieci) — spadamy na
            // UnifiedPush dla TEGO serwera, zamiast zostawić go zupełnie bez pusha.
            val token1 = fcm?.let { fcmToken(context, it) }
            if (fcm != null && token1 != null) {
                // jeden token1 na projekt (rodzina operatora) — serwery rozróżnia E2E, nie osobny pseudonim
                fresh[serverId] = CommunityPushRegistry.ServerPush(push.registerUrl, push.unregisterUrl, fcm)
                postRegister(context, serverId, push.registerUrl, endpoint = null, token1 = token1)
            } else {
                // UnifiedPush — GŁÓWNY tor. Wg trybu: WBUDOWANY (LEP, bez osobnej apki) albo ZEWNĘTRZNY (ntfy).
                fresh[serverId] = CommunityPushRegistry.ServerPush(push.registerUrl, push.unregisterUrl, null)
                if (mode == PushSettings.Mode.EMBEDDED) {
                    // wbudowany dystrybutor: endpoint = topic LEP. Baza = zadeklarowany RELAY (gdy serwer sam nie
                    // hostuje LEP, np. domyślny w GCP), inaczej host serwera. Serwer POST-uje budzik na ten endpoint.
                    (push.relayBase ?: EmbeddedPush.originOf(push.registerUrl))?.let { base ->
                        val endpoint = EmbeddedPush.pubEndpoint(context, serverId, base)
                        postRegister(context, serverId, push.registerUrl, endpoint = endpoint, token1 = null)
                    }
                } else {
                    // zewnętrzny dystrybutor (ntfy itp.) — dotychczasowy flow z własnym endpointem instancji
                    if (distributorReady == null) distributorReady = ensureDistributor(context)
                    if (distributorReady == true) {
                        val instance = CommunityPushRegistry.ensureInstance(context, serverId)
                        registerInstance(context, instance)
                        CommunityPushRegistry.endpointForServer(context, serverId)
                            ?.let { postRegister(context, serverId, push.registerUrl, endpoint = it, token1 = null) }
                    }
                    // brak dystrybutora → zostaje pull; endpoint pojawi się, gdy user go zainstaluje i sync się powtórzy
                }
            }
        }
        // B-07: po zmianie trybu (wbudowany ↔ zewnętrzny) serwer nadal zna endpoint z POPRZEDNIEGO trybu i
        // budziłby nas dwiema drogami. Wyrejestrowujemy go (z opóźnieniem, jak przy rotacji) i sprzątamy lokalnie.
        val stale = ArrayList<CommunityPushRegistry.Pending>()
        fresh.filterValues { it.fcm == null }.forEach { (serverId, sp) ->
            if (mode == PushSettings.Mode.EMBEDDED) {
                val instance = CommunityPushRegistry.instanceForServer(context, serverId) ?: return@forEach
                CommunityPushRegistry.endpointForInstance(context, instance)?.let { old ->
                    sp.unregister?.let { stale += CommunityPushRegistry.Pending(it, token1 = null, endpoint = old) }
                }
                runCatching { UnifiedPush.unregisterApp(context, instance) }
                CommunityPushRegistry.forgetInstance(context, instance)
            } else {
                EmbeddedPush.currentPubEndpoint(context, serverId)?.let { old ->
                    sp.unregister?.let { stale += CommunityPushRegistry.Pending(it, token1 = null, endpoint = old) }
                }
            }
        }
        if (stale.isNotEmpty()) {
            CommunityPushRegistry.addPendingUnregister(context, stale)
            CommunityUnregisterWorker.schedule(context)
        }
        CommunityPushRegistry.replaceServers(context, fresh)
        // Porządek wbudowanego dystrybutora: zostaw tematy LEP tylko dla aktywnych serwerów UnifiedPush i
        // uruchom/zatrzymaj odbiór (usługa instant / worker oszczędny) wg trybu.
        if (mode == PushSettings.Mode.EMBEDDED) {
            EmbeddedPush.retainOnly(context, fresh.filterValues { it.fcm == null }.keys)
            EmbeddedPush.applyRunState(context)
        } else {
            EmbeddedPush.stopAll(context)
            EmbeddedPush.clearAll(context) // B-07: tematy LEP wyrejestrowane wyżej — nie trzymamy ich dalej
        }
    }

    /**
     * Rotacja Token 1 CO POWIADOMIENIE: dla każdego projektu bierze nowy adres dostarczenia (deleteToken
     * → nowy token FCM) i re-rejestruje. Stary adres staje się UNREGISTERED i serwer sam go usuwa — nowa
     * rejestracja nie ma wspólnej wartości ze starą. Wołane z workera po odebraniu pusha.
     */
    fun rotateAndReregister(context: Context, onlyServerId: String? = null): Boolean {
        val all = CommunityPushRegistry.servers(context)
        // R-05: po budziku rotujemy TYLKO nadawcę (onlyServerId), a nie wszystkie serwery. Inaczej budzik
        // serwera A w oknie 1–15 s re-rejestrowałby też B i C (nowe klucze) → współpracujące/porównujące czasy
        // serwery wiążą subskrybenta. onlyServerId == null = pełna rotacja (świadoma „Nowa tożsamość").
        // Dla FCM token1 jest WSPÓLNY per projekt, więc gdy rotujemy nadawcę-FCM, musimy re-rejestrować też
        // pozostałe serwery TEGO SAMEGO projektu (inaczej miałyby nieważny token). Serwerów innych projektów
        // ani innych instancji UnifiedPush NIE dotykamy.
        val target = if (onlyServerId == null) all else all.filterKeys { it == onlyServerId }
        if (target.isEmpty()) return true
        // F-02: rotuj też KLUCZ E2E rotowanych serwerów (nowy pubkey w postRegister = świeży subskrybent).
        // A-28: DWUFAZOWO — nowy klucz jest „oczekujący" i staje się bieżącym dopiero po 2xx z serwera. Inaczej
        // błąd sieci zostawiał serwer ze starym pubkey, którego prywatna połówka wygasała po 15 min.
        val staged = target.keys.associateWith { runCatching { CommunityE2E.stageRotation(context, it) }.getOrNull() }
        var allOk = true
        fun settle(serverId: String, ok: Boolean) {
            if (staged[serverId] == null) return
            if (ok) CommunityE2E.commitRotation(context, serverId) else CommunityE2E.abortRotation(context, serverId)
            if (!ok) allOk = false
        }
        // Rotujemy tylko projekty serwerów już zarejestrowanych na FCM (bramkę .onion/Nieprywatny Tor
        // przeszły wcześniej w syncFromServers), więc tu nie trzeba jej powtarzać.
        val targetProjects = target.values.mapNotNull { it.fcm?.projectId }.toSet()
        // token1 RAZ NA PROJEKT (deleteToken), ale tylko dla projektów rotowanych serwerów
        val newTokenByProject =
            all.values.mapNotNull { it.fcm }.filter { it.projectId in targetProjects }.associateBy { it.projectId }
                .mapValues { (_, cfg) -> fcmToken(context, cfg, forceNew = true) }
        all.forEach { (serverId, sp) ->
            if (sp.fcm != null) {
                // re-rejestruj WSZYSTKIE serwery projektu, którego token rotowaliśmy (wspólny token1)
                if (sp.fcm.projectId in targetProjects) {
                    val token = newTokenByProject[sp.fcm.projectId]
                    val ok = token != null && postRegister(
                        context, serverId, sp.register, endpoint = null, token1 = token,
                        pubkey = staged[serverId] ?: CommunityE2E.publicKeyB64(context, serverId),
                    )
                    // token FCM po deleteToken jest już nowy — przy porażce i tak trzeba ponowić rejestrację
                    if (serverId in target) settle(serverId, ok) else if (!ok) allOk = false
                }
            } else if (onlyServerId == null || serverId == onlyServerId) {
                if (PushSettings.mode(context) == PushSettings.Mode.EMBEDDED) {
                    // WBUDOWANY: rotuj TOPIC (nowy, nieskorelowalny) i re-rejestruj nowy endpoint z NOWYM kluczem
                    // E2E (rotateKey zrobiony wyżej → publicKeyB64 zwraca już nowy). Stary endpoint wyrejestrowujemy
                    // z OPÓŹNIENIEM (CommunityUnregisterWorker) — dekorelacja w czasie, jak przy zewnętrznym flow.
                    val base = EmbeddedPush.baseOf(context, serverId) ?: EmbeddedPush.originOf(sp.register)
                    if (base == null) {
                        settle(serverId, false)
                    } else {
                        val rot = EmbeddedPush.rotateTopic(context, serverId, base)
                        val ok = postRegister(
                            context, serverId, sp.register, endpoint = rot.newEndpoint, token1 = null,
                            pubkey = staged[serverId] ?: CommunityE2E.publicKeyB64(context, serverId),
                        )
                        settle(serverId, ok)
                        if (ok) {
                            EmbeddedPush.finishRotation(context, rot)
                            val unreg = sp.unregister
                            if (unreg != null && rot.oldEndpoint != null) {
                                CommunityPushRegistry.addPendingUnregister(
                                    context,
                                    listOf(CommunityPushRegistry.Pending(unreg, token1 = null, endpoint = rot.oldEndpoint)),
                                )
                                CommunityUnregisterWorker.schedule(context)
                            }
                        } else {
                            // A-28: serwer wciąż zna STARY temat i STARY klucz — wracamy do nich (worker ponowi)
                            EmbeddedPush.revertRotation(context, rot)
                        }
                    }
                } else {
                    // ZEWNĘTRZNY (#8): rotacja instancji UnifiedPush = nowy, nieskorelowalny endpoint.
                    val (old, fresh) = CommunityPushRegistry.rotateInstance(context, serverId)
                    old?.let {
                        // C-10: protokół — po rotacji stary adres wyrejestrowujemy u serwera (z opóźnieniem 1 s–5 min).
                        // D-04: ale dopiero po potwierdzeniu NOWEGO (registerEndpointWithServer) — inaczej przy
                        // nieudanej rejestracji serwer zostawał bez żadnego ważnego adresu.
                        val oldEndpoint = CommunityPushRegistry.endpointForInstance(context, it)
                        val unreg = sp.unregister
                        if (unreg != null && oldEndpoint != null) deferUnregister(context, serverId, unreg, oldEndpoint)
                        runCatching { UnifiedPush.unregisterApp(context, it) }
                        CommunityPushRegistry.forgetInstance(context, it)
                    }
                    registerInstance(context, fresh)
                    // ZEWNĘTRZNY: endpoint przyjdzie asynchronicznie (onNewEndpoint → ACTION_REGISTER), więc
                    // nowy klucz zatwierdzamy od razu — rejestracja z nowym pubkey pójdzie z nowym endpointem.
                    settle(serverId, true)
                }
            }
        }
        return allOk
    }

    /**
     * A-48: wyłączenie awaryjnego FCM = REALNY koniec kontaktu z Google: wyrejestrowanie tokenów FCM u serwerów
     * (opóźnione, jak przy rotacji), zdjęcie dawnej subskrypcji tematu `feed`, skasowanie tokenów (domyślny projekt
     * i projekty serwerów). Kolejny [syncFromServers] (już z fallbackiem OFF) przepina serwery na UnifiedPush.
     * Nie tworzy nowych tokenów tylko po to, by je wycofać (fcmTokenIfInitialized).
     */
    fun disableFcm(context: Context) {
        val servers = CommunityPushRegistry.servers(context)
        val pending = servers.mapNotNull { (_, sp) ->
            val cfg = sp.fcm ?: return@mapNotNull null
            val url = sp.unregister ?: return@mapNotNull null
            fcmTokenIfInitialized(context, cfg)?.let { CommunityPushRegistry.Pending(url, token1 = it, endpoint = null) }
        }
        if (pending.isNotEmpty()) {
            CommunityPushRegistry.addPendingUnregister(context, pending)
            CommunityUnregisterWorker.schedule(context)
        }
        runCatching { Tasks.await(FirebaseMessaging.getInstance().unsubscribeFromTopic("feed")) }
        runCatching { Tasks.await(FirebaseMessaging.getInstance().deleteToken()) }
        runCatching { FirebaseApp.getApps(context) }.getOrDefault(emptyList())
            .filter { it.name.startsWith("op_") }
            .forEach { app -> runCatching { Tasks.await(app.get(FirebaseMessaging::class.java).deleteToken()) } }
    }

    /**
     * Token FCM (Token 1) w PROJEKCIE Firebase — kluczowany po `projectId`, więc WSZYSTKIE serwery
     * jednego operatora (ten sam projekt) dzielą JEDEN token1. Błąd → null (fallback).
     * forceNew=true: kasuje stary token (deleteToken) → Google wydaje NOWY, niepowiązywalny adres
     * (rotacja Token 1 raz na projekt). Stary staje się UNREGISTERED i serwer go usuwa.
     */
    private fun fcmToken(context: Context, cfg: CommunityFcmConfig, forceNew: Boolean = false): String? = try {
        val name = "op_${cfg.projectId}"
        val app = FirebaseApp.getApps(context).firstOrNull { it.name == name } ?: FirebaseApp.initializeApp(
            context,
            FirebaseOptions.Builder()
                .setProjectId(cfg.projectId)
                .setApplicationId(cfg.appId)
                .setApiKey(cfg.apiKey)
                .setGcmSenderId(cfg.senderId)
                .build(),
            name,
        )
        val messaging = app.get(FirebaseMessaging::class.java)
        if (forceNew) runCatching { Tasks.await(messaging.deleteToken()) }
        Tasks.await(messaging.token)
    } catch (e: Exception) {
        Log.w(TAG, "fcm token (${cfg.projectId}): ${e.message}")
        null
    }

    /**
     * M-03: token FCM TYLKO gdy projekt `op_<projectId>` jest JUŻ zainicjowany. Inaczej `null` — nie
     * inicjujemy FirebaseApp i nie dzwonimy do Google tylko po to, by coś wyrejestrować. Używane w ścieżkach
     * sprzątających (disable/snapshot), gdzie nie wolno utworzyć nowego tokenu (byłby to świeży kontakt z Google).
     */
    private fun fcmTokenIfInitialized(context: Context, cfg: CommunityFcmConfig): String? {
        val initialized = runCatching { FirebaseApp.getApps(context) }.getOrDefault(emptyList())
            .any { it.name == "op_${cfg.projectId}" }
        return if (initialized) fcmToken(context, cfg, forceNew = false) else null
    }

    /**
     * Migawka OBECNEJ tożsamości dostarczania (token1 per projekt FCM / endpoint) przypięta do adresów
     * wyrejestrowania każdego serwera. Wołać PRZED [rotateAndReregister] — po rotacji stary token FCM
     * jest już skasowany. Służy do OPÓŹNIONEGO wyrejestrowania starej tożsamości (rozspójnienie w czasie).
     */
    fun snapshotForUnregister(context: Context): List<CommunityPushRegistry.Pending> {
        val servers = CommunityPushRegistry.servers(context)
        // M-03: token1 RAZ NA PROJEKT, ale nie tworzymy go dla projektu nieaktywnego (fcmTokenIfInitialized) —
        // inaczej „Nowa tożsamość" dzwoniłaby do Google tylko po to, by coś wyrejestrować.
        val tokenByProject =
            servers.values.mapNotNull { it.fcm }.associateBy { it.projectId }
                .mapValues { (_, cfg) -> fcmTokenIfInitialized(context, cfg) }
        return buildList {
            servers.forEach { (serverId, sp) ->
                val url = sp.unregister ?: return@forEach
                if (sp.fcm != null) {
                    tokenByProject[sp.fcm.projectId]?.let { add(CommunityPushRegistry.Pending(url, token1 = it, endpoint = null)) }
                } else {
                    // #8: wyrejestrowujemy WŁASNY (per-serwer) endpoint tego serwera, nie wspólny
                    // B-07: w trybie wbudowanym to temat LEP (endpointForServer jest wtedy pusty)
                    unifiedPushEndpoints(context, serverId)
                        .forEach { add(CommunityPushRegistry.Pending(url, token1 = null, endpoint = it)) }
                }
            }
        }
    }


    /**
     * F-02: kasuje Firebase Installation ID (FID) dla WSZYSTKICH zainicjowanych projektów. `deleteToken`
     * rotuje sam token, ale FID pozostawał stały — a token FCM z ery Installations zaczyna się od FID,
     * więc Google i serwer mogliby powiązać stary i nowy token. Po `delete()` kolejny token dostaje NOWY FID.
     */
    fun resetFirebaseInstallations(context: Context) {
        runCatching { FirebaseApp.getApps(context) }.getOrDefault(emptyList()).forEach { app ->
            runCatching { Tasks.await(com.google.firebase.installations.FirebaseInstallations.getInstance(app).delete()) }
        }
    }

    /**
     * Serwer domyślny nie używa FCM (budziki tylko przez UnifiedPush). Wcześniejsze wersje przy włączonym
     * awaryjnym FCM subskrybowały temat `feed` projektu domyślnego — zdejmujemy tę subskrypcję jednorazowo.
     * Wołać tylko przy włączonym FCM (inaczej nie kontaktujemy Google tylko po to, by coś wypisać).
     */
    fun retireDefaultFeedTopic(context: Context) {
        val prefs = context.getSharedPreferences(FEED_RETIRE_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_FEED_RETIRED, false)) return
        runCatching {
            FirebaseMessaging.getInstance().unsubscribeFromTopic("feed")
                .addOnSuccessListener { prefs.edit().putBoolean(KEY_FEED_RETIRED, true).apply() }
        }
    }

    /**
     * Wysyła podane wyrejestrowania starej tożsamości. Wołane z opóźnionego workera (po izolacji obwodu).
     * A-28b: zwraca wpisy, których NIE udało się dostarczyć (sieć/Tor/408/429/5xx) — worker odkłada je z powrotem.
     * 2xx i pozostałe 4xx (serwer nie zna już tej tożsamości) uznajemy za załatwione.
     */
    fun sendUnregistrations(context: Context, items: List<CommunityPushRegistry.Pending>): List<CommunityPushRegistry.Pending> =
        items.filter { item ->
            unregisterNeedsRetry(postUnregister(context, item.url, token1 = item.token1, endpoint = item.endpoint))
        }

    private fun postRegister(
        context: Context,
        serverId: String,
        url: String,
        endpoint: String?,
        token1: String?,
        pubkey: String? = CommunityE2E.publicKeyB64(context, serverId),
    ): Boolean {
        val body = JSONObject().apply {
            token1?.let { put("token1", it) }
            endpoint?.let { put("endpoint", it) }
            pubkey?.let { put("pubkey", it) }
        }
        return post(context, url, body)?.let { it in 200..299 } == true
    }

    private fun postUnregister(context: Context, url: String, token1: String?, endpoint: String?): Int? {
        val body = JSONObject().apply {
            token1?.let { put("token1", it) }
            endpoint?.let { put("endpoint", it) }
        }
        return post(context, url, body)
    }

    /** A-28: zwraca kod HTTP albo null (brak sieci / Tor niegotowy) — wołający decyduje o ponowieniu. */
    private fun post(context: Context, url: String, body: JSONObject): Int? {
        // serwery .onion: rejestracja idzie przez Tor (ukryte IP), inaczej zwykły prywatny klient.
        // H-3 (fail-closed): gdy Tor niegotowy (get()==null), POMIJAMY rejestrację .onion — NIGDY nie
        // wysyłamy jej po clearnecie ani na ślepo na losowy port.
        // T-10: opcja „Rejestruj push przez Tor” kieruje przez Tor także serwery w zwykłym internecie (operator nie
        // połączy starej i nowej tożsamości po adresie IP); też fail-closed
        val client = if (CommunityTorClient.isOnion(url) || PrivacySettings.registerViaTor(context)) {
            CommunityTorClient.get(context) ?: run {
                Log.w(TAG, "post: Tor niegotowy — pomijam rejestrację (fail-closed)")
                return null
            }
        } else {
            PrivacyHttpClient.get(context)
        }
        // L-10: ten sam User-Agent co manifest/obrazy ("lewicowYT") — spójność żądań społeczności.
        val request = Request.Builder().url(url)
            .header("User-Agent", COMMUNITY_USER_AGENT)
            .post(body.toString().toRequestBody(JSON)).build()
        return runCatching { client.newCall(request).execute().use { it.code } }
            .onFailure { Log.w(TAG, "post: ${it.message}") }
            .getOrNull()
    }
}

/**
 * J-08: czy wyrejestrowanie trzeba ponowić. 408 (Request Timeout) i 429 (Too Many Requests) znaczą „spróbuj
 * później", a nie „serwer nie zna tej tożsamości" — dawniej uznawane za załatwione, więc adres dostarczenia
 * i klucz zostawały na serwerze, a użytkownik myślał, że się wyrejestrował. null = brak sieci / Tor niegotowy.
 */
internal fun unregisterNeedsRetry(code: Int?): Boolean = code == null || code >= 500 || code == 408 || code == 429
