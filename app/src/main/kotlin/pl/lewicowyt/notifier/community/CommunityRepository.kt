package pl.lewicowyt.notifier.community

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import pl.lewicowyt.notifier.diagnostics.DiagnosticCategory
import pl.lewicowyt.notifier.diagnostics.DiagnosticLevel
import pl.lewicowyt.notifier.diagnostics.DiagnosticLogStore

/**
 * żywy stan jednego serwera po próbie odświeżenia manifestu – to konsumuje UI.
 * manifest/consentState są null, gdy nie udało się pobrać świeżego manifestu
 * (offline, błąd, nieobsługiwany transport); wtedy zostaje tylko cache z [record].
 */
data class CommunityServerState(
    val record: CommunityServerRecord,
    val manifest: CommunityManifest?,
    val consentState: CommunityConsentState?,
    val error: String?,
    // itemId pozycji tego serwera oznaczonych jako ulubione – do gwiazdki w feedzie
    val favoriteItemIds: Set<String> = emptySet(),
    // R-09: regulamin do PRZEJRZENIA, gdy wymagana jest (ponowna) zgoda, a nie ma świeżego manifestu
    // (np. po restarcie, gdy serwer zmienił regulamin). UI pokazuje „Przejrzyj regulamin" na tej podstawie.
    val reviewableTerms: CommunityTerms? = null,
) {
    /** serwer wnosi coś do zakładki tylko gdy jest włączony, zaakceptowany i coś oferuje. */
    val contributesToTab: Boolean
        get() = record.enabled &&
            consentState == CommunityConsentState.ACCEPTED &&
            (manifest?.capabilities?.isNotEmpty() == true)
}

/** wynik przeładowania bazy z serwerów: ile sprawdzono, ile realnie się zmieniło (SHA-256), ile błędów. */
data class CommunityReloadResult(val checked: Int, val changed: Int, val failed: Int)

sealed interface AddServerOutcome {
    data class Added(val state: CommunityServerState) : AddServerOutcome
    object InvalidEndpoint : AddServerOutcome
    data class Failed(val reason: String) : AddServerOutcome
}

/**
 * spina trwały magazyn ([CommunityStore]), pobieranie manifestu ([CommunityFetcher])
 * i bramkę zgody ([CommunityConsentGate]). fetcher wstrzykiwany funkcją, żeby całość
 * była testowalna bez sieci i żeby onion/HTTPS dobierać po endpointcie.
 *
 * kluczowa zasada: łączymy się z serwerem dopiero, gdy [consentState] = ACCEPTED dla
 * DOKŁADNIE aktualnego regulaminu. do serwera i tak nic nie wysyłamy – zgoda żyje lokalnie.
 */
