package pl.lewicowyt.notifier.community

import android.content.Context
import java.io.IOException
import java.io.Reader
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import pl.lewicowyt.notifier.community.push.CommunityTorClient
import pl.lewicowyt.notifier.network.DerivedHttpClients
import pl.lewicowyt.notifier.network.isAllowedTextResponseMime

/**
 * send-only klient serwerów społeczności. FIZYCZNIE umie tylko warunkowy GET:
 * zero body, zero query ze stanem, zero ciasteczek, zero nagłówków identyfikujących
 * usera/urządzenie/instalację. na zewnątrz idzie jedynie stały User-Agent (taki sam
 * dla wszystkich instalacji) i ewentualne ETag/Last-Modified oddane serwerowi z powrotem.
 * HTTPS wszędzie; adresy `.onion` idą przez Tor (ukryte IP, ten sam obwód co rejestracja push),
 * a zwykły HTTP tylko w LAN (serwer testowy).
 *
 * baza to współdzielony PrivacyHttpClient (prywatny DNS, followRedirects=false), więc
 * serwer nie przekieruje nas po cichu na obcy host.
 */
class CommunityHttpClient(
    private val appContext: Context,
    private val baseClient: OkHttpClient,
) {
    suspend fun fetch(
        url: String,
        conditional: CommunityConditional?,
        maxChars: Int = MAX_RESPONSE_CHARS,
    ): CommunityFetchResult = withContext(Dispatchers.IO) {
        val target = url.toCommunityUrlOrNull()
            ?: return@withContext CommunityFetchResult.Failure(
                "Dozwolony jest HTTPS (lub .onion przez Tor), a zwykły HTTP tylko w sieci lokalnej (serwer testowy)",
            )
        // onion → Tor (serwer nie widzi IP), reszta → współdzielony prywatny klient. Przed pierwszym
        // pobraniem .onion czekamy, aż wbudowany Tor (albo Orbot) zestawi obwód (bootstrap).
        val onion = CommunityTorClient.isOnion(url)
        if (onion) CommunityTorClient.prepare(appContext)
        // H-3 (fail-closed): dla .onion łączymy się TYLKO przez pewny port Tora. Gdy go nie ma (Tor niegotowy,
        // brak dozwolonego Orbota) get() zwraca null — przerywamy, zamiast łączyć się na ślepo na losowy port.
        val base = if (onion) {
            CommunityTorClient.get(appContext) ?: return@withContext CommunityFetchResult.Failure(
                "Tor (wbudowany) jeszcze się uruchamia albo nie zestawił obwodu .onion — spróbuj ponownie za chwilę.",
            )
        } else {
            baseClient
        }
        // .onion jest WOLNE, zwłaszcza za pierwszym razem: budowa obwodu Tora + pobranie deskryptora usługi
        // ukrytej + rendezvous potrafi trwać ~60 s (zmierzone). Krótkie limity clearnetu (15/20/35 s) ucinałyby
        // takie pobranie mimo działającego Tora, dając fałszywe „nie udało się". Dlatego dla onion dłuższe limity.
        // T-11: klient z limitami budowany raz na klienta bazowego (nie przy każdym pobraniu manifestu/pliku)
        val client = DerivedHttpClients.get(
            base,
            if (onion) {
                DerivedHttpClients.Spec(
                    connectTimeoutMillis = TimeUnit.SECONDS.toMillis(CONNECT_TIMEOUT_ONION_SEC),
                    readTimeoutMillis = TimeUnit.SECONDS.toMillis(READ_TIMEOUT_ONION_SEC),
                    callTimeoutMillis = TimeUnit.SECONDS.toMillis(CALL_TIMEOUT_ONION_SEC),
                )
            } else {
                DerivedHttpClients.Spec(
                    connectTimeoutMillis = TimeUnit.SECONDS.toMillis(CONNECT_TIMEOUT_SEC),
                    readTimeoutMillis = TimeUnit.SECONDS.toMillis(READ_TIMEOUT_SEC),
                    callTimeoutMillis = TimeUnit.SECONDS.toMillis(CALL_TIMEOUT_SEC),
                )
            },
        )
        runCatching {
            client.newCall(buildCommunityGetRequest(target, conditional)).execute().use { response ->
                when {
                    response.code == HTTP_NOT_MODIFIED -> CommunityFetchResult.NotModified
                    !response.isSuccessful -> communityHttpFailure(response.code)
                    !isAllowedTextResponseMime(response.body.contentType()?.toString()) ->
                        CommunityFetchResult.Failure("Serwer zwrócił dane binarne zamiast tekstu", permanent = true)
                    else -> CommunityFetchResult.Success(
                        body = readLimited(response.body.charStream(), maxChars),
                        etag = response.header("ETag"),
                        lastModified = response.header("Last-Modified"),
                    )
                }
            }
        }.getOrElse { error ->
            // Dla .onion typowa porażka to brak lokalnego proxy Tora (Orbot) — komunikat „failed to connect
            // to /127.0.0.1:9050" dotyczy PROXY, nie serwera. Dajemy czytelną podpowiedź zamiast surowego błędu.
            if (error is ResponseTooLargeException) {
                // J-03: za duża odpowiedź będzie za duża i przy następnej próbie — porażka trwała
                CommunityFetchResult.Failure(error.message ?: "Odpowiedź przekracza limit", permanent = true)
            } else if (onion && !CommunityTorClient.isTorAvailable()) {
                // L-12: bez rady „włącz Orbota" — zewnętrzny Orbot jest domyślnie wyłączony (allowExternalOrbot=false),
                // więc nie pomoże; łączymy się wyłącznie przez wbudowany Tor.
                CommunityFetchResult.Failure(
                    "Tor (wbudowany) jeszcze się uruchamia albo nie zdążył zestawić obwodu .onion — " +
                        "spróbuj ponownie za chwilę.",
                )
            } else {
                CommunityFetchResult.Failure(error.message ?: error.javaClass.simpleName)
            }
        }
    }

    private fun readLimited(reader: Reader, maxChars: Int): String = reader.use {
        val buffer = CharArray(8_192)
        val result = StringBuilder()
        while (true) {
            val count = it.read(buffer)
            if (count < 0) break
            result.append(buffer, 0, count)
            if (result.length > maxChars) throw ResponseTooLargeException("Odpowiedź przekracza limit $maxChars znaków")
        }
        result.toString()
    }

    private class ResponseTooLargeException(message: String) : IOException(message)

    companion object {
        const val MAX_RESPONSE_CHARS = 1_000_000
        const val CONNECT_TIMEOUT_SEC = 15L
        const val READ_TIMEOUT_SEC = 20L
        const val CALL_TIMEOUT_SEC = 35L
        // .onion — dłuższe limity: pierwsze połączenie przez sieć Tor (obwód + deskryptor HS + rendezvous)
        // potrafi trwać ~60 s. Bez tego pierwsze pobranie oniona padałoby na timeout mimo działającego Tora.
        const val CONNECT_TIMEOUT_ONION_SEC = 60L
        const val READ_TIMEOUT_ONION_SEC = 60L
        const val CALL_TIMEOUT_ONION_SEC = 120L
        private const val HTTP_NOT_MODIFIED = 304
    }
}

