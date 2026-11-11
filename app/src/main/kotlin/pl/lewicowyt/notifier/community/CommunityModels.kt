package pl.lewicowyt.notifier.community

import java.security.MessageDigest

/*
 * modele warstwy serwerów społeczności. wszystko, co przychodzi z serwera, jest
 * NIEZAUFANE – parsujemy tolerancyjnie (allowlista), a i tak apka decyduje co pokazać.
 *
 * dwie żelazne zasady całej warstwy:
 *  - zapytania o manifest i treść to GET bez danych użytkownika: apka nie odsyła zgód ani telemetrii.
 *    Jedyny wyjątek to świadoma rejestracja push (endpoint UnifiedPush / temat LEP / token FCM);
 *  - żadnego kodu z serwera: serwer podaje wyłącznie deklaratywne dane w stałych kontraktach.
 */

/**
 * jak serwer dostarcza treść. bootstrap manifestu (i regulaminu) to zawsze osobny GET,
 * niezależny od tego pola – nawet serwer na FCM musi gdzieś wystawić manifest do pobrania.
 */
enum class CommunityTransportKind {
    // serwer publikuje do FCM, Google robi fan-out – dosłownie nic do nas nie odsyła. CGNAT-proof.
    FCM,
    // apka robi warunkowy GET po publiczny endpoint; operator bije CGNAT tunelem (np. Cloudflare).
    HTTPS_POLL,
    // jak wyżej, ale przez Tor .onion – CGNAT i anonimowość operatora bez pośrednika.
    ONION_POLL;

    companion object {
        fun fromRaw(raw: String?): CommunityTransportKind? = when (raw?.trim()?.lowercase()) {
            "fcm" -> FCM
            "https", "https_poll", "https-poll", "poll" -> HTTPS_POLL
            "onion", "onion_poll", "onion-poll", "tor" -> ONION_POLL
            else -> null // nieznany transport = ignorujemy serwer, nie wywalamy apki
        }
    }
}

/** opcjonalne zdolności zakładki społeczności. nieznane po prostu pomijamy. */
enum class CommunityCapability {
    COMMUNITY_CREATORS,      // dodatkowy zestaw twórców YT, osobny per serwer
    FOREIGN_PLATFORM_ITEMS,  // pozycje z platform spoza oficjalnej apki (np. Twitter/X) – render z szablonu
    YOUTUBE_COMMUNITY_POSTS, // posty społeczności z YT
    ADS,                     // reklamy (link-only) – user może włączyć/wyłączyć, limit 1 na 20 pozycji
    CONTESTS,                // konkursy (link-only) – własne serwera lub stron trzecich
    MEDIA_PUBLICATIONS,      // publikacje medialne/artykuły i newslettery (link-only, np. OKO.press)
    MEMES;                   // memy – treść obrazkowa (obraz + opcjonalny podpis/źródło/link)

    companion object {
        fun fromRaw(raw: String?): CommunityCapability? = when (raw?.trim()?.lowercase()) {
            "community_creators", "creators" -> COMMUNITY_CREATORS
            "foreign_platform_items", "foreign_platforms", "platforms" -> FOREIGN_PLATFORM_ITEMS
            "youtube_community_posts", "yt_community", "community_posts" -> YOUTUBE_COMMUNITY_POSTS
            "ads", "reklamy" -> ADS
            "contests", "konkursy" -> CONTESTS
            "media_publications", "media", "articles", "artykuly", "newsletters" -> MEDIA_PUBLICATIONS
            "memes", "memy", "meme" -> MEMES
            else -> null
        }
    }
}

/**
 * regulamin serwera. wymagany – bez niego nie ma zgody, więc nie ma połączenia.
 * tożsamością jest hash treści, nie deklarowana wersja: zmiana choćby znaku wymusza
 * ponowną zgodę, nawet gdy serwer "zapomni" podbić wersję.
 */
data class CommunityTerms(
    val versionLabel: String, // tylko do pokazania userowi; nie ufamy mu przy wykrywaniu zmian
    val text: String,
    val contentHash: String, // sha-256 hex z text
) {
    companion object {
        fun of(versionLabel: String, text: String): CommunityTerms =
            CommunityTerms(versionLabel, text, communitySha256Hex(text))
    }
}

/**
 * wiersz serwera zapisany lokalnie. name/transport to cache z ostatniego manifestu
 * (do pokazania na liście, zanim pobierzemy manifest ponownie); prawda i tak jest
 * w świeżym manifeście.
 */
