package pl.lewicowyt.notifier.network

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import pl.lewicowyt.notifier.model.PublishedAtEvidence
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
    fun `only a real browse renderer is accepted as complete tab list`() {
        val complete = JSONObject(
            """
            {
              "contents": {
                "twoColumnBrowseResultsRenderer": {
                  "tabs": [{
                    "tabRenderer": {
                      "selected": true,
                      "tabIdentifier": "videos",
                      "endpoint": {"browseEndpoint": {
                        "browseId": "UCaaaaaaaaaaaaaaaaaaaaaa",
                        "params": "videos"
                      }}
                    }
                  }]
                }
              }
            }
            """.trimIndent(),
        )
        val partial = JSONObject(
            """
            {"recommendations":{"tabs":[{
              "tabRenderer": {
                "tabIdentifier": "videos",
                "endpoint": {"browseEndpoint": {"params": "videos"}}
              }
            }]}}
            """.trimIndent(),
        )

        assertEquals(
            setOf(YouTubeHistoryTab.VIDEOS),
            extractCompleteYouTubeChannelTabs(
                complete,
                expectedBrowseId = "UCaaaaaaaaaaaaaaaaaaaaaa",
            )?.keys,
        )
        assertNull(
            extractCompleteYouTubeChannelTabs(
                partial,
                expectedBrowseId = "UCaaaaaaaaaaaaaaaaaaaaaa",
            ),
        )
    }

    @Test
    fun `incomplete tab endpoint cannot mark another tab absent`() {
        val root = JSONObject(
            """
            {"twoColumnBrowseResultsRenderer":{"tabs":[{
              "tabRenderer": {
                "selected": true,
                "tabIdentifier": "shorts",
                "endpoint":{"browseEndpoint":{
                  "browseId":"UCaaaaaaaaaaaaaaaaaaaaaa"
                }}
              }
            }]}}
            """.trimIndent(),
        )

        assertNull(
            extractCompleteYouTubeChannelTabs(
                root,
                expectedBrowseId = "UCaaaaaaaaaaaaaaaaaaaaaa",
            ),
        )
    }

    @Test
    fun `canonical base url identifies tab when internal params change`() {
        val root = JSONObject(
            """
            {
              "tabRenderer":{
                "endpoint":{
                  "browseEndpoint":{
                    "params":"NEW_INTERNAL_VALUE",
                    "canonicalBaseUrl":"/@kanal/shorts"
                  }
                }
              }
            }
            """.trimIndent(),
        )

        assertEquals(
            "NEW_INTERNAL_VALUE",
            extractAvailableYouTubeTabs(root)[YouTubeHistoryTab.SHORTS],
        )
    }

    @Test
    fun `decoding tab params preserves literal plus`() {
        assertEquals("abc+def==", decodeTabParams("abc+def%3D%3D"))
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
    fun `polish weeks are never parsed as days`() {
        val now = 1_785_354_360_000L // 2026-07-29T19:46:00Z
        val response = lockupPage(
            videoId = "j4mc2vj4LFg",
            title = "Mentzen i jego ODERWANIE OD RZECZYWISTOŚCI",
            publishedText = "3 tygodnie temu",
        )

        val item = YouTubeHistoryClient(HttpTextClient()).parsePage(
            json = response,
            tab = YouTubeHistoryTab.VIDEOS,
            apiKey = "test",
            clientVersion = "test",
            nowMillis = now,
            previousCursorToken = null,
        ).items.single()

        assertEquals(now - 21L * 24L * 60L * 60L * 1_000L, item.entry.publishedAtMillis)
        assertEquals(PublishedAtEvidence.WEB_RELATIVE, item.publishedAtEvidence)
    }

    @Test
    fun `unrelated playlist wording is not accepted as a publication age`() {
        val response = lockupPage(
            videoId = "j4mc2vj4LFg",
            title = "Materiał",
            publishedText = "2 filmy na playliście",
        )

        val page = YouTubeHistoryClient(HttpTextClient()).parsePage(
            json = response,
            tab = YouTubeHistoryTab.VIDEOS,
            apiKey = "test",
            clientVersion = "test",
            nowMillis = 1_785_354_360_000L,
            previousCursorToken = null,
        )

        assertTrue(page.items.isEmpty())
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
    fun `short membership is preserved even when tile has no publication date`() {
        val response = JSONObject(
            """
            {
              "tabRenderer": {
                "selected": true,
                "content": {
                  "shortsLockupViewModel": {
                    "onTap": {
                      "innertubeCommand": {
                        "reelWatchEndpoint": {"videoId":"Oj0-9Ks6d7k"}
                      }
                    },
                    "overlayMetadata": {
                      "primaryText": {"content":"OZE zapewni Polsce bezpieczeństwo."}
                    }
                  }
                }
              }
            }
            """.trimIndent(),
        )
        val page = YouTubeHistoryClient(HttpTextClient()).parsePage(
            json = response,
            tab = YouTubeHistoryTab.SHORTS,
            apiKey = "test",
            clientVersion = "test",
            nowMillis = 1_800_000_000_000L,
            previousCursorToken = null,
        )

        assertTrue(page.items.isEmpty())
        assertEquals(VideoKind.SHORT, page.membershipKinds["Oj0-9Ks6d7k"])
    }

    @Test
    fun `missing selected content never scans unrelated root renderers`() {
        val response = JSONObject(
            """
            {
              "videoRenderer":{
                "videoId":"wrongLive01",
                "title":{"simpleText":"Obcy materiał"},
                "publishedTimeText":{"simpleText":"2 dni temu"}
              },
              "tabRenderer":{"selected":true}
            }
            """.trimIndent(),
        )
        val page = YouTubeHistoryClient(HttpTextClient()).parsePage(
            json = response,
            tab = YouTubeHistoryTab.STREAMS,
            apiKey = "test",
            clientVersion = "test",
            nowMillis = 1_800_000_000_000L,
            previousCursorToken = null,
        )

        assertTrue(page.items.isEmpty())
        assertTrue(page.membershipKinds.isEmpty())
    }

    @Test
    fun `continuation reads append container but ignores foreign root renderer`() {
        val response = JSONObject(
            """
            {
              "videoRenderer":{
                "videoId":"wrongLive01",
                "title":{"simpleText":"Obcy materiał"},
                "publishedTimeText":{"simpleText":"2 dni temu"}
              },
              "onResponseReceivedActions":[{
                "appendContinuationItemsAction":{
                  "continuationItems":[{
                    "videoRenderer":{
                      "videoId":"currentFilm",
                      "title":{"simpleText":"Właściwy materiał"},
                      "publishedTimeText":{"simpleText":"2 dni temu"}
                    }
                  }]
                }
              }]
            }
            """.trimIndent(),
        )
        val page = YouTubeHistoryClient(HttpTextClient()).parsePage(
            json = response,
            tab = YouTubeHistoryTab.VIDEOS,
            apiKey = "test",
            clientVersion = "test",
            nowMillis = 1_800_000_000_000L,
            previousCursorToken = "OLD_TOKEN",
        )

        assertEquals(listOf("currentFilm"), page.items.map { it.entry.id })
        assertEquals(setOf("currentFilm"), page.membershipKinds.keys)
    }

    @Test
    fun `unknown continuation shape is rejected instead of scanning whole response`() {
        val client = YouTubeHistoryClient(HttpTextClient())

        assertThrows(java.io.IOException::class.java) {
            client.parsePage(
                json = JSONObject("""{"videoRenderer":{"videoId":"wrongLive01"}}"""),
                tab = YouTubeHistoryTab.VIDEOS,
                apiKey = "test",
                clientVersion = "test",
                nowMillis = 1_800_000_000_000L,
                previousCursorToken = "OLD_TOKEN",
            )
        }
    }

    @Test
    fun `empty known continuation container is a valid terminal page`() {
        val scope = youtubeContinuationContent(
            JSONObject(
                """{"appendContinuationItemsAction":{"continuationItems":[]}}""",
            ),
        )

        assertNotNull(scope)
        assertEquals(0, scope?.length())
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
