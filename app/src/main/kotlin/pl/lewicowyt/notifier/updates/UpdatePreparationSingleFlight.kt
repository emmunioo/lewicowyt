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
 * Procesowy single-flight. Praca nie jest dzieckiem callera, więc anulowanie
 * ekranu oczekującego na wynik nie przerywa pobierania potrzebnego workerowi.
 * Osobny mutex wykonania gwarantuje, że również różne wydania nigdy nie użyją
 * równolegle wspólnego katalogu plików tymczasowych aktualizatora.
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
 * GitHub nie publikuje versionCode obok assetu; jest on odczytywany i
 * obowiązkowo sprawdzany z APK przed publikacją pending. Do single-flight
 * używamy wszystkich dostępnych, niezmiennych cech targetu, w szczególności
 * versionName i SHA-256, a przy braku digestu także unikalnego URL-u assetu.
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
        deltaManifestSha256 = releaseAssets[DELTA_MANIFEST_ASSET_NAME]
            ?.sha256Digest
            ?.lowercase(),
    )
