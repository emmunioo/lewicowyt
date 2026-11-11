package pl.lewicowyt.notifier.community

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * orkiestracja: dodanie serwera zapisuje go i zwraca stan CONSENT_REQUIRED, zgoda/odmowa
 * przełącza stan, a bez ważnego endpointu albo z padniętym pobraniem NIC nie ląduje w bazie.
 * NIGDY nie łączymy bez ACCEPTED dla aktualnego regulaminu.
 */
class CommunityRepositoryTest {

    private class FakeStore : CommunityStore {
        val serversMap = LinkedHashMap<String, CommunityServerRecord>()
        val consents = mutableListOf<CommunityConsentRecord>()
        val itemsMap = LinkedHashMap<String, List<CommunityStoredItem>>()
        val authorsMap = LinkedHashMap<String, CommunityAuthorRecord>()

        override fun servers() = serversMap.values.toList()
        override fun server(serverId: String) = serversMap[serverId]
        override fun addServer(serverId: String, endpoint: String, addedAtMillis: Long) {
            if (serverId in serversMap) return // CONFLICT_IGNORE: nie kasuj enabled/added
            serversMap[serverId] = CommunityServerRecord(
                serverId = serverId,
                endpoint = endpoint,
                addedAtMillis = addedAtMillis,
                enabled = true,
                displayName = null,
                transport = null,
                lastManifestFetchMillis = 0L,
            )
        }
        override fun updateManifestCache(
            serverId: String,
            displayName: String?,
            transport: CommunityTransportKind?,
            canBePrimary: Boolean,
            fetchedAtMillis: Long,
            etag: String?,
            lastModified: String?,
            termsHash: String?,
            pushJson: String?,
            primaryFeedJson: String?,
            termsText: String?,
            termsVersion: String?,
        ) {
            serversMap[serverId] = serversMap[serverId]?.copy(
                displayName = displayName,
                transport = transport,
                canBePrimary = canBePrimary,
                lastManifestFetchMillis = fetchedAtMillis,
                // brak walidatora nie kasuje poprzedniego (jak w produkcyjnym DAO)
                manifestEtag = etag?.takeIf { it.isNotBlank() } ?: serversMap[serverId]?.manifestEtag,
                manifestLastModified = lastModified?.takeIf { it.isNotBlank() }
                    ?: serversMap[serverId]?.manifestLastModified,
                // hash regulaminu zapisujemy ZAWSZE (także przy zmianie) — jak produkcyjny DAO
                manifestTermsHash = termsHash,
                // R-01: push/primaryFeed ze świeżego manifestu (null = serwer usunął) — jak produkcyjny DAO
                push = CommunityServerCodec.pushFromJson(pushJson),
                primaryFeed = CommunityServerCodec.primaryFeedFromJson(primaryFeedJson),
                // R-09: tekst+wersja regulaminu z manifestu
                manifestTermsText = termsText,
                manifestTermsVersion = termsVersion,
            ) ?: return
        }
        override fun updateManifestValidators(serverId: String, etag: String?, lastModified: String?) {
            serversMap[serverId] = serversMap[serverId]?.copy(
                manifestEtag = etag?.takeIf { it.isNotBlank() },
                manifestLastModified = lastModified?.takeIf { it.isNotBlank() },
            ) ?: return
        }
        override fun setEnabled(serverId: String, enabled: Boolean) {
            serversMap[serverId] = serversMap[serverId]?.copy(enabled = enabled) ?: return
        }
        override fun setNotifyEnabled(serverId: String, enabled: Boolean) {
            serversMap[serverId] = serversMap[serverId]?.copy(notifyEnabled = enabled) ?: return
        }
        override fun setNotifyMuted(serverId: String, muted: Set<String>) {
            serversMap[serverId] = serversMap[serverId]?.copy(
                notifyMuted = muted.map { it.lowercase() }.toSet(),
            ) ?: return
        }
        override fun setDisplayMuted(serverId: String, muted: Set<String>) {
            serversMap[serverId] = serversMap[serverId]?.copy(
                displayMuted = muted.map { it.lowercase() }.toSet(),
            ) ?: return
        }
        override fun setAdsEnabled(serverId: String, enabled: Boolean) {
            serversMap[serverId] = serversMap[serverId]?.copy(adsEnabled = enabled) ?: return
        }
        override fun deleteServer(serverId: String) {
            serversMap.remove(serverId)
            consents.removeAll { it.serverId == serverId }
            itemsMap.remove(serverId)
        }
        val favorites = linkedMapOf<String, MutableSet<String>>()
        override fun replaceContent(serverId: String, content: CommunityStoredContent) {
            content.authors.forEach { authorsMap[it.authorId] = it }
            val fav = favorites[serverId].orEmpty()
            itemsMap[serverId] = content.items.map { it.copy(favorite = it.itemId in fav) }
        }
        override fun appendContent(serverId: String, content: CommunityStoredContent) {
            content.authors.forEach { authorsMap[it.authorId] = it }
            val fav = favorites[serverId].orEmpty()
            // upsert po itemId bez kasowania istniejących
            val merged = LinkedHashMap<String, CommunityStoredItem>()
            itemsMap[serverId].orEmpty().forEach { merged[it.itemId] = it }
            content.items.forEach { merged[it.itemId] = it.copy(favorite = it.itemId in fav) }
            itemsMap[serverId] = merged.values.toList()
        }
        override fun storedItems(serverId: String): List<CommunityStoredItem> =
            itemsMap[serverId].orEmpty().map { item ->
                val author = item.authorId?.let { authorsMap[it] }
                item.copy(
                    authorName = author?.name,
                    authorPlatform = author?.platform,
                    authorAvatarUrl = author?.avatarUrl,
                )
            }
        override fun favoriteItemIds(serverId: String): Set<String> = favorites[serverId].orEmpty()
        override fun setItemFavorite(serverId: String, itemId: String, favorite: Boolean) {
            val set = favorites.getOrPut(serverId) { linkedSetOf() }
            if (favorite) set += itemId else set -= itemId
            itemsMap[serverId] = itemsMap[serverId].orEmpty().map { if (it.itemId == itemId) it.copy(favorite = favorite) else it }
        }
        override fun deleteItems(serverId: String, itemIds: Collection<String>) {
            itemsMap[serverId] = itemsMap[serverId].orEmpty().filterNot { it.itemId in itemIds }
            favorites[serverId]?.removeAll(itemIds.toSet())
        }
        override fun pruneContent(
            textBeforeMillis: Long,
            imageBeforeMillis: Long,
            pruneAllText: Boolean,
            pruneAllImage: Boolean,
        ) {
            itemsMap.keys.toList().forEach { sid ->
                itemsMap[sid] = itemsMap[sid].orEmpty().filterNot {
                    if (it.favorite) return@filterNot false
                    if (pruneAllText) true // 0 dni = nie zapisuj: całość poza ulubionymi (także bez daty)
                    else (it.publishedMs != null && it.publishedMs < textBeforeMillis)
                }
            }
        }
        override fun recordConsent(record: CommunityConsentRecord) {
            consents.removeAll { it.serverId == record.serverId && it.termsHash == record.termsHash }
            consents += record
        }
        override fun consentForTerms(serverId: String, termsHash: String) =
            consents.lastOrNull { it.serverId == serverId && it.termsHash == termsHash }
        override fun latestConsent(serverId: String) =
            consents.filter { it.serverId == serverId }.sortedBy { it.decidedAtMillis }.lastOrNull() // remis czasu → ostatnio zapisana
    }

