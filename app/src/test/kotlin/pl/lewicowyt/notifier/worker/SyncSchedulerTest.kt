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
}
