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
) : Dns {
    @Volatile
    private var encryptedDnsDisabledUntilMillis = 0L

    @Throws(UnknownHostException::class)
    override fun lookup(hostname: String): List<InetAddress> {
        if (runCatching(isAndroidPrivateDnsActive).getOrDefault(false)) {
            return systemDns.lookup(hostname)
        }

        val now = nowMillis()
        if (now < encryptedDnsDisabledUntilMillis) {
            return systemDns.lookup(hostname)
        }

        return try {
            encryptedDns.lookup(hostname).also {
                encryptedDnsDisabledUntilMillis = 0L
            }
        } catch (error: UnknownHostException) {
            if (!error.indicatesResolverOutage()) throw error
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
        )
        return OkHttpClient.Builder()
            .dns(privacyDns)
            .eventListenerFactory(DiagnosticNetworkUsage.eventListenerFactory())
            // Klienci nie podążają automatycznie za odpowiedzią niezaufanego
            // serwera do dowolnego hosta. Obrazy mają własną, jawną obsługę
            // przekierowań z allowlistą domen.
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
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

    fun isActive(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val manager = connectivityManager ?: return false
        val network = manager.activeNetwork ?: return false
        return manager.getLinkProperties(network)?.isPrivateDnsActive == true
    }
}
