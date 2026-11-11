package pl.lewicowyt.notifier.community

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * tolerancyjny parser manifestu serwera społeczności. wszystko niezaufane:
 *  - nieznany albo za wysoki contractVersion → null (ignorujemy serwer)
 *  - nieznany transport → null (ta wersja apki i tak by z nim nie pogadała)
 *  - brak regulaminu → null (bez regulaminu nie ma zgody, więc nie ma po co)
 *  - nieznane capabilities → po prostu pomijane
 * zepsuty JSON = null, nigdy wyjątek w górę.
 *
 * rozmiar wejścia tnie warstwa pobierania (maxChars jak w innych klientach) – tu pilnujemy
 * tylko sensownych limitów pól.
 */
object CommunityManifestParser {

    // ta wersja apki rozumie kontrakt 1. serwer z wyższym = po prostu go nie tkniemy.
    const val SUPPORTED_CONTRACT_VERSION = 1
    const val MAX_CONTENT_ITEMS = 200
    // pliki treści: górny limit nazw w manifeście (ochrona przed DoS listą) i długości jednej ścieżki
    const val MAX_CONTENT_FILES = 10_000
    const val MAX_CONTENT_FILE_PATH_CHARS = 512
    const val MAX_CONTENT_FILE_VERSION_CHARS = 128
    const val MAX_NAME_CHARS = 200
    const val MAX_TITLE_CHARS = 300
    const val MAX_MEME_IMAGES = 20 // karuzela IG — górny limit zdjęć w jednym memie
    const val MAX_TEXT_CHARS = 5_000

    // zdjęcia tylko: JXL/AVIF (statyczne) i WEBP (animacje). zakaz wideo/gif/png/jpg itd.
    val ALLOWED_IMAGE_EXTENSIONS = setOf("jxl", "avif", "webp")

    // obraz do POWIADOMIENIA: Android nie dekoduje JXL w powiadomieniach, więc tu (i TYLKO
    // tu) dopuszczamy formaty, które system rysuje sam – JPG/PNG (WEBP też umie).
    val ALLOWED_NOTIFICATION_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    /** dźwięk: Opus w Ogg (.opus/.ogg/.oga) albo w WebM/Matroska — dekoduje go sam Android (bez bibliotek). */
    val ALLOWED_AUDIO_EXTENSIONS = setOf("opus", "ogg", "oga", "webm", "mka")
    const val MAX_AUDIO_DURATION_MS = 24L * 60 * 60 * 1000
    /** film: MP4 (H.265 albo H.264). Limit klatek wymusza protokół — więcej niż 30 kl/s apka nie odtworzy. */
    val ALLOWED_VIDEO_EXTENSIONS = setOf("mp4", "m4v")
    const val MAX_VIDEO_FPS = 30f
    const val MAX_VIDEO_SIDE = 4096

    /**
     * @param sourceEndpoint adres, spod którego pobrano manifest. Gdy podany, obrazy AUTO-pobierane
     *   (imageUrl / notifyImageUrl / avatar) są ograniczone do HOSTA tego serwera (lub LAN dla
     *   serwera testowego). To blokuje deanonimizację: złośliwy serwer nie każe apce pobrać
     *   „beacona" z obcego trackera, który poznałby IP i fakt oglądania. Linki (otwierane ręcznie)
     *   nie są tu ograniczane. M-06: BEZ tego parametru obrazy NIE są auto-dopuszczane (fail-closed) —
     *   każdy produkcyjny punkt wejścia musi podać endpoint, inaczej treść przyjdzie bez obrazów.
     */
    fun parse(json: String, sourceEndpoint: String? = null): CommunityManifest? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null

        val contract = root.optInt("contractVersion", -1)
        if (contract < 1 || contract > SUPPORTED_CONTRACT_VERSION) return null

        val transport = CommunityTransportKind.fromRaw(root.optText("transport")) ?: return null

        val name = root.optText("name").trim()
        if (name.isEmpty()) return null

        val terms = parseTerms(root.optJSONObject("terms")) ?: return null
        val capabilities = parseCapabilities(root.optJSONArray("capabilities"))
        val sourceHost = normalizeHost(sourceEndpoint?.toHttpUrlOrNull()?.host)

