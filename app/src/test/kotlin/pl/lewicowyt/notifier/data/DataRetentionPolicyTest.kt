package pl.lewicowyt.notifier.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DataRetentionPolicyTest {
    @Test
    fun createsSixtyDayHistoryAndFourteenDayNotificationCutoffs() {
        val now = 1_800_000_000_000L
        val cutoffs = DataRetentionPolicy.cutoffs(now)

        assertEquals(
            now - 60L * DataRetentionPolicy.DAY_MILLIS,
            cutoffs.historyBeforeMillis,
        )
        assertEquals(
            now - 14L * DataRetentionPolicy.DAY_MILLIS,
            cutoffs.notificationsBeforeMillis,
        )
    }
}
