package pl.lewicowyt.notifier.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AppViewModelRetentionTest {
    @Test
    fun expiresOnlyDeselectedCreatorsOlderThanRetentionWindow() {
        val now = 10_000L

        assertEquals(
            setOf("expired"),
            expiredDeselectedCreatorIds(
                deselectedAtMillis = mapOf(
                    "expired" to 1_000L,
                    "recent" to 9_000L,
                    "selected-again" to 1_000L,
                ),
                selectedCreatorIds = setOf("selected-again"),
                nowMillis = now,
                retentionMillis = 5_000L,
            ),
        )
    }

    @Test
    fun expiresAtExactRetentionBoundary() {
        assertEquals(
            setOf("creator"),
            expiredDeselectedCreatorIds(
                deselectedAtMillis = mapOf("creator" to 5_000L),
                selectedCreatorIds = emptySet(),
                nowMillis = 10_000L,
                retentionMillis = 5_000L,
            ),
        )
    }
}
