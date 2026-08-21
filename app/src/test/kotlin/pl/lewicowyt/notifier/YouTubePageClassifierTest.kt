package pl.lewicowyt.notifier

import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import pl.lewicowyt.notifier.model.VideoKind
import pl.lewicowyt.notifier.network.HttpTextClient
import pl.lewicowyt.notifier.network.DescriptionFetchResult
import pl.lewicowyt.notifier.network.YouTubePageClassifier

class YouTubePageClassifierTest {
    private val classifier = YouTubePageClassifier(HttpTextClient())

    @Test
    fun `members only response is a final description status`() {
        val videoId = "tTI4LwBuIn4"
        val result = classifier.extractDescriptionFetchResult(
            responseText = """
                {
                  "playabilityStatus": {
                    "status": "UNPLAYABLE",
                    "reason": "Zacznij wspierać ten kanał, aby uzyskać dostęp do treści tylko dla wspierających."
                  }
                }
            """.trimIndent(),
            videoId = videoId,
        )

        assertEquals(DescriptionFetchResult.MembersOnly, result)
    }

    @Test
    fun `offline live streamability is a scheduled stream status`() {
        val videoId = "AP3ptbuJDKE"
        val result = classifier.extractDescriptionFetchResult(
            responseText = """
                {
                  "playabilityStatus": {
                    "status": "LIVE_STREAM_OFFLINE",
                    "liveStreamability": {
                      "liveStreamabilityRenderer": {"videoId": "$videoId"}
                    }
                  }
                }
            """.trimIndent(),
            videoId = videoId,
        )

        assertEquals(DescriptionFetchResult.ScheduledStream, result)
    }

    @Test
    fun `generic unplayable response remains invalid`() {
        val result = classifier.extractDescriptionFetchResult(
            responseText = """{"playabilityStatus":{"status":"UNPLAYABLE","reason":"Film prywatny"}}""",
            videoId = "mZCZR2JuFlM",
        )

        assertEquals(DescriptionFetchResult.Invalid, result)
    }

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

    @Test
    fun `public metadata survives player login required response`() {
        val videoId = "mZCZR2JuFlM"
        val json = """
            {
              "playabilityStatus":{"status":"LOGIN_REQUIRED"},
              "videoDetails":{
                "videoId":"$videoId",
                "channelId":"UCabcdefghijklmnopqrstuv",
                "title":"Blachosmrodziarze cierpią",
                "lengthSeconds":"321",
                "shortDescription":"Opis"
              },
              "microformat":{"playerMicroformatRenderer":{"publishDate":"2024-01-02"}},
              "streamingData":{"formats":[{"width":1920,"height":1080}]}
            }
        """.trimIndent()

        val metadata = classifier.inspectPlayerResponse(json, videoId)

        assertNotNull(metadata)
        assertEquals("Blachosmrodziarze cierpią", metadata?.title)
        assertEquals("UCabcdefghijklmnopqrstuv", metadata?.channelId)
    }

    @Test
    fun `unplayable response is still rejected`() {
        val json = """
            {
              "playabilityStatus":{"status":"UNPLAYABLE"},
              "videoDetails":{
                "videoId":"mZCZR2JuFlM",
                "channelId":"UCabcdefghijklmnopqrstuv",
                "title":"Materiał",
                "lengthSeconds":"321"
              },
              "microformat":{"playerMicroformatRenderer":{"publishDate":"2024-01-02"}}
            }
        """.trimIndent()

        assertNull(classifier.inspectPlayerResponse(json, "mZCZR2JuFlM"))
    }

    @Test
    fun `description does not require publication date`() {
        val videoId = "mZCZR2JuFlM"
        val json = """
            {
              "playabilityStatus":{"status":"OK"},
              "videoDetails":{
                "videoId":"$videoId",
                "channelId":"UCabcdefghijklmnopqrstuv",
                "title":"Materiał bez microformat",
                "shortDescription":"  Opis dostępny bez daty publikacji.  "
              }
            }
        """.trimIndent()

        assertEquals(
            "Opis dostępny bez daty publikacji.",
            classifier.extractDescriptionFromPlayerResponse(json, videoId),
        )
        assertNull(classifier.inspectPlayerResponse(json, videoId))
    }

