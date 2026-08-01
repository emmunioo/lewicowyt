package pl.lewicowyt.notifier.data

import java.time.Instant
import java.time.ZoneId

enum class YouTubeChannelTabState {
    UNKNOWN,
    PRESENT,
    ABSENT,
}

data class StoredYouTubeChannelTab(
    val tabName: String,
    val state: YouTubeChannelTabState,
    val params: String?,
    val checkedAtMillis: Long,
    val lastAttemptAtMillis: Long,
)

data class StoredYouTubeChannelTabs(
    val sourceKey: String,
    val channelId: String,
    val tabs: Map<String, StoredYouTubeChannelTab>,
) {
    fun presentParams(tabName: String): String? = tabs[tabName]
        ?.takeIf { it.state == YouTubeChannelTabState.PRESENT }
        ?.params

    fun hasFreshAbsence(
        tabName: String,
        nowMillis: Long,
        maxAgeMillis: Long = MISSING_TAB_REFRESH_MILLIS,
    ): Boolean {
        val tab = tabs[tabName] ?: return false
        return tab.state == YouTubeChannelTabState.ABSENT &&
            tab.checkedAtMillis in 1..nowMillis &&
            nowMillis - tab.checkedAtMillis < maxAgeMillis
    }

    fun needsMissingRefresh(
        nowMillis: Long,
        minAgeMillis: Long = MISSING_TAB_REFRESH_MILLIS,
    ): Boolean = REQUIRED_CHANNEL_TABS.any { tabName ->
        val tab = tabs[tabName]
        tab == null ||
            (
                tab.state != YouTubeChannelTabState.PRESENT &&
                    (
                        tab.checkedAtMillis <= 0L ||
                            nowMillis < tab.checkedAtMillis ||
                            nowMillis - tab.checkedAtMillis >= minAgeMillis
                        )
                )
    }
}

internal fun StoredYouTubeChannelTab?.needsRefreshAttempt(
    nowMillis: Long,
    minAgeMillis: Long,
): Boolean = this == null || (
    state != YouTubeChannelTabState.PRESENT &&
        (
            maxOf(checkedAtMillis, lastAttemptAtMillis) <= 0L ||
                nowMillis < maxOf(checkedAtMillis, lastAttemptAtMillis) ||
                nowMillis - maxOf(checkedAtMillis, lastAttemptAtMillis) >= minAgeMillis
            )
    )

internal fun StoredYouTubeChannelTab?.needsRefreshAfterBoundary(
    nowMillis: Long,
    attemptBoundaryMillis: Long,
): Boolean = this == null || (
    state != YouTubeChannelTabState.PRESENT &&
        (
            lastAttemptAtMillis <= 0L ||
                lastAttemptAtMillis > nowMillis ||
                lastAttemptAtMillis < attemptBoundaryMillis
            )
    )

internal fun dailyChannelTabRefreshBoundaryMillis(
    nowMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): Long {
    if (nowMillis <= 0L) return 0L
    val now = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
    val boundaryDate = if (now.hour < NIGHT_REFRESH_START_HOUR) {
        now.toLocalDate().minusDays(1)
    } else {
        now.toLocalDate()
    }
    return boundaryDate
        .atTime(NIGHT_REFRESH_START_HOUR, 0)
        .atZone(zoneId)
        .toInstant()
        .toEpochMilli()
}

const val CHANNEL_TAB_VIDEOS = "VIDEOS"
const val CHANNEL_TAB_STREAMS = "STREAMS"
const val CHANNEL_TAB_SHORTS = "SHORTS"

val REQUIRED_CHANNEL_TABS = setOf(
    CHANNEL_TAB_VIDEOS,
    CHANNEL_TAB_STREAMS,
    CHANNEL_TAB_SHORTS,
)

const val MISSING_TAB_REFRESH_MILLIS = 24L * 60L * 60L * 1_000L
const val NEW_ENTRY_TAB_REFRESH_MILLIS = 6L * 60L * 60L * 1_000L
private const val NIGHT_REFRESH_START_HOUR = 2
