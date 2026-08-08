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
    fun `secrets and url query are removed before storage`() {
        val sanitized = DiagnosticLogCodec.sanitize(
            "GET https://example.org/path?key=AIzaABCDEFGHIJKLMNOPQRSTUVWXY12345 " +
                "Authorization: Bearer bardzo_tajny_token " +
                "Cookie: SID=sekret_ciasteczka access_token=sekret_token " +
                "https://objects.githubusercontent.com/file.apk?X-Amz-Signature=podpis " +
                "/data/user/0/pl.lewicowyt.notifier/files/sekret",
        )

        assertTrue("https://example.org/path" in sanitized)
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
}
