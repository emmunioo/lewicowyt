package pl.lewicowyt.notifier.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeApiKeyValidationTest {
    @Test
    fun successfulEmptyResponseConfirmsKey() {
        val result = interpretApiKeyValidationResponse(
            statusCode = 200,
            responseBody = """
                {
                  "kind":"youtube#channelListResponse",
                  "items":[],
                  "pageInfo":{"totalResults":0}
                }
            """.trimIndent(),
        )

        assertEquals(YouTubeApiKeyValidation.Valid, result)
    }

    @Test
    fun invalidKeyIsRejected() {
        val result = interpretApiKeyValidationResponse(
            statusCode = 400,
            responseBody = googleError(
                legacyReason = "badRequest",
                detailedReason = "API_KEY_INVALID",
            ),
        )

        assertTrue(result is YouTubeApiKeyValidation.Rejected)
    }

    @Test
    fun disabledYouTubeApiIsRejectedWithSpecificMessage() {
        val result = interpretApiKeyValidationResponse(
            statusCode = 403,
            responseBody = googleError(
                legacyReason = "accessNotConfigured",
                detailedReason = "SERVICE_DISABLED",
            ),
        )

        assertTrue(result is YouTubeApiKeyValidation.Rejected)
        assertTrue((result as YouTubeApiKeyValidation.Rejected).message.contains("nie jest włączone"))
    }

    @Test
    fun exhaustedQuotaDoesNotActivateKey() {
        val result = interpretApiKeyValidationResponse(
            statusCode = 403,
            responseBody = googleError(legacyReason = "quotaExceeded"),
        )

        assertTrue(result is YouTubeApiKeyValidation.TemporarilyUnavailable)
    }

    @Test
    fun serverFailureDoesNotRejectKeyAsFake() {
        val result = interpretApiKeyValidationResponse(
            statusCode = 503,
            responseBody = """{"error":{"code":503}}""",
        )

        assertTrue(result is YouTubeApiKeyValidation.TemporarilyUnavailable)
    }

    @Test
    fun malformedSuccessfulResponseIsNotAccepted() {
        val result = interpretApiKeyValidationResponse(
            statusCode = 200,
            responseBody = "<html>awaria</html>",
        )

        assertTrue(result is YouTubeApiKeyValidation.TemporarilyUnavailable)
    }

    private fun googleError(
        legacyReason: String,
        detailedReason: String = "",
    ): String = """
        {
          "error": {
            "code": 403,
            "errors": [{"reason": "$legacyReason"}],
            "details": [{"reason": "$detailedReason"}]
          }
        }
    """.trimIndent()
}
