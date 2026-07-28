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
        val html =
            """{"videoId":"video123","isLiveContent":true,"isLiveNow":false,"isUpcoming":false}"""
        assertEquals(
            VideoKind.STREAM_ARCHIVE,
            classifier.classifyHtml(html, "video123"),
        )
    }

    @Test
    fun `currently live broadcast remains live`() {
        val html = """{"videoId":"video123","isLiveContent":true,"isLiveNow":true}"""
        assertEquals(VideoKind.LIVE, classifier.classifyHtml(html, "video123"))
    }

    @Test
    fun `regular video remains video`() {
        val html = """{"videoDetails":{"videoId":"video123","isLiveContent":false}}"""
        assertEquals(VideoKind.VIDEO, classifier.classifyHtml(html, "video123"))
    }

    @Test
    fun `short is recognized from current navigation path`() {
        val videoId = "bluZh7LfTCk"
        val html =
            """{"videoDetails":{"videoId":"$videoId"},"webCommandMetadata":{"url":"/shorts/$videoId"}}"""

        assertEquals(VideoKind.SHORT, classifier.classifyHtml(html, videoId))
    }

    @Test
    fun `short is recognized when YouTube escapes slashes`() {
        val videoId = "bluZh7LfTCk"
        val html =
            """{"videoDetails":{"videoId":"$videoId"},"webCommandMetadata":{"url":"\/shorts\/$videoId"}}"""

        assertEquals(VideoKind.SHORT, classifier.classifyHtml(html, videoId))
    }

    @Test
    fun `live recommendation cannot turn current film into stream archive`() {
        val html = """
            <script>
            var ytInitialPlayerResponse = {
              "videoDetails":{"videoId":"lU4H50GMJyI","isLiveContent":false}
            };
            </script>
            <script>
            {"videoDetails":{"videoId":"recommended","isLiveContent":true}}
            </script>
        """.trimIndent()

        assertEquals(
            VideoKind.VIDEO,
            classifier.classifyHtml(html, "lU4H50GMJyI"),
        )
    }

    @Test
    fun `mismatched player response is rejected`() {
        val html =
            """var ytInitialPlayerResponse = {"videoId":"otherVideo1","isLiveContent":true};"""

        assertEquals(VideoKind.UNKNOWN, classifier.classifyHtml(html, "video123"))
    }

    @Test
    fun `android player identifies completed stream`() {
        val json = playerResponse(
            videoId = "2PSPUWSzP8o",
            durationSeconds = 9_480,
            width = 1_920,
            height = 1_080,
            isLiveContent = true,
        )

        assertEquals(
            VideoKind.STREAM_ARCHIVE,
            classifier.classifyPlayerResponse(json, "2PSPUWSzP8o"),
        )
    }

    @Test
    fun `android player identifies vertical short`() {
        val json = playerResponse(
            videoId = "bluZh7LfTCk",
            durationSeconds = 56,
            width = 1_080,
            height = 1_920,
        )

        assertEquals(
            VideoKind.SHORT,
            classifier.classifyPlayerResponse(json, "bluZh7LfTCk"),
        )
    }

    @Test
    fun `android player keeps Doma Gorajek material as film`() {
        val json = playerResponse(
            videoId = "lU4H50GMJyI",
            durationSeconds = 641,
            width = 3_840,
            height = 1_920,
        )

        assertEquals(
            VideoKind.VIDEO,
            classifier.classifyPlayerResponse(json, "lU4H50GMJyI"),
        )
    }

    @Test
    fun `short duration alone does not turn horizontal film into short`() {
        val json = playerResponse(
            videoId = "horizontal1",
            durationSeconds = 60,
            width = 1_920,
            height = 1_080,
        )

        assertEquals(
            VideoKind.VIDEO,
            classifier.classifyPlayerResponse(json, "horizontal1"),
        )
    }

    private fun playerResponse(
        videoId: String,
        durationSeconds: Long,
        width: Int,
        height: Int,
        isLiveContent: Boolean = false,
    ): String = """
        {
          "playabilityStatus":{"status":"OK"},
          "videoDetails":{
            "videoId":"$videoId",
            "lengthSeconds":"$durationSeconds",
            "isLiveContent":$isLiveContent
          },
          "streamingData":{
            "adaptiveFormats":[{"width":$width,"height":$height}]
          }
        }
    """.trimIndent()
}
