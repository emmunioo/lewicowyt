package pl.lewicowyt.notifier.network

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import pl.lewicowyt.notifier.diagnostics.DiagnosticNetworkUsage

/**
 * Respects Android Private DNS when it is active. Otherwise it uses AdGuard
 * DNS-over-HTTPS and falls back to the system resolver only for a transport or
 * resolver outage, never for a legitimate NXDOMAIN response.
 */
internal class PrivacyAwareDns(
    private val encryptedDns: Dns,
    private val systemDns: Dns = Dns.SYSTEM,
    private val isAndroidPrivateDnsActive: () -> Boolean,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val retryDelayMillis: Long = ENCRYPTED_DNS_RETRY_DELAY_MILLIS,
    // T-02: identyfikator bieżącej sieci — po zmianie sieci (Wi-Fi ↔ LTE) zapamiętane adresy są czyszczone
    private val networkKey: () -> Any? = { null },
    private val cacheTtlMillis: Long = ENCRYPTED_DNS_CACHE_TTL_MILLIS,
    // T-09: tryb ścisły — gdy zwraca false, awaria szyfrowanego DNS oznacza brak połączenia, a NIE jawne zapytanie
    private val allowSystemFallback: () -> Boolean = { true },
) : Dns {
    @Volatile
    private var encryptedDnsDisabledUntilMillis = 0L

    /**
     * T-02: pamięć udanych odpowiedzi DNS-over-HTTPS. Systemowy resolver Androida ma własną pamięć z TTL, ale
     * prywatny DoH ją omija — bez tej pamięci KAŻDE nowe połączenie (OkHttp trzyma bezczynne ~5 min) kosztowało
     * dwa dodatkowe żądania HTTPS do AdGuard (A i AAAA). Zapamiętujemy tylko SUKCESY (NXDOMAIN i awarie jak dotąd),
     * na krótko ([cacheTtlMillis], typowy TTL dużych CDN), w pamięci procesu i tylko dla bieżącej sieci.
     */
    private class CachedAnswer(val addresses: List<InetAddress>, val expiresAtMillis: Long)
    private val cache = object : LinkedHashMap<String, CachedAnswer>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedAnswer>?) = size > MAX_CACHED_NAMES
    }
    private var cacheNetwork: Any? = null

    private fun cached(hostname: String, now: Long): List<InetAddress>? = synchronized(cache) {
        val network = runCatching(networkKey).getOrNull()
        if (network != cacheNetwork) {
            cache.clear()
            cacheNetwork = network
        }
        val hit = cache[hostname] ?: return null
        if (now >= hit.expiresAtMillis) {
            cache.remove(hostname)
            return null
        }
        hit.addresses
    }

    private fun remember(hostname: String, addresses: List<InetAddress>, now: Long) {
        if (addresses.isEmpty()) return
        synchronized(cache) { cache[hostname] = CachedAnswer(addresses, now + cacheTtlMillis) }
    }

    /** Adres prywatny/lokalny: loopback, link-local, site-local (RFC 1918) oraz IPv6 unique-local (fc00::/7). */
    internal fun isLocalAddress(address: InetAddress): Boolean =
        address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress ||
            (address.address.size == 16 && (address.address[0].toInt() and 0xFE) == 0xFC)

    internal fun isLocalNetworkName(hostname: String): Boolean {
        val h = hostname.trimEnd('.').lowercase()
        return LOCAL_SUFFIXES.any { h.endsWith(it) }
    }

    @Throws(UnknownHostException::class)
    override fun lookup(hostname: String): List<InetAddress> {
        // F-17: nazwę .onion rozwiązuje WYŁĄCZNIE Tor (zdalnie, przez SOCKS). Jeśli trafia do tego
        // resolvera, to błąd routingu — NIGDY nie pytamy o nią DoH ani systemowego DNS (wyciek do
        // resolvera/ISP). Fail-closed: od razu UnknownHostException. Klient Tora ma własny Dns (torDns),
        // więc ta blokada nie dotyczy prawdziwej ścieżki .onion.
        if (hostname.trimEnd('.').endsWith(".onion", ignoreCase = true)) {
            throw UnknownHostException("onion address must be resolved via Tor, not DNS")
        }
        // C-08: nazwy sieci lokalnej (mDNS .local, .home.arpa, .lan) zna tylko system/router — publiczny DoH
        // odpowiedziałby NXDOMAIN, a nazwa domowego serwera wyciekłaby do zewnętrznego resolvera.
        // T-08: taka nazwa MUSI wskazywać adres lokalny — inaczej (np. obca sieć Wi-Fi podstawia adres publiczny pod
        // serwer.local) zwykły HTTP wyszedłby poza sieć lokalną
        if (isLocalNetworkName(hostname)) {
            val local = systemDns.lookup(hostname).filter { isLocalAddress(it) }
            if (local.isEmpty()) throw UnknownHostException("$hostname nie wskazuje adresu w sieci lokalnej")
            return local
        }
        if (runCatching(isAndroidPrivateDnsActive).getOrDefault(false)) {
            return systemDns.lookup(hostname)
        }

        val now = nowMillis()
        val key = hostname.trimEnd('.').lowercase()
        cached(key, now)?.let { return it }
        if (now < encryptedDnsDisabledUntilMillis) {
            if (!runCatching(allowSystemFallback).getOrDefault(true)) {
                throw UnknownHostException("szyfrowany DNS niedostępny, a tryb ścisły zabrania jawnego zapytania")
            }
            return systemDns.lookup(hostname)
        }

        return try {
            encryptedDns.lookup(hostname).also {
                encryptedDnsDisabledUntilMillis = 0L
                remember(key, it, now)
            }
        } catch (error: UnknownHostException) {
            if (!error.indicatesResolverOutage()) throw error
            // T-09: tryb ścisły — awaria szyfrowanego DNS = brak połączenia, nie jawne zapytanie o nazwę hosta
            if (!runCatching(allowSystemFallback).getOrDefault(true)) throw error
            encryptedDnsDisabledUntilMillis = now + retryDelayMillis
            try {
                systemDns.lookup(hostname)
            } catch (systemError: UnknownHostException) {
                systemError.addSuppressed(error)
                throw systemError
            }
        }
    }

    private companion object {
        const val ENCRYPTED_DNS_RETRY_DELAY_MILLIS = 5L * 60L * 1_000L
        const val ENCRYPTED_DNS_CACHE_TTL_MILLIS = 5L * 60L * 1_000L
        const val MAX_CACHED_NAMES = 128
        val LOCAL_SUFFIXES = listOf(".local", ".home.arpa", ".lan")
    }
}

