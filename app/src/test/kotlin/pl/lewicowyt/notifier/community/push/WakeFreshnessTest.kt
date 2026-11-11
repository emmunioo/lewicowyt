package pl.lewicowyt.notifier.community.push

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audyt T-13: świeżość budzika wynika z pola ts w ładunku, niezależnie od stanu rotacji klucza. */
class WakeFreshnessTest {
    private val now = 1_800_000_000_000L
    private val max = CommunityWakeProcessor.MAX_WAKE_AGE_MS

    @Test
    fun freshWakeIsAccepted() {
        assertFalse(isWakeStale("""{"ts":${now - 60_000},"video":{"c":"x","v":"y"}}""", now, max))
    }

    @Test
    fun oldWakeIsRejected() {
        assertTrue(isWakeStale("""{"ts":${now - max - 1},"video":{"c":"x","v":"y"}}""", now, max))
    }

    @Test
    fun missingOrInvalidTimestampIsNotStale() {
        // starszy serwer bez pola ts — zachowanie jak dotąd
        assertFalse(isWakeStale("""{"video":{"c":"x","v":"y"}}""", now, max))
        assertFalse(isWakeStale("""{"ts":"nie liczba"}""", now, max))
        assertFalse(isWakeStale("to nie jest json", now, max))
        assertFalse(isWakeStale(null, now, max))
    }

    @Test
    fun futureTimestampIsAcceptedSoBadClockDoesNotSilencePush() {
        assertFalse(isWakeStale("""{"ts":${now + 5L * 60 * 60 * 1000}}""", now, max))
    }

    @Test
    fun inboxEntriesGetLongerWindow() {
        val ts = now - CommunityWakeProcessor.MAX_WAKE_AGE_MS - 60_000
        assertTrue(isWakeStale("""{"ts":$ts}""", now, CommunityWakeProcessor.MAX_WAKE_AGE_MS))
        assertFalse(isWakeStale("""{"ts":$ts}""", now, CommunityWakeProcessor.MAX_INBOX_AGE_MS))
    }
}
