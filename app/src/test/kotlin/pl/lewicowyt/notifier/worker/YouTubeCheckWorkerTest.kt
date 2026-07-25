package pl.lewicowyt.notifier.worker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lewicowyt.notifier.model.SyncOutcome

class YouTubeCheckWorkerTest {
    @Test
    fun retriesWidespreadNetworkFailuresOnlyALimitedNumberOfTimes() {
        val widespreadFailure = outcome(
            checkedSources = 1,
            errors = listOf("Kanał 1: timeout", "Kanał 2: timeout"),
        )

        assertTrue(shouldRetryBackgroundSync(widespreadFailure, runAttemptCount = 0))
        assertTrue(shouldRetryBackgroundSync(widespreadFailure, runAttemptCount = 1))
        assertFalse(shouldRetryBackgroundSync(widespreadFailure, runAttemptCount = 2))
    }

    @Test
    fun doesNotRetryASuccessfulRun() {
        assertFalse(shouldRetryBackgroundSync(outcome(), runAttemptCount = 0))
    }

    @Test
    fun doesNotRepeatAllSourcesForAnIsolatedFailure() {
        val isolatedFailure = outcome(
            checkedSources = 10,
            errors = listOf("Jeden kanał: timeout"),
        )

        assertFalse(shouldRetryBackgroundSync(isolatedFailure, runAttemptCount = 0))
    }

    @Test
    fun retriesWhenEverySourceFailed() {
        val totalFailure = outcome(
            checkedSources = 0,
            errors = listOf("Kanał: timeout"),
        )

        assertTrue(shouldRetryBackgroundSync(totalFailure, runAttemptCount = 0))
    }

    @Test
    fun retriesWhenExactlyHalfOfSourcesFailed() {
        val halfFailure = outcome(
            checkedSources = 2,
            errors = listOf("Kanał 1: timeout", "Kanał 2: timeout"),
        )

        assertTrue(shouldRetryBackgroundSync(halfFailure, runAttemptCount = 0))
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
