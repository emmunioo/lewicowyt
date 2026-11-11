package pl.lewicowyt.notifier.community

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * hierarchiczne wyciszanie: wyciszenie platformy wycisza też jej twórców; wyciszenie
 * pojedynczego twórcy nie rusza reszty platformy. dwie osie (powiadomienia/wyświetlanie).
 */
class CommunityContentFilterTest {

    private fun server(notify: Set<String> = emptySet(), display: Set<String> = emptySet()) =
        CommunityServerRecord(
            serverId = "s",
            endpoint = "https://s.example/m",
            addedAtMillis = 0L,
            enabled = true,
            displayName = null,
            transport = CommunityTransportKind.HTTPS_POLL,
            lastManifestFetchMillis = 0L,
            notifyEnabled = true,
            notifyMuted = notify,
            displayMuted = display,
        )

    @Test
    fun `muting a whole platform hides all its creators`() {
        val f = CommunityContentFilter
        assertTrue(f.isMuted(setOf("twitter"), "twitter", null))
        assertTrue(f.isMuted(setOf("twitter"), "Twitter", "ktokolwiek")) // twórca też, wielkość liter bez znaczenia
    }

    @Test
    fun `muting one creator leaves the rest of the platform visible`() {
        val muted = setOf("youtube/abc")
        val f = CommunityContentFilter
        assertTrue(f.isMuted(muted, "youtube", "ABC"))
        assertFalse(f.isMuted(muted, "youtube", "xyz"))
        assertFalse(f.isMuted(muted, "youtube", null))
    }

    @Test
    fun `ads never notify but can still be displayed`() {
        val f = CommunityContentFilter
        val s = server() // powiadomienia włączone, nic nie wyciszone
        // reklamy: zawsze bez powiadomienia, niezależnie od ustawień
        assertFalse(f.shouldNotify(s, "ads", null))
        assertFalse(f.shouldNotify(s, "Ads", null))
        // ale mogą się wyświetlać w zakładce (gdy nie wyciszone dla wyświetlania)
        assertTrue(f.shouldDisplay(s, "ads", null))
        // inne platformy powiadamiają normalnie
        assertTrue(f.shouldNotify(s, "youtube", "abc"))
    }

    @Test
    fun `display and notify are independent axes`() {
        val s = server(notify = setOf("youtube/abc"), display = setOf("twitter"))
        // ABC z YouTube: brak powiadomień, ale nadal się wyświetla
        assertFalse(CommunityContentFilter.shouldNotify(s, "youtube", "ABC"))
        assertTrue(CommunityContentFilter.shouldDisplay(s, "youtube", "ABC"))
        // twitter: nie wyświetla się, ale (nie wyciszony w notify) powiadomienia dozwolone
        assertFalse(CommunityContentFilter.shouldDisplay(s, "twitter", "ktoś"))
        assertTrue(CommunityContentFilter.shouldNotify(s, "twitter", "ktoś"))
    }

    @Test
    fun `type level mute hides only that type of the creator`() {
        val muted = setOf("youtube/abc/stream")
        val f = CommunityContentFilter
        assertTrue(f.isMuted(muted, "youtube", "ABC", "Stream")) // wielkość liter bez znaczenia
        assertFalse(f.isMuted(muted, "youtube", "ABC", "post"))
        assertFalse(f.isMuted(muted, "youtube", "ABC", null))
    }

    @Test
    fun `update policy - only significant notifies, and only when section allows`() {
        assertFalse("C-15: brak oznaczenia nie powiadamia (tylko SIGNIFICANT)", CommunityUpdatePolicy.shouldNotify(CommunityUpdateKind.NONE, true))
        assertTrue(CommunityUpdatePolicy.shouldNotify(CommunityUpdateKind.SIGNIFICANT, true))
        assertFalse(CommunityUpdatePolicy.shouldNotify(CommunityUpdateKind.MINOR, true))
        assertFalse(CommunityUpdatePolicy.shouldNotify(CommunityUpdateKind.SIGNIFICANT, false))
        assertEquals(CommunityUpdateKind.SIGNIFICANT, CommunityUpdatePolicy.parse("significant"))
        assertEquals(CommunityUpdateKind.MINOR, CommunityUpdatePolicy.parse("drobna"))
        assertEquals(CommunityUpdateKind.NONE, CommunityUpdatePolicy.parse(null))
    }

    @Test
    fun `toggle adds then removes a key`() {
        val once = CommunityContentFilter.toggle(emptySet(), "twitter")
        assertEquals(setOf("twitter"), once)
        assertTrue(CommunityContentFilter.toggle(once, "twitter").isEmpty())
    }

    @Test
    fun `disabled server shows and notifies nothing`() {
        val s = server().copy(enabled = false)
        assertFalse(CommunityContentFilter.shouldDisplay(s, "youtube", null))
        assertFalse(CommunityContentFilter.shouldNotify(s, "youtube", null))
    }

    @Test
    fun `C-03 article and meme mutes from the tree also block notifications`() {
        val f = CommunityContentFilter
        val media = server(notify = setOf("media/oko.press"))
        val article = f.articleTypeLabel(CommunityArticleKind.ARTICLE)
        assertFalse(f.shouldNotify(media, f.PLATFORM_MEDIA, "OKO.press", article))
        assertTrue(f.shouldNotify(media, f.PLATFORM_MEDIA, "Inne medium", article))
        val newsletters = server(notify = setOf("media/oko.press/newsletter"))
        assertFalse(f.shouldNotify(newsletters, f.PLATFORM_MEDIA, "OKO.press", f.articleTypeLabel(CommunityArticleKind.NEWSLETTER)))
        assertTrue(f.shouldNotify(newsletters, f.PLATFORM_MEDIA, "OKO.press", article))
        val memes = server(notify = setOf("meme/strona/mem"), display = setOf("meme/strona/mem"))
        assertFalse(f.shouldNotify(memes, f.PLATFORM_MEME, "Strona", f.TYPE_MEME))
        assertFalse(f.shouldDisplay(memes, f.PLATFORM_MEME, "Strona", f.TYPE_MEME))
    }
}
