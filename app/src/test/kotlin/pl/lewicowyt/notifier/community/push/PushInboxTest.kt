package pl.lewicowyt.notifier.community.push

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PushInboxTest {
    private lateinit var dir: File
    private var now = 1_000_000L

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("push_inbox").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun inbox() = PushInbox(dir) { now }

    private fun files() = dir.list().orEmpty().sorted()

    @Test
    fun `stored wake is claimed once and removed after completion`() {
        val inbox = inbox()
        assertTrue(inbox.put("srvA", "cipher-1"))
        val claimed = inbox.claim()
        assertEquals(1, claimed.size)
        assertEquals("srvA", claimed[0].senderHint)
        assertEquals("cipher-1", claimed[0].blob)
        assertFalse(claimed[0].recovered)
        assertTrue("already in progress", inbox.claim().isEmpty())
        inbox.complete(claimed[0])
        assertTrue(files().isEmpty())
    }

    @Test
    fun `identical wake while pending is stored once`() {
        val inbox = inbox()
        assertTrue(inbox.put("srvA", "same"))
        assertTrue(inbox.put("srvA", "same"))
        assertEquals(1, inbox.claim().size)
    }

    @Test
    fun `same blob from two servers gives two wakes`() {
        val inbox = inbox()
        inbox.put("srvA", "{\"t\":\"1\"}")
        inbox.put("srvB", "{\"t\":\"1\"}")
        assertEquals(setOf("srvA", "srvB"), inbox.claim().map { it.senderHint }.toSet())
    }

    @Test
    fun `wake claimed by a killed process is recovered by the next one`() {
        val first = inbox()
        first.put("srvA", "cipher")
        assertEquals(1, first.claim().size) // obsługa trwa… proces ginie bez complete()

        val afterRestart = inbox() // nowy proces = nowa instancja nad tym samym katalogiem
        val recovered = afterRestart.claim()
        assertEquals(1, recovered.size)
        assertTrue(recovered[0].recovered)
        afterRestart.complete(recovered[0])
        assertTrue(files().isEmpty())
    }

    @Test
    fun `released wake is retried as recovered`() {
        val inbox = inbox()
        inbox.put("srvA", "cipher")
        val entry = inbox.claim().single()
        inbox.release(entry)
        val again = inbox.claim().single()
        assertEquals(entry.id, again.id)
        assertTrue(again.recovered)
    }

    @Test
    fun `new identical wake waits until the running one completes`() {
        val inbox = inbox()
        inbox.put("srvA", "{\"t\":\"1\"}")
        val running = inbox.claim().single()
        inbox.put("srvA", "{\"t\":\"1\"}") // ten sam jawny budzik przyszedł ponownie w trakcie obsługi
        assertTrue(inbox.claim().isEmpty())
        inbox.complete(running)
        val next = inbox.claim().single()
        assertFalse(next.recovered)
    }

    @Test
    fun `oversized or blank wake lets the cursor advance without storing`() {
        val inbox = inbox()
        assertTrue(inbox.put("srvA", "x".repeat(PushInbox.MAX_BLOB_CHARS + 1)))
        assertTrue(inbox.put("srvA", " "))
        assertTrue(inbox.put(" ", "cipher"))
        assertTrue(files().isEmpty())
    }

    @Test
    fun `expired wake is dropped without processing`() {
        val inbox = inbox()
        inbox.put("srvA", "old")
        now += PushInbox.MAX_AGE_MS + 1
        assertTrue(inbox.claim().isEmpty())
        assertTrue(files().isEmpty())
    }

    @Test
    fun `corrupted entry is dropped`() {
        File(dir, "deadbeef.wake").writeText("{not json")
        assertTrue(inbox().claim().isEmpty())
        assertTrue(files().isEmpty())
    }

    @Test
    fun `flooded inbox stops storing but does not block the cursor`() {
        val inbox = inbox()
        repeat(PushInbox.MAX_ENTRIES) { assertTrue(inbox.put("srvA", "c$it")) }
        assertTrue(inbox.put("srvA", "one-too-many"))
        assertEquals(PushInbox.MAX_ENTRIES, files().size)
    }

    @Test
    fun `missing directory is created on first wake`() {
        dir.deleteRecursively()
        assertTrue(inbox().put("srvA", "cipher"))
        assertEquals(1, inbox().claim().size)
    }
}
