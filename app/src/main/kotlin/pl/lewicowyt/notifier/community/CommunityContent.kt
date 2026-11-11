package pl.lewicowyt.notifier.community

/*
 * treść udostępniana przez serwer w manifeście. wszystko niezaufane – parser tnie
 * długości, limituje liczbę i waliduje URL-e do https. apka i tak tylko renderuje;
 * nigdzie się nie loguje ani nie scrapuje – dane przygotował serwer.
 */

/**
 * dźwięk dołączony do pozycji (Opus w kontenerze Ogg/WebM) — odtwarzany natywnie w aplikacji (w tle,
 * z pętlą i prędkością do 3×), nie w przeglądarce. url zawsze z hosta serwera (ta sama granica anty-beacon
 * co obrazy); plik pobieramy prywatną ścieżką (Tor dla .onion) dopiero, gdy user naciśnie „odtwórz".
 */
data class CommunityAudio(
    val url: String,
    val durationMs: Long? = null,
    val title: String? = null,
)

/** jeden wariant filmu (MP4): url z hosta serwera + parametry zadeklarowane przez serwer (sprawdzane po pobraniu). */
data class CommunityVideoVariant(
    val url: String,
    val width: Int? = null,
    val height: Int? = null,
    val fps: Float? = null,
    val bytes: Long? = null,
)

/**
 * film dołączony do pozycji: H.265 (główny, najmniejszy plik) i/lub H.264 (zapasowy dla telefonów bez
 * sprzętowego dekodera H.265). Apka odtwarza WYŁĄCZNIE filmy do 30 kl/s — mierzy to sama w pobranym pliku.
 */
data class CommunityVideo(
    val hevc: CommunityVideoVariant? = null,
    val avc: CommunityVideoVariant? = null,
    val durationMs: Long? = null,
    val title: String? = null,
)

/** dodatkowy twórca YT z serwera. channelUrl już zwalidowany do https albo null. */
data class CommunityCreator(
    val id: String,
    val name: String,
    val channelUrl: String?,
)

/**
 * czy pozycja to nowość, czy aktualizacja wcześniejszej (ten sam id, ponownie wysłana).
 * SIGNIFICANT = istotna zmiana (może dać powiadomienie), MINOR = drobna (bez powiadomienia).
 */
enum class CommunityUpdateKind { NONE, MINOR, SIGNIFICANT }

/** pozycja z platformy spoza oficjalnej apki (np. Twitter/X) – render ze stałego szablonu. */
data class CommunityForeignItem(
    val platform: String,
    val author: String?,
    val type: String?,
    val title: String?,
    val text: String?,
    val url: String?,
    val imageUrl: String?,
    // obraz w JPG/PNG do POWIADOMIENIA – Android nie dekoduje JXL w powiadomieniach,
    // więc do nich serwer podaje osobny, systemowo czytelny obraz (feed dalej używa imageUrl).
    val notifyImageUrl: String? = null,
    // jakość (stopień kompresji) JXL zadeklarowana przez serwer 1..100 – potrzebna do limitu KB
    val imageQuality: Int? = null,
    val publishedAtMillis: Long?,
    val update: CommunityUpdateKind = CommunityUpdateKind.NONE,
    val audio: CommunityAudio? = null,
    val video: CommunityVideo? = null,
)

/** post społeczności z YouTube. type = rodzaj treści u twórcy (post/stream/video/…). */
data class CommunityPost(
    // post społeczności YouTube ZAWSZE ma autora — bez niego pozycję odrzucamy przy parsowaniu
    val author: String,
    val authorImageUrl: String?,
    val type: String?,
    val text: String,
    val url: String?,
    val imageUrl: String?,
    // obraz JPG/PNG do POWIADOMIENIA (Android nie obsługuje JXL w powiadomieniach)
    val notifyImageUrl: String? = null,
    // jakość JXL zadeklarowana przez serwer 1..100 (do limitu rozmiaru KB)
    val imageQuality: Int? = null,
    val publishedAtMillis: Long?,
    val update: CommunityUpdateKind = CommunityUpdateKind.NONE,
    val audio: CommunityAudio? = null,
    val video: CommunityVideo? = null,
)

