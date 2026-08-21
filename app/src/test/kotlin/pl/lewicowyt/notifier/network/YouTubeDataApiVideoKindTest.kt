package pl.lewicowyt.notifier.network

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import pl.lewicowyt.notifier.model.VideoKind
import pl.lewicowyt.notifier.model.VideoKindEvidence

class YouTubeDataApiVideoKindTest {
    @Test
    fun `api descriptions keep empty values and ignore unexpected videos`() {
        val expectedId = "mZCZR2JuFlM"
        val emptyId = "dQw4w9WgXcQ"
        val response = JSONObject(
            """
            {
              "items":[
                {"id":"$expectedId","snippet":{"description":"Opis z Data API"}},
                {"id":"$emptyId","snippet":{"description":""}},
                {"id":"abcdefghijk","snippet":{"description":"Obcy wynik"}}
              ]
            }
            """.trimIndent(),
        )

        assertEquals(
            mapOf(expectedId to "Opis z Data API", emptyId to ""),
            parseDataApiVideoDescriptions(response, setOf(expectedId, emptyId)),
        )
    }

    @Test
    fun `completed premiere remains ambiguous instead of becoming stream`() {
        val item = video(
            duration = "PT10M41S",
            liveDetails = """
                {
                  "scheduledStartTime":"2026-07-20T18:00:00Z",
                  "actualStartTime":"2026-07-20T18:00:03Z",
                  "actualEndTime":"2026-07-20T18:10:44Z"
                }
            """.trimIndent(),
        )

        assertEquals(VideoKind.UNKNOWN, classifyDataApiVideoKind(item))
    }

    @Test
    fun `current live and upcoming states remain definitive`() {
        assertEquals(
            VideoKind.LIVE,
            classifyDataApiVideoKind(video("PT1H", broadcastState = "live")),
        )
        assertEquals(
            VideoKind.UPCOMING,
            classifyDataApiVideoKind(video("PT0S", broadcastState = "upcoming")),
        )
    }

    @Test
    fun `ordinary long upload is a video`() {
        assertEquals(
            VideoKind.VIDEO,
            classifyDataApiVideoKind(video("PT10M41S")),
        )
    }

    @Test
    fun `short duration alone does not prove Shorts membership`() {
        assertEquals(
            VideoKind.UNKNOWN,
            classifyDataApiVideoKind(video("PT60S")),
        )
    }

    @Test
    fun `missing duration remains ambiguous`() {
        assertEquals(
            VideoKind.UNKNOWN,
            classifyDataApiVideoKind(video("")),
        )
    }

    @Test
    fun `api exposes confidence only for fields it can actually prove`() {
        assertEquals(
            VideoKindEvidence.API_CURRENT_STATE,
            classifyDataApiVideoKindDecision(
                video("PT1H", broadcastState = "live"),
            ).evidence,
        )
        assertEquals(
            VideoKindEvidence.API_METADATA,
            classifyDataApiVideoKindDecision(video("PT10M41S")).evidence,
        )
        assertEquals(
            VideoKindEvidence.NONE,
            classifyDataApiVideoKindDecision(video("PT60S")).evidence,
        )
    }

    private fun video(
        duration: String,
        broadcastState: String = "none",
        liveDetails: String? = null,
    ): JSONObject = JSONObject()
        .put(
            "snippet",
            JSONObject().put("liveBroadcastContent", broadcastState),
        )
        .put(
            "contentDetails",
            JSONObject().put("duration", duration),
        )
        .apply {
            liveDetails?.let {
                put("liveStreamingDetails", JSONObject(it))
            }
        }
}
