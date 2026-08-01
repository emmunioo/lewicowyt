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

    @Test
    fun `youtube avatar is requested as 176 square image`() {
        assertEquals(
            "https://yt3.ggpht.com/ytc/abc=s176-c-k-c0x00ffffff-no-rj",
            normalizeYouTubeAvatarUrl(
                "https://yt3.ggpht.com/ytc/abc=s900-c-k-c0x00ffffff-no-rj",
            ),
        )
    }

    @Test
    fun `avatar normalization preserves query and rejects thumbnail hosts`() {
        assertEquals(
            "https://yt3.googleusercontent.com/avatar=s176-c-k-c0x00ffffff-no-rj?x=1",
            normalizeYouTubeAvatarUrl(
                "https://yt3.googleusercontent.com/avatar=s88?x=1",
            ),
        )
        assertNull(
            normalizeYouTubeAvatarUrl("https://i.ytimg.com/vi/AAAAAAAAAAA/hqdefault.jpg"),
        )
    }

    @Test
    fun `avatar normalization does not encode an escaped path twice`() {
        assertEquals(
            "https://yt3.ggpht.com/a%2Fb=s176-c-k-c0x00ffffff-no-rj",
            normalizeYouTubeAvatarUrl(
                "https://yt3.ggpht.com/a%2Fb=s900-c-k-c0x00ffffff-no-rj",
            ),
        )
    }
}
