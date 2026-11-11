package pl.lewicowyt.notifier.community.push

import android.content.Context
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import pl.lewicowyt.notifier.network.PrivacyHttpClient

/**
 * Klient HTTP dla adresów `.onion` — kieruje ruch przez lokalne proxy SOCKS Tora (Orbot). Dzięki temu
 * serwer i sieć NIE widzą IP urządzenia; adres `.onion` w ogóle rozwiązuje dopiero Tor.
 *
 * WAŻNE: to, że apka łączy się z `127.0.0.1`, dotyczy **lokalnego proxy Tora**, a nie serwera — serwer
 * `.onion` jest po drugiej stronie obwodu Tor (NAT/adres LAN serwera są tu bez znaczenia). „failed to
 * connect to /127.0.0.1:9050" oznacza po prostu, że **Orbot nie działa / nie wystawia SOCKS** na tym
 * porcie.
 *
 * Źródło Tora: przede wszystkim **wbudowany Tor** ([EmbeddedTor], SOCKS 9250) — nie wymaga Orbota. Jako
 * fallback wykrywamy zewnętrznego Orbota/tor systemowy na **9050** albo Tor Browser na **9150**.
 * Ruch Tor nie jest testowalny w buildzie — do potwierdzenia na urządzeniu.
 */
object CommunityTorClient {
    private const val SOCKS_HOST = "127.0.0.1"
    private val SOCKS_PORTS = intArrayOf(9050, 9150)
    private const val PROBE_TIMEOUT_MS = 800

    fun isOnion(url: String): Boolean =
        // L-4: trimEnd('.') — adres z kropką końcową (x.onion.) to też onion; inaczej poszedłby clearnetem.
        // A-25: host przez OkHttp (spójnie z toCommunityUrlOrNull) — java.net.URI zwracał null dla hosta z `_`,
        // a wtedy adres .onion szedłby klientem clearnet.
        runCatching { url.trim().toHttpUrlOrNull()?.host?.lowercase()?.trimEnd('.')?.endsWith(".onion") == true }
            .getOrDefault(false)

    // Dns nie rozwiązuje .onion lokalnie — zwracamy placeholder, a nazwę host rozwiąże SOCKS (Tor).
    private val torDns = Dns { hostname ->
        if (hostname.lowercase().trimEnd('.').endsWith(".onion")) listOf(InetAddress.getByAddress(hostname, byteArrayOf(0, 0, 0, 0)))
        else Dns.SYSTEM.lookup(hostname)
    }

    @Volatile private var cached: OkHttpClient? = null
    @Volatile private var cachedPort: Int = 0

    /**
     * F-04: domyślnie ufamy WYŁĄCZNIE wbudowanemu Torowi (nasz proces, znamy go). Ślepy fallback na
     * `127.0.0.1:9050/9150` został wyłączony — tam mogłaby nasłuchiwać DOWOLNA apka udająca SOCKS i
     * podglądać ruch/rejestracje. Zewnętrzny Orbot można świadomie włączyć (wtedy sprawdzamy port),
     * ale to opcja użytkownika, nie zachowanie domyślne.
     */
    @Volatile var allowExternalOrbot: Boolean = false

    /** Pierwszy nasłuchujący port zewnętrznego Orbota (9050/9150) — tylko gdy użytkownik na to pozwolił. */
    fun reachableSocksPort(): Int? {
        if (!allowExternalOrbot) return null
        for (p in SOCKS_PORTS) {
            val ok = runCatching {
                Socket().use { it.connect(InetSocketAddress(SOCKS_HOST, p), PROBE_TIMEOUT_MS) }
                true
            }.getOrDefault(false)
            if (ok) return p
        }
        return null
    }

    /** Czy Tor jest dostępny: wbudowany (gotowy) albo — jeśli dozwolony — zewnętrzny Orbot. */
    fun isTorAvailable(): Boolean = EmbeddedTor.isReady() || reachableSocksPort() != null

    /**
     * Zapewnia działający SOCKS pod `.onion`: wbudowany gotowy (albo dozwolony Orbot) → od razu OK;
     * inaczej startuje wbudowanego Tora i CZEKA na zestawienie obwodu. Wołać z suspend przed pierwszym
     * pobraniem `.onion`, żeby uwzględnić bootstrap.
     */
    suspend fun prepare(context: Context): Boolean {
        if (EmbeddedTor.isReady() || reachableSocksPort() != null) return true
        return EmbeddedTor.awaitReady(context)
    }

    // Port SOCKS: wbudowany-gotowy (realny port kmp-tora) → [opcjonalnie] zewnętrzny Orbot → wbudowany.
    // Port SOCKS: wbudowany-GOTOWY (realny port z LISTENERS) → [opcjonalnie] zewnętrzny Orbot → BRAK.
    // H-3 (fail-closed): gdy Tor nie jest gotowy, NIE zgadujemy 9250 — zwracamy null, więc żądanie .onion
    // się NIE wykona (lepiej błąd niż połączenie do losowego portu, gdzie może siedzieć obca apka).
    private fun resolvePort(context: Context): Int? {
        EmbeddedTor.ensureStarted(context) // start wbudowanego leniwie (nie blokuje)
        EmbeddedTor.readySocksPort()?.let { return it } // realny port TYLKO gdy Tor gotowy
        return reachableSocksPort() // null, gdy allowExternalOrbot == false → brak portu → fail-closed
    }

    /**
     * Klient przez Tor — albo null, gdy nie ma PEWNEGO portu SOCKS (Tor niegotowy i brak dozwolonego Orbota).
     * Wołający MUSI obsłużyć null = „nie dało się bezpiecznie połączyć .onion" (fail-closed, H-3). Przy zmianie
     * portu (np. po bootstrapie wbudowanego Tora) klient jest przebudowywany.
     */
    fun get(context: Context): OkHttpClient? {
        val port = resolvePort(context) ?: return null
        synchronized(this) {
            cached?.let { if (cachedPort == port) return it }
            val client = PrivacyHttpClient.get(context).newBuilder()
                .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(SOCKS_HOST, port)))
                .dns(torDns)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS)
                .build()
            cached = client
            cachedPort = port
            return client
        }
    }
}