    @Test
    fun `watch upload date confirms public video when player omits microformat`() {
        val videoId = "hua7SlaA85o"
        val timestamp = "2025-12-01T13:12:45-08:00"
        val html = """
            <html><head>
              <meta content="$timestamp" itemprop="uploadDate">
            </head></html>
        """.trimIndent()
        val player = """
            {
              "playabilityStatus":{"status":"OK"},
              "videoDetails":{
                "videoId":"$videoId",
                "channelId":"UCM0XopUZWDFr44S7-Yh2z0g",
                "title":"Razem nie jest idealne...",
                "lengthSeconds":"607"
              }
            }
        """.trimIndent()

        val publishedAt = classifier.extractWatchPublishedAtMillis(html)
        val metadata = classifier.inspectPlayerResponse(player, videoId, publishedAt)

        assertEquals(OffsetDateTime.parse(timestamp).toInstant().toEpochMilli(), publishedAt)
        assertNotNull(metadata)
        assertEquals("UCM0XopUZWDFr44S7-Yh2z0g", metadata?.channelId)
        assertEquals("Razem nie jest idealne...", metadata?.title)
    }

    @Test
    fun `description rejects response for a different video`() {
        val json = """
            {
              "playabilityStatus":{"status":"OK"},
              "videoDetails":{
                "videoId":"dQw4w9WgXcQ",
                "channelId":"UCabcdefghijklmnopqrstuv",
                "shortDescription":"Nie ten materiał"
              }
            }
        """.trimIndent()

        assertNull(classifier.extractDescriptionFromPlayerResponse(json, "mZCZR2JuFlM"))
    }

    @Test
    fun `description rejects unplayable response`() {
        val videoId = "mZCZR2JuFlM"
        val json = """
            {
              "playabilityStatus":{"status":"UNPLAYABLE"},
              "videoDetails":{
                "videoId":"$videoId",
                "channelId":"UCabcdefghijklmnopqrstuv",
                "shortDescription":"Opis"
              }
            }
        """.trimIndent()

        assertNull(classifier.extractDescriptionFromPlayerResponse(json, videoId))
    }

    @Test
    fun `empty web description is a valid final result`() {
        val videoId = "mZCZR2JuFlM"
        val json = """
            {
              "playabilityStatus":{"status":"OK"},
              "videoDetails":{
                "videoId":"$videoId",
                "channelId":"UCabcdefghijklmnopqrstuv",
                "title":"Stream bez opisu",
                "shortDescription":""
              }
            }
        """.trimIndent()

        assertEquals(
            "",
            classifier.extractDescriptionFromPlayerResponse(
                responseText = json,
                videoId = videoId,
                expectedTitle = "Stream bez opisu",
                expectedChannelIds = setOf("UCabcdefghijklmnopqrstuv"),
            ),
        )
    }

    @Test
    fun `description requires expected channel when catalog provides it`() {
        val videoId = "mZCZR2JuFlM"
        val json = """
            {
              "playabilityStatus":{"status":"OK"},
              "videoDetails":{
                "videoId":"$videoId",
                "channelId":"UCabcdefghijklmnopqrstuv",
                "title":"Zmieniony tytuł",
                "shortDescription":"Opis"
              }
            }
        """.trimIndent()

        assertEquals(
            "Opis",
            classifier.extractDescriptionFromPlayerResponse(
                json,
                videoId,
                expectedTitle = "Starszy tytuł z Historii",
                expectedChannelIds = setOf("UCabcdefghijklmnopqrstuv"),
            ),
        )
        assertNull(
            classifier.extractDescriptionFromPlayerResponse(
                json,
                videoId,
                expectedTitle = "Zmieniony tytuł",
                expectedChannelIds = setOf("UCzzzzzzzzzzzzzzzzzzzzzz"),
            ),
        )
    }

    @Test
    fun `description falls back to exact normalized title without known channel`() {
        val videoId = "mZCZR2JuFlM"
        val json = """
            {
              "playabilityStatus":{"status":"OK"},
              "videoDetails":{
                "videoId":"$videoId",
                "channelId":"UCabcdefghijklmnopqrstuv",
                "title":"Materiał   bez   opisu",
                "shortDescription":"Opis"
              }
            }
        """.trimIndent()

        assertEquals(
            "Opis",
            classifier.extractDescriptionFromPlayerResponse(
                json,
                videoId,
                expectedTitle = "Materiał bez opisu",
            ),
        )
        assertNull(
            classifier.extractDescriptionFromPlayerResponse(
                json,
                videoId,
                expectedTitle = "Inny materiał",
            ),
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
