package pl.lewicowyt.notifier.worker

import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncSchedulerTest {
    private val warsaw = ZoneId.of("Europe/Warsaw")

    @Test
    fun nextDailyRunKeepsLocalHourAcrossSpringDstChange() {
        val beforeChange = ZonedDateTime.of(2026, 3, 28, 9, 0, 0, 0, warsaw)
        val next = nextDailyRun(beforeChange, hour = 9, minute = 0)

        assertEquals(29, next.dayOfMonth)
        assertEquals(9, next.hour)
        assertEquals(Duration.ofHours(23), Duration.between(beforeChange, next))
    }

    @Test
    fun nextDailyRunKeepsLocalHourAcrossAutumnDstChange() {
        val beforeChange = ZonedDateTime.of(2026, 10, 24, 9, 0, 0, 0, warsaw)
        val next = nextDailyRun(beforeChange, hour = 9, minute = 0)

        assertEquals(25, next.dayOfMonth)
        assertEquals(9, next.hour)
        assertEquals(Duration.ofHours(25), Duration.between(beforeChange, next))
    }

    @Test
    fun reliableAlarmGivesPeriodicWorkTenMinutesOfGrace() {
        val now = ZonedDateTime.of(2026, 7, 26, 12, 0, 0, 0, warsaw)

        val next = nextReliableAlarmRun(
            now = now,
            intervalMinutes = 60,
            dailyHour = 9,
            dailyMinute = 0,
        )

        assertEquals(Duration.ofMinutes(70), Duration.between(now, next))
    }

    @Test
    fun reliableDailyAlarmRunsAfterTheConfiguredDailyTime() {
        val now = ZonedDateTime.of(2026, 7, 26, 8, 50, 0, 0, warsaw)

        val next = nextReliableAlarmRun(
            now = now,
            intervalMinutes = SyncScheduler.DAILY_INTERVAL_MINUTES,
            dailyHour = 9,
            dailyMinute = 0,
        )

        assertEquals(26, next.dayOfMonth)
        assertEquals(9, next.hour)
        assertEquals(10, next.minute)
    }

    @Test
    fun reliableAlarmSkipsARecentlyCompletedSync() {
        val now = 10_000_000L
        val thirtyMinutesAgo = now - Duration.ofMinutes(30).toMillis()

        assertFalse(
            shouldRunReliableAlarm(
                nowMillis = now,
                lastSuccessfulSyncMillis = thirtyMinutesAgo,
                intervalMinutes = 60,
            ),
        )
    }

    @Test
    fun reliableAlarmRunsWhenTheRegularWorkerIsLate() {
        val now = 10_000_000L
        val seventyMinutesAgo = now - Duration.ofMinutes(70).toMillis()

        assertTrue(
            shouldRunReliableAlarm(
                nowMillis = now,
                lastSuccessfulSyncMillis = seventyMinutesAgo,
                intervalMinutes = 60,
            ),
        )
    }

    @Test
    fun balancedWatchdogNeverWakesMoreOftenThanHourly() {
        assertEquals(60L, balancedWatchdogDelayMinutes(intervalMinutes = 15))
        assertEquals(60L, balancedWatchdogDelayMinutes(intervalMinutes = 30))
        assertEquals(120L, balancedWatchdogDelayMinutes(intervalMinutes = 60))
    }

    @Test
    fun balancedWatchdogCapsVeryLongPeriodicDelayAtTwelveHours() {
        assertEquals(12L * 60L, balancedWatchdogDelayMinutes(intervalMinutes = 12 * 60))
        assertEquals(12L * 60L, balancedWatchdogDelayMinutes(intervalMinutes = 23 * 60))
    }

    @Test
    fun balancedCatchUpRunsForAFirstSynchronization() {
        val now = ZonedDateTime.of(2026, 7, 26, 12, 0, 0, 0, warsaw)

        assertTrue(
            shouldRunBalancedCatchUp(
                now = now,
                lastCompletedSyncMillis = 0L,
                intervalMinutes = 60,
                dailyHour = 9,
                dailyMinute = 0,
            ),
        )
    }

    @Test
    fun balancedCatchUpKeepsTenMinutesOfGraceForPeriodicWork() {
        val now = ZonedDateTime.of(2026, 7, 26, 12, 0, 0, 0, warsaw)

        assertFalse(
            shouldRunBalancedCatchUp(
                now = now,
                lastCompletedSyncMillis = now.minusMinutes(65).toInstant().toEpochMilli(),
                intervalMinutes = 60,
                dailyHour = 9,
                dailyMinute = 0,
            ),
        )
        assertTrue(
            shouldRunBalancedCatchUp(
                now = now,
                lastCompletedSyncMillis = now.minusMinutes(71).toInstant().toEpochMilli(),
                intervalMinutes = 60,
                dailyHour = 9,
                dailyMinute = 0,
            ),
        )
    }

    @Test
    fun balancedDailyCatchUpUsesConfiguredLocalTime() {
        val beforeDue = ZonedDateTime.of(2026, 7, 26, 9, 5, 0, 0, warsaw)
        val afterGrace = beforeDue.plusMinutes(6)
        val yesterdayAfterSync = beforeDue.minusDays(1).withHour(9).withMinute(30)

        assertFalse(
            shouldRunBalancedCatchUp(
                now = beforeDue,
                lastCompletedSyncMillis = yesterdayAfterSync.toInstant().toEpochMilli(),
                intervalMinutes = SyncScheduler.DAILY_INTERVAL_MINUTES,
                dailyHour = 9,
                dailyMinute = 0,
            ),
        )
        assertTrue(
            shouldRunBalancedCatchUp(
                now = afterGrace,
                lastCompletedSyncMillis = yesterdayAfterSync.toInstant().toEpochMilli(),
                intervalMinutes = SyncScheduler.DAILY_INTERVAL_MINUTES,
                dailyHour = 9,
                dailyMinute = 0,
            ),
        )
    }

    @Test
    fun balancedDailyWatchdogRunsOneHourAfterConfiguredTime() {
        val now = ZonedDateTime.of(2026, 7, 26, 8, 0, 0, 0, warsaw)

        val next = nextBalancedWatchdogRun(
            now = now,
            intervalMinutes = SyncScheduler.DAILY_INTERVAL_MINUTES,
            dailyHour = 9,
            dailyMinute = 0,
        )

        assertEquals(10, next.hour)
        assertEquals(0, next.minute)
        assertEquals(26, next.dayOfMonth)
    }

    @Test
    fun balancedCatchUpExpeditesOnlyFirstOrLargeBacklog() {
        val now = 100L * Duration.ofHours(1).toMillis()

        assertTrue(
            shouldExpediteBalancedCatchUp(
                nowMillis = now,
                lastCompletedSyncMillis = 0L,
                intervalMinutes = 60,
            ),
        )
        assertFalse(
            shouldExpediteBalancedCatchUp(
                nowMillis = now,
                lastCompletedSyncMillis = now - Duration.ofHours(2).toMillis(),
                intervalMinutes = 60,
            ),
        )
        assertTrue(
            shouldExpediteBalancedCatchUp(
                nowMillis = now,
                lastCompletedSyncMillis = now - Duration.ofHours(7).toMillis(),
                intervalMinutes = 60,
            ),
        )
    }

    @Test
    fun balancedDailyCatchUpNeedsTwoDaysBeforeUsingExpeditedQuota() {
        val now = 100L * Duration.ofDays(1).toMillis()

        assertFalse(
            shouldExpediteBalancedCatchUp(
                nowMillis = now,
                lastCompletedSyncMillis = now - Duration.ofHours(25).toMillis(),
                intervalMinutes = SyncScheduler.DAILY_INTERVAL_MINUTES,
            ),
        )
        assertTrue(
            shouldExpediteBalancedCatchUp(
                nowMillis = now,
                lastCompletedSyncMillis = now - Duration.ofHours(49).toMillis(),
                intervalMinutes = SyncScheduler.DAILY_INTERVAL_MINUTES,
            ),
        )
    }
}
