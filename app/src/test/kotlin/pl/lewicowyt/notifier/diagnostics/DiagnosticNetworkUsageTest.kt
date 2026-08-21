package pl.lewicowyt.notifier.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticNetworkUsageTest {
    @Test
    fun `operation and total snapshots expose nonnegative deltas`() {
        val operationBefore = DiagnosticNetworkUsage.snapshot(
            DiagnosticNetworkOperation.DESCRIPTION,
        )
        val totalBefore = DiagnosticNetworkUsage.snapshot()

        DiagnosticNetworkUsage.recordForTest(
            operation = DiagnosticNetworkOperation.DESCRIPTION,
            uploadedBytes = 123L,
            downloadedBytes = 4_567L,
        )

        val operationDelta = DiagnosticNetworkUsage.snapshot(
            DiagnosticNetworkOperation.DESCRIPTION,
        ).deltaSince(operationBefore)
        val totalDelta = DiagnosticNetworkUsage.snapshot().deltaSince(totalBefore)
        assertEquals(123L, operationDelta.uploadedBytes)
        assertEquals(4_567L, operationDelta.downloadedBytes)
        assertEquals(4_690L, operationDelta.totalBytes)
        assertEquals(operationDelta, totalDelta)
    }
}
