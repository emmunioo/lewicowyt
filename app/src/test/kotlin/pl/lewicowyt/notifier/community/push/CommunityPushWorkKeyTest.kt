package pl.lewicowyt.notifier.community.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CommunityPushWorkKeyTest {
    private fun key(instance: String? = null, hint: String? = null, blob: String? = null, file: String? = null) =
        CommunityPushWorker.refreshWorkKey(instance, hint, blob, file)

    @Test
    fun `D-02 two different big blobs of one server give two works`() {
        assertNotEquals(key(hint = "srvA", file = "aaaa"), key(hint = "srvA", file = "bbbb"))
    }

    @Test
    fun `D-02 external distributor instance does not swallow later wake-ups`() {
        assertNotEquals(key(instance = "inst1", blob = "{\"e\":\"x\"}"), key(instance = "inst1", blob = "{\"e\":\"y\"}"))
    }

    @Test
    fun `identical duplicate is still deduplicated, other servers stay separate`() {
        assertEquals(key(hint = "srvA", blob = "{\"t\":\"1\"}"), key(hint = "srvA", blob = "{\"t\":\"1\"}"))
        assertNotEquals(key(hint = "srvA", blob = "{\"t\":\"1\"}"), key(hint = "srvB", blob = "{\"t\":\"1\"}"))
    }
}
