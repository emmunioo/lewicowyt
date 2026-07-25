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
}