        return CommunityManifest(
            contractVersion = contract,
            name = name.take(MAX_NAME_CHARS),
            description = root.optText("description").trim().ifEmpty { null }?.take(2_000),
            transport = transport,
            capabilities = capabilities,
            terms = terms,
            canBePrimary = root.optBoolean("canBePrimary", false),
            // WYMÓG PROTOKOŁU: manifest NIE niesie treści (posts, memes, articles, creators, foreignItems, ads,
            // contests) ani deletions — treść i usunięcia są wyłącznie w plikach z pola `content`
            // (parseContentFile). Listy wpisane w manifeście są ignorowane (nie powodują błędu).
            // Konstruktor CommunityManifest ma dla tych pól puste wartości domyślne.
            push = parsePush(root.optJSONObject("push"), sourceHost),
            altEndpoints = parseAltEndpoints(root.optJSONArray("altEndpoints"), sourceHost),
            linkPlatforms = parseLinkPlatforms(root.optJSONArray("linkPlatforms")),
            primaryFeed = parsePrimaryFeed(root.optJSONObject("primaryFeed")),
            contentFiles = parseContentFileNames(root.optJSONArray("content"), root.optJSONObject("contentVersions")),
        )
    }

    /**
     * Pliki treści: lista NAZW (ścieżek) w polu `content`. Przyjmujemy ścieżki względne (np. `posts/1.json`)
     * oraz URL-e — host walidujemy dopiero przy pobieraniu (względem endpointu serwera, ta sama twarda granica
     * co obrazy: tylko host serwera / LAN). Tu tylko sanityzacja: niepuste, bez znaków sterujących, bez `..`
     * (traversal), rozsądna długość; limit liczby. Kolejność zachowana (append-only po stronie serwera).
     * Wersja pozwala serwerowi podmienić treść pliku pod TĄ SAMĄ nazwą (apka pobierze go ponownie, gdy wersja
     * się zmieni). Zalecany zapis, zgodny ze starszymi apkami: `content` = same napisy + osobna mapa
     * `contentVersions: {"nazwa": "wersja"}` (starsza apka mapę zignoruje i pobierze plik raz). Przyjmujemy też
     * pozycję-obiekt `{"name": "...", "v": "..."}`.
     */
    private fun parseContentFileNames(array: JSONArray?, versions: JSONObject? = null): List<CommunityContentFile> {
        array ?: return emptyList()
        val out = LinkedHashMap<String, CommunityContentFile>() // nazwa → plik (duplikat nazwy: pierwszy wygrywa)
        for (i in 0 until array.length()) {
            if (out.size >= MAX_CONTENT_FILES) break
            val obj = array.optJSONObject(i)
            val raw = (obj?.optText("name") ?: array.optText(i)).trim()
            if (raw.isEmpty() || raw.length > MAX_CONTENT_FILE_PATH_CHARS) continue
            if (raw.any { it.isISOControl() }) continue
            if (raw.contains("..")) continue // bez traversal
            val version = (obj?.let { it.optText("v").ifEmpty { it.optText("version") } } ?: versions?.optText(raw))?.trim()
                ?.takeIf { v -> v.isNotEmpty() && v.length <= MAX_CONTENT_FILE_VERSION_CHARS && v.none { it.isISOControl() } }
            if (raw !in out) out[raw] = CommunityContentFile(raw, version)
        }
        return out.values.toList()
    }

    /**
     * Parsuje POJEDYNCZY plik treści do manifestu-nośnika: kopiuje konfigurację z [template] (nazwa, transport,
     * regulamin, capabilities…), a listy treści bierze z pliku. Dzięki temu [CommunityContentCodec.toStored]
     * działa bez zmian. `content`/push/altEndpoints z pliku są ignorowane (konfigurację trzyma manifest).
     * Zwraca null, gdy JSON jest niepoprawny.
     */
    fun parseContentFile(json: String, template: CommunityManifest, sourceEndpoint: String?): CommunityManifest? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val sourceHost = normalizeHost(sourceEndpoint?.toHttpUrlOrNull()?.host)
        val caps = template.capabilities
        return template.copy(
            creators = parseCreators(root.optJSONArray("creators"), CommunityCapability.COMMUNITY_CREATORS in caps),
            foreignItems = parseForeignItems(root.optJSONArray("foreignItems"), CommunityCapability.FOREIGN_PLATFORM_ITEMS in caps, sourceHost),
            posts = parsePosts(root.optJSONArray("posts"), CommunityCapability.YOUTUBE_COMMUNITY_POSTS in caps, sourceHost),
            ads = parseLinkItems(root.optJSONArray("ads"), CommunityCapability.ADS in caps, CommunityLinkKind.AD, sourceHost),
            contests = parseLinkItems(root.optJSONArray("contests"), CommunityCapability.CONTESTS in caps, CommunityLinkKind.CONTEST, sourceHost),
            articles = parseArticles(root.optJSONArray("articles"), CommunityCapability.MEDIA_PUBLICATIONS in caps, sourceHost),
            memes = parseMemes(root.optJSONArray("memes"), CommunityCapability.MEMES in caps, sourceHost),
            deletions = parseDeletions(root.optJSONArray("deletions")),
            // nośnik treści nie przenosi własnej listy plików ani konfiguracji sieci
            contentFiles = emptyList(),
        )
    }

    private val CHANNEL_ID = Regex("^UC[A-Za-z0-9_-]{22}$")
    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")

    /** deklaracja serwera głównego: kanały (UC...) + ostatnie filmy (do porównania z RSS). */
    private fun parsePrimaryFeed(obj: JSONObject?): CommunityPrimaryFeed? {
        obj ?: return null
        // E-09f: lista, nie zbiór — powtórzony kanał ma dotrzeć do oceny (DUPLICATE_CREATORS); RSS i porównania
        // i tak pracują na zbiorze (distinct/toSet)
        val creators = ArrayList<String>()
        obj.optJSONArray("creators")?.let { a ->
            for (i in 0 until a.length()) {
                // B-05: limit jak dla filmów — lista kanałów od serwera nie może rosnąć bez końca
                // (każdy kanał = osobne pobranie RSS przy weryfikacji serwera głównego)
                if (creators.size >= MAX_CONTENT_ITEMS) break
                a.optText(i).trim().takeIf { CHANNEL_ID.matches(it) }?.let { creators += it }
            }
        }
        val videos = ArrayList<CommunityPrimaryVideo>()
        obj.optJSONArray("videos")?.let { a ->
            for (i in 0 until a.length()) {
                if (videos.size >= MAX_CONTENT_ITEMS) break
                val v = a.optJSONObject(i) ?: continue
                val ch = v.optText("channelId").trim().takeIf { CHANNEL_ID.matches(it) } ?: continue
                val vid = v.optText("videoId").trim().takeIf { VIDEO_ID.matches(it) } ?: continue
                videos += CommunityPrimaryVideo(ch, vid, publishedAt(v))
            }
        }
        if (creators.isEmpty() && videos.isEmpty()) return null
        return CommunityPrimaryFeed(creators.toList(), videos)
    }

    /**
     * platformy linków deklarowane przez serwer (np. twitter, twitch). id+name wymagane; hosts opcjonalne
     * (nazwy hostów bez schematu, do dopasowania URL-i). Maks. 30, bez duplikatów po id.
     */
    private fun parseLinkPlatforms(array: JSONArray?): List<CommunityLinkPlatform> {
        array ?: return emptyList()
        val out = LinkedHashMap<String, CommunityLinkPlatform>()
        for (i in 0 until array.length()) {
            if (out.size >= 30) break
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optText("id").trim().lowercase().take(64).ifEmpty { continue }
            val name = obj.optText("name").trim().take(MAX_NAME_CHARS).ifEmpty { continue }
            val hosts = LinkedHashSet<String>()
            obj.optJSONArray("hosts")?.let { ha ->
                for (j in 0 until ha.length()) {
                    normalizeHost(ha.optText(j).substringAfter("://").substringBefore("/"))?.let { hosts += it }
                }
            }
            out.putIfAbsent(id, CommunityLinkPlatform(id, name, hosts.toList()))
        }
        return out.values.toList()
    }

    /**
     * alternatywne adresy serwera (obejście hairpin-NAT). M-08: JEDYNĄ dozwoloną alternatywą jest adres
     * LAN/prywatny po http (np. http://192.168.0.10:2137) — i nic więcej. Publiczne https (nawet same-site)
     * odrzucamy: alternatywa ma służyć wyłącznie do połączenia z serwerem w tej samej sieci lokalnej, gdy
     * router nie obsługuje hairpin-NAT. Adres LAN nie opuszcza sieci użytkownika, więc nie grozi wyciekiem IP
     * ani beaconem na obcy host. Maks. 8, bez duplikatów. (sourceHost nieużywany — zostaje dla zgodności API.)
     * J-02: wpis normalizujemy jak adres główny (brak portu → 2137, pusta ścieżka → /manifest.json) — inaczej
     * `http://192.168.0.10:2137` z przykładu w dokumentacji pytał serwer o `/` zamiast o manifest.
     */
    private fun parseAltEndpoints(array: JSONArray?, sourceHost: String?): List<String> {
        array ?: return emptyList()
        val out = LinkedHashSet<String>()
        for (i in 0 until array.length()) {
            if (out.size >= 8) break
            val raw = array.optText(i).trim()
            val url = raw.toHttpUrlOrNull() ?: continue
            val host = normalizeHost(url.host) ?: continue
            // tylko http na hoście prywatnym/LAN — żadnych publicznych hostów ani https spoza LAN
            if (url.scheme == "http" && isPrivateHost(host)) out += normalizeCommunityEndpoint(raw) ?: continue
        }
        return out.toList()
    }

    /**
     * konfiguracja push (UnifiedPush). Adres rejestracji MUSI być na tym samym hoście co serwer
     * (albo LAN↔LAN dla serwera testowego) – ta sama twarda granica co przy obrazach, żeby serwer
     * nie kazał apce wysłać swojego endpointu na obcy host (ochrona przed deanonimizacją).
     */
    private fun parsePush(obj: JSONObject?, sourceHost: String?): CommunityPushConfig? {
        obj ?: return null
        // register/unregister: https, .onion (Tor) albo LAN-http — jak dla manifestu/obrazów, i zawsze
        // na TYM SAMYM hoście co serwer (imageHostAllowed). Wcześniej wymagane https blokowało serwery .onion.
        val register = sanitizedCommunityUrl(obj.optText("register"), sourceHost) ?: return null
        val unregister = sanitizedCommunityUrl(obj.optText("unregister"), sourceHost)
        // serwer .onion deklaruje, że jego push wymaga FCM (trybu „Nieprywatny Tor")
        val requiresNonPrivateTor = obj.optBoolean("requiresNonPrivateTor", false)
        val isOnionServer = sourceHost?.trimEnd('.')?.endsWith(".onion", ignoreCase = true) == true
        // Utwardzenie prywatności: PRYWATNY serwer .onion (UnifiedPush/pull, bez wymogu Nieprywatnego Tora)
        // NIGDY nie dostaje configu FCM — nawet jeśli manifest go zawiera. Odrzucamy go już tutaj, więc nic
        // związanego z FCM nie wchodzi do modelu ani do rejestracji: taki serwer zostaje w pełni prywatny
        // niezależnie od globalnego przełącznika „Nieprywatny Tor".
        val fcm = parseFcm(obj.optJSONObject("fcm"))?.takeUnless { isOnionServer && !requiresNonPrivateTor }
        // A-05: relay LEP musi być w tej samej KLASIE transportu co serwer; dla .onion/LAN dodatkowo TEN SAM host.
        // Inaczej long-poll do relaya szedłby poza Torem z prawdziwego IP (serwer .onion wiązałby topic↔IP) albo
        // cyklicznie odpytywał urządzenia w LAN (SSRF). Clearnet (https) może mieć osobny host https (serwer domyślny).
        val relayBase = obj.optText("relay").trim()
            .takeIf { it.isNotEmpty() && it.toCommunityUrlOrNull() != null }
            ?.trimEnd('/')
            ?.takeIf { relayAllowedForServer(it, sourceHost) }
        return CommunityPushConfig(
            registerUrl = register,
            unregisterUrl = unregister,
            fcm = fcm,
            requiresNonPrivateTor = requiresNonPrivateTor,
            relayBase = relayBase,
        )
    }

    /** URL rejestracji push: https / .onion / LAN-http, tylko na hoście serwera (jak obrazy, ale bez wymogu formatu). */
    private fun sanitizedCommunityUrl(raw: String?, sourceHost: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.toCommunityUrlOrNull() == null) return null
        return if (imageHostAllowed(trimmed, sourceHost)) trimmed else null
    }

    /** publiczny config klienta Firebase (push.fcm) — wszystkie 4 pola wymagane, inaczej pomijamy. */
    private fun parseFcm(obj: JSONObject?): CommunityFcmConfig? {
        obj ?: return null
        val projectId = obj.optText("projectId").trim()
        val appId = obj.optText("appId").trim()
        val apiKey = obj.optText("apiKey").trim()
        val senderId = obj.optText("senderId").trim()
        if (projectId.isEmpty() || appId.isEmpty() || apiKey.isEmpty() || senderId.isEmpty()) return null
        return CommunityFcmConfig(projectId = projectId, appId = appId, apiKey = apiKey, senderId = senderId)
    }

    /**
     * A-05: czy `relay` wolno użyć dla serwera o hoście [sourceHost]. Serwer .onion/LAN → relay MUSI być na tym
     * samym hoście (długi poll idzie wtedy tą samą drogą: .onion przez Tor, LAN w LAN). Serwer https (clearnet)
     * → relay dowolny host https (ale nie .onion/LAN). Nieznany host serwera → odrzucamy (fail-closed).
     */
    private fun relayAllowedForServer(relay: String, sourceHost: String?): Boolean {
        if (sourceHost == null) return false
        val relayHost = normalizeHost(relay.toHttpUrlOrNull()?.host) ?: return false
        val serverOnion = sourceHost.trimEnd('.').endsWith(".onion", ignoreCase = true)
        val serverLan = isPrivateHost(sourceHost)
        return when {
            serverOnion || serverLan -> relayHost == sourceHost
            else -> !relayHost.endsWith(".onion", ignoreCase = true) && !isPrivateHost(relayHost) // clearnet https
        }
    }

    private fun normalizeHost(host: String?): String? =
        host?.trim()?.lowercase()?.removePrefix("www.")?.takeIf { it.isNotEmpty() }

    /**
     * czy obraz wolno AUTO-pobrać: dopuszczamy tylko obraz z TEGO SAMEGO hosta co serwer. To twarda
     * granica przeciw deanonimizacji przez „beacony" na obcych hostach.
     * M-06: gdy hosta serwera NIE znamy (parse bez sourceEndpoint) — fail-closed: NIE auto-pobieramy
     * żadnego obrazu. Wcześniej brak hosta znaczył „bez ograniczenia" (fail-open) i nowy punkt wejścia
     * (import z pliku/QR) przepuściłby obrazy z obcych trackerów.
     */
    private fun imageHostAllowed(imageUrl: String, sourceHost: String?): Boolean {
        if (sourceHost == null) return false
        val imageHost = normalizeHost(imageUrl.toHttpUrlOrNull()?.host) ?: return false
        // L-2: zawsze TEN SAM host co serwer — także dla serwerów LAN. Wcześniej „dowolny host prywatny"
        // pozwalał serwerowi w LAN kierować GET-y na router/IoT (SSRF po sieci lokalnej).
        return imageHost == sourceHost
    }

    /**
     * jawne usunięcia od serwera – tablica URL-i pozycji do skasowania. liczymy z nich itemId
     * (ten sam kanoniczny klucz co przy zapisie), żeby apka usunęła dokładnie te wpisy z bazy.
     */
    private fun parseDeletions(array: JSONArray?): List<String> {
        array ?: return emptyList()
        val out = LinkedHashSet<String>()
        for (i in 0 until array.length()) {
            if (out.size >= MAX_CONTENT_ITEMS) break
            val url = array.optText(i).trim()
            if (url.isEmpty()) continue
            out += communityItemId(url, "", null, null)
        }
        return out.toList()
    }

    /** memy: obraz WYMAGANY (z hosta serwera). Podpis, źródło i link opcjonalne. */
    private fun parseMemes(array: JSONArray?, enabled: Boolean, sourceHost: String?): List<CommunityMeme> {
        if (!enabled || array == null) return emptyList()
        val out = ArrayList<CommunityMeme>()
        for (i in 0 until array.length()) {
            if (out.size >= MAX_CONTENT_ITEMS) break
            val obj = array.optJSONObject(i) ?: continue
            val images = parseMemeImages(obj, sourceHost)
            if (images.isEmpty()) continue // mem bez choćby jednego poprawnego obrazu pomijamy
            out += CommunityMeme(
                source = obj.optText("source").trim().ifEmpty { null }?.take(MAX_NAME_CHARS),
                title = obj.optText("title").trim().ifEmpty { null }?.take(MAX_TITLE_CHARS),
                images = images,
                url = sanitizedHttpsUrl(obj.optText("url")),
                publishedAtMillis = publishedAt(obj),
                update = CommunityUpdatePolicy.parse(obj.optText("update")),
                audio = parseAudio(obj.optJSONObject("audio"), sourceHost),
                video = parseVideo(obj.optJSONObject("video"), sourceHost),
            )
        }
        return out
    }

    /**
     * Dźwięk pozycji: `{"url": "...", "durationMs": 123000, "title": "..."}`. Plik MUSI być na hoście serwera
     * (jak obrazy — apka pobierze go dopiero po „odtwórz", ale nigdy z obcego hosta) i mieć rozszerzenie Opus/
     * Ogg/WebM. Zły adres = brak dźwięku (sama pozycja zostaje).
     */
    private fun parseAudio(obj: JSONObject?, sourceHost: String?): CommunityAudio? {
        obj ?: return null
        val raw = obj.optText("url").trim()
        if (raw.isEmpty() || raw.length > MAX_URL_CHARS || raw.toCommunityUrlOrNull() == null) return null
        if (!imageHostAllowed(raw, sourceHost)) return null
        val ext = raw.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase()
        if (ext !in ALLOWED_AUDIO_EXTENSIONS) return null
        return CommunityAudio(
            url = raw,
            durationMs = obj.optLong("durationMs", 0L).takeIf { it in 1..MAX_AUDIO_DURATION_MS },
            title = obj.optText("title").trim().ifEmpty { null }?.take(MAX_TITLE_CHARS),
        )
    }

    /**
     * Film pozycji: `{"hevc": {...}, "avc": {...}, "durationMs", "title"}`; wariant = `{"url", "width", "height",
     * "fps", "bytes"}`. Plik na hoście serwera (anty-beacon jak obrazy), MP4. Wariant, który sam deklaruje więcej
     * niż 30 kl/s, odrzucamy od razu; ostatecznie i tak mierzymy klatki w pobranym pliku przed odtworzeniem.
     */
    private fun parseVideo(obj: JSONObject?, sourceHost: String?): CommunityVideo? {
        obj ?: return null
        fun variant(v: JSONObject?): CommunityVideoVariant? {
            v ?: return null
            val raw = v.optText("url").trim()
            if (raw.isEmpty() || raw.length > MAX_URL_CHARS || raw.toCommunityUrlOrNull() == null) return null
            if (!imageHostAllowed(raw, sourceHost)) return null
            val ext = raw.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase()
            if (ext !in ALLOWED_VIDEO_EXTENSIONS) return null
            val fps = v.optDouble("fps", Double.NaN).takeIf { !it.isNaN() }?.toFloat()
            if (fps != null && (fps <= 0f || fps > MAX_VIDEO_FPS + 0.5f)) return null
            return CommunityVideoVariant(
                url = raw,
                width = v.optInt("width", 0).takeIf { it in 1..MAX_VIDEO_SIDE },
                height = v.optInt("height", 0).takeIf { it in 1..MAX_VIDEO_SIDE },
                fps = fps,
                bytes = v.optLong("bytes", 0L).takeIf { it > 0 },
            )
        }
        val hevc = variant(obj.optJSONObject("hevc"))
        val avc = variant(obj.optJSONObject("avc"))
        if (hevc == null && avc == null) return null
        return CommunityVideo(
            hevc = hevc,
            avc = avc,
            durationMs = obj.optLong("durationMs", 0L).takeIf { it in 1..MAX_AUDIO_DURATION_MS },
            title = obj.optText("title").trim().ifEmpty { null }?.take(MAX_TITLE_CHARS),
        )
    }

    /**
     * Zdjęcia mema: tablica `images` (karuzela IG), a gdy jej brak — pojedyncze pola najwyższego poziomu
     * (wsteczna zgodność ze starym formatem). Każdy adres ograniczony do HOSTA serwera; limit liczby.
     */
    private fun parseMemeImages(obj: JSONObject, sourceHost: String?): List<CommunityMemeImage> {
        val out = ArrayList<CommunityMemeImage>()
        val arr = obj.optJSONArray("images")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                if (out.size >= MAX_MEME_IMAGES) break
                val io = arr.optJSONObject(i) ?: continue
                val url = sanitizedImageUrl(io.optText("imageUrl"), sourceHost) ?: continue
                out += CommunityMemeImage(
                    imageUrl = url,
                    notifyImageUrl = sanitizedNotificationImageUrl(io.optText("notifyImageUrl"), sourceHost),
                    imageQuality = io.optInt("imageQuality", 0).takeIf { it in 1..100 },
                )
            }
        }
        if (out.isEmpty()) {
            val url = sanitizedImageUrl(obj.optText("imageUrl"), sourceHost) ?: return emptyList()
            out += CommunityMemeImage(
                imageUrl = url,
                notifyImageUrl = sanitizedNotificationImageUrl(obj.optText("notifyImageUrl"), sourceHost),
                imageQuality = obj.optInt("imageQuality", 0).takeIf { it in 1..100 },
            )
        }
        return out
    }

    /** publikacje medialne/newslettery: link-only (url https + tytuł + źródło wymagane). */
    private fun parseArticles(array: JSONArray?, enabled: Boolean, sourceHost: String?): List<CommunityArticle> {
        if (!enabled || array == null) return emptyList()
        val out = ArrayList<CommunityArticle>()
        for (i in 0 until array.length()) {
            if (out.size >= MAX_CONTENT_ITEMS) break
            val obj = array.optJSONObject(i) ?: continue
            val title = obj.optText("title").trim().ifEmpty { null }?.take(MAX_TITLE_CHARS) ?: continue
            val source = obj.optText("source").trim().ifEmpty { null }?.take(MAX_NAME_CHARS) ?: continue
            val kind = when (obj.optText("kind").trim().lowercase()) {
                "newsletter", "newsletters" -> CommunityArticleKind.NEWSLETTER
                else -> CommunityArticleKind.ARTICLE
            }
            val url = sanitizedHttpsUrl(obj.optText("url"))
            // ARTYKUŁ to odnośnik → url (https) wymagany; NEWSLETTER może być samą treścią bez linku
            if (kind == CommunityArticleKind.ARTICLE && url == null) continue
            out += CommunityArticle(
                kind = kind,
                source = source,
                author = obj.optText("author").trim().ifEmpty { null }?.take(MAX_NAME_CHARS),
                title = title,
                text = obj.optText("text").trim().ifEmpty { null }?.take(MAX_TEXT_CHARS),
                url = url,
                imageUrl = sanitizedImageUrl(obj.optText("imageUrl"), sourceHost),
                notifyImageUrl = sanitizedNotificationImageUrl(obj.optText("notifyImageUrl"), sourceHost),
                imageQuality = obj.optInt("imageQuality", 0).takeIf { it in 1..100 },
                publishedAtMillis = publishedAt(obj),
                update = CommunityUpdatePolicy.parse(obj.optText("update")),
                audio = parseAudio(obj.optJSONObject("audio"), sourceHost),
                video = parseVideo(obj.optJSONObject("video"), sourceHost),
            )
        }
        return out
    }

    private fun parseCreators(array: JSONArray?, enabled: Boolean): List<CommunityCreator> {
        if (!enabled || array == null) return emptyList()
        val out = ArrayList<CommunityCreator>()
        for (i in 0 until array.length()) {
            if (out.size >= MAX_CONTENT_ITEMS) break
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optText("id").trim()
            val name = obj.optText("name").trim()
            if (id.isEmpty() || name.isEmpty()) continue
            out += CommunityCreator(
                id = id.take(MAX_NAME_CHARS),
                name = name.take(MAX_NAME_CHARS),
                channelUrl = sanitizedHttpsUrl(obj.optText("channelUrl")),
            )
        }
        return out
    }

    private fun parseForeignItems(array: JSONArray?, enabled: Boolean, sourceHost: String?): List<CommunityForeignItem> {
        if (!enabled || array == null) return emptyList()
        val out = ArrayList<CommunityForeignItem>()
        for (i in 0 until array.length()) {
            if (out.size >= MAX_CONTENT_ITEMS) break
            val obj = array.optJSONObject(i) ?: continue
            val platform = obj.optText("platform").trim()
            val title = obj.optText("title").trim().ifEmpty { null }?.take(MAX_TITLE_CHARS)
            val text = obj.optText("text").trim().ifEmpty { null }?.take(MAX_TEXT_CHARS)
            val url = sanitizedHttpsUrl(obj.optText("url"))
            // bez platformy albo bez żadnej treści nie ma czego pokazać
            if (platform.isEmpty() || (title == null && text == null && url == null)) continue
            out += CommunityForeignItem(
                platform = platform.take(MAX_NAME_CHARS),
                author = obj.optText("author").trim().ifEmpty { null }?.take(MAX_NAME_CHARS),
                type = obj.optText("type").trim().ifEmpty { null }?.take(MAX_NAME_CHARS),
                title = title,
                text = text,
                url = url,
                imageUrl = sanitizedImageUrl(obj.optText("imageUrl"), sourceHost),
                notifyImageUrl = sanitizedNotificationImageUrl(obj.optText("notifyImageUrl"), sourceHost),
                imageQuality = obj.optInt("imageQuality", 0).takeIf { it in 1..100 },
                publishedAtMillis = publishedAt(obj),
                update = CommunityUpdatePolicy.parse(obj.optText("update")),
                audio = parseAudio(obj.optJSONObject("audio"), sourceHost),
                video = parseVideo(obj.optJSONObject("video"), sourceHost),
            )
        }
        return out
    }

    private fun parsePosts(array: JSONArray?, enabled: Boolean, sourceHost: String?): List<CommunityPost> {
        if (!enabled || array == null) return emptyList()
        val out = ArrayList<CommunityPost>()
        for (i in 0 until array.length()) {
            if (out.size >= MAX_CONTENT_ITEMS) break
            val obj = array.optJSONObject(i) ?: continue
            val text = obj.optText("text").trim()
            if (text.isEmpty()) continue
            // post społeczności YouTube ZAWSZE ma autora — bez niego odrzucamy
            val author = obj.optText("author").trim().ifEmpty { null }?.take(MAX_NAME_CHARS) ?: continue
            out += CommunityPost(
                author = author,
                authorImageUrl = sanitizedImageUrl(obj.optText("authorImageUrl"), sourceHost),
                type = obj.optText("type").trim().ifEmpty { null }?.take(MAX_NAME_CHARS),
                text = text.take(MAX_TEXT_CHARS),
                url = sanitizedHttpsUrl(obj.optText("url")),
                imageUrl = sanitizedImageUrl(obj.optText("imageUrl"), sourceHost),
                notifyImageUrl = sanitizedNotificationImageUrl(obj.optText("notifyImageUrl"), sourceHost),
                imageQuality = obj.optInt("imageQuality", 0).takeIf { it in 1..100 },
                publishedAtMillis = publishedAt(obj),
                update = CommunityUpdatePolicy.parse(obj.optText("update")),
                audio = parseAudio(obj.optJSONObject("audio"), sourceHost),
                video = parseVideo(obj.optJSONObject("video"), sourceHost),
            )
        }
        return out
    }

    /** reklamy i konkursy: link-only (url wymagany), reszta walidowana jak zwykle. */
    private fun parseLinkItems(
        array: JSONArray?,
        enabled: Boolean,
        kind: CommunityLinkKind,
        sourceHost: String?,
    ): List<CommunityLinkItem> {
        if (!enabled || array == null) return emptyList()
        val out = ArrayList<CommunityLinkItem>()
        for (i in 0 until array.length()) {
            if (out.size >= MAX_CONTENT_ITEMS) break
            val obj = array.optJSONObject(i) ?: continue
            // url jest obowiązkowy – reklamy/konkursy to WYŁĄCZNIE odnośniki do stron WWW
            val url = sanitizedHttpsUrl(obj.optText("url")) ?: continue
            out += CommunityLinkItem(
                kind = kind,
                title = obj.optText("title").trim().ifEmpty { null }?.take(MAX_TITLE_CHARS),
                text = obj.optText("text").trim().ifEmpty { null }?.take(MAX_TEXT_CHARS),
                url = url,
                thirdParty = obj.optBoolean("thirdParty", false),
                imageUrl = sanitizedImageUrl(obj.optText("imageUrl"), sourceHost),
                notifyImageUrl = sanitizedNotificationImageUrl(obj.optText("notifyImageUrl"), sourceHost),
                imageQuality = obj.optInt("imageQuality", 0).takeIf { it in 1..100 },
                publishedAtMillis = publishedAt(obj),
                update = CommunityUpdatePolicy.parse(obj.optText("update")),
            )
        }
        return out
    }

    private fun sanitizedHttpsUrl(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        // A-14: limit długości URL — pozycja z URL-em > 2 MB wywracała zapis (CommunityBlob.encode) całej listy
        if (trimmed.length > MAX_URL_CHARS) return null
        return trimmed.takeIf { it.isNotEmpty() && it.toHttpsUrlOrNull() != null }
    }

    /**
     * A-13: data publikacji z niezaufanego manifestu — clamp do „teraz" (+5 min tolerancji zegara). Data z
     * przyszłości przypinała pozycję na górze feedu, wygrywała dedup i omijała retencję czasową; wartości
     * skrajne (sprzed 2005 r.) odrzucamy.
     */
    private fun publishedAt(obj: JSONObject): Long? {
        val raw = obj.optLong("publishedAtMillis", 0L)
        if (raw < MIN_PUBLISHED_MS) return null
        return raw.coerceAtMost(System.currentTimeMillis() + FUTURE_TOLERANCE_MS)
    }

    private const val MAX_URL_CHARS = 4_096
    private const val MIN_PUBLISHED_MS = 1_104_537_600_000L // 2005-01-01
    private const val FUTURE_TOLERANCE_MS = 5L * 60 * 1000

    /**
     * obraz akceptujemy TYLKO w JXL/AVIF (statyczne) lub WEBP (animacje) i tylko po https.
     * wszystko inne – wideo, gif, png, jpg itd. – odrzucamy (zwracamy null). walidacja po
     * rozszerzeniu; docelowo można dołożyć sprawdzenie magic bytes przy pobieraniu.
     */
    private fun sanitizedImageUrl(raw: String?, sourceHost: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.length > MAX_URL_CHARS) return null // A-14
        // obraz może być na serwerze LAN (http) – jak endpoint; linki treści zostają https-only
        if (trimmed.isEmpty() || trimmed.toCommunityUrlOrNull() == null) return null
        // twarda granica anty-deanonimizacyjna: auto-pobieramy obraz tylko z hosta serwera
        if (!imageHostAllowed(trimmed, sourceHost)) return null
        val ext = trimmed.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase()
        return if (ext in ALLOWED_IMAGE_EXTENSIONS) trimmed else null
    }

    /** obraz do powiadomienia – jak wyżej, ale dozwolone formaty czytane przez Androida (JPG/PNG/WEBP). */
    private fun sanitizedNotificationImageUrl(raw: String?, sourceHost: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.length > MAX_URL_CHARS) return null // A-14
        if (trimmed.isEmpty() || trimmed.toCommunityUrlOrNull() == null) return null
        if (!imageHostAllowed(trimmed, sourceHost)) return null
        val ext = trimmed.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase()
        return if (ext in ALLOWED_NOTIFICATION_IMAGE_EXTENSIONS) trimmed else null
    }

    private fun parseTerms(obj: JSONObject?): CommunityTerms? {
        obj ?: return null
        val text = obj.optText("text").trim()
        // R-22: regulamin NIELIMITOWANY — nie odrzucamy ani nie przycinamy po długości (dawniej >200k => cały
        // manifest odrzucany, więc serwera z długim regulaminem nie dało się dodać). Pusty tekst dalej odrzucamy.
        if (text.isEmpty()) return null
        val version = obj.optText("version").trim().ifEmpty { "1" }.take(64)
        return CommunityTerms.of(versionLabel = version, text = text)
    }

    private fun parseCapabilities(array: JSONArray?): Set<CommunityCapability> {
        array ?: return emptySet()
        val out = linkedSetOf<CommunityCapability>()
        for (i in 0 until array.length()) {
            CommunityCapability.fromRaw(array.optText(i))?.let { out += it }
        }
        return out
    }
}

/**
 * B-01: na Androidzie `optString` zamienia JSON `null` na tekst `"null"` (biblioteka JVM w testach zwraca ""),
 * przez co np. `"title": null` dawało tytuł „null”, a `"url": null` — błędny link. `null` = brak wartości.
 */
internal fun JSONObject.optText(key: String): String = if (isNull(key)) "" else optString(key)

internal fun JSONArray.optText(index: Int): String = if (isNull(index)) "" else optString(index)
