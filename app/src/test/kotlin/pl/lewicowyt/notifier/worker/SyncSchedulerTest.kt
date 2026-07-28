package pl.lewicowyt.notifier.worker

import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
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
    fun periodicAlarmUsesTheConfiguredInterval() {
        val now = ZonedDateTime.of(2026, 7, 28, 12, 0, 0, 0, warsaw)

        val next = nextAlarmRun(
            now = now,
            intervalMinutes = 60,
            dailyHour = 9,
            dailyMinute = 0,
        )

        assertEquals(Duration.ofMinutes(60), Duration.between(now, next))
    }

    @Test
    fun periodicAlarmEnforcesTheAndroidMinimumOfFifteenMinutes() {
        val now = ZonedDateTime.of(2026, 7, 28, 12, 0, 0, 0, warsaw)

        val next = nextAlarmRun(
            now = now,
            intervalMinutes = 1,
            dailyHour = 9,
            dailyMinute = 0,
        )

        assertEquals(Duration.ofMinutes(15), Duration.between(now, next))
    }

    @Test
    fun dailyAlarmUsesTheConfiguredLocalTime() {
        val now = ZonedDateTime.of(2026, 7, 28, 8, 50, 0, 0, warsaw)

        val next = nextAlarmRun(
            now = now,
            intervalMinutes = SyncScheduler.DAILY_INTERVAL_MINUTES,
            dailyHour = 9,
            dailyMinute = 0,
        )

        assertEquals(28, next.dayOfMonth)
        assertEquals(9, next.hour)
        assertEquals(0, next.minute)
    }

    @Test
    fun dailyAlarmMovesToTomorrowWhenTodaysTimeHasPassed() {
        val now = ZonedDateTime.of(2026, 7, 28, 9, 1, 0, 0, warsaw)

        val next = nextAlarmRun(
            now = now,
            intervalMinutes = SyncScheduler.DAILY_INTERVAL_MINUTES,
            dailyHour = 9,
            dailyMinute = 0,
        )

        assertEquals(29, next.dayOfMonth)
        assertEquals(9, next.hour)
        assertEquals(0, next.minute)
    }

    @Test
    fun failedSynchronizationRetriesAfterFifteenMinutes() {
        val now = ZonedDateTime.of(2026, 7, 28, 12, 0, 0, 0, warsaw)

        assertEquals(
            Duration.ofMinutes(15),
            Duration.between(now, nextRetryAlarmRun(now)),
        )
    }
}
