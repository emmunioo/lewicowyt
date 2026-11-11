package pl.lewicowyt.notifier.community

import org.junit.Assert.assertEquals
import org.junit.Test

/** twardy limit reklam: najwyżej 1 na 20 zwykłych pozycji (A-19: zaokrąglając w DÓŁ). */
class CommunityAdPolicyTest {

    @Test
    fun `no regular items means no ads`() {
        assertEquals(0, CommunityAdPolicy.allowedAdCount(0))
    }

    @Test
    fun `fewer than twenty items allow no ad`() {
        assertEquals(0, CommunityAdPolicy.allowedAdCount(1))
        assertEquals(0, CommunityAdPolicy.allowedAdCount(19))
    }

    @Test
    fun `twenty to thirty nine items allow a single ad`() {
        assertEquals(1, CommunityAdPolicy.allowedAdCount(20))
        assertEquals(1, CommunityAdPolicy.allowedAdCount(39))
    }

    @Test
    fun `forty items allow two ads`() {
        assertEquals(2, CommunityAdPolicy.allowedAdCount(40))
    }
}