data class CommunityServerRecord(
    val serverId: String,
    val endpoint: String,
    val addedAtMillis: Long,
    val enabled: Boolean,
    val displayName: String?,
    val transport: CommunityTransportKind?,
    val lastManifestFetchMillis: Long,
    // ustawienia per serwer (domyślnie wszystko włączone). dwie niezależne osie:
    // powiadomienia i wyświetlanie w zakładce. wyciszenia hierarchiczne – klucz to
    // "platforma" albo "platforma/twórca" (patrz CommunityContentFilter).
    val notifyEnabled: Boolean = true,
    val notifyMuted: Set<String> = emptySet(),
    val displayMuted: Set<String> = emptySet(),
    // czy pokazywać reklamy tego serwera (domyślnie tak; user może całkiem wyłączyć)
    val adsEnabled: Boolean = true,
    // cache z manifestu: czy ten serwer może zostać wybrany jako "główny"
    val canBePrimary: Boolean = false,
    // #10: walidatory warunkowego GET-a zapamiętane z poprzedniego pobrania manifestu. Oddajemy je
    // przy kolejnym żądaniu (If-None-Match / If-Modified-Since); serwer może odpowiedzieć 304.
    val manifestEtag: String? = null,
    val manifestLastModified: String? = null,
    // H-01: hash regulaminu z OSTATNIEGO manifestu. Zgoda (CommunityConsentRecord) obowiązuje tylko, gdy
    // jej termsHash równa się temu polu — inaczej regulamin się zmienił i trzeba ponownej zgody.
    val manifestTermsHash: String? = null,
    // R-01: ostatnio znana konfiguracja push i prymarnego feedu (z manifestu). Trzymane w bazie, żeby po
    // zimnym starcie/304 restoredState odtworzył manifest z push/primaryFeed i serwer nie wypadł z rejestru.
    val push: CommunityPushConfig? = null,
    val primaryFeed: CommunityPrimaryFeed? = null,
    // R-09: tekst+wersja OSTATNIEGO regulaminu — do pokazania „Przejrzyj regulamin" po restarcie, gdy
    // regulamin się zmienił, a nie ma świeżego manifestu (offline). Hash jest w manifestTermsHash.
    val manifestTermsText: String? = null,
    val manifestTermsVersion: String? = null,
)

/**
 * sparsowany manifest serwera. serverId NIE stąd – patrz [communityServerId].
 * listy treści są niepuste tylko dla capability, które serwer ZADEKLAROWAŁ.
 */
data class CommunityManifest(
    val contractVersion: Int,
    val name: String,
    val description: String?,
    val transport: CommunityTransportKind,
    val capabilities: Set<CommunityCapability>,
    val terms: CommunityTerms,
    // serwer sam deklaruje, czy nadaje się na "serwer główny" (źródło podstawowych
    // powiadomień). serwer tylko do sekcji społeczności zostawia to na false.
    val canBePrimary: Boolean = false,
    val creators: List<CommunityCreator> = emptyList(),
    val foreignItems: List<CommunityForeignItem> = emptyList(),
    val posts: List<CommunityPost> = emptyList(),
    val ads: List<CommunityLinkItem> = emptyList(),
    val contests: List<CommunityLinkItem> = emptyList(),
    val articles: List<CommunityArticle> = emptyList(),
    val memes: List<CommunityMeme> = emptyList(),
    // deklaracja platform, których LINKI serwer chce otwierać (np. twitter, twitch) — apka pokazuje je
    // w ustawieniach i pozwala per platforma wybrać „otwieraj w" (przeglądarka/aplikacja/inna), tak jak
    // dla YouTube w serwerze domyślnym.
    val linkPlatforms: List<CommunityLinkPlatform> = emptyList(),
    // gdy serwer jest „główny" (zastępuje domyślny): DEKLARACJA co dostarcza — kanały + ostatnie filmy z
    // czasem publikacji. Apka porównuje to z RSS (to samo, co robi serwer domyślny) i ostrzega, gdy się
    // rozjeżdża (brak/nadmiar/duplikaty twórców lub filmów, wysokie opóźnienia). Patrz „Weryfikacja serwera głównego".
    val primaryFeed: CommunityPrimaryFeed? = null,
    // itemId pozycji, które serwer JAWNIE usuwa – apka kasuje je z bazy całkowicie (także ulubione)
    val deletions: List<String> = emptyList(),
    // UnifiedPush: adresy, pod które apka może zgłosić/wycofać swój endpoint push (jeśli serwer to oferuje)
    val push: CommunityPushConfig? = null,
    // alternatywne adresy tego samego serwera (publiczny + LAN) — do obejścia zepsutego hairpin-NAT:
    // gdy adres główny jest nieosiągalny, aplikacja próbuje kolejnego z tej listy
    val altEndpoints: List<String> = emptyList(),
    // Pliki treści (append-only): manifest może zamiast list treści NIEŚĆ tylko NAZWY plików (ścieżki
    // względne albo URL-e na TYM SAMYM hoście), z których apka dociąga posty/obce/artykuły/memy/usunięcia.
    // Pozwala rozbić treść na dowolną liczbę plików (1 z całością albo 2000 po jednym poście — obojętnie),
    // a automatyzacja po stronie serwera tylko dopisuje nazwę nowego pliku. Apka pobiera TYLKO nowe nazwy
    // albo nazwy z NOWĄ wersją (`{"name":…,"v":…}`) — wtedy plik jest pobierany ponownie i zastępuje swoją treść.
    val contentFiles: List<CommunityContentFile> = emptyList(),
)

/**
 * pozycja listy `content`: nazwa pliku + opcjonalna wersja. Zmiana wersji = plik zmienił treść i trzeba go
 * pobrać ponownie (ta sama nazwa, np. `post/<id>.json`). Bez wersji — klasyczne append-only (raz na nazwę).
 */
