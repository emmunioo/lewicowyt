package pl.lewicowyt.notifier.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lewicowyt.notifier.data.NotificationCursor
import pl.lewicowyt.notifier.model.VideoEntry

class NotificationCursorTest {
    @Test
    fun acceptsOnlyItemsAfterTheStoredBaseline() {
        val cursor = NotificationCursor(videoId = "current", publishedAtMillis = 2_000L)

        assertFalse(isAfterNotificationCursor(entry("current", 2_000L), cursor))
        assertFalse(isAfterNotificationCursor(entry("older", 1_999L), cursor))
        assertFalse(isAfterNotificationCursor(entry("before", 2_000L), cursor))
        assertTrue(isAfterNotificationCursor(entry("newer", 2_001L), cursor))
        assertTrue(isAfterNotificationCursor(entry("same-time", 2_000L), cursor))
    }

    @Test
    fun selectsStableNewestEntryForTheNextBaseline() {
        val newest = newestTrustedNotificationEntry(
            listOf(
                entry("old", 1_000L),
                entry("a", 2_000L),
                entry("b", 2_000L),
            ),
        )

        assertEquals("b", newest?.id)
        assertEquals(2_000L, newest?.publishedAtMillis)
    }

    @Test
    fun shortFeedDoesNotClaimToCoverCursorWhenItContainsOnlyNewerItems() {
        val cursor = NotificationCursor(videoId = "current", publishedAtMillis = 2_000L)

        assertFalse(
            notificationFeedCoversCursor(
                entries = listOf(entry("newer-a", 3_000L), entry("newer-b", 2_001L)),
                cursor = cursor,
            ),
        )
        assertTrue(
            notificationFeedCoversCursor(
                entries = listOf(entry("newer", 3_000L), entry("current", 2_000L)),
                cursor = cursor,
            ),
        )
    }

    @Test
    fun firstSynchronizationDoesNotRequireHistoricalCoverage() {
        assertTrue(notificationFeedCoversCursor(entries = emptyList(), cursor = null))
    }

    @Test
    fun notificationGapIsBoundedByHistoryRetention() {
        val oldCursor = NotificationCursor(videoId = "old", publishedAtMillis = 1_000L)
        val bounded = notificationCoverageCursor(
            cursor = oldCursor,
            retentionCutoffMillis = 5_000L,
        )

        assertEquals(5_000L, bounded?.publishedAtMillis)
        assertEquals("", bounded?.videoId)
        assertEquals(
            oldCursor,
            notificationCoverageCursor(oldCursor, retentionCutoffMillis = 500L),
        )
    }

    @Test
    fun chronologicalNotificationFeedMayStopAtCursorButManualPlaylistMustReachEnd() {
        assertTrue(
            isNotificationPagingComplete(
                publishedTimes = listOf(2_000L, 999L),
                cursorPublishedAtMillis = 1_000L,
                hasNextPage = true,
                chronological = true,
            ),
        )
        assertFalse(
            isNotificationPagingComplete(
                publishedTimes = listOf(2_000L, 999L),
                cursorPublishedAtMillis = 1_000L,
                hasNextPage = true,
                chronological = false,
            ),
        )
        assertTrue(
            isNotificationPagingComplete(
                publishedTimes = listOf(999L),
                cursorPublishedAtMillis = 1_000L,
                hasNextPage = false,
                chronological = false,
            ),
        )
    }

    private fun entry(
        id: String,
        publishedAtMillis: Long,
    ) = VideoEntry(
        id = id,
        title = id,
        url = "https://www.youtube.com/watch?v=$id",
        publishedAtMillis = publishedAtMillis,
        author = "Kanał",
    )
}