    /**
     * Protokół wymaga, by manifest NIE niósł treści. Testy wygodnie opisują serwer jednym JSON-em „z treścią w
     * środku", więc ten fetcher rozdziela go tak, jak zrobiłby to prawdziwy serwer: manifest dostaje tylko
     * konfigurację + plik `inline-content.json` (wersja = skrót treści), a ten plik niesie treść i usunięcia.
     */
    private class FakeFetcher(private val result: CommunityFetchResult) : CommunityFetcher {
        override val kind = CommunityTransportKind.HTTPS_POLL
        override suspend fun fetch(url: String, conditional: CommunityConditional?): CommunityFetchResult {
            val ok = result as? CommunityFetchResult.Success ?: return result
            return splitInline(ok, url)
        }
    }

    companion object {
        private val CONTENT_KEYS = listOf("creators", "foreignItems", "posts", "ads", "contests", "articles", "memes", "deletions")
        const val INLINE_FILE = "inline-content.json"

        fun splitInline(ok: CommunityFetchResult.Success, url: String): CommunityFetchResult {
            val root = runCatching { org.json.JSONObject(ok.body) }.getOrNull() ?: return ok
            val keys = CONTENT_KEYS.filter { root.has(it) }
            if (keys.isEmpty()) return ok
            val content = org.json.JSONObject()
            keys.forEach { content.put(it, root.get(it)); root.remove(it) }
            if (url.endsWith("/$INLINE_FILE")) return CommunityFetchResult.Success(content.toString(), null, null)
            val files = root.optJSONArray("content") ?: org.json.JSONArray().also { root.put("content", it) }
            files.put(INLINE_FILE)
            val versions = root.optJSONObject("contentVersions") ?: org.json.JSONObject().also { root.put("contentVersions", it) }
            versions.put(INLINE_FILE, content.toString().hashCode().toString())
            return CommunityFetchResult.Success(root.toString(), ok.etag, ok.lastModified)
        }
    }

    /** zwraca różne odpowiedzi per URL (manifest + pliki treści); brak w mapie = Failure. */
    private class MapFetcher(private val byUrl: Map<String, CommunityFetchResult>) : CommunityFetcher {
        override val kind = CommunityTransportKind.HTTPS_POLL
        override suspend fun fetch(url: String, conditional: CommunityConditional?) =
            byUrl[url] ?: CommunityFetchResult.Failure("HTTP 404 $url", permanent = true)
    }

    private val manifestJson = """
        {
          "contractVersion": 1,
          "name": "Serwer X",
          "transport": "https",
          "capabilities": ["community_creators"],
          "terms": { "version": "1", "text": "Regulamin serwera X." }
        }
    """.trimIndent()

    private val endpoint = "https://serwer.example/manifest"
    private val serverId = communityServerId(endpoint)

    private fun repo(
        store: CommunityStore,
        fetcher: CommunityFetcher? = FakeFetcher(CommunityFetchResult.Success(manifestJson, null, null)),
    ) = CommunityRepository(
        store = store,
        fetcherProvider = { _ -> fetcher },
        nowMillis = { 1_000L },
    )

    @Test
    fun `adding a server persists it and needs consent`() = runBlocking {
        val store = FakeStore()
        val outcome = repo(store).addServer(endpoint)
        assertTrue(outcome is AddServerOutcome.Added)
        outcome as AddServerOutcome.Added
        assertEquals("Serwer X", outcome.state.manifest?.name)
        assertEquals(CommunityConsentState.CONSENT_REQUIRED, outcome.state.consentState)
        assertFalse(outcome.state.contributesToTab) // brak zgody = brak wkładu w zakładkę
        assertEquals(1, store.servers().size)
        assertEquals(CommunityTransportKind.HTTPS_POLL, store.server(serverId)?.transport)
    }

    @Test
    fun `non https endpoint is rejected and stored nothing`() = runBlocking {
        val store = FakeStore()
        val outcome = repo(store, fetcher = null).addServer("http://serwer.example/m")
        assertTrue(outcome is AddServerOutcome.InvalidEndpoint)
        assertTrue(store.servers().isEmpty())
    }

    @Test
    fun `failed manifest fetch stores nothing`() = runBlocking {
        val store = FakeStore()
        val outcome = repo(store, FakeFetcher(CommunityFetchResult.Failure("HTTP 503"))).addServer(endpoint)
        assertTrue(outcome is AddServerOutcome.Failed)
        assertTrue(store.servers().isEmpty())
    }

    @Test
    fun `accepting terms makes the server contribute to the tab`() = runBlocking {
        val store = FakeStore()
        val r = repo(store)
        val added = r.addServer(endpoint) as AddServerOutcome.Added
        val terms = added.state.manifest!!.terms

        r.decide(serverId, terms, accepted = true, presentedAtMillis = 900L)
        assertEquals(CommunityConsentState.ACCEPTED, r.consentState(serverId, terms))

        val refreshed = r.refresh(store.server(serverId)!!)
        assertEquals(CommunityConsentState.ACCEPTED, refreshed.consentState)
        assertTrue(refreshed.contributesToTab)
    }

    @Test
    fun `declining terms blocks the tab and is stored`() = runBlocking {
        val store = FakeStore()
        val r = repo(store)
        val terms = (r.addServer(endpoint) as AddServerOutcome.Added).state.manifest!!.terms

        r.decide(serverId, terms, accepted = false, presentedAtMillis = 900L)
        assertEquals(CommunityConsentState.DECLINED, r.consentState(serverId, terms))
        assertFalse(store.consentForTerms(serverId, terms.contentHash)!!.accepted)

        assertFalse(r.refresh(store.server(serverId)!!).contributesToTab)
    }

