package pl.lewicowyt.notifier.community.push

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** J-08: wyrejestrowanie push ponawiamy przy braku sieci, 408, 429 i 5xx; 2xx i pozostałe 4xx są załatwione. */
class UnregisterRetryTest {
    @Test
    fun `retry on no network, 408, 429 and 5xx`() {
        listOf<Int?>(null, 408, 429, 500, 502, 503).forEach { assertTrue("kod $it", unregisterNeedsRetry(it)) }
    }

    @Test
    fun `done on 2xx and other 4xx`() {
        listOf(200, 204, 400, 404, 410).forEach { assertFalse("kod $it", unregisterNeedsRetry(it)) }
    }
}
