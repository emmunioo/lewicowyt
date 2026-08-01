package pl.lewicowyt.notifier.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lewicowyt.notifier.data.NotificationCursor
import pl.lewicowyt.notifier.data.shouldReplacePublishedAt
import pl.lewicowyt.notifier.model.PublishedAtEvidence
import pl.lewicowyt.notifier.model.VideoEntry
import pl.lewicowyt.notifier.model.VideoKind
import pl.lewicowyt.notifier.model.VideoKindEvidence

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
    fun rssSnapshotNotifiesOnlyAboutPreviouslyUnseenVideoIds() {
        assertEquals(
            setOf("CCCCCCCCCCC"),
            newRssNotificationVideoIds(
                sourceInitialized = true,
                previousKnownVideoIds = listOf("AAAAAAAAAAA", "BBBBBBBBBBB"),
                currentVideoIds = listOf("CCCCCCCCCCC", "BBBBBBBBBBB"),
            ),
        )
    }

    @Test
    fun rssSnapshotDoesNotNotifyOnFirstBaselineReorderOrRemoval() {
        assertTrue(
            newRssNotificationVideoIds(
                sourceInitialized = false,
                previousKnownVideoIds = null,
                currentVideoIds = listOf("AAAAAAAAAAA"),
            ).isEmpty(),
        )
        assertTrue(
            newRssNotificationVideoIds(
                sourceInitialized = true,
                previousKnownVideoIds = listOf("AAAAAAAAAAA", "BBBBBBBBBBB"),
                currentVideoIds = listOf("BBBBBBBBBBB"),
            ).isEmpty(),
        )
    }

    @Test
    fun rssKnownIdsKeepCurrentFeedAndBoundOlderMemory() {
        assertEquals(
            listOf("CCCCCCCCCCC", "AAAAAAAAAAA"),
            mergeRssKnownVideoIds(
                currentVideoIds = listOf("CCCCCCCCCCC"),
                previousKnownVideoIds = listOf("AAAAAAAAAAA", "BBBBBBBBBBB"),
                limit = 2,
            ),
        )
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

    @Test
    fun apiUnknownCannotEraseShortConfirmedByChannelTab() {
        val confirmed = FetchedNotificationEntry(
            entry = entry("Oj0-9Ks6d7k", 2_000L),
            kind = VideoKind.SHORT,
            evidence = VideoKindEvidence.CHANNEL_TAB,
        )
        val apiUnknown = FetchedNotificationEntry(
            entry = entry("Oj0-9Ks6d7k", 2_000L),
        )

        val merged = mergeFetchedNotificationEntry(confirmed, apiUnknown)

        assertEquals(VideoKind.SHORT, merged.kind)
        assertEquals(VideoKindEvidence.CHANNEL_TAB, merged.evidence)
    }

    @Test
    fun webRelativeDateCannotReplaceExactRssDate() {
        val rss = FetchedNotificationEntry(
            entry = entry("j4mc2vj4LFg", 1_783_310_428_000L),
            publishedAtEvidence = PublishedAtEvidence.RSS,
        )
        val web = FetchedNotificationEntry(
            entry = entry("j4mc2vj4LFg", 1_783_539_960_000L),
            kind = VideoKind.VIDEO,
            evidence = VideoKindEvidence.CHANNEL_TAB,
            publishedAtEvidence = PublishedAtEvidence.WEB_RELATIVE,
        )

        val merged = mergeFetchedNotificationEntry(rss, web)

        assertEquals(1_783_310_428_000L, merged.entry.publishedAtMillis)
        assertEquals(PublishedAtEvidence.RSS, merged.publishedAtEvidence)
        assertEquals(VideoKind.VIDEO, merged.kind)
        assertEquals(VideoKindEvidence.CHANNEL_TAB, merged.evidence)
    }

    @Test
    fun exactRssDateRepairsEarlierApproximation() {
        val web = FetchedNotificationEntry(
            entry = entry("j4mc2vj4LFg", 1_783_539_960_000L),
            publishedAtEvidence = PublishedAtEvidence.WEB_RELATIVE,
        )
        val rss = FetchedNotificationEntry(
            entry = entry("j4mc2vj4LFg", 1_783_310_428_000L),
            publishedAtEvidence = PublishedAtEvidence.RSS,
        )

        val merged = mergeFetchedNotificationEntry(web, rss)

        assertEquals(1_783_310_428_000L, merged.entry.publishedAtMillis)
        assertEquals(PublishedAtEvidence.RSS, merged.publishedAtEvidence)
    }

    @Test
    fun databaseDateAuthorityRejectsRepeatedRelativeDrift() {
        assertTrue(
            shouldReplacePublishedAt(
                existingEvidenceRank = PublishedAtEvidence.UNKNOWN.rank,
                incomingEvidence = PublishedAtEvidence.WEB_RELATIVE,
            ),
        )
        assertTrue(
            shouldReplacePublishedAt(
                existingEvidenceRank = PublishedAtEvidence.PLAYLIST_ITEM.rank,
                incomingEvidence = PublishedAtEvidence.WEB_RELATIVE,
            ),
        )
        assertFalse(
            shouldReplacePublishedAt(
                existingEvidenceRank = PublishedAtEvidence.WEB_RELATIVE.rank,
                incomingEvidence = PublishedAtEvidence.WEB_RELATIVE,
            ),
        )
        assertFalse(
            shouldReplacePublishedAt(
                existingEvidenceRank = PublishedAtEvidence.RSS.rank,
                incomingEvidence = PublishedAtEvidence.WEB_RELATIVE,
            ),
        )
        assertTrue(
            shouldReplacePublishedAt(
                existingEvidenceRank = PublishedAtEvidence.WEB_RELATIVE.rank,
                incomingEvidence = PublishedAtEvidence.RSS,
            ),
        )
    }

    @Test
    fun typedSeedItemDoesNotLoseItsKind() {
        val fetched = FetchedNotificationEntry(
            entry = entry("Oj0-9Ks6d7k", 2_000L),
            kind = VideoKind.SHORT,
            evidence = VideoKindEvidence.RSS_SHORT_URL,
        )

        val historyItem = fetched.asHistoryItem()

        assertEquals(VideoKind.SHORT, historyItem.kind)
        assertEquals(VideoKindEvidence.RSS_SHORT_URL, historyItem.evidence)
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