/** stały UA, taki sam dla wszystkich instalacji – nie mówi nic o userze ani urządzeniu. */
internal const val COMMUNITY_USER_AGENT = "lewicowYT"

/** domyślny port serwera społeczności (aplikacja i serwer referencyjny). */
const val DEFAULT_COMMUNITY_PORT = 2137
const val DEFAULT_COMMUNITY_MANIFEST_PATH = "/manifest.json"

/**
 * Pozwala wpisać SKRÓCONY adres: sam host (np. „10.0.2.2" w emulatorze albo „192.168.0.10"),
 * ewentualnie z portem/ścieżką. Uzupełnia to, czego brak:
 *  - schemat: dla adresów LAN i .onion → http, dla publicznych → https,
 *  - port: 2137, gdy nie podano — JEDEN wspólny port dla LAN-http, domeny-https ORAZ .onion. Jawny port
 *    w adresie zawsze ma pierwszeństwo (tak zmienia się domyślny),
 *  - ścieżka: /manifest.json, gdy nie podano.
 * Zwraca pełny URL albo null, gdy wejścia nie da się sensownie zinterpretować.
 */
internal fun normalizeCommunityEndpoint(raw: String): String? {
    val input = raw.trim()
    if (input.isEmpty()) return null
    val hasScheme = input.startsWith("http://", true) || input.startsWith("https://", true)
    val withScheme = if (hasScheme) {
        input
    } else {
        val hostPart = input.substringBefore('/').substringBefore(':').trim()
        if (hostPart.isEmpty()) return null
        // onion i LAN → http (Tor daje szyfrowanie usługi; LAN to serwer testowy), reszta → https
        val scheme = if (isPrivateHost(hostPart) || hostPart.trimEnd('.').endsWith(".onion", true)) "http" else "https"
        "$scheme://$input"
    }
    val url = withScheme.toHttpUrlOrNull() ?: return null
    if (url.host.isBlank()) return null

    val authority = withScheme.substringAfter("://").substringBefore('/')
    val explicitPort = Regex(""":\d+$""").containsMatchIn(authority)

    val builder = url.newBuilder()
    // JEDEN wspólny port (2137) dla WSZYSTKICH sieci: LAN-http, domena-https ORAZ Tor (.onion, w przyszłości
    // .lewyt). Serwer słucha HTTP/HTTPS na 2137, a port wirtualny usługi .onion też = 2137 (nie 80/443/9050).
    // Jawny port w adresie zawsze ma pierwszeństwo — to sposób na zmianę domyślnego.
    if (!explicitPort) builder.port(DEFAULT_COMMUNITY_PORT)
    if (url.encodedPath.isEmpty() || url.encodedPath == "/") {
        builder.encodedPath(DEFAULT_COMMUNITY_MANIFEST_PATH)
    }
    return builder.build().toString()
}

