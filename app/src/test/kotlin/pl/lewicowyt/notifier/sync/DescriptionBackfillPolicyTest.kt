package pl.lewicowyt.notifier.sync

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class DescriptionBackfillPolicyTest {
    @Test
    fun validEmptyDescriptionIsStoredAsSearchableFinalMarker() {
        assertEquals(
            NO_DESCRIPTION_SEARCH_TEXT,
            descriptionForStorage(fetchSucceeded = true, description = "  "),
        )
    }

    @Test
    fun failedFetchDoesNotPretendThatVideoHasNoDescription() {
        assertEquals(null, descriptionForStorage(fetchSucceeded = false, description = null))
    }

    @Test
    fun backgroundWebUsesEightDescriptions() {
        assertEquals(8, descriptionBatchLimit(requested = null, apiAvailable = false))
    }

    @Test
    fun backgroundApiUsesFullDataApiBatch() {
        assertEquals(50, descriptionBatchLimit(requested = null, apiAvailable = true))
    }

    @Test
    fun foregroundRequestIsBoundedByHardLimit() {
        assertEquals(24, descriptionBatchLimit(requested = 24, apiAvailable = false))
        assertEquals(50, descriptionBatchLimit(requested = 200, apiAvailable = false))
    }

    @Test
    fun transientDescriptionFailureCanRetryAfterFifteenMinutes() {
        val now = 1_000_000L
        assertEquals(
            now - TimeUnit.MINUTES.toMillis(15),
            descriptionRetryBeforeMillis(now),
        )
    }
}
