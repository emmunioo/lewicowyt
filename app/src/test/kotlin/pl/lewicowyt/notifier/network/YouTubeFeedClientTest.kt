package pl.lewicowyt.notifier.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import pl.lewicowyt.notifier.model.SourceType
import pl.lewicowyt.notifier.model.VideoEntry
import pl.lewicowyt.notifier.model.VideoKind
import pl.lewicowyt.notifier.model.VideoKindEvidence

class YouTubeFeedClientTest {
    private val client = YouTubeFeedClient(HttpTextClient())

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

    @Test
    fun `channel feed identity matches expected source`() {
        val parsed = client.parseFeed(channelFeed(CHANNEL_ID, CHANNEL_ID))

        requireExpectedFeedIdentity(channelSource(CHANNEL_ID), parsed.identity)
        assertEquals(CHANNEL_ID, parsed.identity.channelId)
        assertEquals(1, parsed.entries.size)
    }

    @Test
    fun `official compact feed channelId is normalized to full UC identity`() {
        val compactId = CHANNEL_ID.removePrefix("UC")
        val xml = channelFeed(compactId, CHANNEL_ID).replace(
            "https://www.youtube.com/feeds/videos.xml?channel_id=$compactId",
            "http://www.youtube.com/feeds/videos.xml?channel_id=$CHANNEL_ID",
        )

        val parsed = client.parseFeed(xml)

        requireExpectedFeedIdentity(channelSource(CHANNEL_ID), parsed.identity)
        assertEquals(CHANNEL_ID, parsed.identity.channelId)
        assertEquals(1, parsed.entries.size)
    }

    @Test
    fun `foreign compact feed channelId remains rejected after normalization`() {
        val parsed = client.parseFeed(
            channelFeed(FOREIGN_CHANNEL_ID.removePrefix("UC"), CHANNEL_ID),
        )

        assertThrows(InvalidYouTubeFeedIdentityException::class.java) {
            requireExpectedFeedIdentity(channelSource(CHANNEL_ID), parsed.identity)
        }
    }

    @Test
    fun `foreign feed level channelId is rejected even with valid entries`() {
        val parsed = client.parseFeed(channelFeed(FOREIGN_CHANNEL_ID, CHANNEL_ID))

        assertThrows(InvalidYouTubeFeedIdentityException::class.java) {
            requireExpectedFeedIdentity(channelSource(CHANNEL_ID), parsed.identity)
        }
    }

    @Test
    fun `conflicting feed level identities are rejected during parse`() {
        val xml = channelFeed(CHANNEL_ID, CHANNEL_ID)
            .replace("yt:channel:$CHANNEL_ID", "yt:channel:$FOREIGN_CHANNEL_ID")

        assertThrows(InvalidYouTubeFeedIdentityException::class.java) {
            client.parseFeed(xml)
        }
    }

    @Test
    fun `malformed feed level channelId is rejected`() {
        assertThrows(InvalidYouTubeFeedIdentityException::class.java) {
            client.parseFeed(channelFeed("not-a-channel", CHANNEL_ID))
        }
    }

    @Test
    fun `missing feed identity is rejected by explicit policy`() {
        val parsed = client.parseFeed(entryOnlyFeed(CHANNEL_ID))
        assertNull(parsed.identity.channelId)

        assertThrows(InvalidYouTubeFeedIdentityException::class.java) {
            requireExpectedFeedIdentity(channelSource(CHANNEL_ID), parsed.identity)
        }
    }

    @Test
    fun `playlist identity can be confirmed from feed self link`() {
        val parsed = client.parseFeed(
            entryOnlyFeed(CHANNEL_ID).replace(
                "<feed>",
                "<feed><link rel=\"self\" href=\"https://www.youtube.com/feeds/videos.xml?playlist_id=$PLAYLIST_ID\"/>",
            ),
        )

        requireExpectedFeedIdentity(
            ResolvedSource("playlist", SourceType.PLAYLIST, PLAYLIST_ID, "https://example.invalid"),
            parsed.identity,
        )
        assertEquals(PLAYLIST_ID, parsed.identity.playlistId)
    }

    @Test
    fun `official http self link is accepted only for YouTube feed path`() {
        val parsed = client.parseFeed(
            entryOnlyFeed(CHANNEL_ID).replace(
                "<feed>",
                "<feed><link rel=\"self\" href=\"http://www.youtube.com/feeds/videos.xml?channel_id=$CHANNEL_ID\"/>",
            ),
        )

        requireExpectedFeedIdentity(channelSource(CHANNEL_ID), parsed.identity)
        assertEquals(CHANNEL_ID, parsed.identity.channelId)
    }

    @Test
    fun `identity mismatch has no save cursor or notification side effects`() {
        var savedEntries = 0
        var cursorMoves = 0
        var notifications = 0
        val result = runCatching {
            val parsed = client.parseFeed(channelFeed(FOREIGN_CHANNEL_ID, CHANNEL_ID))
            requireExpectedFeedIdentity(channelSource(CHANNEL_ID), parsed.identity)
            savedEntries += parsed.entries.size
            cursorMoves += 1
            notifications += parsed.entries.size
        }

        assertEquals(InvalidYouTubeFeedIdentityException::class.java, result.exceptionOrNull()!!::class.java)
        assertEquals(0, savedEntries)
        assertEquals(0, cursorMoves)
        assertEquals(0, notifications)
    }

    @Test
    fun `identity mismatch is fatal and cannot enter another source fallback`() {
        var fallbackStarted = false
        val mismatch = Result.failure<List<VideoEntry>>(
            InvalidYouTubeFeedIdentityException("mismatch"),
        )

        assertThrows(InvalidYouTubeFeedIdentityException::class.java) {
            requireNoFeedIdentityFailure(mismatch)
            fallbackStarted = true
        }
        assertEquals(false, fallbackStarted)
    }

    private fun channelSource(channelId: String) = ResolvedSource(
        sourceKey = "creator|CHANNEL",
        type = SourceType.CHANNEL,
        externalId = channelId,
        feedUrl = "https://www.youtube.com/feeds/videos.xml?channel_id=$channelId",
    )

    private fun channelFeed(feedChannelId: String, entryChannelId: String): String = """
        <feed xmlns:yt="http://www.youtube.com/xml/schemas/2015">
          <id>yt:channel:$feedChannelId</id>
          <yt:channelId>$feedChannelId</yt:channelId>
          <link rel="self" href="https://www.youtube.com/feeds/videos.xml?channel_id=$feedChannelId"/>
          ${entry(entryChannelId)}
        </feed>
    """.trimIndent()

    private fun entryOnlyFeed(entryChannelId: String): String =
        "<feed>${entry(entryChannelId)}</feed>"

    private fun entry(entryChannelId: String): String = """
        <entry xmlns:yt="http://www.youtube.com/xml/schemas/2015">
          <yt:videoId>VgOPdcq9oQc</yt:videoId>
          <yt:channelId>$entryChannelId</yt:channelId>
          <title>Film</title>
          <published>2026-07-06T04:00:28+00:00</published>
          <author><name>Kanał</name></author>
        </entry>
    """.trimIndent()

    private companion object {
        const val CHANNEL_ID = "UCM0XopUZWDFr44S7-Yh2z0g"
        const val FOREIGN_CHANNEL_ID = "UC1DbpEM6ve_ugCGwRVRmAMA"
        const val PLAYLIST_ID = "PL1234567890ABCDE"
    }
}
