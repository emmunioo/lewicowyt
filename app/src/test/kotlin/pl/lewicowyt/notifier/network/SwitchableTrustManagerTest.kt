package pl.lewicowyt.notifier.network

import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SwitchableTrustManagerTest {
    private class Tm(val ok: Boolean) : X509TrustManager {
        var calls = 0
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            calls++
            if (!ok) throw CertificateException("odrzucono")
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val chain = emptyArray<X509Certificate>()

    @Test
    fun `disabled setting never consults user store`() {
        val user = Tm(true)
        val m = SwitchableTrustManager(Tm(false), { false }, { user })
        assertThrows(CertificateException::class.java) { m.checkServerTrusted(chain, "RSA") }
        assertEquals(0, user.calls)
    }

    @Test
    fun `enabled setting falls back to user store only after system rejection`() {
        val user = Tm(true)
        SwitchableTrustManager(Tm(false), { true }, { user }).checkServerTrusted(chain, "RSA")
        assertEquals(1, user.calls)
        val user2 = Tm(true)
        SwitchableTrustManager(Tm(true), { true }, { user2 }).checkServerTrusted(chain, "RSA")
        assertEquals(0, user2.calls)
    }

    @Test
    fun `enabled but user store rejects or missing still fails`() {
        assertThrows(CertificateException::class.java) {
            SwitchableTrustManager(Tm(false), { true }, { Tm(false) }).checkServerTrusted(chain, "RSA")
        }
        assertThrows(CertificateException::class.java) {
            SwitchableTrustManager(Tm(false), { true }, { null }).checkServerTrusted(chain, "RSA")
        }
    }
}
