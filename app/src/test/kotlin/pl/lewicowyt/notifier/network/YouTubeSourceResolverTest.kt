package pl.lewicowyt.notifier.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeSourceResolverTest {
    @Test
    fun `recommended channel id cannot replace canonical channel id`() {
        val html = """
            {"channelId":"UCbbbbbbbbbbbbbbbbbbbbbb"}
            <a href="https://www.youtube.com/channel/UCcccccccccccccccccccccc">Polecany</a>
            {"externalId":"UCaaaaaaaaaaaaaaaaaaaaaa"}
        """.trimIndent()

        assertEquals(
            "UCaaaaaaaaaaaaaaaaaaaaaa",
            extractCanonicalYouTubeChannelId(html),
        )
    }

    @Test
    fun `generic recommendation data is rejected without canonical metadata`() {
        val html = """
            {"channelId":"UCbbbbbbbbbbbbbbbbbbbbbb"}
            <a href="https://www.youtube.com/channel/UCcccccccccccccccccccccc">Polecany</a>
        """.trimIndent()

        assertNull(extractCanonicalYouTubeChannelId(html))
    }

    @Test
    fun `channelId meta tag remains a safe fallback`() {
        val html =
            """<meta itemprop="channelId" content="UCdddddddddddddddddddddd">"""

        assertEquals(
            "UCdddddddddddddddddddddd",
            extractCanonicalYouTubeChannelId(html),
        )
    }
}
