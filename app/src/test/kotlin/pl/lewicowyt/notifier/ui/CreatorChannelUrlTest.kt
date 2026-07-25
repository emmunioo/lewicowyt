package pl.lewicowyt.notifier.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import pl.lewicowyt.notifier.model.Creator
import pl.lewicowyt.notifier.model.CreatorSource
import pl.lewicowyt.notifier.model.SourceType

class CreatorChannelUrlTest {
    @Test
    fun prefersCanonicalChannelOverPlaylist() {
        val creator = Creator(
            id = "creator",
            name = "Twórca",
            sources = listOf(
                CreatorSource(
                    type = SourceType.PLAYLIST,
                    url = "https://www.youtube.com/playlist?list=PL123",
                    externalId = "PL123",
                ),
                CreatorSource(
                    type = SourceType.CHANNEL,
                    url = "https://www.youtube.com/@tworca",
                    externalId = "UC123456789012345678",
                ),
            ),
        )

        assertEquals(
            "https://www.youtube.com/channel/UC123456789012345678",
            creatorYouTubeChannelUrl(creator),
        )
    }

    @Test
    fun rejectsAnUntrustedFallbackUrl() {
        val creator = Creator(
            id = "creator",
            name = "Twórca",
            sources = listOf(
                CreatorSource(
                    type = SourceType.PLAYLIST,
                    url = "http://example.org/playlist",
                    externalId = null,
                ),
            ),
        )

        assertNull(creatorYouTubeChannelUrl(creator))
    }
}