class CommunityRepository(
    private val store: CommunityStore,
    private val fetcherProvider: (String) -> CommunityFetcher?,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val endpointHints: CommunityEndpointHints = NoEndpointHints,
    private val contentFileTracker: CommunityContentFileTracker = InMemoryContentFileTracker(),
    // bramka: czy wolno kontaktować serwer domyślny (zgoda na regulamin aplikacji); testy domyślnie: tak
    private val defaultServerAllowed: () -> Boolean = { true },
) {
    /**
     * Pobiera manifest: NAJPIERW adres główny (https/.onion), a dopiero gdy zawiedzie — alternatywy LAN
     * (obejście zepsutego hairpin-NAT), równolegle między sobą. Zapamiętuje adres, który zadziałał, i ogłoszone
     * alternatywy (przetrwają restart aplikacji).
     *
     * A-07: adres LAN (http, bez uwierzytelnienia) NIE ściga się już z https. W obcej sieci urządzenie pod
     * zapamiętanym adresem LAN mogło odpowiedzieć szybciej i podmienić treść, skasować ulubione (`deletions`),
     * zmienić regulamin (DoS zgody) albo podsunąć `push.relay`. Teraz odpowiedź z LAN: tylko jako failover,
     * musi mieć TEN SAM hash regulaminu co ostatnio znany ([knownTermsHash]; nieznany → odrzucamy), a jej
     * `deletions` i `push` są ignorowane (push zostaje z zapisanego rekordu: [storedPush]).
     */
    private suspend fun loadWithFallback(
        serverId: String,
        primary: String,
        conditional: CommunityConditional? = null,
        knownTermsHash: String? = null,
        storedPush: CommunityPushConfig? = null,
    ): CommunityManifestLoadResult {
        if (fetcherProvider(primary) == null) return CommunityManifestLoadResult.Failed("Nieobsługiwany transport")
        // #10: walidatory oddajemy tylko głównemu adresowi
        val cond = conditional?.takeUnless { it.isEmpty }
        val primaryResult = CommunityManifestLoader(fetcherProvider(primary)!!).load(primary, cond, parseEndpoint = primary)
        when (primaryResult) {
            is CommunityManifestLoadResult.Loaded -> {
                endpointHints.remember(serverId, primary, primaryResult.manifest.altEndpoints)
                return primaryResult.copy(sourceEndpoint = primary)
            }
            CommunityManifestLoadResult.NotModified -> return primaryResult
            is CommunityManifestLoadResult.Failed -> Unit
        }
        val lanResult = loadFromLanAlternatives(serverId, primary)
        if (lanResult !is CommunityManifestLoadResult.Loaded) return primaryResult
        if (knownTermsHash == null || lanResult.manifest.terms.contentHash != knownTermsHash) {
            return CommunityManifestLoadResult.Failed("Adres LAN zwrócił inny regulamin niż serwer — odrzucono")
        }
        return lanResult.copy(
            manifest = lanResult.manifest.copy(deletions = emptyList(), push = storedPush),
            // walidatory z innego hosta nie są ważne dla adresu głównego
            etag = null,
            lastModified = null,
        )
    }

    private suspend fun loadFromLanAlternatives(serverId: String, primary: String): CommunityManifestLoadResult =
        coroutineScope {
            // M-08: oprócz adresu głównego jedyną dozwoloną alternatywą jest adres LAN/prywatny po http
            // (obejście hairpin-NAT — połączenie z serwerem w tej samej sieci). Adres LAN nie opuszcza sieci
            // użytkownika, więc nie zdradza IP. Dla serwera .onion NIE dopuszczamy żadnej alternatywy — ma
            // zostać wyłącznie w Torze (F-01: onion nie może podsunąć adresu, który poszedłby poza Torem).
            val primaryClass = communityTransportOf(primary)
            // J-02: zapamiętane alternatywy (także zapisane surowo przez starsze wersje) normalizujemy jak adres główny
            val candidates = endpointHints.candidates(serverId, primary)
                .map { if (it == primary) it else normalizeCommunityEndpoint(it) ?: it }
                .distinct()
                .filter { ep ->
                    if (fetcherProvider(ep) == null) return@filter false
                    ep != primary && primaryClass != CommunityTransport.ONION &&
                        communityTransportOf(ep) == CommunityTransport.LAN_HTTP
                }
            if (candidates.isEmpty()) {
                return@coroutineScope CommunityManifestLoadResult.Failed("Brak alternatyw LAN")
            }
            val jobs = candidates.associateWith { ep ->
                async {
                    // R-08: pobieramy z kandydata `ep`, ale host do walidacji obrazów/push bierzemy z `primary`
                    // (kanoniczny adres serwera) — inaczej wygrana alternatywa LAN odrzuciłaby obrazy/push z domeny.
                    CommunityManifestLoader(fetcherProvider(ep)!!).load(ep, null, parseEndpoint = primary)
                }
            }.toMutableMap()
            var lastFail: CommunityManifestLoadResult =
                CommunityManifestLoadResult.Failed("Brak osiągalnego adresu serwera")
            while (jobs.isNotEmpty()) {
                val (ep, result) = select<Pair<String, CommunityManifestLoadResult>> {
                    jobs.forEach { (e, d) -> d.onAwait { e to it } }
                }
                jobs.remove(ep)
                when (result) {
                    is CommunityManifestLoadResult.Loaded -> {
                        // alternatyw ogłoszonych przez odpowiedź LAN NIE zapamiętujemy (niezweryfikowane źródło) i nie
                        // nadpisujemy zapamiętanych — lista pochodzi wyłącznie z odpowiedzi adresu głównego
                        jobs.values.forEach { it.cancel() }
                        return@coroutineScope result.copy(sourceEndpoint = ep)
                    }
                    CommunityManifestLoadResult.NotModified -> {
                        jobs.values.forEach { it.cancel() }
                        return@coroutineScope result
                    }
                    is CommunityManifestLoadResult.Failed -> lastFail = result
                }
            }
            lastFail
        }

    suspend fun addServer(rawEndpoint: String): AddServerOutcome {
        val endpoint = rawEndpoint.trim()
        if (fetcherProvider(endpoint) == null) return AddServerOutcome.InvalidEndpoint
        val serverId = communityServerId(endpoint)
        return when (val loaded = loadWithFallback(serverId, endpoint)) {
            is CommunityManifestLoadResult.Loaded -> {
                val consent = consentState(serverId, loaded.manifest.terms)
                // M-01: świeżo dodany serwer nie ma jeszcze zgody na AKTUALNY regulamin → NIE zapisujemy jego
                // treści (tylko metadane, by pokazać ekran zgody). Treść dojdzie po akceptacji (refresh).
                val accepted = consent == CommunityConsentState.ACCEPTED
                persistAndIngest(serverId, endpoint, loaded, accepted)
                AddServerOutcome.Added(
                    CommunityServerState(
                        record = store.server(serverId) ?: return AddServerOutcome.Failed("Nie zapisano serwera"),
                        manifest = loaded.manifest,
                        consentState = consent,
                        error = null,
                        favoriteItemIds = store.favoriteItemIds(serverId),
                    ),
                )
            }
            CommunityManifestLoadResult.NotModified -> AddServerOutcome.Failed("Serwer nie zwrócił manifestu")
            is CommunityManifestLoadResult.Failed -> AddServerOutcome.Failed(loaded.reason)
        }
    }

    suspend fun refresh(record: CommunityServerRecord): CommunityServerState {
        // zgoda na regulamin aplikacji = zgoda na kontakt z serwerem domyślnym; bez niej zero ruchu sieciowego do niego
        if (CommunityDefaults.isDefault(record.serverId) && !defaultServerAllowed()) return restoredState(record)
        if (fetcherProvider(record.endpoint) == null) {
            return CommunityServerState(record, null, null, "Nieobsługiwany transport")
        }
        val conditional = CommunityConditional(record.manifestEtag, record.manifestLastModified)
        val loaded = loadWithFallback(
            record.serverId, record.endpoint, conditional,
            knownTermsHash = record.manifestTermsHash,
            storedPush = record.push,
        )
        return when (loaded) {
            is CommunityManifestLoadResult.Loaded -> {
                var consent = consentState(record.serverId, loaded.manifest.terms)
                // D-01 → T-11: regulamin WBUDOWANEGO serwera domyślnego akceptujemy automatycznie tylko RAZ — przy pierwszym
                // dodaniu (brak jakiejkolwiek wcześniejszej decyzji). ZMIANĘ tekstu użytkownik musi zaakceptować sam:
                // UI pokazuje wtedy zasłonę z nowym regulaminem (albo wybór innego serwera głównego), a do czasu zgody
                // serwer domyślny nie jest kontaktowany.
                if (CommunityDefaults.isDefault(record.serverId) && consent != CommunityConsentState.ACCEPTED &&
                    store.latestConsent(record.serverId) == null
                ) {
                    decide(record.serverId, loaded.manifest.terms, accepted = true, presentedAtMillis = nowMillis())
                    consent = consentState(record.serverId, loaded.manifest.terms)
                }
                // H-01: treść zapisujemy tylko, gdy zgoda dotyczy AKTUALNEGO regulaminu. Po zmianie regulaminu
                // zapamiętujemy nowy hash (w persistManifest), a UI dostaje CONSENT_REQUIRED i ekran zgody.
                val accepted = consent == CommunityConsentState.ACCEPTED
                persistAndIngest(record.serverId, record.endpoint, loaded, accepted)
                if (accepted) CommunityContentEvents.contentStored(record.serverId)
                CommunityServerState(
                    record = store.server(record.serverId) ?: record,
                    manifest = loaded.manifest,
                    consentState = consent,
                    error = null,
                    favoriteItemIds = store.favoriteItemIds(record.serverId),
                )
            }
            // 304: regulamin i treść bez zmian – UI trzyma poprzednio pobrany manifest.
            CommunityManifestLoadResult.NotModified -> CommunityServerState(record, null, null, null)
            is CommunityManifestLoadResult.Failed -> CommunityServerState(record, null, null, loaded.reason)
        }
    }

    // M-6: zapis do bazy (zwłaszcza bulk replaceContent z niezaufanego manifestu) jest blokujący — robimy go
    // na Dispatchers.IO, nigdy na głównym wątku, żeby serwer nie mógł wywołać ANR dużym manifestem.
    /**
     * Zapisuje metadane serwera z manifestu (nazwa/transport/walidatory/hash regulaminu) ZAWSZE, a TREŚĆ
     * (posty, obrazy, usunięcia) tylko gdy [storeContent] = true. H-01/M-01: treść zapisujemy wyłącznie po
     * zgodzie na AKTUALNY regulamin — ale hash regulaminu i tak zapamiętujemy, żeby bramka zgody po haszu
     * (contactableServers/restoredState) działała również po jego zmianie.
     */
    private suspend fun persistManifest(
        serverId: String,
        endpoint: String,
        manifest: CommunityManifest,
        etag: String? = null,
        lastModified: String? = null,
        storeContent: Boolean = true,
        viaLan: Boolean = false,
    ) =
        withContext(Dispatchers.IO) {
            store.addServer(serverId, endpoint, nowMillis())
            // J-01: w trybie plików treści walidatory zapisuje dopiero persistAndIngest po KOMPLETNYM pobraniu
            // plików (inaczej 304 zatrzymałby resztę kolejki). Odpowiedź z LAN też ich nie zostawia — następne
            // pobranie z adresu głównego ma być bezwarunkowe, żeby zastąpić nieuwierzytelnioną treść (J-04).
            val keepValidators = storeContent && manifest.contentFiles.isEmpty() && !viaLan
            store.updateManifestCache(
                serverId = serverId,
                displayName = manifest.name,
                transport = manifest.transport,
                canBePrimary = manifest.canBePrimary,
                fetchedAtMillis = nowMillis(),
                // A-34: walidatory ETag/Last-Modified zapisujemy TYLKO gdy zapisaliśmy też treść. Inaczej po
                // akceptacji regulaminu refresh() wysyłałby If-None-Match → 304 → treść nigdy by się nie zapisała,
                // a karta zostawałaby „wymaga zgody". Bez walidatorów pierwszy refresh po zgodzie jest bezwarunkowy.
                etag = if (keepValidators) etag else null,
                lastModified = if (keepValidators) lastModified else null,
                termsHash = manifest.terms.contentHash,
                // R-01: zapamiętujemy push/primaryFeed, by restoredState odtworzył je po zimnym starcie/304
                pushJson = CommunityServerCodec.pushToJson(manifest.push),
                primaryFeedJson = CommunityServerCodec.primaryFeedToJson(manifest.primaryFeed),
                // R-09: tekst+wersja regulaminu — do offline „Przejrzyj regulamin" po zmianie regulaminu
                termsText = manifest.terms.text,
                termsVersion = manifest.terms.versionLabel,
            )
            // updateManifestCache nie kasuje walidatorów przy null — robimy to jawnie (A-34, J-01)
            if (!keepValidators) store.updateManifestValidators(serverId, null, null)
            if (storeContent) {
                // Tryb plików treści (manifest ma `content`): treść NIE jest w manifeście, tylko w osobnych
                // plikach dociąganych i DOKŁADANYCH (append) w [ingestNewContentFiles]. Dlatego TU nie wołamy
                // replaceContent — inaczej skasowałby nie-ulubione pozycje z wcześniej pobranych plików.
                if (manifest.contentFiles.isEmpty() && viaLan) {
                    // J-04: manifest z adresu LAN (http, bez uwierzytelnienia; jedyną bramką jest publiczny
                    // regulamin) tylko DOKŁADA treść — nie kasuje zapisanej. Pełny zapis zrobi najbliższe
                    // pobranie z adresu głównego (walidatory wyczyszczone wyżej, więc bez 304).
                    store.appendContent(serverId, CommunityContentCodec.toStored(serverId, manifest))
                } else if (manifest.contentFiles.isEmpty()) {
                    // klasyczny manifest z treścią w środku: pełny zapis (ZSTD + znormalizowane author_id)
                    store.replaceContent(serverId, CommunityContentCodec.toStored(serverId, manifest))
                    // C-21: pozycje z plików treści właśnie zniknęły — rejestr pobranych plików też, inaczej po
                    // powrocie do trybu `content` te same pliki uznano by za pobrane i treść by nie wróciła
                    contentFileTracker.forget(serverId)
                }
                // jawne usunięcia z manifestu (działa w obu trybach): kasujemy całkowicie (także ulubione)
                if (manifest.deletions.isNotEmpty()) {
                    store.deleteItems(serverId, manifest.deletions)
                }
                logCommunityTraffic(serverId, manifest)
            }
        }

    /**
     * Zapis manifestu i (po zgodzie) dociągnięcie plików treści. J-01: walidatory ETag/Last-Modified dla trybu
     * plików treści zapisujemy dopiero, gdy kolejka jest KOMPLETNA — po limicie 500 plików albo przerwaniu tury
     * zostają wyczyszczone, więc kolejny cykl jest bezwarunkowy i dociąga resztę zamiast dostać 304.
     */
    private suspend fun persistAndIngest(
        serverId: String,
        endpoint: String,
        loaded: CommunityManifestLoadResult.Loaded,
        accepted: Boolean,
    ) {
        val viaLan = loaded.sourceEndpoint != null && loaded.sourceEndpoint != endpoint
        persistManifest(
            serverId, endpoint, loaded.manifest, loaded.etag, loaded.lastModified,
            storeContent = accepted, viaLan = viaLan,
        )
        if (!accepted || loaded.manifest.contentFiles.isEmpty()) return
        val complete = ingestNewContentFiles(serverId, endpoint, loaded.manifest, loaded.sourceEndpoint)
        if (complete && !viaLan) {
            withContext(Dispatchers.IO) { store.updateManifestValidators(serverId, loaded.etag, loaded.lastModified) }
        }
    }

    /**
     * Tryb plików treści: dla manifestu z listą `content` dociąga TYLKO nazwy, których jeszcze nie pobrano
     * (append-only — skaluje się do tysięcy plików), parsuje każdy jak treść i DOKŁADA do bazy (appendContent).
     * Każdy plik musi być na TYM SAMYM hoście co serwer (albo LAN↔LAN) — ta sama twarda granica anty-beacon
     * co obrazy; dla .onion pobranie i tak idzie przez Tor (fetcher). Wołać tylko po zgodzie na regulamin.
     * Zwraca true, gdy kolejka jest kompletna (nic nie zostało na kolejny cykl) — J-01.
     */
    private suspend fun ingestNewContentFiles(
        serverId: String,
        endpoint: String,
        manifest: CommunityManifest,
        sourceEndpoint: String? = null,
    ): Boolean {
        if (manifest.contentFiles.isEmpty()) return true
        // A-09: pliki treści rozwiązujemy względem adresu, z którego FAKTYCZNIE przyszedł manifest (np. LAN za
        // hairpin-NAT) — host jest i tak sprawdzany w resolveContentFileUrl (ten sam host co źródło).
        val base = sourceEndpoint?.takeIf { fetcherProvider(it) != null } ?: endpoint
        val fetcher = fetcherProvider(base) ?: return false
        // B-02: pliki pobrane przez adres LAN (failover, http bez uwierzytelnienia) traktujemy jak manifest
        // z LAN — treść dokładamy, ale ich `deletions` ignorujemy i NIE oznaczamy plików jako pobranych, więc
        // przy najbliższym odświeżeniu adresem głównym zostaną pobrane ponownie i wtedy zastosowane w całości.
        val viaLan = base != endpoint
        val already = contentFileTracker.ingested(serverId)
        // R-11: zachowujemy KOLEJNOŚĆ z manifestu (append-only). `already` może być nieuporządkowane, ale
        // bierzemy tylko PREFIKS jeszcze niepobranych nazw w kolejności manifestu i zatrzymujemy się na
        // pierwszej dziurze — inaczej np. plik c2 z `deletions` dla pozycji z (nieudanego) c1 skasowałby ją
        // „na sucho", a przy następnym odświeżeniu c1 dołożyłby ją z powrotem (wycofana treść wraca).
        // Pliki zdjęte z `content`: ich pozycje znikają (poza ulubionymi). Tylko z adresu głównego i tylko, gdy
        // lista nie została ucięta limitem parsera — inaczej „zniknięcie" mogłoby być skutkiem obcięcia.
        if (!viaLan && manifest.contentFiles.size < CommunityManifestParser.MAX_CONTENT_FILES) {
            val listed = manifest.contentFiles.mapTo(HashSet()) { it.name }
            val gone = contentFileTracker.trackedFiles(serverId) - listed
            if (gone.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    removeFileItems(serverId, gone.flatMap { contentFileTracker.itemsOf(serverId, it) }.toSet(), except = gone)
                    contentFileTracker.forgetFiles(serverId, gone)
                }
            }
        }
        // nowe nazwy ORAZ nazwy z nową wersją (`{"name","v"}`) — te drugie pobieramy ponownie
        val fresh = manifest.contentFiles.filterNot { it.key in already }
        if (fresh.isEmpty()) return true
        return withContext(Dispatchers.IO) {
            // J-01: ucięcie limitem albo przerwana tura = kolejka niekompletna
            var complete = fresh.size <= MAX_NEW_CONTENT_FILES_PER_REFRESH
            val done = ArrayList<String>()
            try {
                for (file in fresh.take(MAX_NEW_CONTENT_FILES_PER_REFRESH)) {
                    val name = file.name
                    val url = resolveContentFileUrl(base, name)
                    if (url == null) {
                        // zła/obca nazwa (inny host, traversal) — nie jest „dziurą" w treści, pomijamy i idziemy dalej
                        done += file.key
                        continue
                    }
                    val res = fetcher.fetch(url, null)
                    if (res !is CommunityFetchResult.Success) {
                        // R-11: PORAŻKA pobrania = koniec tury (bez stosowania późniejszych `deletions`/treści przed
                        // brakującym plikiem). A-08: ale licznik porażek per nazwa — plik TRWALE martwy (N tur z
                        // rzędu) zostaje pominięty, żeby jeden zły wpis nie blokował na zawsze całego ingestu.
                        // B-03: liczymy TYLKO porażki trwałe (4xx/dane binarne) i tylko z adresu głównego —
                        // timeout/brak sieci/niegotowy Tor kończą turę bez zbliżania pliku do pominięcia.
                        val permanent = res is CommunityFetchResult.Failure && res.permanent
                        if (!permanent || viaLan) {
                            complete = false
                            break
                        }
                        val failures = contentFileTracker.recordFailure(serverId, file.key)
                        if (failures >= MAX_CONTENT_FILE_FAILURES) {
                            DiagnosticLogStore.event(
                                category = DiagnosticCategory.COMMUNITY,
                                level = DiagnosticLevel.WARNING,
                                name = "CONTENT_FILE_SKIPPED",
                                fields = mapOf("server" to serverId, "failures" to failures),
                            )
                            done += file.key
                            continue
                        }
                        complete = false
                        break
                    }
                    val page = CommunityManifestParser.parseContentFile(res.body, manifest, endpoint)
                    if (page != null) {
                        val stored = CommunityContentCodec.toStored(serverId, page)
                        store.appendContent(serverId, stored)
                        if (!viaLan) {
                            // nowa wersja pliku zastępuje poprzednią: pozycje, których w nim już nie ma (np. wpis
                            // bez linku po edycji dostał nowy klucz z treści), znikają — poza ulubionymi
                            val now = stored.items.mapTo(HashSet()) { it.itemId }
                            val dropped = contentFileTracker.itemsOf(serverId, name) - now
                            if (dropped.isNotEmpty()) removeFileItems(serverId, dropped, except = setOf(name))
                            contentFileTracker.setItems(serverId, name, now)
                        }
                        if (!viaLan && page.deletions.isNotEmpty()) store.deleteItems(serverId, page.deletions)
                    }
                    // R-11: postęp zapisujemy PO KAŻDYM pobranym pliku (anulowanie nie cofnie już zrobionego)
                    if (!viaLan) contentFileTracker.remember(serverId, listOf(file.key))
                }
            } finally {
                // A-08b: pominięte nazwy zapisujemy WSADOWO raz po pętli (nawet przy anulowaniu)
                if (done.isNotEmpty() && !viaLan) contentFileTracker.remember(serverId, done)
            }
            complete
        }
    }

    /**
     * Usuwa pozycje, które przestały pochodzić z pliku (nowa wersja / plik zdjęty z listy). Zostawia ulubione
     * (to robi tylko jawne `deletions`) oraz pozycje, które nadal przychodzą z INNEGO pliku niż [except].
     */
    private fun removeFileItems(serverId: String, itemIds: Set<String>, except: Set<String>) {
        if (itemIds.isEmpty()) return
        val keep = HashSet(store.favoriteItemIds(serverId))
        for (other in contentFileTracker.trackedFiles(serverId)) {
            if (other in except) continue
            keep += contentFileTracker.itemsOf(serverId, other).filter { it in itemIds }
        }
        val remove = itemIds - keep
        if (remove.isNotEmpty()) store.deleteItems(serverId, remove)
    }

    /**
     * Zamienia nazwę pliku treści (względną lub URL) na pełny URL do pobrania — ale TYLKO gdy wychodzi na
     * ten sam host co serwer (albo LAN↔LAN) i bez zmiany schematu. Inaczej null (ochrona przed beaconem na
     * obcym hoście / zmianą na groźny schemat).
     */
    private fun resolveContentFileUrl(endpoint: String, name: String): String? {
        val base = endpoint.toHttpUrlOrNull() ?: return null
        val resolved = base.resolve(name) ?: return null
        if (resolved.scheme != base.scheme) return null
        val baseHost = base.host.lowercase().removePrefix("www.")
        val resolvedHost = resolved.host.lowercase().removePrefix("www.")
        // R-15: WYŁĄCZNIE ten sam host co serwer (jak imageHostAllowed, L-2). Dawniej dopuszczaliśmy dowolny
        // host prywatny↔prywatny — to SSRF: serwer LAN mógł kazać apce GET-ować router/IoT (np. /cgi-bin/reboot).
        return if (resolvedHost == baseHost) resolved.toString() else null
    }

    /**
     * #7: w trybie deweloperskim zapisujemy sam RUCH z serwerów – bez treści: id serwera,
     * platformę i czas co do minuty. Usunięte posty też zostają w logu ([DiagnosticLogStore]
     * i tak działa tylko, gdy diagnostyka włączona). Nazwy autorów/tekst NIE trafiają do logu.
     */
    private fun logCommunityTraffic(serverId: String, manifest: CommunityManifest) {
        val minute = (nowMillis() / 60_000L) * 60_000L
        fun log(name: String, platform: String) = DiagnosticLogStore.event(
            category = DiagnosticCategory.COMMUNITY,
            level = DiagnosticLevel.INFO,
            name = name,
            fields = mapOf("server" to serverId, "platform" to platform, "atMinute" to minute),
        )
        manifest.creators.forEach { log("ITEM", "youtube") }
        manifest.posts.forEach { log("ITEM", "youtube") }
        manifest.foreignItems.forEach { log("ITEM", it.platform) }
        manifest.articles.forEach { log("ITEM", if (it.kind == CommunityArticleKind.NEWSLETTER) "newsletter" else "article") }
        manifest.memes.forEach { log("ITEM", "meme") } // C-22g
        manifest.ads.forEach { log("ITEM", "ad") }
        manifest.contests.forEach { log("ITEM", "contest") }
        manifest.deletions.forEach { itemId ->
            DiagnosticLogStore.event(
                category = DiagnosticCategory.COMMUNITY,
                level = DiagnosticLevel.INFO,
                name = "DELETED",
                fields = mapOf("server" to serverId, "item" to itemId, "atMinute" to minute),
            )
        }
    }

    /**
     * Stan odtworzony z LOKALNEGO magazynu (bez sieci) – żeby po restarcie/offline zakładka
     * pokazała ostatnio zapisaną treść, zanim (albo gdy nie) dojdzie świeży manifest.
     * Pokazujemy tylko serwery z zapisaną ZGODĄ (treść i tak zapisano wyłącznie po akceptacji).
     */
    // T-07: zdekodowana treść (rozpakowanie ZSTD + JSON każdej pozycji) per serwer, wiązana z TĄ SAMĄ instancją
    // listy z magazynu. LocalDatabaseCommunityStore zwraca tę samą instancję, póki treść się nie zmieni, a każdy
    // zapis daje nową — porównanie tożsamości (===) nie może więc zwrócić nieaktualnego wyniku.
    private val decodedCache = java.util.concurrent.ConcurrentHashMap<String, Pair<List<CommunityStoredItem>, CommunityContentLists>>()

    private fun decodedContent(serverId: String, items: List<CommunityStoredItem>): CommunityContentLists {
        decodedCache[serverId]?.let { (source, lists) -> if (source === items) return lists }
        return CommunityContentCodec.fromStored(items).also { decodedCache[serverId] = items to it }
    }

    fun restoredState(record: CommunityServerRecord): CommunityServerState {
        val consent = store.latestConsent(record.serverId)
        if (consent == null || !consent.accepted) {
            return CommunityServerState(record, null, null, null)
        }
        // H-01: jeśli regulamin serwera zmienił się od czasu zgody (hash z ostatniego manifestu ≠ hash zgody),
        // NIE odtwarzamy stanu jako ACCEPTED — inaczej powiadomienia i zakładka działałyby na nieaktualnej
        // zgodzie. Null = hash jeszcze nieznany (legacy) → zachowujemy dotychczasowe zachowanie.
        if (record.manifestTermsHash != null && consent.termsHash != record.manifestTermsHash) {
            // R-09: regulamin się zmienił. Zamiast ślepego zaułka dajemy UI nowy regulamin do przejrzenia
            // (z bazy, offline) i stan CONSENT_REQUIRED — użytkownik może go zaakceptować bez usuwania serwera.
            val reviewable = record.manifestTermsText?.let {
                CommunityTerms(record.manifestTermsVersion ?: "1", it, record.manifestTermsHash)
            }
            return CommunityServerState(
                record, null, CommunityConsentState.CONSENT_REQUIRED, null, reviewableTerms = reviewable,
            )
        }
        val lists = decodedContent(record.serverId, store.storedItems(record.serverId))
        // A-06: serwer PUSH-ONLY (np. domyślny: manifest tylko `push`, bez treści) ma puste listy, ale MUSI
        // odtworzyć manifest z `push`/`primaryFeed` — inaczej syncFromServers pomija go i replaceServers wyrzuca
        // z rejestru push (budziki ignorowane). Pusty wpis bez niczego do odtworzenia zwracamy jak dawniej.
        if (lists.isEmpty && record.push == null && record.primaryFeed == null) {
            return CommunityServerState(record, null, null, null)
        }
        val terms = CommunityTerms(consent.termsVersionLabel, consent.termsText, consent.termsHash)
        val manifest = CommunityManifest(
            contractVersion = CommunityManifestParser.SUPPORTED_CONTRACT_VERSION,
            name = record.displayName?.takeIf { it.isNotBlank() } ?: "Serwer społeczności",
            description = null,
            transport = record.transport ?: CommunityTransportKind.HTTPS_POLL,
            capabilities = lists.impliedCapabilities(),
            terms = terms,
            canBePrimary = record.canBePrimary,
            creators = lists.creators,
            foreignItems = lists.foreignItems,
            posts = lists.posts,
            ads = lists.ads,
            contests = lists.contests,
            articles = lists.articles,
            memes = lists.memes,
            // R-01: odtwarzamy push/primaryFeed z bazy — inaczej syncFromServers pominąłby serwer i
            // replaceServers wyrzuciłby go z rejestru push (budziki ignorowane).
            push = record.push,
            primaryFeed = record.primaryFeed,
        )
        return CommunityServerState(
            record, manifest, CommunityConsentState.ACCEPTED, null,
            favoriteItemIds = store.favoriteItemIds(record.serverId),
        )
    }

    /** dodaje/usuwa pozycję z ulubionych (nie zniknie z bazy przy retencji ani aktualizacji). */
    fun setFavorite(serverId: String, itemId: String, favorite: Boolean) =
        store.setItemFavorite(serverId, itemId, favorite)

    fun favoriteItemIds(serverId: String): Set<String> = store.favoriteItemIds(serverId)

    /** retencja treści: osobne okna dla tekstu i obrazów (0 = bez limitu danej osi). */
    /**
     * M-09: 0 dni = „nie zapisuj" (tnij CAŁĄ treść poza ulubionymi, także pozycje bez daty) — zgodnie z UI.
     * N > 0 = tnij starsze niż N dni (tylko datowane; bez daty zostają, bo nie znamy ich wieku). Ulubione
     * zawsze zostają. Wcześniej 0 znaczyło „bez limitu" (odwrotnie niż opisywało UI — pułapka prywatności).
     */
    fun pruneContent(textRetentionDays: Int, imageRetentionDays: Int, nowMs: Long = nowMillis()) {
        val day = 24L * 60L * 60L * 1000L
        store.pruneContent(
            textBeforeMillis = if (textRetentionDays > 0) nowMs - textRetentionDays * day else nowMs,
            imageBeforeMillis = if (imageRetentionDays > 0) nowMs - imageRetentionDays * day else nowMs,
            pruneAllText = textRetentionDays <= 0,
            pruneAllImage = imageRetentionDays <= 0,
        )
    }

    /** zapisuje decyzję usera (zgodę lub odmowę) dla aktualnego regulaminu. */
    fun decide(serverId: String, terms: CommunityTerms, accepted: Boolean, presentedAtMillis: Long) {
        store.recordConsent(
            CommunityConsentGate.recordFor(serverId, terms, accepted, presentedAtMillis, nowMillis()),
        )
    }

    fun consentState(serverId: String, terms: CommunityTerms): CommunityConsentState =
        CommunityConsentGate.evaluate(store.consentForTerms(serverId, terms.contentHash), terms)

    /**
     * Przeładowuje bazę treści ze wszystkich serwerów i WERYFIKUJE zmiany SHA-256: liczymy odcisk
     * zapisanej treści przed i po świeżym pobraniu; jeśli się różni, wiersze zostały odświeżone.
     * Ulubione zostają (upsert w [CommunityStore.replaceContent]), a jawne usunięcia z manifestu
     * są respektowane. Zwraca licznik: sprawdzone / zmienione / błędne.
     */
    /**
     * Serwery, z którymi WOLNO się kontaktować: WŁĄCZONE i z ZAAKCEPTOWANYM regulaminem (M-3/N-10). Używane
     * i przy przeładowaniu z pushu, i przy odświeżaniu z UI — żeby nigdzie nie odpytywać wyłączonych/odrzuconych.
     */
    fun contactableServers(): List<CommunityServerRecord> =
        store.servers().filter { record ->
            if (!record.enabled) return@filter false
            // regulamin aplikacji nie zaakceptowany → serwer domyślny nie jest kontaktowany
            if (CommunityDefaults.isDefault(record.serverId) && !defaultServerAllowed()) return@filter false
            val consent = store.latestConsent(record.serverId) ?: return@filter false
            if (!consent.accepted) return@filter false
            // H-01: zgoda musi dotyczyć AKTUALNEGO regulaminu (hash z ostatniego manifestu). Po zmianie
            // regulaminu record.manifestTermsHash rozjeżdża się z consent.termsHash → serwer niekontaktowalny.
            // Null = hash jeszcze nieznany (świeża/migrowana instalacja) → pozwalamy na JEDNO pobranie, bo i tak
            // zapis treści oraz powiadomienia są bramkowane zgodą na świeżo pobrany regulamin (reload/refresh).
            record.manifestTermsHash == null || consent.termsHash == record.manifestTermsHash
        }

    suspend fun reloadFromServers(): CommunityReloadResult {
        // M-3: łączymy się TYLKO z serwerami włączonymi i z zaakceptowanym regulaminem (patrz contactableServers).
        val servers = contactableServers()
        var changed = 0
        var failed = 0
        for (record in servers) {
            if (fetcherProvider(record.endpoint) == null) {
                failed++
                continue
            }
            // B-04: jak w refresh() — failover LAN (hairpin-NAT) z tym samym regulaminem i zapisanym push.
            // J-01: „Przeładuj bazę" to PEŁNE pobranie — bez If-None-Match/If-Modified-Since, więc 304 nie zablokuje
            // dociągnięcia plików treści, których nie pobrano wcześniej.
            val loaded = loadWithFallback(
                record.serverId, record.endpoint, conditional = null,
                knownTermsHash = record.manifestTermsHash,
                storedPush = record.push,
            )
            when (loaded) {
                is CommunityManifestLoadResult.Loaded -> {
                    // H-01: po pobraniu sprawdzamy zgodę na ŚWIEŻO pobrany regulamin. Jeśli się zmienił,
                    // zapamiętujemy tylko nowy hash (persistManifest, storeContent=false) i NIE zapisujemy
                    // treści ani nie liczymy jej jako zmiany — serwer wypadnie z contactableServers do zgody.
                    // E-09c: jak w refresh() (D-01) — wbudowany serwer domyślny akceptuje zmieniony regulamin sam,
                    // inaczej „Przeładuj bazę" wyrzucała go z contactableServers do najbliższego refresh
                    if (CommunityDefaults.isDefault(record.serverId) &&
                        consentState(record.serverId, loaded.manifest.terms) != CommunityConsentState.ACCEPTED &&
                        store.latestConsent(record.serverId) == null // T-11: zmiany regulaminu nie akceptujemy za użytkownika
                    ) {
                        decide(record.serverId, loaded.manifest.terms, accepted = true, presentedAtMillis = nowMillis())
                    }
                    val accepted = consentState(record.serverId, loaded.manifest.terms) == CommunityConsentState.ACCEPTED
                    val before = communityContentSha256(store.storedItems(record.serverId))
                    // tryb plików treści: nowe pliki (append) dociągamy ZANIM policzymy skrót — inaczej
                    // ich treść nie weszłaby do porównania „czy coś się zmieniło".
                    persistAndIngest(record.serverId, record.endpoint, loaded, accepted)
                    val after = communityContentSha256(store.storedItems(record.serverId))
                    if (accepted && before != after) changed++
                }
                // bez walidatorów serwer nie powinien odpowiedzieć 304; gdyby jednak — to sukces bez zmian
                CommunityManifestLoadResult.NotModified -> Unit
                is CommunityManifestLoadResult.Failed -> failed++
            }
        }
        return CommunityReloadResult(checked = servers.size, changed = changed, failed = failed)
    }

    fun storedServers(): List<CommunityServerRecord> = store.servers()
    fun setEnabled(serverId: String, enabled: Boolean) = store.setEnabled(serverId, enabled)
    fun setNotifyEnabled(serverId: String, enabled: Boolean) = store.setNotifyEnabled(serverId, enabled)
    fun setNotifyMuted(serverId: String, muted: Set<String>) = store.setNotifyMuted(serverId, muted)
    fun setDisplayMuted(serverId: String, muted: Set<String>) = store.setDisplayMuted(serverId, muted)
    fun setAdsEnabled(serverId: String, enabled: Boolean) = store.setAdsEnabled(serverId, enabled)
    fun removeServer(serverId: String) {
        if (CommunityDefaults.isDefault(serverId)) return // WBUDOWANY serwer domyślny jest NIEUSUWALNY
        store.deleteServer(serverId)
        endpointHints.forget(serverId) // nie zostawiaj zatrutych altEndpoints po usunięciu (F-01)
        contentFileTracker.forget(serverId) // po usunięciu serwera pozwól pobrać pliki treści od nowa
    }

    /**
     * Zapewnia obecność WBUDOWANEGO serwera domyślnego (best-effort; wymaga sieci do pobrania manifestu).
     * Auto-akceptuje regulamin (wbudowany — user nic nie robi).
     * Zwraca true, gdy serwer domyślny jest obecny po wywołaniu (świeżo dodany albo już był).
     */
    suspend fun ensureDefaultServer(): Boolean {
        if (!defaultServerAllowed()) return false // dopóki użytkownik nie zaakceptuje regulaminu aplikacji — nie łączymy się
        store.server(CommunityDefaults.SERVER_ID)?.let { existing ->
            // D-01: regulamin zmienił się od zgody (hash z ostatniego manifestu ≠ zgoda) — akceptujemy zapamiętany
            // tekst od razu (offline), żeby serwer wrócił do kontaktowalnych; świeży refresh dokończy resztę
            val hash = existing.manifestTermsHash
            val text = existing.manifestTermsText
            // T-11: tylko gdy użytkownik NIGDY nie podjął decyzji (np. pierwsze dodanie offline); zmiana tekstu = jego zgoda
            if (hash != null && text != null && store.latestConsent(existing.serverId) == null) {
                decide(existing.serverId, CommunityTerms(existing.manifestTermsVersion ?: "1", text, hash), accepted = true, presentedAtMillis = nowMillis())
            }
            return true
        }
        val outcome = addServer(CommunityDefaults.ENDPOINT)
        if (outcome is AddServerOutcome.Added) {
            outcome.state.manifest?.terms?.let { terms ->
                decide(CommunityDefaults.SERVER_ID, terms, accepted = true, presentedAtMillis = nowMillis())
            }
            store.server(CommunityDefaults.SERVER_ID)?.let { runCatching { refresh(it) } }
            return true
        }
        return false // brak sieci / placeholder niedostępny — spróbujemy ponownie przy następnym starcie
    }

    internal companion object {
        // ile NOWYCH plików treści pobrać w jednym odświeżeniu (reszta w kolejnych cyklach) — bufor na
        // pierwszy sync serwera z tysiącami plików, bez zamrażania jednego odświeżenia ani zalewania Tora.
        const val MAX_NEW_CONTENT_FILES_PER_REFRESH = 500
        // A-08: po tylu kolejnych porażkach pobrania plik treści uznajemy za trwale martwy i pomijamy
        const val MAX_CONTENT_FILE_FAILURES = 5
    }
}
