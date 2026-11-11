package pl.lewicowyt.notifier.community

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * niezmienniki send-only: żądanie do serwera społeczności to WYŁĄCZNIE GET, bez body,
 * bez ciasteczek/autoryzacji i bez niczego, co identyfikowałoby usera. jedyne dozwolone
 * nagłówki to stały UA, Accept i (opcjonalnie) warunkowy ETag/Last-Modified.
 */
class CommunityHttpRequestTest {

    private val url = "https://serwer.example/manifest".toHttpsUrlOrNull()!!

    @Test
    fun `request is a bodyless GET`() {
        val request = buildCommunityGetRequest(url, conditional = null)
        assertEquals("GET", request.method)
        assertNull(request.body)
    }

    @Test
    fun `only allowlisted headers without conditional`() {
        val request = buildCommunityGetRequest(url, conditional = null)
        assertEquals(setOf("User-Agent", "Accept"), request.headers.names())
        assertEquals(COMMUNITY_USER_AGENT, request.header("User-Agent"))
        assertEquals("application/json", request.header("Accept"))
    }

    @Test
    fun `no identifying headers leak`() {
        val request = buildCommunityGetRequest(url, conditional = null)
        assertNull(request.header("Cookie"))
        assertNull(request.header("Authorization"))
        assertNull(request.header("Accept-Language"))
        assertNull(request.header("X-Device-Id"))
    }

    @Test
    fun `conditional headers are added only when present`() {
        val request = buildCommunityGetRequest(
            url,
            CommunityConditional(etag = "\"abc\"", lastModified = "Wed, 21 Oct 2026 07:28:00 GMT"),
        )
        assertEquals("\"abc\"", request.header("If-None-Match"))
        assertEquals("Wed, 21 Oct 2026 07:28:00 GMT", request.header("If-Modified-Since"))
    }

    @Test
    fun `blank conditional values are ignored`() {
        val request = buildCommunityGetRequest(url, CommunityConditional(etag = "  ", lastModified = ""))
        assertNull(request.header("If-None-Match"))
        assertNull(request.header("If-Modified-Since"))
    }

    @Test
    fun `only valid https urls are accepted`() {
        assertTrue("https://serwer.example/x".toHttpsUrlOrNull() != null)
        assertNull("http://serwer.example/x".toHttpsUrlOrNull()) // cleartext odrzucony
        assertNull("to nie jest url".toHttpsUrlOrNull())
        assertNull("https://".toHttpsUrlOrNull()) // brak hosta
    }

    @Test
    fun `community url allows https anywhere and http only on the LAN`() {
        // https zawsze OK
        assertTrue("https://serwer.example/m".toCommunityUrlOrNull() != null)
        assertTrue("https://192.168.1.10/m".toCommunityUrlOrNull() != null)
        // http publiczny odrzucony
        assertNull("http://serwer.example/m".toCommunityUrlOrNull())
        assertNull("http://8.8.8.8/m".toCommunityUrlOrNull())
        // http w LAN dozwolony
        assertTrue("http://192.168.1.50:8080/manifest.json".toCommunityUrlOrNull() != null)
        assertTrue("http://10.0.0.5:8080/m".toCommunityUrlOrNull() != null)
        assertTrue("http://10.0.2.2:8080/m".toCommunityUrlOrNull() != null) // emulator
        assertTrue("http://localhost:8080/m".toCommunityUrlOrNull() != null)
        assertTrue("http://127.0.0.1:8080/m".toCommunityUrlOrNull() != null)
        assertTrue("http://serwer.local:8080/m".toCommunityUrlOrNull() != null)
    }

    @Test
    fun `non canonical IPv4 notation is not treated as private lan (audit T-08)`() {
        // zera wiodące bywają czytane jako ósemkowe (010.1.1.1 = 8.1.1.1, adres publiczny) — cleartext nie przejdzie
        assertNull("http://010.1.1.1:8080/m".toCommunityUrlOrNull())
        assertNull("http://192.168.001.010:8080/m".toCommunityUrlOrNull())
        assertNull("http://0127.0.0.1:8080/m".toCommunityUrlOrNull())
        assertTrue("http://10.1.1.1:8080/m".toCommunityUrlOrNull() != null)
        assertTrue("http://192.168.0.10:8080/m".toCommunityUrlOrNull() != null)
        assertTrue("http://10.0.0.5:8080/m".toCommunityUrlOrNull() != null)
    }