    @Test
    fun `changed terms require consent again even after acceptance`() = runBlocking {
        val store = FakeStore()
        val r = repo(store)
        val terms = (r.addServer(endpoint) as AddServerOutcome.Added).state.manifest!!.terms
        r.decide(serverId, terms, accepted = true, presentedAtMillis = 900L)

        val newTerms = CommunityTerms.of(versionLabel = "1", text = "Regulamin serwera X. Punkt 6.")
        assertEquals(CommunityConsentState.CONSENT_REQUIRED, r.consentState(serverId, newTerms))
    }

    private fun manifestWith(body: String) = """
        {
          "contractVersion": 1, "name": "Serwer X", "transport": "https",
          "capabilities": ["youtube_community_posts"],
          "terms": { "version": "1", "text": "Regulamin serwera X." },
          $body
        }
    """.trimIndent()

    @Test
    fun `server deletions remove stored items on refresh`() = runBlocking {
        val store = FakeStore()
        val withPost = manifestWith("""
            "posts": [ { "author": "A", "text": "a", "url": "https://www.youtube.com/post/P1" } ]
        """.trimIndent())
        val r = repo(store, FakeFetcher(CommunityFetchResult.Success(withPost, null, null)))
        val added = r.addServer(endpoint) as AddServerOutcome.Added
        // H-01: treść zapisujemy dopiero po zgodzie na aktualny regulamin — zgadzamy się i odświeżamy
        r.decide(serverId, added.state.manifest!!.terms, accepted = true, presentedAtMillis = 900L)
        r.refresh(store.server(serverId)!!)
        assertTrue(store.storedItems(serverId).any { it.itemId == "yt-post:P1" })

        // kolejny manifest jawnie usuwa ten post (ten sam regulamin → zgoda nadal obowiązuje)
        val withDeletion = manifestWith("""
            "deletions": [ "https://www.youtube.com/post/P1" ]
        """.trimIndent())
        val r2 = CommunityRepository(
            store = store,
            fetcherProvider = { FakeFetcher(CommunityFetchResult.Success(withDeletion, null, null)) },
            nowMillis = { 1_000L },
        )
        r2.refresh(store.server(serverId)!!)
        assertFalse(store.storedItems(serverId).any { it.itemId == "yt-post:P1" })
    }

    @Test
    fun `favorite is remembered across a refresh`() = runBlocking {
        val store = FakeStore()
        val withPost = manifestWith("""
            "posts": [ { "author": "A", "text": "a", "url": "https://www.youtube.com/post/P2" } ]
        """.trimIndent())
        val r = repo(store, FakeFetcher(CommunityFetchResult.Success(withPost, null, null)))
        val added = r.addServer(endpoint) as AddServerOutcome.Added
        // H-01: zgoda na regulamin, potem odświeżenie, które zapisuje treść
        r.decide(serverId, added.state.manifest!!.terms, accepted = true, presentedAtMillis = 900L)
        r.refresh(store.server(serverId)!!)
        r.setFavorite(serverId, "yt-post:P2", true)
        assertTrue("yt-post:P2" in r.favoriteItemIds(serverId))
        // ponowne pobranie tej samej treści nie kasuje ulubionego
        r.refresh(store.server(serverId)!!)
        assertTrue("yt-post:P2" in r.favoriteItemIds(serverId))
        assertTrue(store.storedItems(serverId).first { it.itemId == "yt-post:P2" }.favorite)
    }

    @Test
    fun `reload reports changed servers via sha256 and zero when identical`() = runBlocking {
        val store = FakeStore()
        val bodyA = """"posts": [ { "author": "A", "text": "wersja 1", "url": "https://www.youtube.com/post/P9" } ]"""
        val r0 = repo(store, FakeFetcher(CommunityFetchResult.Success(manifestWith(bodyA), null, null)))
        val added = r0.addServer(endpoint) as AddServerOutcome.Added
        // reloadFromServers łączy się TYLKO z serwerami włączonymi i z ZAAKCEPTOWANYM regulaminem (M-3),
        // więc po dodaniu akceptujemy regulamin — inaczej serwer byłby (słusznie) pomijany przy przeładowaniu.
        r0.decide(serverId, added.state.manifest!!.terms, accepted = true, presentedAtMillis = 900L)
        // H-01: add nie zapisuje treści przed zgodą — pierwsze przeładowanie po zgodzie ustala bazę treści
        r0.reloadFromServers()

        // przeładowanie tą samą treścią: SHA-256 bez zmian
        val same = repo(store, FakeFetcher(CommunityFetchResult.Success(manifestWith(bodyA), null, null)))
            .reloadFromServers()
        assertEquals(1, same.checked)
        assertEquals(0, same.changed)
        assertEquals(0, same.failed)

        // przeładowanie zmienioną treścią: wykryte jako zmiana
        val bodyB = """"posts": [ { "author": "A", "text": "wersja 2", "url": "https://www.youtube.com/post/P9" } ]"""
        val diff = repo(store, FakeFetcher(CommunityFetchResult.Success(manifestWith(bodyB), null, null)))
            .reloadFromServers()
        assertEquals(1, diff.changed)
    }

    @Test
    fun `changed terms drop server out of contactable and block restored state`() = runBlocking {
        val store = FakeStore()
        fun manifestTerms(text: String) = """
            {
              "contractVersion": 1,
              "name": "Serwer X",
              "transport": "https",
              "capabilities": ["community_posts"],
              "terms": { "version": "1", "text": "$text" },
              "posts": [ { "author": "A", "text": "a", "url": "https://www.youtube.com/post/PX" } ]
            }
        """.trimIndent()

        // zgoda na regulamin v1 + zapis treści
        val r1 = repo(store, FakeFetcher(CommunityFetchResult.Success(manifestTerms("Regulamin v1"), null, null)))
        val added = r1.addServer(endpoint) as AddServerOutcome.Added
        r1.decide(serverId, added.state.manifest!!.terms, accepted = true, presentedAtMillis = 900L)
        r1.refresh(store.server(serverId)!!)
        assertTrue(store.storedItems(serverId).any { it.itemId == "yt-post:PX" })
        assertEquals(1, r1.contactableServers().size)
        assertTrue(r1.restoredState(store.server(serverId)!!).contributesToTab)

        // serwer zmienia regulamin → świeży refresh zapamiętuje nowy hash, ale NIE zapisuje treści
        val r2 = repo(store, FakeFetcher(CommunityFetchResult.Success(manifestTerms("Regulamin v2 — zbieramy dane"), null, null)))
        val refreshed = r2.refresh(store.server(serverId)!!)
        assertEquals(CommunityConsentState.CONSENT_REQUIRED, refreshed.consentState)
        // po zmianie regulaminu: serwer niekontaktowalny i stan odtworzony zablokowany (do ponownej zgody)
        assertTrue(r2.contactableServers().isEmpty())
        assertNull(r2.restoredState(store.server(serverId)!!).manifest)
        // reload nie dotyka niekontaktowalnego serwera
        assertEquals(0, r2.reloadFromServers().checked)
    }

