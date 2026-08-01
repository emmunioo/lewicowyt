package pl.lewicowyt.notifier.network

import org.junit.Assert.assertEquals
import org.junit.Test
import pl.lewicowyt.notifier.model.VideoEntry
import pl.lewicowyt.notifier.model.VideoKind
import pl.lewicowyt.notifier.model.VideoKindEvidence

class YouTubeFeedClientTest {
    @Test
    fun `rss keeps exact publication timestamp for reported PoDaMi video`() {
        val publishedAt = parseYouTubePublishedInstant(
            value = "2026-07-06T04:00:28+00:00",
            nowMillis = 1_785_354_360_000L,
        )

        assertEquals(1_783_310_428_000L, publishedAt)
    }

    @Test
    fun `rss preserves canonical shorts link from YouTube`() {
        val entry = VideoEntry(
            id = "Oj0-9Ks6d7k",
            title = "OZE zapewni Polsce bezpieczeństwo.",
            url = canonicalYouTubeEntryUrl(
                videoId = "Oj0-9Ks6d7k",
                alternateUrl = "https://www.youtube.com/shorts/Oj0-9Ks6d7k",
            ),
            publishedAtMillis = 1L,
            author = "Krytyka Polityczna",
        )
        val decision = rssVideoKindDecision(entry)

        assertEquals("https://www.youtube.com/shorts/Oj0-9Ks6d7k", entry.url)
        assertEquals(VideoKind.SHORT, decision.kind)
        assertEquals(VideoKindEvidence.RSS_SHORT_URL, decision.evidence)
    }

    @Test
    fun `ordinary rss entry is immediately visible as reversible film fallback`() {
        val entry = VideoEntry(
            id = "AAAAAAAAAAA",
            title = "Film",
            url = "https://www.youtube.com/watch?v=AAAAAAAAAAA",
            publishedAtMillis = 1L,
            author = "Kanał",
        )

        val decision = rssVideoKindDecision(entry)

        assertEquals(VideoKind.VIDEO, decision.kind)
        assertEquals(VideoKindEvidence.DEFAULT_VIDEO_FALLBACK, decision.evidence)
    }

    @Test
    fun `foreign or mismatched alternate link is never trusted`() {
        assertEquals(
            "https://www.youtube.com/watch?v=Oj0-9Ks6d7k",
            canonicalYouTubeEntryUrl(
                videoId = "Oj0-9Ks6d7k",
                alternateUrl = "https://example.org/shorts/Oj0-9Ks6d7k",
            ),
        )
        assertEquals(
            "https://www.youtube.com/watch?v=Oj0-9Ks6d7k",
            canonicalYouTubeEntryUrl(
                videoId = "Oj0-9Ks6d7k",
                alternateUrl = "https://www.youtube.com/shorts/AAAAAAAAAAA",
            ),
        )
    }
}