    @Test
    fun `shortened endpoints expand with scheme, default port and manifest path`() {
        // LAN: http + port 2137 + /manifest.json
        assertEquals(
            "http://10.0.2.2:2137/manifest.json",
            normalizeCommunityEndpoint("10.0.2.2"),
        )
        assertEquals(
            "http://192.168.0.10:2137/manifest.json",
            normalizeCommunityEndpoint("192.168.0.10"),
        )
        // jawny port zachowany
        assertEquals(
            "http://192.168.0.10:9000/manifest.json",
            normalizeCommunityEndpoint("192.168.0.10:9000"),
        )
        // scheme podany, brak portu/ścieżki -> dopisane
        assertEquals(
            "http://10.0.2.2:2137/manifest.json",
            normalizeCommunityEndpoint("http://10.0.2.2"),
        )
        // publiczny host (domena) -> https NA PORCIE 2137 (serwer słucha HTTP i HTTPS na 2137, nie na 443)
        assertEquals(
            "https://serwer.example:2137/manifest.json",
            normalizeCommunityEndpoint("serwer.example"),
        )
        // jawny port na domenie zachowany (nie nadpisujemy 2137)
        assertEquals(
            "https://serwer.example:8443/manifest.json",
            normalizeCommunityEndpoint("https://serwer.example:8443"),
        )
        // własna ścieżka zostaje; port nie był podany wprost → domyślne 2137 (serwer nie słucha na 443)
        assertEquals(
            "https://serwer.example:2137/inny.json",
            normalizeCommunityEndpoint("https://serwer.example/inny.json"),
        )
        // Tor (.onion) → http + TEN SAM port 2137 (port wirtualny usługi = 2137, nie 80/9050)
        assertEquals(
            "http://abcdefgh234567.onion:2137/manifest.json",
            normalizeCommunityEndpoint("abcdefgh234567.onion"),
        )
        // jawny port na onionie zachowany (sposób na zmianę domyślnego; 80 pomijam, bo to domyślny port http
        // i OkHttp go wycina z adresu — używam niedomyślnego, żeby test był jednoznaczny)
        assertEquals(
            "http://abcdefgh234567.onion:8080/manifest.json",
            normalizeCommunityEndpoint("abcdefgh234567.onion:8080"),
        )
        assertNull(normalizeCommunityEndpoint("   "))
    }

    @Test
    fun `private host detection covers rfc1918 ranges and their edges`() {
        assertTrue(isPrivateHost("10.255.255.255"))
        assertTrue(isPrivateHost("192.168.0.1"))
        assertTrue(isPrivateHost("172.16.0.1"))
        assertTrue(isPrivateHost("172.31.255.255"))
        assertTrue(isPrivateHost("169.254.1.1"))
        // poza zakresami prywatnymi
        assertFalse(isPrivateHost("172.15.0.1"))
        assertFalse(isPrivateHost("172.32.0.1"))
        assertFalse(isPrivateHost("8.8.8.8"))
        assertFalse(isPrivateHost("example.com"))
    }

    @Test
    fun `dns names starting with fc or fd are not treated as LAN (audyt F-03)`() {
        // regresja: wcześniej host.startsWith("fc"/"fd") uznawał nazwy DNS za prywatne → cleartext do internetu
        assertFalse(isPrivateHost("fdroid.org"))
        assertFalse(isPrivateHost("fcbarcelona.com"))
        assertFalse(isPrivateHost("fd-cdn.atakujacy.example"))
        assertNull("http://fdroid.org/m".toCommunityUrlOrNull())
        assertNull("http://fd-x.atakujacy.example/m.json".toCommunityUrlOrNull())
        // literały IPv6 ULA/link-local nadal prywatne
        assertTrue(isPrivateHost("fd00::1"))
        assertTrue(isPrivateHost("[fc00::1]"))
        assertTrue(isPrivateHost("fe80::1"))
        assertTrue(isPrivateHost("::1"))
    }

    @Test
    fun `J-03 redirects are permanent failures, 408 and 429 stay transient`() {
        listOf(301, 302, 307, 308).forEach { assertTrue(communityHttpFailure(it).permanent) }
        assertTrue(communityHttpFailure(301).reason.contains("przekierowania"))
        assertTrue(communityHttpFailure(404).permanent)
        listOf(408, 429, 500, 503).forEach { assertFalse(communityHttpFailure(it).permanent) }
    }
}