    @Test
    fun `T-11 default server needs user consent after terms change and recovers after accepting`() = runBlocking {
        val store = FakeStore()
        fun manifestTerms(text: String) = """
            { "contractVersion": 1, "name": "lewicowYT", "transport": "https",
              "capabilities": ["community_posts"], "terms": { "version": "1", "text": "$text" },
              "posts": [ { "author": "A", "text": "a", "url": "https://www.youtube.com/post/PD" } ] }
        """.trimIndent()
        val r1 = repo(store, FakeFetcher(CommunityFetchResult.Success(manifestTerms("Regulamin v1"), null, null)))
        assertTrue(r1.ensureDefaultServer())
        assertEquals(1, r1.contactableServers().size)
        // operator zmienia regulamin — użytkownik musi to zaakceptować sam (zasłona w UI), serwer nie jest kontaktowalny
        val r2 = repo(store, FakeFetcher(CommunityFetchResult.Success(manifestTerms("Regulamin v2 (inny)"), null, null)))
        val refreshed = r2.refresh(store.server(CommunityDefaults.SERVER_ID)!!)
        assertEquals(CommunityConsentState.CONSENT_REQUIRED, refreshed.consentState)
        assertTrue(r2.contactableServers().isEmpty())
        // ensureDefaultServer nie akceptuje zmiany za użytkownika, nawet offline
        assertTrue(r2.ensureDefaultServer())
        assertTrue(r2.contactableServers().isEmpty())
        // użytkownik akceptuje nowy regulamin → serwer wraca
        val terms = CommunityTerms.of(versionLabel = "1", text = "Regulamin v2 (inny)")
        r2.decide(CommunityDefaults.SERVER_ID, terms, accepted = true, presentedAtMillis = 1L)
        val after = r2.refresh(store.server(CommunityDefaults.SERVER_ID)!!)
        assertEquals(CommunityConsentState.ACCEPTED, after.consentState)
        assertEquals(1, r2.contactableServers().size)
    }

    @Test
    fun `default server is not contacted until app terms are accepted`() = runBlocking {
        var allowed = false
        var calls = 0
        val manifest = """
            { "contractVersion": 1, "name": "lewicowYT", "transport": "https", "capabilities": ["youtube_community_posts"],
              "terms": { "version": "1", "text": "Regulamin serwera" } }
        """.trimIndent()
        val fetcher = object : CommunityFetcher {
            override val kind = CommunityTransportKind.HTTPS_POLL
            override suspend fun fetch(url: String, conditional: CommunityConditional?): CommunityFetchResult {
                calls++
                return CommunityFetchResult.Success(manifest, null, null)
            }
        }
        val store = FakeStore()
        val r = CommunityRepository(store = store, fetcherProvider = { fetcher }, nowMillis = { 1_000L }, defaultServerAllowed = { allowed })
        assertFalse(r.ensureDefaultServer())
        assertEquals(0, calls)
        assertTrue(r.contactableServers().isEmpty())
        allowed = true // zgoda na regulamin aplikacji
        assertTrue(r.ensureDefaultServer())
        assertTrue(calls > 0)
        assertEquals(1, r.contactableServers().size)
        allowed = false // bramka zamknięta (np. nowa wersja regulaminu aplikacji) — refresh nie rusza sieci
        val before = calls
        assertTrue(r.contactableServers().isEmpty())
        r.refresh(store.server(CommunityDefaults.SERVER_ID)!!)
        assertEquals(before, calls)
    }

    @Test
    fun `content files are fetched same-host and appended, only new ones re-fetched`() = runBlocking {
        val store = FakeStore()
        val manifest = """
            {
              "contractVersion": 1, "name": "S", "transport": "https",
              "capabilities": ["youtube_community_posts"], "terms": { "text": "R" },
              "content": ["posts/p1.json", "posts/p2.json", "https://evil.example/beacon.json"]
            }
        """.trimIndent()
        val p1 = """{ "posts": [ { "author": "A", "text": "plik 1", "url": "https://www.youtube.com/post/F1" } ] }"""
        val p2 = """{ "posts": [ { "author": "B", "text": "plik 2", "url": "https://www.youtube.com/post/F2" } ] }"""
        val fetcher = MapFetcher(
            mapOf(
                endpoint to CommunityFetchResult.Success(manifest, null, null),
                "https://serwer.example/posts/p1.json" to CommunityFetchResult.Success(p1, null, null),
                "https://serwer.example/posts/p2.json" to CommunityFetchResult.Success(p2, null, null),
            ),
        )
        val tracker = InMemoryContentFileTracker()
        val r = CommunityRepository(store, { fetcher }, nowMillis = { 1_000L }, contentFileTracker = tracker)
        val added = r.addServer(endpoint) as AddServerOutcome.Added
        r.decide(serverId, added.state.manifest!!.terms, accepted = true, presentedAtMillis = 900L)
        r.refresh(store.server(serverId)!!)

        // treść z OBU plików same-host scalona; obcy host (beacon) pominięty
        assertTrue(store.storedItems(serverId).any { it.itemId == "yt-post:F1" })
        assertTrue(store.storedItems(serverId).any { it.itemId == "yt-post:F2" })
        // p1, p2 oraz zły (obcy) wpis oznaczone jako „załatwione" — nie pobieramy ich ponownie
        assertEquals(
            setOf("posts/p1.json", "posts/p2.json", "https://evil.example/beacon.json"),
            tracker.ingested(serverId),
        )
    }

