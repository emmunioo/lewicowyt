package pl.lewicowyt.notifier.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VideoKindDecisionTest {
    @Test
    fun `default film fallback stays eligible for bounded verification`() {
        assertFalse(VideoKindEvidence.DEFAULT_VIDEO_FALLBACK.isFinal)
    }

    @Test
    fun `unknown response never erases a known result`() {
        val current = VideoKindDecision(
            VideoKind.SHORT,
            VideoKindEvidence.CHANNEL_TAB,
        )

        assertEquals(current, chooseVideoKindDecision(current, VideoKindDecision.Unknown))
    }

    @Test
    fun `channel tab overrides weaker player guess`() {
        val player = VideoKindDecision(
            VideoKind.STREAM_ARCHIVE,
            VideoKindEvidence.PLAYER_METADATA,
        )
        val tab = VideoKindDecision(
            VideoKind.VIDEO,
            VideoKindEvidence.CHANNEL_TAB,
        )

        assertEquals(tab, chooseVideoKindDecision(player, tab))
    }

    @Test
    fun `current api live state wins while broadcast is active`() {
        val tab = VideoKindDecision(
            VideoKind.STREAM_ARCHIVE,
            VideoKindEvidence.CHANNEL_TAB,
        )
        val live = VideoKindDecision(
            VideoKind.LIVE,
            VideoKindEvidence.API_CURRENT_STATE,
        )

        assertEquals(live, chooseVideoKindDecision(tab, live))
    }

    @Test
    fun `terminal channel tab may close stale active state`() {
        val staleLive = VideoKindDecision(
            VideoKind.LIVE,
            VideoKindEvidence.API_CURRENT_STATE,
        )
        val archive = VideoKindDecision(
            VideoKind.STREAM_ARCHIVE,
            VideoKindEvidence.CHANNEL_TAB,
        )

        assertEquals(archive, chooseVideoKindDecision(staleLive, archive))
    }

    @Test
    fun `equal conflicting evidence is deterministic`() {
        val first = VideoKindDecision(
            VideoKind.SHORT,
            VideoKindEvidence.CHANNEL_TAB,
        )
        val second = VideoKindDecision(
            VideoKind.VIDEO,
            VideoKindEvidence.CHANNEL_TAB,
        )

        assertEquals(first, chooseVideoKindDecision(first, second))
    }

    @Test
    fun `channel membership overrides default film fallback`() {
        val fallback = VideoKindDecision(
            VideoKind.VIDEO,
            VideoKindEvidence.DEFAULT_VIDEO_FALLBACK,
        )
        val stream = VideoKindDecision(
            VideoKind.STREAM_ARCHIVE,
            VideoKindEvidence.CHANNEL_TAB,
        )

        assertEquals(stream, chooseVideoKindDecision(fallback, stream))
    }

    @Test
    fun `canonical rss short overrides default film fallback`() {
        val fallback = VideoKindDecision(
            VideoKind.VIDEO,
            VideoKindEvidence.DEFAULT_VIDEO_FALLBACK,
        )
        val short = VideoKindDecision(
            VideoKind.SHORT,
            VideoKindEvidence.RSS_SHORT_URL,
        )

        assertEquals(short, chooseVideoKindDecision(fallback, short))
    }
}
