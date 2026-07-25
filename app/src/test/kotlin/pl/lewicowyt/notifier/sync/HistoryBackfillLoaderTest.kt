package pl.lewicowyt.notifier.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun successfulPipedFallbackRemainsPartialWhenYoutubeFailed() {
        val errors = combineHistorySourceErrors(
            youtubeErrors = listOf("Kanał: YouTube HTTP 503"),
            pipedSucceeded = true,
            pipedError = null,
            creatorName = "Kanał",
        )

        assertTrue(errors.any { "niezweryfikowaną część historii" in it })
    }

    @Test
    fun completeYoutubeResultIgnoresOptionalPipedFailure() {
        val errors = combineHistorySourceErrors(
            youtubeErrors = emptyList(),
            pipedSucceeded = false,
            pipedError = "timeout",
            creatorName = "Kanał",
        )

        assertTrue(errors.isEmpty())
    }
}
