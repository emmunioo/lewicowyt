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
        // dziennik ruchu: jeden wpis na zapytanie (metoda, adres BEZ zapytania ?…, kod, bajty, czas)
        private val startNanos = System.nanoTime()
        private var sent = 0L
        private var received = 0L
        private var status = 0

        override fun requestBodyEnd(call: Call, byteCount: Long) {
            sent += byteCount
            record(operation, uploadedBytes = byteCount, downloadedBytes = 0L)
        }

        override fun responseHeadersEnd(call: Call, response: okhttp3.Response) {
            status = response.code
        }

        override fun responseBodyEnd(call: Call, byteCount: Long) {
            received += byteCount
            record(operation, uploadedBytes = 0L, downloadedBytes = byteCount)
        }

        override fun callEnd(call: Call) = logTraffic(call, null)

        override fun callFailed(call: Call, ioe: java.io.IOException) = logTraffic(call, ioe)

        override fun canceled(call: Call) = logTraffic(call, null, canceled = true)

        private var logged = false

        private fun logTraffic(call: Call, error: java.io.IOException?, canceled: Boolean = false) {
            if (logged || !DiagnosticLogStore.isTrafficEnabled()) return
            logged = true
            val url = call.request().url
            val ms = (System.nanoTime() - startNanos) / 1_000_000L
            val outcome = when {
                canceled -> "ANULOWANE"
                error != null -> "BŁĄD " + error.javaClass.simpleName
                else -> status.toString()
            }
            // ścieżka bez query (sanitize i tak tnie ?…); nazwy hostów spoza znanej listy maskuje sanitize (C-20),
            // sekretny temat LEP w ścieżce zamienia na [TEMAT] (E-01)
            DiagnosticLogStore.traffic(
                if (error != null || status >= 400) DiagnosticLevel.WARNING else DiagnosticLevel.INFO,
                "${call.request().method} ${url.scheme}://${url.host}${if (url.port != url.defaultPort()) ":" + url.port else ""}" +
                    "${url.encodedPath} → $outcome; wysłano=${sent}B; odebrano=${received}B; ${ms}ms; rodzaj=${operation.name}",
            )
        }

        private fun okhttp3.HttpUrl.defaultPort(): Int = okhttp3.HttpUrl.defaultPort(scheme)
    }
}
