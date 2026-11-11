package pl.lewicowyt.notifier.network

import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PrivacyAwareDnsTest {
    @Test
    fun redundantEncryptedDnsTriesSecondAddressAfterPrimaryOutage() {
        val primaryFailure = UnknownHostException("example.com").apply {
            initCause(IOException("primary timeout"))
        }
        val primary = RecordingDns(error = primaryFailure)
        val secondary = RecordingDns(result = listOf(address(8)))
        val dns = RedundantEncryptedDns(listOf(primary, secondary))

        assertEquals(listOf(address(8)), dns.lookup("example.com"))
        assertEquals(1, primary.calls)
        assertEquals(1, secondary.calls)
    }

    @Test
    fun redundantEncryptedDnsDoesNotRetryNxdomain() {
        val primary = RecordingDns(
            error = UnknownHostException("blocked.example: NXDOMAIN"),
        )
        val secondary = RecordingDns(result = listOf(address(9)))
        val dns = RedundantEncryptedDns(listOf(primary, secondary))

        assertThrows(UnknownHostException::class.java) {
            dns.lookup("blocked.example")
        }
        assertEquals(1, primary.calls)
        assertEquals(0, secondary.calls)
    }

    @Test
    fun onionHostnameNeverReachesAnyResolver() {
        // F-17: .onion rozwiązuje tylko Tor — resolver DNS musi fail-closed bez pytania DoH/systemu
        val encrypted = RecordingDns(result = listOf(address(1)))
        val system = RecordingDns(result = listOf(address(2)))
        val dns = PrivacyAwareDns(
            encryptedDns = encrypted,
            systemDns = system,
            isAndroidPrivateDnsActive = { false },
        )

        assertThrows(UnknownHostException::class.java) {
            dns.lookup("abcdefghijklmnop1234567890.onion")
        }
        assertEquals(0, encrypted.calls)
        assertEquals(0, system.calls)
    }

    @Test
    fun activeAndroidPrivateDnsAlwaysUsesSystemResolver() {
        val encrypted = RecordingDns(result = listOf(address(1)))
        val system = RecordingDns(result = listOf(address(2)))
        val dns = PrivacyAwareDns(
            encryptedDns = encrypted,
            systemDns = system,
            isAndroidPrivateDnsActive = { true },
        )

        assertEquals(listOf(address(2)), dns.lookup("example.com"))
        assertEquals(0, encrypted.calls)
        assertEquals(1, system.calls)
    }

    @Test
    fun encryptedDnsIsDefaultWhenAndroidPrivateDnsIsInactive() {
        val encrypted = RecordingDns(result = listOf(address(3)))
        val system = RecordingDns(result = listOf(address(4)))
        val dns = PrivacyAwareDns(
            encryptedDns = encrypted,
            systemDns = system,
            isAndroidPrivateDnsActive = { false },
        )

        assertEquals(listOf(address(3)), dns.lookup("example.com"))
        assertEquals(1, encrypted.calls)
        assertEquals(0, system.calls)
    }

    @Test
    fun `T-02 successful encrypted answer is reused within ttl and refreshed after`() {
        var now = 1_000L
        val encrypted = RecordingDns(result = listOf(address(3)))
        val dns = PrivacyAwareDns(
            encryptedDns = encrypted,
            systemDns = RecordingDns(result = listOf(address(4))),
            isAndroidPrivateDnsActive = { false },
            nowMillis = { now },
            cacheTtlMillis = 60_000L,
        )
        assertEquals(listOf(address(3)), dns.lookup("example.com"))
        now += 59_000L
        assertEquals(listOf(address(3)), dns.lookup("EXAMPLE.com."))
        assertEquals(1, encrypted.calls)
        now += 2_000L
        assertEquals(listOf(address(3)), dns.lookup("example.com"))
        assertEquals(2, encrypted.calls)
    }

    @Test
    fun `T-02 network change clears remembered answers`() {
        var network = "wifi"
        val encrypted = RecordingDns(result = listOf(address(3)))
        val dns = PrivacyAwareDns(
            encryptedDns = encrypted,
            systemDns = RecordingDns(result = listOf(address(4))),
            isAndroidPrivateDnsActive = { false },
            networkKey = { network },
        )
        dns.lookup("example.com")
        dns.lookup("example.com")
        assertEquals(1, encrypted.calls)
        network = "lte"
        dns.lookup("example.com")
        assertEquals(2, encrypted.calls)
    }

    @Test
    fun `T-02 nxdomain and system fallback answers are not remembered`() {
        val nx = RecordingDns(error = UnknownHostException("blocked.example: NXDOMAIN"))
        val dnsNx = PrivacyAwareDns(encryptedDns = nx, systemDns = RecordingDns(), isAndroidPrivateDnsActive = { false })
        repeat(2) { assertThrows(UnknownHostException::class.java) { dnsNx.lookup("blocked.example") } }
        assertEquals(2, nx.calls)

        val system = RecordingDns(result = listOf(address(2)))
        val viaPrivateDns = PrivacyAwareDns(
            encryptedDns = RecordingDns(result = listOf(address(1))),
            systemDns = system,
            isAndroidPrivateDnsActive = { true },
        )
        repeat(2) { viaPrivateDns.lookup("example.com") }
        assertEquals(2, system.calls)
    }

    @Test
    fun transportFailureFallsBackAndTemporarilySkipsEncryptedDns() {
        var now = 1_000L
        val transportFailure = UnknownHostException("example.com").apply {
            initCause(IOException("DoH timeout"))
        }
        val encrypted = RecordingDns(error = transportFailure)
        val system = RecordingDns(result = listOf(address(5)))
        val dns = PrivacyAwareDns(
            encryptedDns = encrypted,
            systemDns = system,
            isAndroidPrivateDnsActive = { false },
            nowMillis = { now },
            retryDelayMillis = 5_000L,
        )

        assertEquals(listOf(address(5)), dns.lookup("example.com"))
        now += 1_000L
        assertEquals(listOf(address(5)), dns.lookup("example.org"))
        assertEquals(1, encrypted.calls)
        assertEquals(2, system.calls)
    }

    @Test
    fun nxdomainNeverLeaksToSystemResolver() {
        val encrypted = RecordingDns(
            error = UnknownHostException("blocked.example: NXDOMAIN"),
        )
        val system = RecordingDns(result = listOf(address(6)))
        val dns = PrivacyAwareDns(
            encryptedDns = encrypted,
            systemDns = system,
            isAndroidPrivateDnsActive = { false },
        )

        assertThrows(UnknownHostException::class.java) {
            dns.lookup("blocked.example")
        }
        assertEquals(1, encrypted.calls)
        assertEquals(0, system.calls)
    }

    @Test
    fun servfailUsesSystemFallback() {
        val encrypted = RecordingDns(
            error = UnknownHostException("example.com: SERVFAIL"),
        )
        val system = RecordingDns(result = listOf(address(7)))
        val dns = PrivacyAwareDns(
            encryptedDns = encrypted,
            systemDns = system,
            isAndroidPrivateDnsActive = { false },
        )

        assertEquals(listOf(address(7)), dns.lookup("example.com"))
        assertEquals(1, encrypted.calls)
        assertEquals(1, system.calls)
    }

    @Test
    fun localNetworkNameResolvingToPublicAddressIsRejected() {
        // T-08: obca sieć podstawia adres publiczny pod serwer.local — zwykły HTTP nie może wyjść poza sieć lokalną
        val publicAddress = InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))
        val system = RecordingDns(result = listOf(publicAddress))
        val dns = PrivacyAwareDns(encryptedDns = RecordingDns(), systemDns = system, isAndroidPrivateDnsActive = { false })
        assertThrows(UnknownHostException::class.java) { dns.lookup("serwer.local") }
        // mieszana odpowiedź: zostaje tylko adres lokalny
        val mixed = PrivacyAwareDns(
            encryptedDns = RecordingDns(),
            systemDns = RecordingDns(result = listOf(publicAddress, InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 5)))),
            isAndroidPrivateDnsActive = { false },
        )
        assertEquals(listOf(InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 5))), mixed.lookup("serwer.local"))
        // nawet przy włączonym prywatnym DNS Androida nazwa lokalna musi wskazywać adres lokalny
        val privateDns = PrivacyAwareDns(encryptedDns = RecordingDns(), systemDns = system, isAndroidPrivateDnsActive = { true })
        assertThrows(UnknownHostException::class.java) { privateDns.lookup("nas.lan") }
    }

    @Test
    fun strictModeNeverFallsBackToSystemDns() {
        // T-09: awaria szyfrowanego DNS = brak połączenia, bez jawnego zapytania
        val outage = UnknownHostException("example.com").apply { initCause(IOException("timeout")) }
        val encrypted = RecordingDns(error = outage)
        val system = RecordingDns(result = listOf(address(5)))
        val strict = PrivacyAwareDns(encryptedDns = encrypted, systemDns = system, isAndroidPrivateDnsActive = { false }, allowSystemFallback = { false })
        assertThrows(UnknownHostException::class.java) { strict.lookup("example.com") }
        assertThrows(UnknownHostException::class.java) { strict.lookup("example.com") }
        assertEquals(0, system.calls)
        // tryb domyślny (jak dotąd) przechodzi na DNS systemu
        val relaxed = PrivacyAwareDns(encryptedDns = RecordingDns(error = outage), systemDns = system, isAndroidPrivateDnsActive = { false })
        assertEquals(listOf(address(5)), relaxed.lookup("example.com"))
        assertEquals(1, system.calls)
    }

    private fun address(lastOctet: Int): InetAddress =
        InetAddress.getByAddress(byteArrayOf(127, 0, 0, lastOctet.toByte()))

    private class RecordingDns(
        private val result: List<InetAddress> = emptyList(),
        private val error: UnknownHostException? = null,
    ) : Dns {
        var calls = 0

        override fun lookup(hostname: String): List<InetAddress> {
            calls += 1
            error?.let { throw it }
            return result
        }
    }

    @Test
    fun localNetworkNamesGoToSystemResolverNotDoh() {
        // C-08: domowy serwer x.local zna tylko system/router — DoH dałby NXDOMAIN i poznał nazwę
        val encrypted = RecordingDns(result = listOf(address(1)))
        val system = RecordingDns(result = listOf(address(2)))
        val dns = PrivacyAwareDns(encryptedDns = encrypted, systemDns = system, isAndroidPrivateDnsActive = { false })
        assertEquals(listOf(address(2)), dns.lookup("domowy.local"))
        assertEquals(listOf(address(2)), dns.lookup("nas.home.arpa."))
        assertEquals(0, encrypted.calls)
        assertEquals(listOf(address(1)), dns.lookup("example.com"))
    }
}
