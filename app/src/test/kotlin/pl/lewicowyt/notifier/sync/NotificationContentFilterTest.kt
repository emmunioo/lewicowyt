package pl.lewicowyt.notifier.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lewicowyt.notifier.model.HistoryFilter
import pl.lewicowyt.notifier.model.PublishedAtEvidence
import pl.lewicowyt.notifier.model.VideoEntry
import pl.lewicowyt.notifier.model.VideoKind
import pl.lewicowyt.notifier.model.VideoKindEvidence
import pl.lewicowyt.notifier.model.VideoOrigin

class NotificationContentFilterTest {
    @Test
    fun selectiveModeRejectsAmbiguousRssFilmUntilChannelTabConfirmsIt() {
        val ambiguous = entry(
            kind = VideoKind.VIDEO,
            evidence = VideoKindEvidence.DEFAULT_VIDEO_FALLBACK,
        )
        val confirmed = entry(
            kind = VideoKind.VIDEO,
            evidence = VideoKindEvidence.CHANNEL_TAB,
        )

        assertFalse(
            ambiguous.isSafeForEnabledContentTypes(setOf(HistoryFilter.VIDEOS)),
        )
        assertTrue(
            confirmed.isSafeForEnabledContentTypes(setOf(HistoryFilter.VIDEOS)),
        )
    }

    @Test
    fun disabledTypeIsRejectedEvenWhenClassificationIsFinal() {
        val short = entry(
            kind = VideoKind.SHORT,
            evidence = VideoKindEvidence.CHANNEL_TAB,
        )

        assertFalse(short.isSafeForEnabledContentTypes(setOf(HistoryFilter.VIDEOS)))
        assertTrue(short.isSafeForEnabledContentTypes(setOf(HistoryFilter.SHORTS)))
    }

    private fun entry(kind: VideoKind, evidence: VideoKindEvidence) =
        FetchedNotificationEntry(
            entry = VideoEntry(
                id = "AAAAAAAAAAA",
                title = "Materiał",
                url = "https://www.youtube.com/watch?v=AAAAAAAAAAA",
                publishedAtMillis = 1_000L,
                author = "Kanał",
                origin = VideoOrigin.YOUTUBE,
            ),
            kind = kind,
            evidence = evidence,
            publishedAtEvidence = PublishedAtEvidence.RSS,
        )
}
