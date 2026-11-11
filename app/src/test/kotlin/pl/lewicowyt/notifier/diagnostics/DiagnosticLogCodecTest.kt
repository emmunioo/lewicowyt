package pl.lewicowyt.notifier.diagnostics

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLogCodecTest {
    @Test
    fun `binary diagnostic frame round trips`() {
        val original = DiagnosticEvent(
            timestampSeconds = 1_785_600_000L,
            level = DiagnosticLevel.WARNING,
            category = DiagnosticCategory.SYNC,
            message = "Synchronizacja nie powiodła się dla kilku źródeł",
        )

        val decoded = DataInputStream(
            ByteArrayInputStream(DiagnosticLogCodec.encode(original)),
        ).use(DiagnosticLogCodec::decode)

        assertEquals(original, decoded)
    }

    @Test
    fun `community server hosts are masked, well-known hosts stay`() {
        val a = DiagnosticLogCodec.sanitize("GET https://moj-serwer.example.net/manifest.json failed")
        val b = DiagnosticLogCodec.sanitize("blad https://MOJ-SERWER.example.net/x")
        val host = Regex("host#[0-9a-f]{8}").find(a)?.value
        assertTrue(host != null && "moj-serwer" !in a)
        assertTrue("ten sam host = ten sam skrót (porównywalność w dzienniku)", host!! in b)
        val gh = DiagnosticLogCodec.sanitize("GET https://objects.githubusercontent.com/file.apk https://www.youtube.com/watch")
        assertTrue("https://objects.githubusercontent.com/file.apk" in gh)
        assertTrue("https://www.youtube.com/watch" in gh)
    }

    @Test
    fun `E-01 secret LEP topic in path is replaced by route template`() {
        val topic = "3f9c0a1b2c3d4e5f60718293a4b5c6e1"
        val sub = DiagnosticLogCodec.sanitize("GET https://moj-serwer.example.net/lep/sub/$topic → 200; rodzaj=PUSH")
        val pub = DiagnosticLogCodec.sanitize("POST https://moj-serwer.example.net/lep/pub/$topic?x=1 → 200")
        assertFalse(topic in sub)
        assertFalse(topic in pub)
        assertTrue("/lep/sub/[TEMAT]" in sub)
        assertTrue("/lep/pub/[TEMAT]" in pub)
    }

    @Test
    fun `D-12 bare hosts in exception messages are masked`() {
        val a = DiagnosticLogCodec.sanitize("Unable to resolve host \"moj-serwer.example.net\": No address")
        val b = DiagnosticLogCodec.sanitize("Failed to connect to moj-serwer.example.net/203.0.113.5:2137")
        assertFalse("moj-serwer" in a)
        assertFalse("moj-serwer" in b)
        assertTrue(Regex("host#[0-9a-f]{8}").containsMatchIn(b))
    }

    @Test
    fun `secrets and url query are removed before storage`() {
        val sanitized = DiagnosticLogCodec.sanitize(
            "GET https://example.org/path?key=AIzaABCDEFGHIJKLMNOPQRSTUVWXY12345 " +
                "Authorization: Bearer bardzo_tajny_token " +
                "Cookie: SID=sekret_ciasteczka access_token=sekret_token " +
                "https://objects.githubusercontent.com/file.apk?X-Amz-Signature=podpis " +
                "/data/user/0/pl.lewicowyt.notifier/files/sekret",
        )

        // C-20: nieznany host (np. serwer społeczności) zamaskowany skrótem, ścieżka zostaje
        assertTrue(Regex("https://host#[0-9a-f]{8}/path").containsMatchIn(sanitized))
        assertFalse("example.org" in sanitized)
        assertFalse("AIza" in sanitized)
        assertFalse("bardzo_tajny_token" in sanitized)
        assertFalse("?key=" in sanitized)
        assertFalse("sekret_ciasteczka" in sanitized)
        assertFalse("sekret_token" in sanitized)
        assertFalse("X-Amz-Signature" in sanitized)
        assertFalse("podpis" in sanitized)
        assertFalse("/data/user" in sanitized)
    }

    @Test
    fun `diagnostic youtube link keeps video id without query parameters`() {
        val link = diagnosticYouTubeVideoUrl("AbCdEf_12-3")

        assertEquals("https://youtu.be/AbCdEf_12-3", link)
        assertEquals(link, DiagnosticLogCodec.sanitize(requireNotNull(link)))
        assertEquals(null, diagnosticYouTubeVideoUrl("zly"))
    }

    @Test
    fun `ipv6 addresses are redacted but clock times are kept`() {
        val sanitized = DiagnosticLogCodec.sanitize(
            "connect to fd12:3456:789a::1 port 2137 failed; [2001:db8:85a3:0:0:8a2e:370:7334]; " +
                "o 12:30:45 sync",
        )
        assertFalse("fd12:3456" in sanitized)
        assertFalse("2001:db8" in sanitized)
        assertTrue("12:30:45" in sanitized)
    }

    @Test
    fun `one expanded diagnostic event remains strictly bounded`() {
        val encoded = DiagnosticLogCodec.encode(
            DiagnosticEvent(
                timestampSeconds = 1L,
                level = DiagnosticLevel.ERROR,
                category = DiagnosticCategory.NETWORK,
                message = buildString {
                    repeat(10_000) { append(('A'.code + it % 26).toChar()) }
                },
            ),
        )
        val decoded = DataInputStream(ByteArrayInputStream(encoded))
            .use(DiagnosticLogCodec::decode)

        assertTrue(encoded.size <= 4_096)
        assertTrue(requireNotNull(decoded).message.toByteArray().size <= 1_024)
    }

    @Test
    fun `J-09 host digest depends on per-install salt`() {
        val a = DiagnosticLogCodec.hostDigest("moj-serwer.example.net", ByteArray(16) { 1 })
        val b = DiagnosticLogCodec.hostDigest("moj-serwer.example.net", ByteArray(16) { 2 })
        assertEquals(8, a.length)
        assertTrue("inna sól = inny skrót tego samego hosta", a != b)
        assertEquals(a, DiagnosticLogCodec.hostDigest("moj-serwer.example.net", ByteArray(16) { 1 }))
        // skrót nie jest już zwykłym SHA-256 nazwy (do sprawdzenia z zewnątrz)
        val plain = java.security.MessageDigest.getInstance("SHA-256").digest("moj-serwer.example.net".toByteArray())
            .joinToString("") { "%02x".format(it) }.take(8)
        assertTrue(a != plain && b != plain)
    }
}
