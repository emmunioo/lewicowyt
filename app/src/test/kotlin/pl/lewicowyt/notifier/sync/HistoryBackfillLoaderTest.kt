package pl.lewicowyt.notifier.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lewicowyt.notifier.model.VideoEntry
import pl.lewicowyt.notifier.model.VideoKind
import pl.lewicowyt.notifier.model.VideoOrigin

class HistoryBackfillLoaderTest {
    @Test
    fun continuesOnlyWhilePageIsInsideSelectedTimeRange() {
        val cutoff = 1_000L

        assertTrue(
            shouldContinueHistoryPaging(
                publishedTimes = listOf(1_500L, 1_200L, 1_000L),
                cutoff = cutoff,
                hasNextPage = true,
                loadedPageCount = 1,
            ),
        )
        assertFalse(
            shouldContinueHistoryPaging(
                publishedTimes = listOf(1_500L, 999L),
                cutoff = cutoff,
                hasNextPage = true,
                loadedPageCount = 1,
            ),
        )
    }

    @Test
    fun emptyPageWithCursorContinuesButLastOrSafetyLimitedPageStops() {
        val cutoff = 1_000L

        assertTrue(
            shouldContinueHistoryPaging(
                publishedTimes = emptyList(),
                cutoff = cutoff,
                hasNextPage = true,
                loadedPageCount = 1,
            ),
        )
        assertFalse(
            shouldContinueHistoryPaging(
                publishedTimes = listOf(1_500L),
                cutoff = cutoff,
                hasNextPage = false,
                loadedPageCount = 1,
            ),
        )
        assertFalse(
            shouldContinueHistoryPaging(
                publishedTimes = listOf(1_500L),
                cutoff = cutoff,
                hasNextPage = true,
                loadedPageCount = 30,
            ),
        )
    }

    @Test
    fun completenessRequiresEndOfFeedOrCrossingTheCutoff() {
        val cutoff = 1_000L

        assertTrue(
            isHistoryRangeComplete(
                publishedTimes = emptyList(),
                cutoff = cutoff,
                hasNextPage = false,
            ),
        )
        assertTrue(
            isHistoryRangeComplete(
                publishedTimes = listOf(1_500L, 999L),
                cutoff = cutoff,
                hasNextPage = true,
            ),
        )
        assertFalse(
            isHistoryRangeComplete(
                publishedTimes = listOf(1_500L, 1_000L),
                cutoff = cutoff,
                hasNextPage = true,
            ),
        )
    }

    @Test
    fun customPlaylistMustReachItsEndBecauseItsOrderMayBeManual() {
        assertFalse(
            isHistoryTargetComplete(
                publishedTimes = listOf(500L),
                cutoff = 1_000L,
                hasNextPage = true,
                chronological = false,
            ),
        )
        assertTrue(
            isHistoryTargetComplete(
                publishedTimes = listOf(500L, 2_000L),
                cutoff = 1_000L,
                hasNextPage = false,
                chronological = false,
            ),
        )
    }

    @Test
    fun rssHistoryStartsWithRecentUniqueYouTubeEntries() {
        val items = rssHistoryItems(
            entries = listOf(
                rssEntry("AAAAAAAAAAA", publishedAt = 1_500L),
                rssEntry("BBBBBBBBBBB", publishedAt = 999L),
                rssEntry("AAAAAAAAAAA", publishedAt = 1_600L),
            ),
            cutoff = 1_000L,
        )

        assertEquals(1, items.size)
        assertEquals("AAAAAAAAAAA", items.single().entry.id)
        assertEquals(VideoOrigin.YOUTUBE, items.single().entry.origin)
        assertEquals(VideoKind.UNKNOWN, items.single().kind)
    }

    private fun rssEntry(id: String, publishedAt: Long) = VideoEntry(
        id = id,
        title = "Tytuł",
        url = "https://www.youtube.com/watch?v=$id",
        publishedAtMillis = publishedAt,
        author = "Kanał",
    )
}