    @Test
    fun `versioned content file is re-fetched when v changes and replaces its old items`() = runBlocking {
        val store = FakeStore()
        val byUrl = HashMap<String, CommunityFetchResult>()
        fun serve(version: String, text: String) {
            byUrl[endpoint] = CommunityFetchResult.Success(
                // zapis zgodny ze starszymi apkami: same nazwy + osobna mapa wersji
                manifestWith(""" "content": ["post/a.json"], "contentVersions": { "post/a.json": "$version" } """), null, null,
            )
            // wpis BEZ linku: klucz liczony z treści, więc edycja daje nowy itemId
            byUrl["https://serwer.example/post/a.json"] =
                CommunityFetchResult.Success("""{ "posts": [ { "author": "A", "text": "$text" } ] }""", null, null)
        }
        serve("1", "pierwsza wersja")
        val tracker = InMemoryContentFileTracker()
        val r = CommunityRepository(store, { MapFetcher(byUrl) }, nowMillis = { 1_000L }, contentFileTracker = tracker)
        val added = r.addServer(endpoint) as AddServerOutcome.Added
        r.decide(serverId, added.state.manifest!!.terms, accepted = true, presentedAtMillis = 900L)
        r.refresh(store.server(serverId)!!)
        assertEquals(1, store.storedItems(serverId).size)

        // ta sama nazwa, ta sama wersja → bez ponownego pobrania (zmiana treści bez v jest ignorowana)
        byUrl["https://serwer.example/post/a.json"] =
            CommunityFetchResult.Success("""{ "posts": [ { "author": "A", "text": "cicha zmiana" } ] }""", null, null)
        r.refresh(store.server(serverId)!!)
        assertEquals(1, store.storedItems(serverId).size)

        // nowa wersja → pobrany ponownie; stara pozycja z tego pliku znika, jest tylko nowa
        serve("2", "druga wersja")
        r.refresh(store.server(serverId)!!)
        val items = store.storedItems(serverId)
        assertEquals(1, items.size)
        assertEquals(setOf("post/a.json\t2"), tracker.ingested(serverId))
        assertEquals(setOf(items.single().itemId), tracker.itemsOf(serverId, "post/a.json"))
    }

    @Test
    fun `file removed from content drops its items but keeps favorites`() = runBlocking {
        val store = FakeStore()
        val byUrl = HashMap<String, CommunityFetchResult>()
        byUrl[endpoint] = CommunityFetchResult.Success(
            manifestWith(""" "content": ["post/a.json", "post/b.json", "post/c.json"] """), null, null,
        )
        listOf("a", "b", "c").forEach { n ->
            byUrl["https://serwer.example/post/$n.json"] = CommunityFetchResult.Success(
                """{ "posts": [ { "author": "A", "text": "wpis $n", "url": "https://www.youtube.com/post/X$n" } ] }""", null, null,
            )
        }
        val tracker = InMemoryContentFileTracker()
        val r = CommunityRepository(store, { MapFetcher(byUrl) }, nowMillis = { 1_000L }, contentFileTracker = tracker)
        val added = r.addServer(endpoint) as AddServerOutcome.Added
        r.decide(serverId, added.state.manifest!!.terms, accepted = true, presentedAtMillis = 900L)
        r.refresh(store.server(serverId)!!)
        assertEquals(3, store.storedItems(serverId).size)
        store.setItemFavorite(serverId, "yt-post:Xb", true)

        // serwer usunął wpisy a i b (pliki zniknęły z listy)
        byUrl[endpoint] = CommunityFetchResult.Success(manifestWith(""" "content": ["post/c.json"] """), null, null)
        r.refresh(store.server(serverId)!!)
        val ids = store.storedItems(serverId).map { it.itemId }.toSet()
        assertEquals(setOf("yt-post:Xb", "yt-post:Xc"), ids) // a zniknął, ulubiony b został
        assertEquals(setOf("post/c.json"), tracker.trackedFiles(serverId))
    }

    @Test
    fun `R-01 push config survives and is restored from store (no registry drop)`() = runBlocking {
        val store = FakeStore()
        val withPush = manifestWith(
            """
            "posts": [ { "author": "A", "text": "p", "url": "https://www.youtube.com/post/P1" } ],
            "push": { "register": "https://serwer.example/push/register",
                      "unregister": "https://serwer.example/push/unregister" }
            """.trimIndent(),
        )
        val r = repo(store, FakeFetcher(CommunityFetchResult.Success(withPush, null, null)))
        val added = r.addServer(endpoint) as AddServerOutcome.Added
        assertEquals("https://serwer.example/push/register", added.state.manifest!!.push?.registerUrl)
        r.decide(serverId, added.state.manifest!!.terms, accepted = true, presentedAtMillis = 900L)
        r.refresh(store.server(serverId)!!)

        // zimny start / 304: odtwarzamy stan z bazy BEZ świeżego manifestu — push MUSI wrócić,
        // inaczej syncFromServers pominąłby serwer i replaceServers wyrzuciłby go z rejestru (R-01).
        val restored = r.restoredState(store.server(serverId)!!)
        assertEquals("https://serwer.example/push/register", restored.manifest?.push?.registerUrl)
        assertEquals("https://serwer.example/push/unregister", restored.manifest?.push?.unregisterUrl)
    }

    @Test
    fun `R-11 content file failure stops the batch and does not skip ahead`() = runBlocking {
        val store = FakeStore()
        val manifest = manifestWith(""" "content": ["posts/c1.json", "posts/c2.json"] """.trimIndent())
        val c2 = """{ "posts": [ { "author": "B", "text": "z c2", "url": "https://www.youtube.com/post/F2" } ] }"""
        // c1 NIE ma go w mapie -> Failure; c2 jest. Poprawnie: przerywamy na c1, c2 nie jest pobierany.
        val fetcher = MapFetcher(
            mapOf(
                endpoint to CommunityFetchResult.Success(manifest, null, null),
                "https://serwer.example/posts/c2.json" to CommunityFetchResult.Success(c2, null, null),
            ),
        )
        val tracker = InMemoryContentFileTracker()
        val r = CommunityRepository(store, { fetcher }, nowMillis = { 1_000L }, contentFileTracker = tracker)
        val added = r.addServer(endpoint) as AddServerOutcome.Added
        r.decide(serverId, added.state.manifest!!.terms, accepted = true, presentedAtMillis = 900L)
        r.refresh(store.server(serverId)!!)

        // c1 padł -> przerwano; c2 NIE został pobrany ani oznaczony (inaczej jego deletions mogłyby wracać treść)
        assertFalse(store.storedItems(serverId).any { it.itemId == "yt-post:F2" })
        assertFalse("posts/c2.json" in tracker.ingested(serverId))
        assertFalse("posts/c1.json" in tracker.ingested(serverId))
    }

    /** 304, gdy klient przyśle ETag "v1"; inaczej pełna odpowiedź z ETag "v1" (sekcja 7: ścieżka 304). */
    private class ConditionalFetcher(private val body: String) : CommunityFetcher {
        override val kind = CommunityTransportKind.HTTPS_POLL
        var calls = 0
        override suspend fun fetch(url: String, conditional: CommunityConditional?): CommunityFetchResult {
            calls++
            if (url.endsWith("/$INLINE_FILE")) return splitInline(CommunityFetchResult.Success(body, null, null), url)
            return if (conditional?.etag == "\"v1\"") CommunityFetchResult.NotModified
            else splitInline(CommunityFetchResult.Success(body, "\"v1\"", null), url)
        }
    }

