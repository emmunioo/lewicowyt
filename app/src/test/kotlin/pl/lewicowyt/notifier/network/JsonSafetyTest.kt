package pl.lewicowyt.notifier.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonSafetyTest {
    @Test
    fun rejectsExcessivelyNestedOrUnbalancedJsonBeforeParsing() {
        assertTrue(hasSafeJsonNesting("""{"items":[{"title":"{inside string}"}]}"""))
        assertFalse(hasSafeJsonNesting("[".repeat(101) + "]".repeat(101)))
        assertFalse(hasSafeJsonNesting("""{"items":]"""))
    }
}
