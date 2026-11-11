package pl.lewicowyt.notifier.community.push

import org.junit.Assert.assertEquals
import org.junit.Test

class LepPollPolicyTest {
    private fun next(result: EmbeddedPush.Poll, failures: Int = 0, elapsedMs: Long = 50_000L) =
        LepPollPolicy.next(result, failures, elapsedMs)

    @Test
    fun `received wakes re-poll immediately`() {
        assertEquals(LepPollPolicy.Plan(0, 0L), next(EmbeddedPush.Poll.Ok(cursor = 5, received = 1), elapsedMs = 200))
    }

    @Test
    fun `normal end of hold re-polls immediately`() {
        assertEquals(LepPollPolicy.Plan(0, 0L), next(EmbeddedPush.Poll.Ok(cursor = 5, received = 0)))
    }

    @Test
    fun `server that does not hold the connection is throttled`() {
        assertEquals(
            LepPollPolicy.Plan(0, LepPollPolicy.QUICK_EMPTY_WAIT_MS),
            next(EmbeddedPush.Poll.Ok(cursor = 5, received = 0), elapsedMs = 30),
        )
    }

    @Test
    fun `deliberate interruption is not a failure`() {
        assertEquals(LepPollPolicy.Plan(0, 0L), next(EmbeddedPush.Poll.Interrupted, failures = 4))
    }

    @Test
    fun `failures back off exponentially up to five minutes`() {
        assertEquals(LepPollPolicy.Plan(1, 1_000L), next(EmbeddedPush.Poll.Failed, failures = 0))
        assertEquals(LepPollPolicy.Plan(2, 2_000L), next(EmbeddedPush.Poll.Failed, failures = 1))
        assertEquals(LepPollPolicy.Plan(9, 256_000L), next(EmbeddedPush.Poll.Failed, failures = 8))
        assertEquals(LepPollPolicy.Plan(10, LepPollPolicy.MAX_BACKOFF_MS), next(EmbeddedPush.Poll.Failed, failures = 9))
        assertEquals(LepPollPolicy.Plan(16, LepPollPolicy.MAX_BACKOFF_MS), next(EmbeddedPush.Poll.Failed, failures = 16))
    }

    @Test
    fun `success after failures resets the counter`() {
        assertEquals(0, next(EmbeddedPush.Poll.Ok(cursor = 1, received = 0), failures = 7).failures)
    }
}
