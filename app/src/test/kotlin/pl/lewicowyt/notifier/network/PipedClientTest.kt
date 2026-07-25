package pl.lewicowyt.notifier.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lewicowyt.notifier.model.VideoEntry
import pl.lewicowyt.notifier.model.VideoKind
import pl.lewicowyt.notifier.model.VideoOrigin

class PipedClientTest {
    @Test
    fun rejectsExcessivelyNestedOrUnbalancedJsonBeforeParsing() {
        assertTrue(hasSafeJsonNesting("""{"items":[{"title":"{inside string}"}]}"""))
        assertFalse(hasSafeJsonNesting("[".repeat(101) + "]".repeat(101)))
        assertFalse(hasSafeJsonNesting("""{"items":]"""))
    }

    @Test
    fun parsesEpochTimestampKindsAndContinuation() {
        val page = parsePipedPage(
            json = """
                {
                  "relatedStreams": [
                    {
                      "url": "/watch?v=abcdefghijk",
                      "title": "Zwykły film",
                      "uploaded": 1720000000000,
                      "duration": 600,
                      "isShort": false,
                      "uploaderName": "Kanał"
                    },
                    {
                      "url": "/shorts/ABCDEFGHIJK",
                      "title": "Short",
                      "uploaded": 1720000000,
                      "duration": 45,
                      "isShort": true
                    },
                    {
                      "url": "/watch?v=12345678901",
                      "title": "Live",
                      "uploaded": 1720000000000,
                      "duration": 0,
                      "isShort": false
                    }
                  ],
                  "nextpage": "token"
                }
            """.trimIndent(),
            nowMillis = 1_800_000_000_000L,
            instanceBaseUrl = "https://piped.example",
        )

        assertEquals(3, page.items.size)
        assertEquals(VideoKind.VIDEO, page.items[0].kind)
        assertEquals(VideoKind.SHORT, page.items[1].kind)
        assertEquals(1_720_000_000_000L, page.items[1].entry.publishedAtMillis)
        assertEquals(VideoKind.LIVE, page.items[2].kind)
        assertEquals(VideoOrigin.PIPED, page.items[2].entry.origin)
        assertEquals("token", page.nextPageToken)
    }

    @Test
    fun ignoresUntrustedNonYoutubeUrlsAndParsesRelativeDate() {
        val now = 2_000_000_000_000L
        val page = parsePipedPage(
            json = """
                {
                  "relatedStreams": [
                    {
                      "url": "https://malicious.example/video",
                      "title": "Nieprawidłowy wpis",
                      "uploaded": 0
                    },
                    {
                      "url": "/watch?v=zyxwvutsrqp",
                      "title": "Wczorajszy film",
                      "uploaded": 0,
                      "uploadedDate": "1 day ago",
                      "duration": 100
                    }
                  ],
                  "nextpage": null
                }
            """.trimIndent(),
            nowMillis = now,
            instanceBaseUrl = "https://piped.example",
        )

        assertEquals(1, page.items.size)
        assertEquals("https://www.youtube.com/watch?v=zyxwvutsrqp", page.items.single().entry.url)
        assertEquals(now - 86_400_000L, page.items.single().entry.publishedAtMillis)
        assertNull(page.nextPageToken)
    }

    @Test
    fun parsesTopLevelUnauthenticatedFeed() {
        val items = parsePipedFeed(
            json = """
                [
                  {
                    "url": "/watch?v=KwJwYIqDMLY",
                    "title": "Nowy film",
                    "uploaded": 1783396819000,
                    "duration": 184,
                    "uploaderName": "Kanał",
                    "isShort": false
                  }
                ]
            """.trimIndent(),
            nowMillis = 1_800_000_000_000L,
        )

        assertEquals(1, items.size)
        assertEquals("KwJwYIqDMLY", items.single().entry.id)
        assertEquals(1_783_396_819_000L, items.single().entry.publishedAtMillis)
        assertEquals(VideoKind.VIDEO, items.single().kind)
    }

    @Test
    fun mergesFeedFallbackWithChannelPageAndKeepsContinuation() {
        val feedOnly = historyItem("feed_only01", 3_000L)
        val duplicateFromFeed = historyItem("duplicate01", 2_000L)
        val duplicateFromChannel = historyItem("duplicate01", 2_000L)
        val channelOnly = historyItem("channel_on1", 1_000L)

        val merged = mergePipedHistoryPages(
            channelPage = PipedPage(
                items = listOf(duplicateFromChannel, channelOnly),
                nextPageToken = "kolejna-strona",
                instanceBaseUrl = "https://channel.example",
            ),
            feedPage = PipedPage(
                items = listOf(feedOnly, duplicateFromFeed),
                nextPageToken = null,
                instanceBaseUrl = "https://feed.example",
            ),
        )

        assertEquals(
            listOf("feed_only01", "duplicate01", "channel_on1"),
            merged.items.map { it.entry.id },
        )
        assertEquals("kolejna-strona", merged.nextPageToken)
        assertEquals("https://channel.example", merged.instanceBaseUrl)
    }

    private fun historyItem(id: String, publishedAtMillis: Long) =
        YouTubeHistoryItem(
            entry = VideoEntry(
                id = id,
                title = id,
                url = "https://www.youtube.com/watch?v=$id",
                publishedAtMillis = publishedAtMillis,
                author = "Kanał",
                origin = VideoOrigin.PIPED,
            ),
            kind = VideoKind.VIDEO,
        )
}
