package pl.lewicowyt.notifier.messaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Kontrakt ładunku FCM jest niezaufany i wersjonowany: klient obsługuje wyłącznie
 * zdarzenia YouTube tej wersji, a wszystko inne (przyszłe platformy, nowe pola,
 * zniekształcony/niepełny ładunek) ignoruje bez błędu.
 */
class FeedPayloadTest {

    private val channel = "UCgL0f77U3iEPSSU-Mv5yV5g"
    private val video = "dQw4w9WgXcQ"

    @Test
    fun `legacy payload without platform is treated as youtube`() {
        // Zgodność wsteczna z serwerem 1.9, który wysyła samo { c, v }.
        val event = FeedPayload.parse(mapOf("c" to channel, "v" to video))
        assertEquals(FeedPayload.YouTubeVideo(channel, video), event)
    }

    @Test
    fun `explicit youtube platform is accepted case-insensitively`() {
        val event = FeedPayload.parse(mapOf("p" to "YouTube", "c" to channel, "v" to video))
        assertEquals(FeedPayload.YouTubeVideo(channel, video), event)
    }

    @Test
    fun `twitch platform is ignored`() {
        assertNull(FeedPayload.parse(mapOf("p" to "twitch", "c" to channel, "v" to video)))
    }

    @Test
    fun `tiktok platform is ignored`() {
        assertNull(FeedPayload.parse(mapOf("p" to "tiktok", "c" to channel, "v" to video)))
    }

    @Test
    fun `unknown future platform is ignored`() {
        assertNull(FeedPayload.parse(mapOf("p" to "kick", "c" to "streamer_xyz", "v" to "live-123")))
    }

    @Test
    fun `non youtube channel id is rejected even without platform field`() {
        // Serwer mógłby ponownie użyć klucza c z wartością spoza YouTube.
        assertNull(FeedPayload.parse(mapOf("c" to "twitch:streamer_xyz", "v" to video)))
    }

    @Test
    fun `malformed video id is rejected`() {
        assertNull(FeedPayload.parse(mapOf("c" to channel, "v" to "short")))
    }

    @Test
    fun `missing fields are ignored`() {
        assertNull(FeedPayload.parse(mapOf("c" to channel)))
        assertNull(FeedPayload.parse(mapOf("v" to video)))
        assertNull(FeedPayload.parse(emptyMap()))
    }

    @Test
    fun `blank fields are ignored`() {
        assertNull(FeedPayload.parse(mapOf("c" to "   ", "v" to video)))
        assertNull(FeedPayload.parse(mapOf("c" to channel, "v" to "")))
    }

    @Test
    fun `extra unknown keys do not break a valid youtube payload`() {
        val event = FeedPayload.parse(
            mapOf("c" to channel, "v" to video, "title" to "cokolwiek", "streamer" to "x"),
        )
        assertEquals(FeedPayload.YouTubeVideo(channel, video), event)
    }
}
