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

    @Test
    fun periodicQueueContainsFifteenConsecutiveRuns() {
        val now = ZonedDateTime.of(2026, 7, 28, 12, 0, 0, 0, warsaw)

        val runs = nextAlarmRuns(
            now = now,
            intervalMinutes = 30,
            dailyHour = 9,
            dailyMinute = 0,
            count = SyncScheduler.REGULAR_ALARM_QUEUE_SIZE,
        )

        assertEquals(15, runs.size)
        assertEquals(Duration.ofMinutes(30), Duration.between(now, runs.first()))
        assertEquals(Duration.ofHours(7).plusMinutes(30), Duration.between(now, runs.last()))
    }

    @Test
    fun dailyQueueKeepsLocalHourAcrossDst() {
        val now = ZonedDateTime.of(2026, 3, 27, 10, 0, 0, 0, warsaw)

        val runs = nextAlarmRuns(
            now = now,
            intervalMinutes = SyncScheduler.DAILY_INTERVAL_MINUTES,
            dailyHour = 9,
            dailyMinute = 15,
            count = SyncScheduler.REGULAR_ALARM_QUEUE_SIZE,
        )

        assertEquals(15, runs.size)
        runs.forEach {
            assertEquals(9, it.hour)
            assertEquals(15, it.minute)
        }
    }

    @Test
    fun schedulerSnapshotSeparatesRegularRetryWatchdogAndDndProbe() {
        val healthy = SchedulerDiagnosticSnapshot(
            regularPresent = 15,
            regularExpected = 15,
            missingSlots = emptyList(),
            nextAlarmAtMillis = 123L,
            retryPresent = true,
            watchdogPresent = false,
            dndProbePresent = true,
            intervalMinutes = 30,
        )
        val broken = healthy.copy(regularPresent = 11, missingSlots = listOf(4, 7, 8, 12))

        assertTrue(healthy.regularHealthy)
        assertTrue(healthy.retryPresent)
        assertFalse(healthy.watchdogPresent)
        assertTrue(healthy.dndProbePresent)
        assertFalse(broken.regularHealthy)
        assertEquals("4,7,8,12", broken.fields()["missingSlots"])
    }
}
