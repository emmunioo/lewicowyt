package pl.lewicowyt.notifier.sync

import java.util.concurrent.atomic.AtomicInteger
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConcurrentWorkTest {
    @Test
    fun concurrencyIsBoundedAndResultOrderIsPreserved() = runBlocking {
        val sourceCount = 52
        val concurrencyLimit = 4
        val startedCount = AtomicInteger(0)
        val startedSources = Collections.synchronizedList(mutableListOf<Int>())
        val activeCount = AtomicInteger(0)
        val maxActiveCount = AtomicInteger(0)
        val firstGroupStarted = CompletableDeferred<Unit>()
        val allowFinish = CompletableDeferred<Unit>()

        val result = async {
            (0 until sourceCount).mapConcurrently(concurrencyLimit) { source ->
                startedSources += source
                startedCount.incrementAndGet()
                val active = activeCount.incrementAndGet()
                maxActiveCount.updateAndGet { current -> maxOf(current, active) }
                if (active == concurrencyLimit) {
                    firstGroupStarted.complete(Unit)
                }
                allowFinish.await()
                activeCount.decrementAndGet()
                source
            }
        }

        withTimeout(5_000L) {
            firstGroupStarted.await()
        }
        delay(100L)
        assertEquals(concurrencyLimit, startedCount.get())
        assertEquals((0 until concurrencyLimit).toSet(), startedSources.toSet())
        allowFinish.complete(Unit)
        assertEquals((0 until sourceCount).toList(), result.await())
        assertTrue(maxActiveCount.get() <= concurrencyLimit)
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroConcurrencyIsRejected() {
        runBlocking {
            listOf(1).mapConcurrently(0) { it }
        }
    }
}