data class CommunityContentFile(val name: String, val version: String? = null) {
    /** klucz w rejestrze pobranych plików; tabulator nie występuje w nazwach (parser odrzuca znaki sterujące). */
    val key: String get() = if (version == null) name else "$name\t$version"
}

/**
 * Deklaracja „serwera głównego": co dokładnie serwer dostarcza jako podstawowe powiadomienia. Apka
 * porównuje to z YouTube RSS (referencja = to, co robi serwer domyślny) i z listą wybranych twórców.
 */
data class CommunityPrimaryFeed(
    val creators: List<String> = emptyList(),       // kanały YouTube (UC...), które serwer obsługuje
    val videos: List<CommunityPrimaryVideo> = emptyList(), // ostatnie filmy, które serwer zgłosił
)

data class CommunityPrimaryVideo(
    val channelId: String,           // UC...
    val videoId: String,             // 11-znakowe id filmu YouTube
    val publishedAtMillis: Long?,    // czas publikacji wg serwera (do porównania opóźnień)
)

/**
 * Deklarowana przez serwer platforma linków (np. Twitter/X, Twitch, Mastodon). Apka pokazuje ją w
 * ustawieniach społeczności i pozwala wybrać sposób otwierania linków tej platformy — dokładnie jak
 * dla YouTube. `hosts` to nazwy hostów (bez schematu), po których apka dopasowuje adres do platformy.
 */
data class CommunityLinkPlatform(
    val id: String,        // stabilny identyfikator, np. "twitter"
    val name: String,      // nazwa wyświetlana, np. "Twitter / X"
    val hosts: List<String> = emptyList(), // np. ["twitter.com", "x.com"] — do dopasowania URL-i
)

/**
 * Adresy do rejestracji push (UnifiedPush). Apka POST-uje tu swój endpoint dystrybutora, a serwer
 * wypycha na niego "ping" o zmianie – treść apka i tak dociąga manifestem po HTTPS. To JEDYNY moment,
 * gdy apka coś wysyła do serwera; serwer nie poznaje tożsamości, tylko nieprzezroczysty endpoint.
 */
data class CommunityPushConfig(
    val registerUrl: String,
    val unregisterUrl: String?,
    // opcjonalny PUBLICZNY config klienta Firebase serwera (gdy serwer ma własny projekt FCM).
    // Sam w sobie niczego nie pozwala wysłać — służy tylko, by apka wzięła w tym projekcie swój token.
    val fcm: CommunityFcmConfig? = null,
    // Serwer .onion deklaruje, że jego powiadomienia wymagają FCM, a więc trybu „Nieprywatny Tor"
    // (Tor tylko do omijania CGNAT — Google i tak zobaczy subskrybenta). Bez tego trybu apka NIE
    // zarejestruje FCM dla takiego serwera i powiadomień się nie da włączyć. Operator MUSI napisać to
    // też wprost w regulaminie. Dla serwerów nie-.onion pole jest bez znaczenia (FCM działa normalnie).
    val requiresNonPrivateTor: Boolean = false,
    // Baza RELAYA LEP (np. "https://relay.example:2139") dla trybu WBUDOWANEGO. Gdy serwer sam nie hostuje
    // LEP (np. serwer domyślny w GCP), deklaruje tu zewnętrzny relay — apka long-polluje `${relay}/lep/sub/…`,
    // a serwer POST-uje budziki na `${relay}/lep/pub/…`. Null = apka używa hosta serwera (serwer hostuje LEP sam).
    val relayBase: String? = null,
)

/** Publiczny config klienta Firebase (z bloku push.fcm) — do inicjalizacji osobnej instancji FirebaseApp. */
data class CommunityFcmConfig(
    val projectId: String,
    val appId: String,
    val apiKey: String,
    val senderId: String,
)

/**
 * decyzja usera wobec regulaminu, zapisywana LOKALNIE (do serwera nic nie leci).
 * trzymamy też odmowę – to dowód świadomego wyboru, ważny pod kątem Google Play.
 */
data class CommunityConsentRecord(
    val serverId: String,
    val termsVersionLabel: String,
    val termsText: String,
    val termsHash: String,
    val presentedAtMillis: Long,
    val decidedAtMillis: Long,
    val accepted: Boolean,
)

/** stan zgody dla aktualnego regulaminu, policzony z zapisanego rekordu. */
enum class CommunityConsentState {
    CONSENT_REQUIRED, // brak decyzji dla tego regulaminu (nowy serwer albo regulamin się zmienił)
    ACCEPTED,         // user zgodził się dokładnie na ten regulamin
    DECLINED,         // user odrzucił ten regulamin – nie łączymy
}

/**
 * stabilne, app-owe id serwera liczone z endpointu wpisanego przez usera – NIE z manifestu,
 * żeby jeden serwer nie mógł podszyć się pod id innego.
 */
internal fun communityServerId(endpoint: String): String =
    communitySha256Hex(endpoint.trim().lowercase()).take(16)

internal fun communitySha256Hex(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
