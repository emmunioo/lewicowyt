package pl.lewicowyt.notifier

import org.junit.Assert.assertEquals
import org.junit.Test
import pl.lewicowyt.notifier.model.VideoKind
import pl.lewicowyt.notifier.network.HttpTextClient
import pl.lewicowyt.notifier.network.YouTubePageClassifier

class YouTubePageClassifierTest {
    private val classifier = YouTubePageClassifier(HttpTextClient())

    @Test
    fun `completed live metadata remains ambiguous without channel tab`() {
        val html = playerResponse(
            videoId = "video123",
            durationSeconds = 3_600,
            width = 1_920,
            height = 1_080,
            isLiveContent = true,
        )
        assertEquals(
            VideoKind.UNKNOWN,
            classifier.classifyHtml(html, "video123"),
        )
    }

    @Test
    fun `currently live broadcast remains live`() {
        val html = playerResponse(
            videoId = "video123",
            durationSeconds = 3_600,
            width = 1_920,
            height = 1_080,
            isLiveContent = true,
            isLive = true,
        )
        assertEquals(VideoKind.LIVE, classifier.classifyHtml(html, "video123"))
    }

    @Test
    fun `regular video remains video`() {
        val html = playerResponse(
            videoId = "video123",
            durationSeconds = 600,
            width = 1_920,
            height = 1_080,
        )
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
              "playabilityStatus":{"status":"OK"},
              "videoDetails":{
                "videoId":"lU4H50GMJyI",
                "lengthSeconds":"641",
                "isLiveContent":false
              },
              "streamingData":{"formats":[{"width":3840,"height":1920}]}
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
    fun `android player does not confuse completed premiere with stream`() {
        val json = playerResponse(
            videoId = "2PSPUWSzP8o",
            durationSeconds = 9_480,
            width = 1_920,
            height = 1_080,
            isLiveContent = true,
        )

        assertEquals(
            VideoKind.UNKNOWN,
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
    fun `android player identifies square short`() {
        val json = playerResponse(
            videoId = "squareVid01",
            durationSeconds = 180,
            width = 1_080,
            height = 1_080,
        )

        assertEquals(
            VideoKind.SHORT,
            classifier.classifyPlayerResponse(json, "squareVid01"),
        )
    }

    @Test
    fun `short duration without dimensions remains unknown`() {
        val json = playerResponse(
            videoId = "Oj0-9Ks6d7k",
            durationSeconds = 76,
            width = null,
            height = null,
        )

        assertEquals(
            VideoKind.UNKNOWN,
            classifier.classifyPlayerResponse(json, "Oj0-9Ks6d7k"),
        )
    }

    @Test
    fun `offline status alone does not prove upcoming stream`() {
        val json = """
            {
              "playabilityStatus":{"status":"LIVE_STREAM_OFFLINE"},
              "videoDetails":{
                "videoId":"offlineVid1",
                "lengthSeconds":"600",
                "isUpcoming":false,
                "isLiveContent":true
              }
            }
        """.trimIndent()

        assertEquals(
            VideoKind.UNKNOWN,
            classifier.classifyPlayerResponse(json, "offlineVid1"),
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
        width: Int?,
        height: Int?,
        isLiveContent: Boolean = false,
        isLive: Boolean = false,
    ): String {
        val streamingData = if (width != null && height != null) {
            ""","streamingData":{"adaptiveFormats":[{"width":$width,"height":$height}]}"""
        } else {
            ""
        }
        return """
        {
          "playabilityStatus":{"status":"OK"},
          "videoDetails":{
            "videoId":"$videoId",
            "lengthSeconds":"$durationSeconds",
            "isLiveContent":$isLiveContent,
            "isLive":$isLive
          }
          $streamingData
        }
        """.trimIndent()
    }
}
