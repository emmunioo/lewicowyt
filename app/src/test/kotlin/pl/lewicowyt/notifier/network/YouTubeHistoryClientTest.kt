package pl.lewicowyt.notifier.network

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lewicowyt.notifier.model.VideoKind

class YouTubeHistoryClientTest {
    @Test
    fun `fallback to home after missing streams is not accepted as streams`() {
        val response = JSONObject(
            """
            {
              "tabs": [
                {
                  "tabRenderer": {
                    "selected": true,
                    "title": "Główna",
                    "endpoint": {
                      "commandMetadata": {
                        "webCommandMetadata": {"url": "/@DomaGorajek/featured"}
                      },
                      "browseEndpoint": {
                        "params": "EghmZWF0dXJlZPIGBAoCMgA%3D"
                      }
                    }
                  }
                },
                {
                  "tabRenderer": {
                    "title": "Wideo",
                    "endpoint": {
                      "commandMetadata": {
                        "webCommandMetadata": {"url": "/@DomaGorajek/videos"}
                      },
                      "browseEndpoint": {
                        "params": "EgZ2aWRlb3PyBgQKAjoA"
                      }
                    }
                  }
                }
              ]
            }
            """.trimIndent(),
        )

        val selected = findSelectedYouTubeTab(response)

        assertNotNull(selected)
        assertNull(selected?.type)
        assertFalse(selected?.type == YouTubeHistoryTab.STREAMS)
    }

    @Test
    fun `selected videos tab is identified from current endpoint`() {
        val response = JSONObject(
            """
            {
              "tabRenderer": {
                "selected": true,
                "title": "Wideo",
                "endpoint": {
                  "commandMetadata": {
                    "webCommandMetadata": {"url": "/@DomaGorajek/videos"}
                  },
                  "browseEndpoint": {
                    "params": "EgZ2aWRlb3PyBgQKAjoA",
                    "canonicalBaseUrl": "/@DomaGorajek"
                  }
                }
              }
            }
            """.trimIndent(),
        )

        assertEquals(
            YouTubeHistoryTab.VIDEOS,
            findSelectedYouTubeTab(response)?.type,
        )
    }

    @Test
    fun `channel without live tab does not expose streams`() {
        val root = JSONObject(
            """
            {
              "tabs": [
                {
                  "tabRenderer": {
                    "tabIdentifier": "videos",
                    "endpoint": {
                      "browseEndpoint": {
                        "params": "EgZ2aWRlb3PyBgQKAjoA",
                        "canonicalBaseUrl": "/@DomaGorajek/videos"
                      }
                    }
                  }
                },
                {
                  "tabRenderer": {
                    "tabIdentifier": "shorts",
                    "endpoint": {
                      "browseEndpoint": {
                        "params": "EgZzaG9ydHPyBgUKA5oBAA%3D%3D",
                        "canonicalBaseUrl": "/@DomaGorajek/shorts"
                      }
                    }
                  }
                }
              ]
            }
            """.trimIndent(),
        )

        val tabs = extractAvailableYouTubeTabs(root)

        assertTrue(YouTubeHistoryTab.VIDEOS in tabs)
        assertTrue(YouTubeHistoryTab.SHORTS in tabs)
        assertFalse(YouTubeHistoryTab.STREAMS in tabs)
        assertEquals(
            "EgZzaG9ydHPyBgUKA5oBAA==",
            tabs[YouTubeHistoryTab.SHORTS],
        )
    }

    @Test
    fun `current lockup video is parsed as film only on videos tab`() {
        val now = 1_800_000_000_000L
        val response = lockupPage(
            videoId = "lU4H50GMJyI",
            title = "Wiedz, że coś się dzieje",
            publishedText = "2 dni temu",
        )
        val client = YouTubeHistoryClient(HttpTextClient())

        val videoPage = client.parsePage(
            json = response,
            tab = YouTubeHistoryTab.VIDEOS,
            apiKey = "test",
            clientVersion = "test",
            nowMillis = now,
            previousCursorToken = null,
        )
        val streamPage = client.parsePage(
            json = response,
            tab = YouTubeHistoryTab.STREAMS,
            apiKey = "test",
            clientVersion = "test",
            nowMillis = now,
            previousCursorToken = null,
        )

        assertEquals(1, videoPage.items.size)
        assertEquals("lU4H50GMJyI", videoPage.items.single().entry.id)
        assertEquals("Wiedz, że coś się dzieje", videoPage.items.single().entry.title)
        assertEquals(VideoKind.VIDEO, videoPage.items.single().kind)
        assertEquals(VideoKind.STREAM_ARCHIVE, streamPage.items.single().kind)
    }

