package pl.lewicowyt.notifier.community

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunityPrimaryHealthEvaluatorTest {
    private val now = 1_000_000_000_000L
    private val chA = "UC" + "a".repeat(22)
    private val chB = "UC" + "b".repeat(22)
    private fun eval(
        reachable: Boolean = true,
        feed: CommunityPrimaryFeed? = CommunityPrimaryFeed(),
        expected: Set<String> = emptySet(),
        rss: Map<String, List<RssVideo>> = emptyMap(),
    ) = CommunityPrimaryHealthEvaluator.evaluate(reachable, feed, expected, rss, now)

    private fun kinds(list: List<PrimaryHealthDifference>) = list.map { it.kind }.toSet()

    @Test fun `unreachable server reports one difference`() {
        assertEquals(setOf(PrimaryHealthKind.SERVER_UNREACHABLE), kinds(eval(reachable = false)))
    }

    @Test fun `no primaryFeed means nothing to compare`() {
        assertTrue(eval(feed = null).isEmpty())
    }

    @Test fun `matching feed and rss has no differences`() {
        val feed = CommunityPrimaryFeed(listOf(chA), listOf(CommunityPrimaryVideo(chA, "vid00000001", now - 60_000)))
        val rss = mapOf(chA to listOf(RssVideo("vid00000001", now - 60_000)))
        assertTrue(eval(feed = feed, expected = setOf(chA), rss = rss).isEmpty())
    }

    @Test fun `extra creator injected by server is flagged`() {
        val feed = CommunityPrimaryFeed(listOf(chA, chB))
        val diffs = eval(feed = feed, expected = setOf(chA))
        assertTrue(PrimaryHealthKind.EXTRA_CREATORS in kinds(diffs))
    }

    @Test fun `missing selected creator is flagged`() {
        val feed = CommunityPrimaryFeed(listOf(chA))
        assertTrue(PrimaryHealthKind.MISSING_CREATORS in kinds(eval(feed = feed, expected = setOf(chA, chB))))
    }

    @Test fun `duplicate creators and videos are flagged`() {
        val feed = CommunityPrimaryFeed(
            listOf(chA, chA),
            listOf(CommunityPrimaryVideo(chA, "vid00000001", now), CommunityPrimaryVideo(chA, "vid00000001", now)),
        )
        val k = kinds(eval(feed = feed, rss = mapOf(chA to listOf(RssVideo("vid00000001", now)))))
        assertTrue(PrimaryHealthKind.DUPLICATE_CREATORS in k)
        assertTrue(PrimaryHealthKind.DUPLICATE_VIDEOS in k)
    }

    @Test fun `R-13 server reporting no videos for a channel does not false-alarm missing`() {
        // serwer deklaruje OGRANICZONĄ listę; brak zgłoszeń dla kanału nie znaczy, że „gubi" całe RSS
        val old = now - CommunityPrimaryHealthEvaluator.MISSING_AFTER_MS - 60_000
        val feed = CommunityPrimaryFeed(listOf(chA), emptyList())
        val rss = mapOf(chA to listOf(RssVideo("vid00000009", old)))
        assertTrue(PrimaryHealthKind.MISSING_VIDEOS !in kinds(eval(feed = feed, rss = rss)))
    }

    @Test fun `R-13 genuine gap within server window is still missing`() {
        // serwer zgłasza vidA (horyzont), ale pomija vidB z TEGO SAMEGO okna (nowszy niż horyzont, >6 h) → brak
        val horizon = now - CommunityPrimaryHealthEvaluator.MISSING_AFTER_MS - 120_000
        val withinWindow = now - CommunityPrimaryHealthEvaluator.MISSING_AFTER_MS - 60_000
        val feed = CommunityPrimaryFeed(listOf(chA), listOf(CommunityPrimaryVideo(chA, "vidAAAAAAA1", horizon)))
        val rss = mapOf(
            chA to listOf(RssVideo("vidAAAAAAA1", horizon), RssVideo("vidBBBBBBB2", withinWindow)),
        )
        assertTrue(PrimaryHealthKind.MISSING_VIDEOS in kinds(eval(feed = feed, rss = rss)))
    }

    @Test fun `R-13 rss video older than server window is not missing`() {
        // film z RSS starszy niż najstarszy zgłoszony (poza oknem serwera) NIE jest brakiem
        val horizon = now - CommunityPrimaryHealthEvaluator.MISSING_AFTER_MS - 60_000
        val older = now - CommunityPrimaryHealthEvaluator.MISSING_AFTER_MS - 10L * 60 * 60 * 1000
        val feed = CommunityPrimaryFeed(listOf(chA), listOf(CommunityPrimaryVideo(chA, "vidAAAAAAA1", horizon)))
        val rss = mapOf(
            chA to listOf(RssVideo("vidAAAAAAA1", horizon), RssVideo("vidOLDEROLD", older)),
        )
        assertTrue(PrimaryHealthKind.MISSING_VIDEOS !in kinds(eval(feed = feed, rss = rss)))
    }

    @Test fun `server video absent from rss is extra`() {
        val feed = CommunityPrimaryFeed(listOf(chA), listOf(CommunityPrimaryVideo(chA, "fakeVIDEOxx", now)))
        val rss = mapOf(chA to listOf(RssVideo("realVIDEOxx", now)))
        assertTrue(PrimaryHealthKind.EXTRA_VIDEOS in kinds(eval(feed = feed, rss = rss)))
    }

    @Test fun `late server timestamp vs rss is high latency`() {
        val pub = now - 10L * 60 * 60 * 1000
        val feed = CommunityPrimaryFeed(listOf(chA), listOf(CommunityPrimaryVideo(chA, "vid00000001", now)))
        val rss = mapOf(chA to listOf(RssVideo("vid00000001", pub)))
        assertTrue(PrimaryHealthKind.HIGH_LATENCY in kinds(eval(feed = feed, rss = rss)))
    }

    @Test fun `A-16 server video older than full rss window is not extra`() {
        val hour = 60L * 60 * 1000
        // pełne RSS (15 filmów) z ostatnich 15 h; serwer zgłasza film sprzed 2 dni — poza oknem RSS
        val rssVids = (1..CommunityPrimaryHealthEvaluator.RSS_FEED_SIZE).map { i ->
            RssVideo("rss%08d".format(i), now - i * hour)
        }
        val feed = CommunityPrimaryFeed(listOf(chA), listOf(CommunityPrimaryVideo(chA, "oldVIDEOxxx", now - 48 * hour)))
        val diffs = eval(feed = feed, rss = mapOf(chA to rssVids))
        assertTrue(PrimaryHealthKind.EXTRA_VIDEOS !in kinds(diffs))
    }

    @Test fun `A-59 channel silenced by server with recent rss videos is missing`() {
        val hour = 60L * 60 * 1000
        val feed = CommunityPrimaryFeed(listOf(chA), emptyList())
        val rss = mapOf(chA to listOf(RssVideo("vidRECENT01", now - 10 * hour), RssVideo("vidRECENT02", now - 30 * hour)))
        assertTrue(PrimaryHealthKind.MISSING_VIDEOS in kinds(eval(feed = feed, rss = rss)))
    }

    @Test fun `E-03 videos-only primaryFeed matching rss has no differences`() {
        val feed = CommunityPrimaryFeed(emptyList(), listOf(CommunityPrimaryVideo(chA, "vid00000001", now - 60_000)))
        val rss = mapOf(chA to listOf(RssVideo("vid00000001", now - 60_000)))
        assertTrue(eval(feed = feed, expected = setOf(chA, chB), rss = rss).isEmpty())
    }

    @Test fun `E-03 videos-only primaryFeed still flags video absent from rss`() {
        val feed = CommunityPrimaryFeed(emptyList(), listOf(CommunityPrimaryVideo(chA, "fakeVIDEOxx", now)))
        val rss = mapOf(chA to listOf(RssVideo("realVIDEOxx", now)))
        assertTrue(PrimaryHealthKind.EXTRA_VIDEOS in kinds(eval(feed = feed, rss = rss)))
    }

    @Test fun `D-19j video for undeclared channel is extra when creators are declared`() {
        val feed = CommunityPrimaryFeed(listOf(chA), listOf(CommunityPrimaryVideo(chB, "vid00000002", now)))
        val diffs = eval(feed = feed, rss = mapOf(chA to emptyList()))
        assertTrue(diffs.any { it.kind == PrimaryHealthKind.EXTRA_VIDEOS && "zadeklarowanych" in it.detail })
    }
}
