package pl.lewicowyt.notifier

import org.junit.Assert.assertEquals
import org.junit.Test
import pl.lewicowyt.notifier.model.SyncOutcome

class BasicLogicTest {
    @Test
    fun syncSummaryContainsCounts() {
        val result = SyncOutcome(3, 2, 1, emptyList()).toPolishSummary()
        assertEquals("Sprawdzono źródeł: 3 · nowe: 2 · powiadomienia: 1", result)
    }
}