    private class FixedHints(private val alts: List<String>) : CommunityEndpointHints {
        override fun candidates(serverId: String, primary: String) = listOf(primary) + alts
        override fun remember(serverId: String, working: String, altEndpoints: List<String>) {}
        override fun forget(serverId: String) {}
    }

    @Test
    fun `A-06 push-only server keeps push in restored state after 304`() = runBlocking {
        val store = FakeStore()
        // serwer BEZ żadnej listy treści — tylko push (np. serwer domyślny)
        val pushOnly = manifestWith(
            """
            "push": { "register": "https://serwer.example/push/register" }
            """.trimIndent(),
        )
        val fetcher = ConditionalFetcher(pushOnly)
        val r = repo(store, fetcher)
        val added = r.addServer(endpoint) as AddServerOutcome.Added
        r.decide(serverId, added.state.manifest!!.terms, accepted = true, presentedAtMillis = 900L)
        r.refresh(store.server(serverId)!!)
        // kolejne odświeżenie = 304 (serwer nic nie zmienił)
        val notModified = r.refresh(store.server(serverId)!!)
        assertNull(notModified.manifest)
        // stan odtworzony z bazy MUSI nieść push, inaczej serwer wypada z rejestru push
        val restored = r.restoredState(store.server(serverId)!!)
        assertEquals("https://serwer.example/push/register", restored.manifest?.push?.registerUrl)
    }

    @Test
    fun `A-34 validators are not stored before consent so first refresh after consent stores content`() = runBlocking {
        val store = FakeStore()
        val withPost = manifestWith(
            """ "posts": [ { "author": "A", "text": "a", "url": "https://www.youtube.com/post/E1" } ] """.trimIndent(),
        )
        val fetcher = ConditionalFetcher(withPost)
        val r = repo(store, fetcher)
        val added = r.addServer(endpoint) as AddServerOutcome.Added
        // przed zgodą: treść NIE zapisana i ETag NIE zapamiętany
        assertNull(store.server(serverId)!!.manifestEtag)
        r.decide(serverId, added.state.manifest!!.terms, accepted = true, presentedAtMillis = 900L)
        // pierwszy refresh po zgodzie jest bezwarunkowy → 200 → treść zapisana
        r.refresh(store.server(serverId)!!)
        assertTrue(store.storedItems(serverId).any { it.itemId == "yt-post:E1" })
        assertEquals("\"v1\"", store.server(serverId)!!.manifestEtag)
    }

    @Test
    fun `A-08 permanently failing content file is skipped after repeated failures`() = runBlocking {
        val store = FakeStore()
        val manifest = manifestWith(""" "content": ["posts/dead.json", "posts/ok.json"] """.trimIndent())
        val ok = """{ "posts": [ { "author": "B", "text": "ok", "url": "https://www.youtube.com/post/OK" } ] }"""
        val fetcher = MapFetcher(
            mapOf(
                endpoint to CommunityFetchResult.Success(manifest, null, null),
                "https://serwer.example/posts/ok.json" to CommunityFetchResult.Success(ok, null, null),
            ),
        )
        val tracker = InMemoryContentFileTracker()
        val r = CommunityRepository(store, { fetcher }, nowMillis = { 1_000L }, contentFileTracker = tracker)
        val added = r.addServer(endpoint) as AddServerOutcome.Added
        r.decide(serverId, added.state.manifest!!.terms, accepted = true, presentedAtMillis = 900L)
        repeat(CommunityRepository.MAX_CONTENT_FILE_FAILURES - 1) { r.refresh(store.server(serverId)!!) }
        // jeszcze przed limitem: martwy plik blokuje kolejne (R-11)
        assertFalse(store.storedItems(serverId).any { it.itemId == "yt-post:OK" })
        r.refresh(store.server(serverId)!!)
        // po limicie porażek: martwy plik pominięty, kolejny pobrany
        assertTrue("posts/dead.json" in tracker.ingested(serverId))
        assertTrue(store.storedItems(serverId).any { it.itemId == "yt-post:OK" })
    }

    @Test
    fun `A-07 LAN failover with different terms is rejected and same terms ignore deletions`() = runBlocking {
        val store = FakeStore()
        val lan = "http://192.168.0.10:2137/manifest.json"
        val withPost = manifestWith(
            """ "posts": [ { "author": "A", "text": "a", "url": "https://www.youtube.com/post/L1" } ] """.trimIndent(),
        )
        // 1) normalnie: adres główny działa, zgoda, treść zapisana
        val r0 = CommunityRepository(
            store, { FakeFetcher(CommunityFetchResult.Success(withPost, null, null)) }, nowMillis = { 1_000L },
        )
        val added = r0.addServer(endpoint) as AddServerOutcome.Added
        r0.decide(serverId, added.state.manifest!!.terms, accepted = true, presentedAtMillis = 900L)
        r0.refresh(store.server(serverId)!!)
        assertTrue(store.storedItems(serverId).any { it.itemId == "yt-post:L1" })

        // 2) adres główny pada; podszywający się host LAN podaje INNY regulamin → odrzucone
        val evil = """
            { "contractVersion": 1, "name": "X", "transport": "https", "capabilities": ["youtube_community_posts"],
              "terms": { "version": "1", "text": "Inny regulamin" } }
        """.trimIndent()
        val r1 = CommunityRepository(
            store,
            { url -> MapFetcher(mapOf(lan to CommunityFetchResult.Success(evil, null, null))).takeIf { url.isNotEmpty() } },
            nowMillis = { 1_000L },
            endpointHints = FixedHints(listOf(lan)),
        )
        val rejected = r1.refresh(store.server(serverId)!!)
        assertTrue(rejected.error != null)
        assertEquals(CommunityConsentState.ACCEPTED, r1.consentState(serverId, added.state.manifest!!.terms))

        // 3) LAN z TYM SAMYM regulaminem jest przyjęty, ale jego `deletions` są ignorowane
        val sameTermsDeleting = manifestWith(
            """
            "posts": [ { "author": "A", "text": "a", "url": "https://www.youtube.com/post/L1" } ],
            "deletions": [ "https://www.youtube.com/post/L1" ]
            """.trimIndent(),
        )
        val r2 = CommunityRepository(
            store,
            { url -> MapFetcher(mapOf(lan to CommunityFetchResult.Success(sameTermsDeleting, null, null))).takeIf { url.isNotEmpty() } },
            nowMillis = { 1_000L },
            endpointHints = FixedHints(listOf(lan)),
        )
        val viaLan = r2.refresh(store.server(serverId)!!)
        assertNull(viaLan.error)
        assertTrue(store.storedItems(serverId).any { it.itemId == "yt-post:L1" })
    }

