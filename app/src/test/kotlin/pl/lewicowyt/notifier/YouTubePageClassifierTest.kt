package pl.lewicowyt.notifier

import org.junit.Assert.assertEquals
import org.junit.Test
import pl.lewicowyt.notifier.model.VideoKind
import pl.lewicowyt.notifier.network.HttpTextClient
import pl.lewicowyt.notifier.network.YouTubePageClassifier

class YouTubePageClassifierTest {
    private val classifier = YouTubePageClassifier(HttpTextClient())

    @Test
    fun `completed live broadcast is an archive stream`() {
        val html = """{"isLiveContent":true,"isLiveNow":false,"isUpcoming":false}"""
        assertEquals(
            VideoKind.STREAM_ARCHIVE,
            classifier.classifyHtml(html, "video123"),
        )
    }

    @Test
    fun `currently live broadcast remains live`() {
        val html = """{"isLiveContent":true,"isLiveNow":true}"""
        assertEquals(VideoKind.LIVE, classifier.classifyHtml(html, "video123"))
    }

    @Test
    fun `regular video remains video`() {
        assertEquals(VideoKind.VIDEO, classifier.classifyHtml("{}", "video123"))
    }
}
