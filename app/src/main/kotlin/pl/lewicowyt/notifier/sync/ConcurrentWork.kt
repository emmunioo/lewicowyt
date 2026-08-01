package pl.lewicowyt.notifier.sync

import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Stała pula workerów zachowuje kolejność wyników oraz gwarantuje, że pierwszą
 * falą pracy są pierwsze elementy wejścia. Jest to istotne dla adaptacyjnego
 * rankingu: późniejszy kanał nie może wygrać wyścigu o semafor z kanałem o
 * wyższym priorytecie.
 */
internal suspend fun <T, R> Iterable<T>.mapConcurrently(
    maxConcurrency: Int,
    transform: suspend (T) -> R,
): List<R> = coroutineScope {
    require(maxConcurrency > 0) { "maxConcurrency musi być większe od zera" }
    val items = this@mapConcurrently.toList()
    if (items.isEmpty()) return@coroutineScope emptyList()

    val nextIndex = AtomicInteger(0)
    val indexedResults = Collections.synchronizedList(
        mutableListOf<IndexedValue<R>>(),
    )
    List(minOf(maxConcurrency, items.size)) {
        async(Dispatchers.IO) {
            while (true) {
                val index = nextIndex.getAndIncrement()
                if (index >= items.size) break
                indexedResults += IndexedValue(
                    index = index,
                    value = transform(items[index]),
                )
            }
        }
    }.awaitAll()
    indexedResults.sortedBy(IndexedValue<R>::index).map(IndexedValue<R>::value)
}