    @Test
    fun `B-03 transient content file failures never skip the file`() = runBlocking {
        val store = FakeStore()
        val manifest = manifestWith(""" "content": ["posts/slow.json", "posts/ok.json"] """.trimIndent())
        val ok = """{ "posts": [ { "author": "B", "text": "ok", "url": "https://www.youtube.com/post/OK" } ] }"""
        val fetcher = MapFetcher(
            mapOf(
                endpoint to CommunityFetchResult.Success(manifest, null, null),
                // timeout / niegotowy Tor = porażka CHWILOWA
                "https://serwer.example/posts/slow.json" to CommunityFetchResult.Failure("timeout"),
                "https://serwer.example/posts/ok.json" to CommunityFetchResult.Success(ok, null, null),
            ),
        )
        val tracker = InMemoryContentFileTracker()
        val r = CommunityRepository(store, { fetcher }, nowMillis = { 1_000L }, contentFileTracker = tracker)
        val added = r.addServer(endpoint) as AddServerOutcome.Added
        r.decide(serverId, added.state.manifest!!.terms, accepted = true, presentedAtMillis = 900L)
        repeat(CommunityRepository.MAX_CONTENT_FILE_FAILURES * 2) { r.refresh(store.server(serverId)!!) }
        // plik z chwilowym błędem NIE został pominięty, więc kolejny też czeka (R-11)
        assertFalse("posts/slow.json" in tracker.ingested(serverId))
        assertFalse(store.storedItems(serverId).any { it.itemId == "yt-post:OK" })
    }

    @Test
    fun `B-02 content files fetched via LAN keep content but ignore their deletions`() = runBlocking {
        val store = FakeStore()
        val lanBase = "http://192.168.0.10:2137"
        val lan = "$lanBase/manifest.json"
        val withPost = manifestWith(
            """ "posts": [ { "author": "A", "text": "a", "url": "https://www.youtube.com/post/K1" } ] """.trimIndent(),
        )
        val r0 = CommunityRepository(
            store, { FakeFetcher(CommunityFetchResult.Success(withPost, null, null)) }, nowMillis = { 1_000L },
        )
        val added = r0.addServer(endpoint) as AddServerOutcome.Added
        r0.decide(serverId, added.state.manifest!!.terms, accepted = true, presentedAtMillis = 900L)
        r0.refresh(store.server(serverId)!!)
        assertTrue(store.storedItems(serverId).any { it.itemId == "yt-post:K1" })

        // adres główny pada; LAN (ten sam regulamin) podaje plik treści, który próbuje skasować K1
        val viaLanManifest = manifestWith(""" "content": ["posts/del.json"] """.trimIndent())
        val delFile = """{ "deletions": [ "https://www.youtube.com/post/K1" ],
            "posts": [ { "author": "B", "text": "n", "url": "https://www.youtube.com/post/K2" } ] }"""
        val tracker = InMemoryContentFileTracker()
        val lanFetcher = MapFetcher(
            mapOf(
                lan to CommunityFetchResult.Success(viaLanManifest, null, null),
                "$lanBase/posts/del.json" to CommunityFetchResult.Success(delFile, null, null),
            ),
        )
        val r1 = CommunityRepository(
            store,
            { url -> lanFetcher.takeIf { url.isNotEmpty() } },
            nowMillis = { 1_000L },
            endpointHints = FixedHints(listOf(lan)),
            contentFileTracker = tracker,
        )
        r1.refresh(store.server(serverId)!!)
        assertTrue("usunięcie z pliku LAN zignorowane", store.storedItems(serverId).any { it.itemId == "yt-post:K1" })
        assertTrue("treść z pliku LAN dołożona", store.storedItems(serverId).any { it.itemId == "yt-post:K2" })
        assertFalse("plik z LAN nie jest oznaczony jako pobrany", "posts/del.json" in tracker.ingested(serverId))
    }

    @Test
    fun `disabling and removing a server work`() = runBlocking {
        val store = FakeStore()
        val r = repo(store)
        r.addServer(endpoint)

        r.setEnabled(serverId, false)
        assertFalse(store.server(serverId)!!.enabled)

        r.removeServer(serverId)
        assertNull(store.server(serverId))
        assertTrue(store.servers().isEmpty())
    }

    /**
     * J-01: serwer z ETag — manifest odpowiada 304, gdy klient odda bieżący ETag; pliki treści z mapy (brak = 404),
     * a [failOnce] daje jednorazowe 503. Zapisuje, czy żądanie manifestu miało walidator.
     */
    private class EtagServer(
        private val manifest: String,
        private val files: Map<String, String>,
        private val failOnce: MutableSet<String> = mutableSetOf(),
    ) : CommunityFetcher {
        override val kind = CommunityTransportKind.HTTPS_POLL
        val manifestConditionals = mutableListOf<CommunityConditional?>()
        override suspend fun fetch(url: String, conditional: CommunityConditional?): CommunityFetchResult {
            if (url == "https://serwer.example/manifest") {
                manifestConditionals += conditional
                return if (conditional?.etag == "\"m1\"") CommunityFetchResult.NotModified
                else CommunityFetchResult.Success(manifest, "\"m1\"", null)
            }
            if (failOnce.remove(url)) return CommunityFetchResult.Failure("HTTP 503")
            val body = files[url] ?: return CommunityFetchResult.Failure("HTTP 404", permanent = true)
            return CommunityFetchResult.Success(body, null, null)
        }
    }

    private fun postFile(id: String) =
        """{ "posts": [ { "author": "A", "text": "t", "url": "https://www.youtube.com/post/$id" } ] }"""

    @Test
    fun `J-01 more than 500 content files are completed in later cycles despite ETag`() = runBlocking {
        val store = FakeStore()
        val names = (1..600).map { "posts/p$it.json" }
        val manifest = manifestWith(""" "content": [ ${names.joinToString { "\"$it\"" }} ] """)
        val server = EtagServer(manifest, names.withIndex().associate { (i, n) -> "https://serwer.example/$n" to postFile("P${i + 1}") })
        val tracker = InMemoryContentFileTracker()
        val r = CommunityRepository(store, { server }, nowMillis = { 1_000L }, contentFileTracker = tracker)
        val added = r.addServer(endpoint) as AddServerOutcome.Added
        r.decide(serverId, added.state.manifest!!.terms, accepted = true, presentedAtMillis = 900L)
        r.refresh(store.server(serverId)!!)
        assertEquals(500, tracker.ingested(serverId).size)
        // kolejka niekompletna → ETag NIE zapisany, kolejny cykl jest bezwarunkowy
        assertNull(store.server(serverId)!!.manifestEtag)
        r.refresh(store.server(serverId)!!)
        assertEquals(600, tracker.ingested(serverId).size)
        assertTrue(store.storedItems(serverId).any { it.itemId == "yt-post:P600" })
        // kompletne → teraz ETag zapisany, a następny cykl to tanie 304
        assertEquals("\"m1\"", store.server(serverId)!!.manifestEtag)
        r.refresh(store.server(serverId)!!)
        assertEquals("\"m1\"", server.manifestConditionals.last()?.etag)
    }

