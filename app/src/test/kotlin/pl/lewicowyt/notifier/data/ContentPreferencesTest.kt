package pl.lewicowyt.notifier.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lewicowyt.notifier.model.HistoryFilter
import pl.lewicowyt.notifier.model.VideoKind

class ContentPreferencesTest {
    @Test
    fun disablingHistoryAlwaysDisablesNotifications() {
        val settings = AppSettings(
            selectedCreatorIds = setOf("kanal"),
            globalHistoryTypes = setOf(HistoryFilter.VIDEOS, HistoryFilter.STREAMS),
            globalNotificationTypes = ALL_CONTENT_TYPES,
        )

        assertFalse(settings.isHistoryEnabledFor("kanal", VideoKind.SHORT))
        assertFalse(settings.isNotificationEnabledFor("kanal", VideoKind.SHORT))
        assertTrue(settings.isNotificationEnabledFor("kanal", VideoKind.VIDEO))
    }

    @Test
    fun creatorOverridesAreCombinedWithGlobalRules() {
        val settings = AppSettings(
            globalHistoryTypes = ALL_CONTENT_TYPES,
            globalNotificationTypes = setOf(HistoryFilter.STREAMS, HistoryFilter.SHORTS),
            creatorHistoryDisabledTypes = mapOf("kanal" to setOf(HistoryFilter.SHORTS)),
            creatorNotificationDisabledTypes = mapOf("kanal" to setOf(HistoryFilter.STREAMS)),
        )

        assertTrue(settings.isHistoryEnabledFor("kanal", VideoKind.VIDEO))
        assertFalse(settings.isNotificationEnabledFor("kanal", VideoKind.VIDEO))
        assertFalse(settings.isHistoryEnabledFor("kanal", VideoKind.SHORT))
        assertFalse(settings.isNotificationEnabledFor("kanal", VideoKind.STREAM_ARCHIVE))
    }

    @Test
    fun creatorOverridesRoundTripWithoutMalformedValues() {
        val encoded = encodeCreatorContentTypes(
            mapOf("kanal" to setOf(HistoryFilter.VIDEOS, HistoryFilter.SHORTS)),
        ) + setOf("zepsute", "|VIDEOS", "kanal|NIE_MA")

        assertEquals(
            mapOf("kanal" to setOf(HistoryFilter.VIDEOS, HistoryFilter.SHORTS)),
            decodeCreatorContentTypes(encoded),
        )
    }
}
