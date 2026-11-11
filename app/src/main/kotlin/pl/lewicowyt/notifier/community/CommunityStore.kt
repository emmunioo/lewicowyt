package pl.lewicowyt.notifier.community

import pl.lewicowyt.notifier.data.LocalDatabase

/**
 * wąskie wejście do trwałego stanu serwerów społeczności. wydzielone jako interfejs,
 * żeby orkiestrację ([CommunityRepository]) dało się przetestować bez bazy/urządzenia.
 */
interface CommunityStore {
    fun servers(): List<CommunityServerRecord>
    fun server(serverId: String): CommunityServerRecord?
    fun addServer(serverId: String, endpoint: String, addedAtMillis: Long)
    fun updateManifestCache(
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
    )
    /** J-01: ustawia walidatory DOKŁADNIE (null kasuje) — updateManifestCache nie kasuje ich przy braku nagłówka. */
    fun updateManifestValidators(serverId: String, etag: String?, lastModified: String?)
    fun setEnabled(serverId: String, enabled: Boolean)
    fun setNotifyEnabled(serverId: String, enabled: Boolean)
    fun setNotifyMuted(serverId: String, muted: Set<String>)
    fun setDisplayMuted(serverId: String, muted: Set<String>)
    fun setAdsEnabled(serverId: String, enabled: Boolean)
    fun deleteServer(serverId: String)
    // trwały, wspólny magazyn treści (ZSTD + znormalizowane author_id/server_id)
    fun replaceContent(serverId: String, content: CommunityStoredContent)
    /** jak replaceContent, ale DOKŁADA/aktualizuje pozycje bez kasowania istniejących (tryb plików treści). */
    fun appendContent(serverId: String, content: CommunityStoredContent)
    /**
     * T-07 (kontrakt): zwrócenie TEJ SAMEJ instancji listy co poprzednio oznacza „treść bez zmian" — repozytorium
     * nie dekoduje jej wtedy ponownie. Implementacja zwracająca nową listę przy każdym wywołaniu też jest poprawna.
     */
    fun storedItems(serverId: String): List<CommunityStoredItem>
    fun favoriteItemIds(serverId: String): Set<String>
    fun setItemFavorite(serverId: String, itemId: String, favorite: Boolean)
    fun deleteItems(serverId: String, itemIds: Collection<String>)
    fun pruneContent(
        textBeforeMillis: Long,
        imageBeforeMillis: Long,
        pruneAllText: Boolean,
        pruneAllImage: Boolean,
    )
    fun recordConsent(record: CommunityConsentRecord)
    fun consentForTerms(serverId: String, termsHash: String): CommunityConsentRecord?
    fun latestConsent(serverId: String): CommunityConsentRecord?
}

/** produkcyjny adapter – cienka delegacja do metod DAO w [LocalDatabase]. */
class LocalDatabaseCommunityStore(
    private val database: LocalDatabase,
) : CommunityStore {
    /**
     * T-07: odczyt treści serwera (zapytanie + złożenie listy) był wykonywany przy KAŻDYM budziku dla KAŻDEGO
     * serwera (notifyFresh) i przy każdym przeładowaniu zakładki. Pamiętamy ostatni odczyt per serwer, dopóki
     * treść się nie zmieni. Ten adapter jest JEDYNĄ drogą zapisu treści społeczności (pozycje, autorzy, ulubione,
     * retencja, usunięcie serwera), więc każdy zapis niżej unieważnia pamięć. Licznik pokoleń chroni przed
     * wyścigiem: odczyt rozpoczęty przed zapisem nie odłoży do pamięci starej treści.
     * Ta sama instancja listy wraca, póki treść się nie zmieni — [CommunityRepository] po tym rozpoznaje,
     * że może użyć już zdekodowanego wyniku.
     */
    private val itemsCache = java.util.concurrent.ConcurrentHashMap<String, List<CommunityStoredItem>>()
    private val contentGeneration = java.util.concurrent.atomic.AtomicLong()

    private inline fun <T> writingContent(block: () -> T): T =
        try {
            block()
        } finally {
            contentGeneration.incrementAndGet()
            itemsCache.clear()
        }

    override fun servers() = database.communityServers()
    override fun server(serverId: String) = database.communityServer(serverId)
    override fun addServer(serverId: String, endpoint: String, addedAtMillis: Long) =
        database.addCommunityServer(serverId, endpoint, addedAtMillis)
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
    ) = database.updateCommunityServerManifestCache(
        serverId, displayName, transport, canBePrimary, fetchedAtMillis, etag, lastModified, termsHash,
        pushJson, primaryFeedJson, termsText, termsVersion,
    )
    override fun updateManifestValidators(serverId: String, etag: String?, lastModified: String?) =
        database.setCommunityServerManifestValidators(serverId, etag, lastModified)
    override fun setEnabled(serverId: String, enabled: Boolean) =
        database.setCommunityServerEnabled(serverId, enabled)
    override fun setNotifyEnabled(serverId: String, enabled: Boolean) =
        database.setCommunityServerNotifyEnabled(serverId, enabled)
    override fun setNotifyMuted(serverId: String, muted: Set<String>) =
        database.setCommunityServerNotifyMuted(serverId, muted)
    override fun setDisplayMuted(serverId: String, muted: Set<String>) =
        database.setCommunityServerDisplayMuted(serverId, muted)
    override fun setAdsEnabled(serverId: String, enabled: Boolean) =
        database.setCommunityServerAdsEnabled(serverId, enabled)
    override fun deleteServer(serverId: String) = writingContent { database.deleteCommunityServer(serverId) }
    override fun replaceContent(serverId: String, content: CommunityStoredContent) =
        writingContent { database.replaceCommunityContent(serverId, content) }
    override fun appendContent(serverId: String, content: CommunityStoredContent) =
        writingContent { database.appendCommunityContent(serverId, content) }
    override fun storedItems(serverId: String): List<CommunityStoredItem> {
        itemsCache[serverId]?.let { return it }
        val generation = contentGeneration.get()
        val items = database.communityStoredItems(serverId)
        // zapis w trakcie odczytu = wynik może być nieaktualny → oddajemy go, ale nie zapamiętujemy
        if (contentGeneration.get() == generation) itemsCache[serverId] = items
        return items
    }
    override fun favoriteItemIds(serverId: String) = database.communityFavoriteItemIds(serverId)
    override fun setItemFavorite(serverId: String, itemId: String, favorite: Boolean) =
        writingContent { database.setCommunityItemFavorite(serverId, itemId, favorite) }
    override fun deleteItems(serverId: String, itemIds: Collection<String>) =
        writingContent { database.deleteCommunityItems(serverId, itemIds) }
    override fun pruneContent(
        textBeforeMillis: Long,
        imageBeforeMillis: Long,
        pruneAllText: Boolean,
        pruneAllImage: Boolean,
    ) = writingContent {
        database.pruneCommunityContent(textBeforeMillis, imageBeforeMillis, pruneAllText, pruneAllImage)
    }
    override fun recordConsent(record: CommunityConsentRecord) =
        database.recordCommunityConsent(record)
    override fun consentForTerms(serverId: String, termsHash: String) =
        database.communityConsentForTerms(serverId, termsHash)
    override fun latestConsent(serverId: String) = database.latestCommunityConsent(serverId)
}
