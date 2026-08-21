package pl.lewicowyt.notifier.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduleSettingsPersistenceTest {
    @Test
    fun datastoreIntervalHasPriorityOverBackup() {
        assertEquals(30, resolveIntervalMinutes(stored = 30, backup = 360))
    }

    @Test
    fun backupRestoresIntervalWhenDatastoreKeyIsMissing() {
        assertEquals(360, resolveIntervalMinutes(stored = null, backup = 360))
    }

    @Test
    fun invalidStoredIntervalFallsBackToValidBackup() {
        assertEquals(120, resolveIntervalMinutes(stored = 0, backup = 120))
    }

    @Test
    fun scheduleDefaultsAreUsedOnlyWhenBothCopiesAreUnavailable() {
        assertEquals(DEFAULT_INTERVAL_MINUTES, resolveIntervalMinutes(null, null))
        assertEquals(DEFAULT_DAILY_HOUR, resolveDailyHour(null, null))
        assertEquals(DEFAULT_DAILY_MINUTE, resolveDailyMinute(null, null))
    }

    @Test
    fun dailyTimeAlsoRecoversFromBackup() {
        assertEquals(22, resolveDailyHour(stored = null, backup = 22))
        assertEquals(45, resolveDailyMinute(stored = null, backup = 45))
    }
}
