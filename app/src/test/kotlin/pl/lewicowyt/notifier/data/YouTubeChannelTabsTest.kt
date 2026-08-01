package pl.lewicowyt.notifier.data

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeChannelTabsTest {
    @Test
    fun `present tabs never require missing-tab refresh`() {
        val now = 2L * MISSING_TAB_REFRESH_MILLIS
        val stored = storedTabs(
            state = YouTubeChannelTabState.PRESENT,
            checkedAtMillis = 1L,
        )

        assertFalse(stored.needsMissingRefresh(now))
    }

    @Test
    fun `unknown and absent tabs retry only after requested interval`() {
        val checked = 10_000L
        val recentUnknown = storedTabs(
            state = YouTubeChannelTabState.UNKNOWN,
            checkedAtMillis = checked,
        )
        val staleAbsent = storedTabs(
            state = YouTubeChannelTabState.ABSENT,
            checkedAtMillis = checked,
        )

        assertFalse(recentUnknown.needsMissingRefresh(checked + 999L, 1_000L))
        assertTrue(staleAbsent.needsMissingRefresh(checked + 1_000L, 1_000L))
    }

    @Test
    fun `daily refresh boundary prefers two but allows first later sync`() {
        val zone = ZoneId.of("Europe/Warsaw")
        fun millis(hour: Int) = LocalDateTime.of(2026, 8, 1, hour, 0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

        assertTrue(dailyChannelTabRefreshBoundaryMillis(millis(1), zone) < millis(1))
        assertTrue(dailyChannelTabRefreshBoundaryMillis(millis(2), zone) == millis(2))
        assertTrue(
            dailyChannelTabRefreshBoundaryMillis(millis(12), zone) == millis(2),
        )
    }

    @Test
    fun `daily claim is due once after boundary even when sync happens at noon`() {
        val boundary = 100_000L
        val beforeBoundary = StoredYouTubeChannelTab(
            tabName = CHANNEL_TAB_STREAMS,
            state = YouTubeChannelTabState.ABSENT,
            params = null,
            checkedAtMillis = 1L,
            lastAttemptAtMillis = boundary - 1L,
        )
        val afterBoundary = beforeBoundary.copy(lastAttemptAtMillis = boundary + 1L)

        assertTrue(beforeBoundary.needsRefreshAfterBoundary(boundary + 10_000L, boundary))
        assertFalse(afterBoundary.needsRefreshAfterBoundary(boundary + 10_000L, boundary))
    }

    private fun storedTabs(
        state: YouTubeChannelTabState,
        checkedAtMillis: Long,
    ) = StoredYouTubeChannelTabs(
        sourceKey = "source",
        channelId = "UCaaaaaaaaaaaaaaaaaaaaaa",
        tabs = REQUIRED_CHANNEL_TABS.associateWith { tabName ->
            StoredYouTubeChannelTab(
                tabName = tabName,
                state = state,
                params = if (state == YouTubeChannelTabState.PRESENT) "params" else null,
                checkedAtMillis = checkedAtMillis,
                lastAttemptAtMillis = checkedAtMillis,
            )
        },
    )
}