internal fun String.toHttpsUrlOrNull(): HttpUrl? =
    toHttpUrlOrNull()?.takeIf { it.isHttps && it.host.isNotBlank() }

/**
 * URL serwera społeczności: HTTPS wszędzie, a zwykły HTTP dopuszczamy WYŁĄCZNIE do
 * adresów w sieci lokalnej (serwer testowy w LAN) – nigdy do publicznego internetu.
 * to jest twarda granica po stronie apki, niezależna od konfiguracji sieci Androida.
 */
internal fun String.toCommunityUrlOrNull(): HttpUrl? {
    val url = toHttpUrlOrNull() ?: return null
    if (url.host.isBlank()) return null
    return when {
        url.isHttps -> url
        url.host.trimEnd('.').endsWith(".onion", true) -> url // onion: Tor zapewnia szyfrowanie i uwierzytelnienie usługi
        isPrivateHost(url.host) -> url // cleartext tylko w LAN
        else -> null
    }
}

/** czy host to adres prywatny/LAN/loopback (wtedy wolno zwykły HTTP na potrzeby testów). */
internal fun isPrivateHost(rawHost: String): Boolean {
    val host = rawHost.trim().lowercase().removeSurrounding("[", "]")
    if (host.isEmpty()) return false
    if (host == "localhost") return true
    if (host.endsWith(".local")) return true // mDNS/Bonjour, np. serwer.local

    val octets = host.split(".")
    // T-08: tylko zapis kanoniczny (cyfry, bez zer wiodących) — „010.1.1.1” bywa czytane jako ósemkowe 8.1.1.1 (adres publiczny)
    val canonical = octets.all { it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) && (it == "0" || !it.startsWith('0')) }
    if (octets.size == 4 && !canonical && octets.all { o -> o.isNotEmpty() && o.all(Char::isDigit) }) return false
    if (octets.size == 4 && canonical && octets.all { (it.toIntOrNull() ?: -1) in 0..255 }) {
        val a = octets[0].toInt()
        val b = octets[1].toInt()
        return when {
            a == 10 -> true                 // 10.0.0.0/8
            a == 127 -> true                // loopback
            a == 192 && b == 168 -> true    // 192.168.0.0/16
            a == 172 && b in 16..31 -> true // 172.16.0.0/12
            a == 169 && b == 254 -> true    // link-local
            else -> false
        }
    }

    // IPv6: loopback, link-local (fe80::/10), unique-local (fc00::/7) — TYLKO dla literałów IPv6.
    // Literał IPv6 zawsze zawiera ':'. KLUCZOWE: bez tego warunku nazwa DNS zaczynająca się od
    // „fc"/„fd" (np. fdroid.org, fcbarcelona.com) byłaby błędnie uznana za LAN → cleartext do
    // publicznego internetu (audyt F-03).
    if (host.contains(":")) {
        return host == "::1" || host.startsWith("fe80:") || host.startsWith("fc") || host.startsWith("fd")
    }
    return false
}

