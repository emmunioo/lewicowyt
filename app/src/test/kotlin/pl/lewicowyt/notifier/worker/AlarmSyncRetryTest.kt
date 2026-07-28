package pl.lewicowyt.notifier.worker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lewicowyt.notifier.model.SyncOutcome

class AlarmSyncRetryTest {
    @Test
    fun retriesWidespreadFailuresOnlyALimitedNumberOfTimes() {
        val widespreadFailure = outcome(
            checkedSources = 1,
            errors = listOf("Kanał 1: timeout", "Kanał 2: timeout"),
        )

        assertTrue(shouldRetryAlarmSync(widespreadFailure, retryAttempt = 0))
        assertTrue(shouldRetryAlarmSync(widespreadFailure, retryAttempt = 1))
        assertFalse(shouldRetryAlarmSync(widespreadFailure, retryAttempt = 2))
    }

    @Test
    fun doesNotRetryASuccessfulRun() {
        assertFalse(shouldRetryAlarmSync(outcome(), retryAttempt = 0))
    }

    @Test
    fun doesNotRepeatAllSourcesForAnIsolatedFailure() {
        val isolatedFailure = outcome(
            checkedSources = 10,
            errors = listOf("Jeden kanał: timeout"),
        )

        assertFalse(shouldRetryAlarmSync(isolatedFailure, retryAttempt = 0))
    }

    @Test
    fun retriesWhenEverySourceFailed() {
        val totalFailure = outcome(
            checkedSources = 0,
            errors = listOf("Kanał: timeout"),
        )

        assertTrue(shouldRetryAlarmSync(totalFailure, retryAttempt = 0))
    }

    @Test
    fun retriesWhenExactlyHalfOfSourcesFailed() {
        val halfFailure = outcome(
            checkedSources = 2,
            errors = listOf("Kanał 1: timeout", "Kanał 2: timeout"),
        )

        assertTrue(shouldRetryAlarmSync(halfFailure, retryAttempt = 0))
    }

    private fun outcome(
        checkedSources: Int = 1,
        errors: List<String> = emptyList(),
    ) = SyncOutcome(
        checkedSources = checkedSources,
        detectedItems = 0,
        notificationsSent = 0,
        errors = errors,
    )
}
