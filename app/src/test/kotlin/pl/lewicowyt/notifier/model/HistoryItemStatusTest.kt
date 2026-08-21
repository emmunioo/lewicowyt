package pl.lewicowyt.notifier.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryItemStatusTest {
    @Test
    fun expiredUpcomingIsDisplayedAsStreamArchiveWithoutCalendar() {
        val item = item(
            publishedAtMillis = 1_000L,
            availability = DescriptionAvailability.SCHEDULED_STREAM,
        )

        assertEquals(VideoKind.STREAM_ARCHIVE, item.displayKindAt(1_001L))
        assertFalse(item.statusBadgesAt(1_001L).contains(MaterialStatusBadge.SCHEDULED))
    }

    @Test
    fun futureStreamWithDownloadedDescriptionShowsBothBadges() {
        val item = item(
            publishedAtMillis = 2_000L,
            availability = DescriptionAvailability.DOWNLOADED,
        )

        assertEquals(VideoKind.UPCOMING, item.displayKindAt(1_000L))
        assertEquals(
            listOf(MaterialStatusBadge.SCHEDULED, MaterialStatusBadge.DESCRIPTION),
            item.statusBadgesAt(1_000L),
        )
    }

    @Test
    fun futureMarkerShowsCalendarButDoesNotPretendToBeDescription() {
        val badges = item(
            publishedAtMillis = 2_000L,
            availability = DescriptionAvailability.SCHEDULED_STREAM,
        ).statusBadgesAt(1_000L)

        assertTrue(badges.contains(MaterialStatusBadge.SCHEDULED))
        assertFalse(badges.contains(MaterialStatusBadge.DESCRIPTION))
    }

    private fun item(
        publishedAtMillis: Long,
        availability: DescriptionAvailability,
    ) = HistoryItem(
        videoId = "abcdefghijk",
        creatorId = "creator",
        creatorName = "Twórca",
        title = "Transmisja",
        url = "https://www.youtube.com/watch?v=abcdefghijk",
        publishedAtMillis = publishedAtMillis,
        detectedAtMillis = 1L,
        kind = VideoKind.UPCOMING,
        notified = false,
        descriptionAvailability = availability,
    )
}
