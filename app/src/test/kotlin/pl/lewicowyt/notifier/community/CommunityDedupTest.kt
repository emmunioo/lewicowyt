package pl.lewicowyt.notifier.community

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** kanoniczny klucz do wykrywania tej samej treści z różnych serwerów. */
class CommunityDedupTest {

    @Test
    fun `same youtube post regardless of host or query`() {
        val a = CommunityDedup.canonicalKey(
            "https://www.youtube.com/post/Ugkx123?feature=share", "youtube", "ABC", "cześć",
        )
        val b = CommunityDedup.canonicalKey(
            "https://m.youtube.com/post/Ugkx123", "youtube", "Inny opis", "co innego",
        )
        assertEquals(a, b)
        assertEquals("yt-post:Ugkx123", a)
    }

    @Test
    fun `youtu be short link equals watch link`() {
        val short = CommunityDedup.canonicalKey("https://youtu.be/xyz987", null, null, null)
        val watch = CommunityDedup.canonicalKey("https://www.youtube.com/watch?v=xyz987", null, null, null)
        assertEquals(short, watch)
    }

    @Test
    fun `twitter and x resolve to same status`() {
        val tw = CommunityDedup.canonicalKey("https://twitter.com/user/status/555", "twitter", "user", "t")
        val x = CommunityDedup.canonicalKey("https://x.com/user/status/555", "twitter", "user", "t")
        assertEquals(tw, x)
        assertEquals("tw:555", tw)
    }

    @Test
    fun `no url falls back to platform author and text fingerprint`() {
        val a = CommunityDedup.canonicalKey(null, "twitter", "Jan", "Taki   sam\ntekst")
        val b = CommunityDedup.canonicalKey(null, "Twitter", "jan", "taki sam tekst")
        assertEquals(a, b)
    }

    @Test
    fun `different content produces different keys`() {
        val a = CommunityDedup.canonicalKey("https://www.youtube.com/post/AAA", null, null, null)
        val b = CommunityDedup.canonicalKey("https://www.youtube.com/post/BBB", null, null, null)
        assertNotEquals(a, b)
    }

    @Test
    fun `M-05 generic urls differ by query`() {
        val one = CommunityDedup.canonicalKey("https://blog.example/?p=1", null, null, null)
        val two = CommunityDedup.canonicalKey("https://blog.example/?p=2", null, null, null)
        assertNotEquals("różne query = różne pozycje (dawniej gubiona jedna z nich)", one, two)
    }

    @Test
    fun `M-05 tracking params are ignored and order does not matter`() {
        val clean = CommunityDedup.canonicalKey("https://blog.example/wpis?id=7", null, null, null)
        val tracked = CommunityDedup.canonicalKey("https://blog.example/wpis?id=7&utm_source=fb&fbclid=xyz", null, null, null)
        val reordered = CommunityDedup.canonicalKey("https://blog.example/wpis?a=1&b=2", null, null, null)
        val reordered2 = CommunityDedup.canonicalKey("https://blog.example/wpis?b=2&a=1", null, null, null)
        assertEquals("parametry śledzące nie zmieniają tożsamości treści", clean, tracked)
        assertEquals("kolejność parametrów nie zmienia klucza", reordered, reordered2)
    }

    @Test
    fun `M-05 path-only url unchanged when no query`() {
        val key = CommunityDedup.canonicalKey("https://blog.example/wpis-123", null, null, null)
        assertEquals("u:blog.example/wpis-123", key)
    }
}
