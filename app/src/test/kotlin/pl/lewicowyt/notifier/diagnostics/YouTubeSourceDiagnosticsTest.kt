package pl.lewicowyt.notifier.diagnostics

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import pl.lewicowyt.notifier.model.CreatorSource
import pl.lewicowyt.notifier.model.SourceType
import pl.lewicowyt.notifier.network.HttpStatusException
import pl.lewicowyt.notifier.network.ResolvedSource

class YouTubeSourceDiagnosticsTest {
    private val source = CreatorSource(
        type = SourceType.CHANNEL,
        url = "https://www.youtube.com/@example",
        externalId = "UC1234567890123456789012",
    )
    private val resolved = ResolvedSource(
        sourceKey = "ignored-private-key",
        type = SourceType.CHANNEL,
        externalId = "UC1234567890123456789012",
        feedUrl = "https://www.youtube.com/feeds/videos.xml?channel_id=UC1234567890123456789012",
    )

    @Test
    fun `source context contains public ids but no url or resolver key`() {
        val fields = diagnosticYouTubeSourceFields(
            creatorId = "creator-test",
            source = source,
            resolved = resolved,
            operation = DiagnosticYouTubeOperation.CHANNEL_TABS,
        )

        assertEquals("creator-test", fields["creatorId"])
        assertEquals("CHANNEL", fields["sourceType"])
        assertEquals("WEB", fields["provider"])
        assertEquals("UC1234567890123456789012", fields["channelId"])
        assertEquals("CHANNEL_TABS", fields["operation"])
        assertNull(fields["url"])
        assertNull(fields["sourceKey"])
        assertFalse(fields.values.any { it.toString().contains("ignored-private-key") })
    }

    @Test
    fun `playlistItems 404 has a precise stable reason and status`() {
        val error = HttpStatusException(
            statusCode = 404,
            responseBody = "secret body is never logged",
            safeUrl = "https://www.googleapis.com/youtube/v3/playlistItems",
        )

        assertEquals(
            DiagnosticReasonCode.API_PLAYLIST_ITEMS_NOT_FOUND,
            diagnosticYouTubeFailureReason(
                error = error,
                operation = DiagnosticYouTubeOperation.API_PLAYLIST_ITEMS,
                fallback = DiagnosticReasonCode.DATA_API_SOURCE_FAILED,
            ),
        )
        val fields = diagnosticYouTubeSourceFields(
                creatorId = "creator-test",
                source = source,
                resolved = resolved,
                operation = DiagnosticYouTubeOperation.API_PLAYLIST_ITEMS,
                error = error,
            )
        assertEquals(404, fields["httpStatus"])
        assertFalse(fields.values.any { it.toString().contains("secret body") })
        assertFalse(fields.values.any { it.toString().contains("key=") })
    }

    @Test
    fun `missing channel tabs has a precise stable reason`() {
        assertEquals(
            DiagnosticReasonCode.CHANNEL_TABS_UNAVAILABLE,
            diagnosticYouTubeFailureReason(
                error = IOException("YouTube nie udostępnił listy kart kanału"),
                operation = DiagnosticYouTubeOperation.WEB_HISTORY,
                fallback = DiagnosticReasonCode.WEB_SOURCE_FAILED,
            ),
        )
    }
}