/**
 * Each AdGuard bootstrap address has an independent connection pool. This also
 * retries the second address after an HTTP-level SERVFAIL, not only after a
 * failed TCP connection.
 */
internal class RedundantEncryptedDns(
    private val resolvers: List<Dns>,
) : Dns {
    init {
        require(resolvers.isNotEmpty())
    }

    @Throws(UnknownHostException::class)
    override fun lookup(hostname: String): List<InetAddress> {
        val outages = mutableListOf<UnknownHostException>()
        for (resolver in resolvers) {
            try {
                return resolver.lookup(hostname)
            } catch (error: UnknownHostException) {
                if (!error.indicatesResolverOutage()) throw error
                outages += error
            }
        }
        val result = outages.last()
        outages.dropLast(1).forEach(result::addSuppressed)
        throw result
    }
}

private fun UnknownHostException.indicatesResolverOutage(): Boolean {
    val failures = listOf(this as Throwable) + listOfNotNull(cause) + suppressed
    if (failures.any { it.message?.endsWith(": NXDOMAIN") == true }) return false
    return failures.any {
        it.message?.endsWith(": SERVFAIL") == true ||
            it.cause != null ||
            it !is UnknownHostException
    }
}

internal object PrivacyHttpClient {
    const val ADGUARD_DOH_URL = "https://dns.adguard-dns.com/dns-query"
    const val ADGUARD_PRIMARY_IPV4 = "94.140.14.14"
    const val ADGUARD_SECONDARY_IPV4 = "94.140.15.15"

