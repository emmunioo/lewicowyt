package pl.lewicowyt.notifier.network

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DerivedHttpClientsTest {
    private val base = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS) // jak klient Tora
        .build()

    @Test
    fun `T-11 same settings reuse one client, different settings do not`() {
        val spec = DerivedHttpClients.Spec(connectTimeoutMillis = 12_000L, readTimeoutMillis = 15_000L, noRedirects = true)
        val a = DerivedHttpClients.get(base, spec)
        assertSame(a, DerivedHttpClients.get(base, spec.copy()))
        assertNotSame(a, DerivedHttpClients.get(base, spec.copy(readTimeoutMillis = 16_000L)))
        assertNotSame(a, DerivedHttpClients.get(OkHttpClient(), spec))
    }

    @Test
    fun `T-11 derived client equals the former newBuilder result`() {
        val derived = DerivedHttpClients.get(
            base,
            DerivedHttpClients.Spec(connectTimeoutMillis = 12_000L, readTimeoutMillis = 15_000L, noRedirects = true),
        )
        assertEquals(12_000, derived.connectTimeoutMillis)
        assertEquals(15_000, derived.readTimeoutMillis)
        assertEquals("niepodany limit całości dziedziczy z klienta bazowego", 60_000, derived.callTimeoutMillis)
        assertFalse(derived.followRedirects)
        assertFalse(derived.followSslRedirects)
        assertSame("ta sama pula połączeń co klient bazowy", base.connectionPool, derived.connectionPool)
        assertSame(base.dispatcher, derived.dispatcher)
    }

    @Test
    fun `T-11 redirect settings are inherited unless disabled`() {
        val derived = DerivedHttpClients.get(base, DerivedHttpClients.Spec(readTimeoutMillis = 1_000L))
        assertTrue(derived.followRedirects)
        assertEquals(30_000, derived.connectTimeoutMillis)
    }
}