/**
 * Klasa transportu adresu serwera. Alternatywy (`altEndpoints`) wolno próbować WYŁĄCZNIE w tej samej
 * klasie co adres GŁÓWNY — dzięki temu złośliwy serwer `.onion` nie zmusi apki do połączenia przez
 * clearnet (audyt F-01). ONION → tylko przez Tor; HTTPS → tylko https; LAN_HTTP → tylko adresy prywatne.
 */
internal enum class CommunityTransport { ONION, HTTPS, LAN_HTTP, UNKNOWN }

internal fun communityTransportOf(endpoint: String): CommunityTransport {
    val url = endpoint.toHttpUrlOrNull() ?: return CommunityTransport.UNKNOWN
    if (url.host.isBlank()) return CommunityTransport.UNKNOWN
    return when {
        url.host.trimEnd('.').endsWith(".onion", true) -> CommunityTransport.ONION
        url.isHttps -> CommunityTransport.HTTPS
        isPrivateHost(url.host) -> CommunityTransport.LAN_HTTP
        else -> CommunityTransport.UNKNOWN
    }
}

/**
 * jedyny konstruktor żądania w tej warstwie: GET, bez body, z wąską allowlistą nagłówków.
 * wydzielone i internal, żeby test mógł sprawdzić, że nic identyfikującego ani żadnej
 * metody zapisu tu nie przecieka.
 */
internal fun buildCommunityGetRequest(url: HttpUrl, conditional: CommunityConditional?): Request =
    Request.Builder()
        .url(url)
        .get()
        .header("User-Agent", COMMUNITY_USER_AGENT)
        .header("Accept", "application/json")
        .apply {
            conditional?.etag?.takeIf { it.isNotBlank() }?.let { header("If-None-Match", it) }
            conditional?.lastModified?.takeIf { it.isNotBlank() }?.let { header("If-Modified-Since", it) }
        }
        .build()

/** B-03: odpowiedź HTTP, której ponawianie nic nie da (4xx poza 408 Request Timeout i 429 Too Many Requests). */
internal fun isPermanentHttpFailure(code: Int): Boolean = code in 400..499 && code != 408 && code != 429

/**
 * Wynik dla odpowiedzi spoza 2xx/304. J-03: przekierowań nie śledzimy (followRedirects=false), więc 3xx jest
 * deterministyczne i liczy się jak trwałe — inaczej plik `content` z 301 blokowałby kolejkę bez końca.
 */
internal fun communityHttpFailure(code: Int): CommunityFetchResult.Failure =
    if (code in 300..399) {
        CommunityFetchResult.Failure(
            "HTTP $code (przekierowania nie są obsługiwane — adres musi odpowiadać bezpośrednio)",
            permanent = true,
        )
    } else {
        CommunityFetchResult.Failure("HTTP $code", permanent = isPermanentHttpFailure(code))
    }