    @Test
    fun `J-01 transient content file failure is retried even though server sends ETag`() = runBlocking {
        val store = FakeStore()
        val names = (1..5).map { "posts/q$it.json" }
        val manifest = manifestWith(""" "content": [ ${names.joinToString { "\"$it\"" }} ] """)
        val server = EtagServer(
            manifest,
            names.withIndex().associate { (i, n) -> "https://serwer.example/$n" to postFile("Q${i + 1}") },
            failOnce = mutableSetOf("https://serwer.example/posts/q3.json"),
        )
        val r = CommunityRepository(store, { server }, nowMillis = { 1_000L }, contentFileTracker = InMemoryContentFileTracker())
        val added = r.addServer(endpoint) as AddServerOutcome.Added
        r.decide(serverId, added.state.manifest!!.terms, accepted = true, presentedAtMillis = 900L)
        r.refresh(store.server(serverId)!!)
        assertFalse(store.storedItems(serverId).any { it.itemId == "yt-post:Q3" })
        r.refresh(store.server(serverId)!!)
        assertTrue(store.storedItems(serverId).any { it.itemId == "yt-post:Q3" })
        assertTrue(store.storedItems(serverId).any { it.itemId == "yt-post:Q5" })
    }

    @Test
    fun `J-01 reload from servers never sends validators`() = runBlocking {
        val store = FakeStore()
        val manifest = manifestWith(""" "content": ["posts/r1.json"] """)
        val server = EtagServer(manifest, mapOf("https://serwer.example/posts/r1.json" to postFile("R1")))
        val r = CommunityRepository(store, { server }, nowMillis = { 1_000L }, contentFileTracker = InMemoryContentFileTracker())
        val added = r.addServer(endpoint) as AddServerOutcome.Added
        r.decide(serverId, added.state.manifest!!.terms, accepted = true, presentedAtMillis = 900L)
        r.refresh(store.server(serverId)!!)
        assertEquals("\"m1\"", store.server(serverId)!!.manifestEtag)
        r.reloadFromServers()
        assertNull(server.manifestConditionals.last())
    }

    @Test
    fun `J-03 content file answering with redirect is skipped after repeated failures`() = runBlocking {
        val store = FakeStore()
        val manifest = manifestWith(""" "content": ["posts/moved.json", "posts/ok.json"] """.trimIndent())
        val fetcher = MapFetcher(
            mapOf(
                endpoint to CommunityFetchResult.Success(manifest, null, null),
                "https://serwer.example/posts/moved.json" to communityHttpFailure(301),
                "https://serwer.example/posts/ok.json" to CommunityFetchResult.Success(postFile("OK3"), null, null),
            ),
        )
        val tracker = InMemoryContentFileTracker()
        val r = CommunityRepository(store, { fetcher }, nowMillis = { 1_000L }, contentFileTracker = tracker)
        val added = r.addServer(endpoint) as AddServerOutcome.Added
        r.decide(serverId, added.state.manifest!!.terms, accepted = true, presentedAtMillis = 900L)
        repeat(CommunityRepository.MAX_CONTENT_FILE_FAILURES) { r.refresh(store.server(serverId)!!) }
        assertTrue("posts/moved.json" in tracker.ingested(serverId))
        assertTrue(store.storedItems(serverId).any { it.itemId == "yt-post:OK3" })
    }

    @Test
    fun `J-04 classic LAN failover adds content without wiping stored items`() = runBlocking {
        val store = FakeStore()
        val lan = "http://192.168.0.10:2137/manifest.json"
        val original = manifestWith(
            """ "posts": [ { "author": "A", "text": "a", "url": "https://www.youtube.com/post/ORIG" } ] """.trimIndent(),
        )
        val r0 = CommunityRepository(
            store, { FakeFetcher(CommunityFetchResult.Success(original, "\"e1\"", null)) }, nowMillis = { 1_000L },
        )
        val added = r0.addServer(endpoint) as AddServerOutcome.Added
        r0.decide(serverId, added.state.manifest!!.terms, accepted = true, presentedAtMillis = 900L)
        r0.refresh(store.server(serverId)!!)
        assertEquals("\"e1\"", store.server(serverId)!!.manifestEtag)
        // adres główny pada; urządzenie w LAN z tym samym (publicznym) regulaminem podaje inną treść
        val injected = manifestWith(
            """ "posts": [ { "author": "Z", "text": "z", "url": "https://www.youtube.com/post/LANX" } ] """.trimIndent(),
        )
        val r1 = CommunityRepository(
            store,
            { MapFetcher(mapOf(lan to CommunityFetchResult.Success(injected, null, null))) },
            nowMillis = { 1_000L },
            endpointHints = FixedHints(listOf(lan)),
        )
        assertNull(r1.refresh(store.server(serverId)!!).error)
        // prawdziwa treść zostaje, a walidatory są skasowane — następne pobranie z adresu głównego jest pełne
        assertTrue(store.storedItems(serverId).any { it.itemId == "yt-post:ORIG" })
        assertNull(store.server(serverId)!!.manifestEtag)
    }

    @Test
    fun `J-02 remembered LAN alternative without path is fetched at manifest json`() = runBlocking {
        val store = FakeStore()
        val withPost = manifestWith(
            """ "posts": [ { "author": "A", "text": "a", "url": "https://www.youtube.com/post/N1" } ] """.trimIndent(),
        )
        val r0 = CommunityRepository(
            store, { FakeFetcher(CommunityFetchResult.Success(withPost, null, null)) }, nowMillis = { 1_000L },
        )
        val added = r0.addServer(endpoint) as AddServerOutcome.Added
        r0.decide(serverId, added.state.manifest!!.terms, accepted = true, presentedAtMillis = 900L)
        r0.refresh(store.server(serverId)!!)
        // starsza wersja zapamiętała surowy wpis „http://192.168.0.10" — serwer zgodny z dokumentem zna tylko /manifest.json
        val r1 = CommunityRepository(
            store,
            { MapFetcher(mapOf("http://192.168.0.10:2137/manifest.json" to CommunityFetchResult.Success(withPost, null, null))) },
            nowMillis = { 1_000L },
            endpointHints = FixedHints(listOf("http://192.168.0.10")),
        )
        assertNull(r1.refresh(store.server(serverId)!!).error)
    }
}
