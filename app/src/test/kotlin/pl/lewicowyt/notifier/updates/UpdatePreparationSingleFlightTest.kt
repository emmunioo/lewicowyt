package pl.lewicowyt.notifier.updates

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePreparationSingleFlightTest {
    @Test
    fun `two callers for the same release share one preparation`() = withGate { gate ->
        val executions = AtomicInteger()
        val first = async { gate.run("target") { executions.incrementAndGet(); delay(30); "apk" } }
        val second = async { gate.run("target") { executions.incrementAndGet(); "other" } }

        assertEquals("apk", first.await())
        assertEquals("apk", second.await())
        assertEquals(1, executions.get())
    }

    @Test
    fun `same target performs only one download`() = withGate { gate ->
        val downloads = AtomicInteger()
        val start = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async {
            gate.run("target") {
                downloads.incrementAndGet()
                start.complete(Unit)
                release.await()
                "pending.apk"
            }
        }
        start.await()
        val second = async { gate.run("target") { downloads.incrementAndGet(); "bad" } }
        yield()
        release.complete(Unit)

        assertEquals(first.await(), second.await())
        assertEquals(1, downloads.get())
    }

    @Test
    fun `same target reconstructs xdelta only once`() = withGate { gate ->
        val reconstructions = AtomicInteger()
        val callers = List(2) {
            async { gate.run("delta") { reconstructions.incrementAndGet(); delay(20); "rebuilt" } }
        }

        assertEquals(listOf("rebuilt", "rebuilt"), callers.map { it.await() })
        assertEquals(1, reconstructions.get())
    }

    @Test
    fun `different releases never own shared part files concurrently`() = withGate { gate ->
        val activeOwners = AtomicInteger()
        val maximumOwners = AtomicInteger()
        val events = mutableListOf<String>()
        suspend fun prepare(name: String): String = gate.run(name) {
            val active = activeOwners.incrementAndGet()
            maximumOwners.updateAndGet { previous -> maxOf(previous, active) }
            events += "$name:start"
            delay(20)
            events += "$name:cleanup"
            activeOwners.decrementAndGet()
            name
        }

        val first = async { prepare("A") }
        val second = async { prepare("B") }
        first.await()
        second.await()

        assertEquals(1, maximumOwners.get())
        assertFalse(events.indexOf("B:start") < events.indexOf("A:cleanup") && events.first() == "A:start")
        assertFalse(events.indexOf("A:start") < events.indexOf("B:cleanup") && events.first() == "B:start")
    }

    @Test
    fun `background and manual caller receive the same result`() = withGate { gate ->
        val background = async { gate.run("release") { delay(20); "verified.apk" } }
        val manual = async { gate.run("release") { "must-not-run" } }

        assertEquals("verified.apk", background.await())
        assertEquals("verified.apk", manual.await())
    }

    @Test
    fun `cancelling one waiter does not cancel process preparation`() = withGate { gate ->
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val firstWaiter = async {
            gate.run("release") {
                started.complete(Unit)
                release.await()
                "verified.apk"
            }
        }
        started.await()
        val secondWaiter = async { gate.run("release") { "must-not-run" } }
        firstWaiter.cancelAndJoin()
        release.complete(Unit)

        assertEquals("verified.apk", secondWaiter.await())
    }

    @Test
    fun `next prepare after success can start a new operation`() = withGate { gate ->
        val executions = AtomicInteger()
        assertEquals("1", gate.run("release") { executions.incrementAndGet().toString() })
        assertEquals("2", gate.run("release") { executions.incrementAndGet().toString() })
    }

    @Test
    fun `failure removes stale deferred and releases serialization`() = withGate { gate ->
        val first = runCatching { gate.run("release") { error("network") } }
        assertTrue(first.isFailure)

        assertEquals("retry-ok", gate.run("release") { "retry-ok" })
    }

    @Test
    fun `full apk fallback remains single flight after delta failure`() = withGate { gate ->
        val deltaAttempts = AtomicInteger()
        val fullDownloads = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async {
            gate.run("release") {
                deltaAttempts.incrementAndGet()
                started.complete(Unit)
                release.await()
                val delta = runCatching { error("bad patch") }.getOrNull()
                delta ?: fullDownloads.incrementAndGet().let { "full.apk" }
            }
        }
        started.await()
        val second = async {
            gate.run("release") {
                deltaAttempts.incrementAndGet()
                val delta = runCatching { error("bad patch") }.getOrNull()
                delta ?: fullDownloads.incrementAndGet().let { "full.apk" }
            }
        }
        yield()
        release.complete(Unit)

        assertEquals(listOf("full.apk", "full.apk"), listOf(first, second).map { it.await() })
        assertEquals(1, deltaAttempts.get())
        assertEquals(1, fullDownloads.get())
    }

    @Test
    fun `failed target A cannot overlap cleanup with target B`() = withGate { gate ->
        val aCleaning = CompletableDeferred<Unit>()
        val allowFailure = CompletableDeferred<Unit>()
        val bStarted = CompletableDeferred<Unit>()
        val a = async {
            runCatching {
                gate.run("A") {
                    aCleaning.complete(Unit)
                    allowFailure.await()
                    error("A failed after cleanup")
                }
            }
        }
        aCleaning.await()
        val b = async { gate.run("B") { bStarted.complete(Unit); "B-ok" } }
        delay(20)
        assertFalse(bStarted.isCompleted)
        allowFailure.complete(Unit)

        assertTrue(a.await().isFailure)
        assertEquals("B-ok", b.await())
    }

    private fun <T> withGate(
        block: suspend CoroutineScope.(UpdatePreparationSingleFlight<String, String>) -> T,
    ): T = runBlocking {
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            block(UpdatePreparationSingleFlight(processScope))
        } finally {
            processScope.cancel()
        }
    }
}
