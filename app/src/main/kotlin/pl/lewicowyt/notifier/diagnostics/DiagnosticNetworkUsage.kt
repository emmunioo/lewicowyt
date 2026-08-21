package pl.lewicowyt.notifier.diagnostics

import java.util.concurrent.atomic.AtomicLongArray
import okhttp3.Call
import okhttp3.EventListener

/**
 * Licznik ciał HTTP współdzielonego klienta aplikacji. Nie obejmuje nagłówków,
 * negocjacji TLS ani ruchu DNS, dlatego w eksporcie zawsze używamy jawnej nazwy
 * httpBodyBytes zamiast sugerować pomiar całego interfejsu sieciowego Androida.
 */
internal enum class DiagnosticNetworkOperation {
    OTHER,
    DESCRIPTION,
    OLDER_SEARCH,
    OLDER_CONFIRMATION,
}

internal data class DiagnosticNetworkSnapshot(
    val uploadedBytes: Long,
    val downloadedBytes: Long,
) {
    val totalBytes: Long get() = uploadedBytes + downloadedBytes

    fun deltaSince(previous: DiagnosticNetworkSnapshot): DiagnosticNetworkSnapshot =
        DiagnosticNetworkSnapshot(
            uploadedBytes = (uploadedBytes - previous.uploadedBytes).coerceAtLeast(0L),
            downloadedBytes = (downloadedBytes - previous.downloadedBytes).coerceAtLeast(0L),
        )
}

internal object DiagnosticNetworkUsage {
    private val uploaded = AtomicLongArray(DiagnosticNetworkOperation.entries.size)
    private val downloaded = AtomicLongArray(DiagnosticNetworkOperation.entries.size)
    private val activeOperation = ThreadLocal<DiagnosticNetworkOperation>()

    fun eventListenerFactory(): EventListener.Factory = EventListener.Factory {
        TransferEventListener(activeOperation.get() ?: DiagnosticNetworkOperation.OTHER)
    }

    fun <T> withOperation(operation: DiagnosticNetworkOperation, block: () -> T): T {
        val previous = activeOperation.get()
        activeOperation.set(operation)
        return try {
            block()
        } finally {
            if (previous == null) activeOperation.remove() else activeOperation.set(previous)
        }
    }

    fun snapshot(operation: DiagnosticNetworkOperation? = null): DiagnosticNetworkSnapshot {
        if (operation != null) {
            return DiagnosticNetworkSnapshot(
                uploadedBytes = uploaded.get(operation.ordinal),
                downloadedBytes = downloaded.get(operation.ordinal),
            )
        }
        var uploadTotal = 0L
        var downloadTotal = 0L
        DiagnosticNetworkOperation.entries.forEach { item ->
            uploadTotal += uploaded.get(item.ordinal)
            downloadTotal += downloaded.get(item.ordinal)
        }
        return DiagnosticNetworkSnapshot(uploadTotal, downloadTotal)
    }

    internal fun recordForTest(
        operation: DiagnosticNetworkOperation,
        uploadedBytes: Long = 0L,
        downloadedBytes: Long = 0L,
    ) {
        add(operation, uploadedBytes, downloadedBytes)
    }

    private fun record(
        operation: DiagnosticNetworkOperation,
        uploadedBytes: Long,
        downloadedBytes: Long,
    ) {
        if (!DiagnosticLogStore.isEnabled()) return
        add(operation, uploadedBytes, downloadedBytes)
    }

    private fun add(
        operation: DiagnosticNetworkOperation,
        uploadedBytes: Long,
        downloadedBytes: Long,
    ) {
        if (uploadedBytes > 0L) uploaded.addAndGet(operation.ordinal, uploadedBytes)
        if (downloadedBytes > 0L) downloaded.addAndGet(operation.ordinal, downloadedBytes)
    }

    private class TransferEventListener(
        private val operation: DiagnosticNetworkOperation,
    ) : EventListener() {
        override fun requestBodyEnd(call: Call, byteCount: Long) {
            record(operation, uploadedBytes = byteCount, downloadedBytes = 0L)
        }

        override fun responseBodyEnd(call: Call, byteCount: Long) {
            record(operation, uploadedBytes = 0L, downloadedBytes = byteCount)
        }
    }
}
