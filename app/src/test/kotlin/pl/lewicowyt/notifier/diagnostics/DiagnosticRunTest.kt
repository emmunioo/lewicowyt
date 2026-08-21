package pl.lewicowyt.notifier.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticRunTest {
    @Test
    fun `one run keeps one sync id and retry gets another id`() {
        val first = DiagnosticSyncRun.create(DiagnosticSyncTrigger.EXACT_ALARM)
        val retry = DiagnosticSyncRun.create(
            DiagnosticSyncTrigger.RETRY,
            retryOf = first.syncId,
        )

        assertTrue(first.syncId.matches(Regex("[0-9A-F]{8}")))
        assertEquals(first.syncId, retry.retryOf)
        assertNotEquals(first.syncId, retry.syncId)
        assertEquals(first.syncId, first.syncId)
    }

    @Test
    fun `independent runs never share the same id in a local diagnostic batch`() {
        val ids = List(128) {
            DiagnosticSyncRun.create(DiagnosticSyncTrigger.MANUAL).syncId
        }

        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `reason and notification result formatting is stable`() {
        val formatted = formatDiagnosticEvent(
            name = "notification_decision",
            syncId = "8F31A2B4",
            reason = DiagnosticReasonCode.DND_ACTIVE,
            fields = mapOf("result" to DiagnosticNotificationResult.DEFERRED.name),
        )

        assertEquals(
            "NOTIFICATION_DECISION | sync=8F31A2B4 | reason=DND_ACTIVE | result=DEFERRED",
            formatted,
        )
    }

    @Test
    fun `all notification decision outcomes have stable names`() {
        assertEquals(
            listOf("SENT", "INBOX_ONLY", "DEFERRED", "SKIPPED"),
            DiagnosticNotificationResult.entries.map { it.name },
        )
    }

    @Test
    fun `critical reason codes remain machine readable`() {
        val expected = setOf(
            "DND_ACTIVE",
            "NO_NETWORK",
            "TYPE_DISABLED",
            "NOTIFICATION_PERMISSION_MISSING",
            "ALREADY_DELIVERED",
            "RETRY_SCHEDULED",
            "HOST_NOT_ALLOWED",
            "DESCRIPTION_FETCH_FAILED",
            "OLDER_SEARCH_FAILED",
            "OLDER_MATERIAL_CHANNEL_MISMATCH",
        )

        assertTrue(DiagnosticReasonCode.entries.map { it.name }.toSet().containsAll(expected))
    }
}
