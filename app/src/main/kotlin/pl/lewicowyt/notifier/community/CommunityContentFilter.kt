package pl.lewicowyt.notifier.community

/**
 * Hierarchiczne wyciszanie treści z serwera — osobno dla powiadomień i dla wyświetlania.
 * Klucz wyciszenia jest na jednym z poziomów:
 *  - platforma:            "youtube", "twitter", …  → wycisza CAŁĄ platformę,
 *  - twórca na platformie:  "youtube/abc"           → wycisza jednego twórcę/autora.
 *  - typ treści u twórcy:   "youtube/abc/stream"    → wycisza jeden typ (np. newsletter, mem) danego twórcy/źródła.
 *
 * To APKA decyduje, nie serwer: serwer tylko kataloguje platformę i twórcę pozycji.
 */
object CommunityContentFilter {
    /** reklamy NIGDY nie powiadamiają – mogą się tylko wyświetlać w zakładce społeczności. */
    const val PLATFORM_ADS = "ads"

    /** C-03: klucze drzewa wyciszeń — TE SAME w feedzie, w drzewie „Zarządzaj" i w powiadomieniach z pusha. */
    const val PLATFORM_MEDIA = "media"
    const val PLATFORM_MEME = "meme"
    const val TYPE_MEME = "mem"

    /** typ pozycji medialnej jako stabilny klucz/etykieta (poziom 3 drzewa wyciszeń). */
    fun articleTypeLabel(kind: CommunityArticleKind): String =
        if (kind == CommunityArticleKind.NEWSLETTER) "newsletter" else "artykuł"

    fun platformKey(platform: String): String = platform.trim().lowercase()

    fun creatorKey(platform: String, creator: String): String =
        platformKey(platform) + "/" + creator.trim().lowercase()

    fun typeKey(platform: String, creator: String, type: String): String =
        creatorKey(platform, creator) + "/" + type.trim().lowercase()

    /** czy pozycja (platforma [+ twórca [+ typ]]) jest wyciszona — wystarczy dowolny przodek. */
    fun isMuted(muted: Set<String>, platform: String, creator: String?, type: String? = null): Boolean {
        if (platformKey(platform) in muted) return true
        if (!creator.isNullOrBlank()) {
            if (creatorKey(platform, creator) in muted) return true
            if (!type.isNullOrBlank() && typeKey(platform, creator, type) in muted) return true
        }
        return false
    }

    /** przełącza klucz w zbiorze (dodaje, jeśli go nie ma; usuwa, jeśli jest). */
    fun toggle(muted: Set<String>, key: String): Set<String> =
        if (key in muted) muted - key else muted + key

    fun shouldDisplay(
        server: CommunityServerRecord,
        platform: String,
        creator: String?,
        type: String? = null,
    ): Boolean = server.enabled && !isMuted(server.displayMuted, platform, creator, type)

    fun shouldNotify(
        server: CommunityServerRecord,
        platform: String,
        creator: String?,
        type: String? = null,
    ): Boolean =
        // reklamy są twardo wykluczone z powiadomień – niezależnie od ustawień serwera/usera
        platformKey(platform) != PLATFORM_ADS &&
            server.enabled && server.notifyEnabled && !isMuted(server.notifyMuted, platform, creator, type)
}

/**
 * Aktualizacje pozycji: powiadamia WYŁĄCZNIE istotna zmiana (SIGNIFICANT, zgodnie z protokołem); brak
 * oznaczenia (NONE) i drobna aktualizacja (MINOR) nigdy. O tym, czy sekcja pozwala, decyduje
 * [CommunityContentFilter.shouldNotify] — czyli APKA, nie serwer.
 */
object CommunityUpdatePolicy {
    fun parse(raw: String?): CommunityUpdateKind = when (raw?.trim()?.lowercase()) {
        "significant", "notify", "istotna" -> CommunityUpdateKind.SIGNIFICANT
        "minor", "silent", "drobna" -> CommunityUpdateKind.MINOR
        else -> CommunityUpdateKind.NONE
    }

    fun shouldNotify(update: CommunityUpdateKind, sectionAllowsNotify: Boolean): Boolean =
        sectionAllowsNotify && update == CommunityUpdateKind.SIGNIFICANT
}