/**
 * publikacja medialna albo newsletter (np. artykuł OKO.press). oficjalny sposób podania
 * przez serwer treści prasowej – to jak serwer ją zdobywa zależy od niego. w tej samej
 * sekcji co reszta. ARTYKUŁ to zawsze ODNOŚNIK do strony WWW, więc url (https) jest wymagany;
 * NEWSLETTER może być samą treścią (zajawką) bez linku, więc dla niego url jest opcjonalny.
 */
enum class CommunityArticleKind { ARTICLE, NEWSLETTER }

data class CommunityArticle(
    val kind: CommunityArticleKind,
    val source: String,       // nazwa wydawcy/tytułu, np. "OKO.press"
    val author: String?,
    val title: String,
    val text: String?,        // zajawka/streszczenie
    // wymagany dla ARTICLE (odnośnik), opcjonalny dla NEWSLETTER
    val url: String?,
    val imageUrl: String?,
    // obraz JPG/PNG do POWIADOMIENIA (Android nie obsługuje JXL w powiadomieniach)
    val notifyImageUrl: String? = null,
    // jakość JXL zadeklarowana przez serwer 1..100 (do limitu rozmiaru KB)
    val imageQuality: Int? = null,
    val publishedAtMillis: Long?,
    val update: CommunityUpdateKind = CommunityUpdateKind.NONE,
    val audio: CommunityAudio? = null,
    val video: CommunityVideo? = null,
)

/** Pojedyncze zdjęcie mema/posta (jedno z wielu w karuzeli IG). */
data class CommunityMemeImage(
    val imageUrl: String,                 // obraz z hosta serwera (.jxl)
    val notifyImageUrl: String? = null,   // wersja do powiadomień (.jpg)
    val imageQuality: Int? = null,        // jakość JXL podana przez serwer (do dokompresji)
)

/**
 * Mem/post. Jeden post z Instagrama bywa KARUZELĄ — wiele zdjęć ([images], ≥1, [0] = główne).
 * Aplikacja pokazuje je jako przesuwalny pager. Pola `imageUrl/notifyImageUrl/imageQuality` to
 * skróty do pierwszego zdjęcia (zgodność z kodem traktującym mem jak jeden obraz: powiadomienia,
 * zapis, content-addressing).
 */
data class CommunityMeme(
    val source: String?,      // skąd/autor (np. nazwa strony)
    val title: String?,       // podpis mema
    val images: List<CommunityMemeImage>, // ≥1; karuzela gdy >1
    val url: String? = null,  // opcjonalny link do oryginału
    val publishedAtMillis: Long?,
    val update: CommunityUpdateKind = CommunityUpdateKind.NONE,
    val audio: CommunityAudio? = null,
    val video: CommunityVideo? = null,
) {
    val imageUrl: String get() = images.first().imageUrl
    val notifyImageUrl: String? get() = images.first().notifyImageUrl
    val imageQuality: Int? get() = images.first().imageQuality
}

/** reklama albo konkurs. oba działają jak platforma w tej samej sekcji, ale wyłącznie
 * jako ODNOŚNIK do strony WWW – dlatego url jest wymagany. */
enum class CommunityLinkKind { AD, CONTEST }

data class CommunityLinkItem(
    val kind: CommunityLinkKind,
    val title: String?,
    val text: String?,
    val url: String,
    // dla konkursów: czy to konkurs strony trzeciej (a nie własny serwera)
    val thirdParty: Boolean,
    val imageUrl: String?,
    // obraz JPG/PNG do POWIADOMIENIA (Android nie obsługuje JXL w powiadomieniach)
    val notifyImageUrl: String? = null,
    // jakość JXL zadeklarowana przez serwer 1..100 (do limitu rozmiaru KB)
    val imageQuality: Int? = null,
    val publishedAtMillis: Long?,
    val update: CommunityUpdateKind = CommunityUpdateKind.NONE,
)