    @Volatile
    private var sharedClient: OkHttpClient? = null

    fun get(context: Context): OkHttpClient =
        sharedClient ?: synchronized(this) {
            sharedClient ?: create(context.applicationContext).also {
                sharedClient = it
            }
        }

    private fun create(context: Context): OkHttpClient {
        val encryptedDns = RedundantEncryptedDns(
            listOf(
                createEncryptedDns(
                    ipv4Address(ADGUARD_PRIMARY_IPV4, 94, 140, 14, 14),
                ),
                createEncryptedDns(
                    ipv4Address(ADGUARD_SECONDARY_IPV4, 94, 140, 15, 15),
                ),
            ),
        )
        val androidPrivateDnsState = AndroidPrivateDnsState(context)
        val privacyDns = PrivacyAwareDns(
            encryptedDns = encryptedDns,
            isAndroidPrivateDnsActive = androidPrivateDnsState::isActive,
            networkKey = androidPrivateDnsState::networkHandle,
            allowSystemFallback = { !PrivacySettings.strictDns(context) }, // czytane przy każdym zapytaniu — zmiana działa od razu
        )
        // Zaufanie TLS: domyślnie tylko CA systemowe; ukryte ustawienie (PrivacySettings.trustUserCertificates) dopuszcza też
        // CA użytkownika — patrz nota dla audytorów w UserCertificateTrust.kt. Dotyczy TYLKO tego wspólnego klienta;
        // klienty DNS-over-HTTPS (createEncryptedDns) celowo go NIE używają.
        val (tlsSocketFactory, tlsTrustManager) = UserCertificateTrust.forOkHttp { PrivacySettings.trustUserCertificates(context) }
        return OkHttpClient.Builder()
            .dns(privacyDns)
            .sslSocketFactory(tlsSocketFactory, tlsTrustManager)
            .eventListenerFactory(DiagnosticNetworkUsage.eventListenerFactory())
            // nie idziemy automatycznie za redirectem niezaufanego serwera na
            // dowolny host; obrazy mają własną obsługę przekierowań z allowlistą domen
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            // D-03 (B-14): żądanie bez własnego User-Agent dostaje UA przeglądarki zamiast „okhttp/5.x" — inaczej
            // np. miniatura z i.ytimg.com zdradzałaby Google użytkownika lewicowYT. Serwery społeczności ustawiają
            // własny, stały UA (COMMUNITY_USER_AGENT), więc ich to nie dotyczy.
            .addInterceptor { chain ->
                val request = chain.request()
                chain.proceed(
                    if (request.header("User-Agent") != null) request
                    else request.newBuilder().header("User-Agent", BROWSER_USER_AGENT).build(),
                )
            }
            .build()
    }

    private fun createEncryptedDns(bootstrapAddress: InetAddress): Dns {
        val bootstrapClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(7, TimeUnit.SECONDS)
            .build()
        return DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url(ADGUARD_DOH_URL.toHttpUrl())
            .bootstrapDnsHosts(bootstrapAddress)
            .includeIPv6(true)
            .post(true)
            .build()
    }

    private fun ipv4Address(
        hostname: String,
        first: Int,
        second: Int,
        third: Int,
        fourth: Int,
    ): InetAddress = InetAddress.getByAddress(
        hostname,
        byteArrayOf(first.toByte(), second.toByte(), third.toByte(), fourth.toByte()),
    )
}

private class AndroidPrivateDnsState(context: Context) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    /** T-02: uchwyt bieżącej sieci (zmiana sieci czyści pamięć DNS). */
    fun networkHandle(): Long? = connectivityManager?.activeNetwork?.networkHandle

    fun isActive(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val manager = connectivityManager ?: return false
        val network = manager.activeNetwork ?: return false
        return manager.getLinkProperties(network)?.isPrivateDnsActive == true
    }
}