    @Test
    fun `playlist lockup is ignored`() {
        val client = YouTubeHistoryClient(HttpTextClient())
        val response = JSONObject(
            """
            {
              "tabRenderer": {
                "selected": true,
                "content": {
                  "lockupViewModel": {
                    "contentId": "PL0123456789",
                    "contentType": "LOCKUP_CONTENT_TYPE_PLAYLIST"
                  }
                }
              }
            }
            """.trimIndent(),
        )

        val page = client.parsePage(
            json = response,
            tab = YouTubeHistoryTab.VIDEOS,
            apiKey = "test",
            clientVersion = "test",
            nowMillis = 1_800_000_000_000L,
            previousCursorToken = null,
        )

        assertTrue(page.items.isEmpty())
    }

    @Test
    fun `only selected tab content is returned`() {
        val root = JSONObject(
            """
            {
              "tabs": [
                {
                  "tabRenderer": {
                    "selected": false,
                    "content": {"videoRenderer":{"videoId":"wrongLive01"}}
                  }
                },
                {
                  "tabRenderer": {
                    "selected": true,
                    "content": {"videoRenderer":{"videoId":"currentFilm"}}
                  }
                }
              ]
            }
            """.trimIndent(),
        )

        val selected = selectedYouTubeTabContent(root)
        assertNotNull(selected)
        val serialized = selected.toString()
        assertTrue(serialized.contains("currentFilm"))
        assertFalse(serialized.contains("wrongLive01"))
    }

    @Test
    fun `navigation command cannot replace next page cursor`() {
        val response = JSONObject(
            """
            {
              "navigation":{"continuationCommand":{"token":"OLD_TOKEN"}},
              "onResponseReceivedActions":[{
                "appendContinuationItemsAction":{
                  "continuationItems":[{
                    "continuationItemRenderer":{
                      "continuationEndpoint":{
                        "continuationCommand":{"token":"NEW_TOKEN"}
                      }
                    }
                  }]
                }
              }]
            }
            """.trimIndent(),
        )

        assertEquals(
            "NEW_TOKEN",
            findYouTubeContinuationToken(response, previousToken = "OLD_TOKEN"),
        )
    }

    @Test
    fun `echoed previous renderer token is skipped`() {
        val response = JSONObject(
            """
            {
              "continuationItems":[
                {
                  "continuationItemRenderer":{
                    "continuationEndpoint":{
                      "continuationCommand":{"token":"OLD_TOKEN"}
                    }
                  }
                },
                {
                  "continuationItemRenderer":{
                    "continuationEndpoint":{
                      "continuationCommand":{"token":"NEW_TOKEN"}
                    }
                  }
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(
            "NEW_TOKEN",
            findYouTubeContinuationToken(response, previousToken = "OLD_TOKEN"),
        )
    }

    @Test
    fun `generic continuation command alone is ignored`() {
        val response = JSONObject(
            """{"navigation":{"continuationCommand":{"token":"WRONG_TOKEN"}}}""",
        )

        assertNull(findYouTubeContinuationToken(response))
    }

    @Test
    fun `legacy next continuation data is accepted`() {
        val response = JSONObject(
            """{"nextContinuationData":{"continuation":"LEGACY_NEXT"}}""",
        )

        assertEquals("LEGACY_NEXT", findYouTubeContinuationToken(response))
    }

    @Test
    fun `echoed pagination cursor without successor means exhausted page`() {
        val response = JSONObject(
            """
            {
              "continuationItemRenderer":{
                "continuationEndpoint":{
                  "continuationCommand":{"token":"SAME_TOKEN"}
                }
              }
            }
            """.trimIndent(),
        )

        assertNull(findYouTubeContinuationToken(response, previousToken = "SAME_TOKEN"))
    }

    private fun lockupPage(
        videoId: String,
        title: String,
        publishedText: String,
    ): JSONObject = JSONObject(
        """
        {
          "tabRenderer": {
            "selected": true,
            "content": {
              "richGridRenderer": {
                "contents": [
                  {
                    "richItemRenderer": {
                      "content": {
                        "lockupViewModel": {
                          "contentId": "$videoId",
                          "contentType": "LOCKUP_CONTENT_TYPE_VIDEO",
                          "metadata": {
                            "lockupMetadataViewModel": {
                              "title": {"content": "$title"},
                              "metadata": {
                                "contentMetadataViewModel": {
                                  "metadataRows": [
                                    {
                                      "metadataParts": [
                                        {"text": {"content": "123 wyświetlenia"}},
                                        {"text": {"content": "$publishedText"}}
                                      ]
                                    }
                                  ]
                                }
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                ]
              }
            }
          }
        }
        """.trimIndent(),
    )
}
