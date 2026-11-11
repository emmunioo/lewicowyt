package pl.lewicowyt.notifier.updates

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * procesowy single-flight. praca nie jest dzieckiem callera, więc zamknięcie
 * ekranu czekającego na wynik nie ubija pobierania potrzebnego workerowi.
 * osobny mutex wykonania pilnuje, żeby też różne wydania nie sięgały równolegle
 * po wspólny katalog tymczasowy aktualizatora.
 */
internal class UpdatePreparationSingleFlight<K : Any, V : Any>(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val stateMutex = Mutex()
    private val executionMutex = Mutex()
    private val inFlight = mutableMapOf<K, Deferred<V>>()

    suspend fun run(key: K, block: suspend () -> V): V {
        val deferred = stateMutex.withLock {
            inFlight[key] ?: createDeferred(key, block).also { inFlight[key] = it }
        }
        deferred.start()
        return deferred.await()
    }

    private fun createDeferred(key: K, block: suspend () -> V): Deferred<V> {
        lateinit var created: Deferred<V>
        created = scope.async(start = CoroutineStart.LAZY) {
            try {
                executionMutex.withLock { block() }
            } finally {
                stateMutex.withLock {
                    if (inFlight[key] === created) inFlight.remove(key)
                }
            }
        }
        return created
    }
}

/**
 * GitHub nie podaje versionCode obok assetu – czytamy go i obowiązkowo sprawdzamy
 * z APK przed publikacją pending. do single-flight bierzemy wszystkie dostępne,
 * niezmienne cechy targetu: versionName i SHA-256, a przy braku digestu też
 * unikalny URL assetu.
 */
internal data class UpdatePreparationTargetKey(
    val versionName: String,
    val targetApkSha256: String?,
    val apkName: String,
    val apkSizeBytes: Long?,
    val apkDownloadUrl: String,
    val deltaManifestSha256: String?,
)

internal fun AvailableUpdate.preparationTargetKey(): UpdatePreparationTargetKey =
    UpdatePreparationTargetKey(
        versionName = version.trim().removePrefix("v").removePrefix("V"),
        targetApkSha256 = sha256Digest?.lowercase(),
        apkName = apkName,
        apkSizeBytes = apkSizeBytes,
        apkDownloadUrl = apkDownloadUrl,
        deltaManifestSha256 = deltaManifestAsset()
            ?.sha256Digest
            ?.lowercase(),
    )
