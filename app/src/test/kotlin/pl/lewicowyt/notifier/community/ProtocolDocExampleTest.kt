package pl.lewicowyt.notifier.community

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * „Pełny przykład" z dokumentacji protokołu (Protokół serwera społeczności lewicowYT.md i
 * lewicowYT-pages/protokol-serwera.html) musi przechodzić przez prawdziwy parser bez utraty pozycji.
 * Zmieniasz przykład w dokumentacji — zmień go też tutaj.
 */
class ProtocolDocExampleTest {
    private val source = "https://twojserwer.example:2137/manifest.json"

    private val manifestJson = """
        {
          "contractVersion": 1,
          "name": "Kolektyw Przykładowy",
          "description": "Posty, artykuły i memy kolektywu.",
          "transport": "https",
          "canBePrimary": true,
          "capabilities": ["youtube_community_posts", "media_publications", "memes", "foreign_platform_items"],
          "terms": {
            "version": "2",
            "text": "Regulamin. Serwer tylko wysyła treści i nie zbiera Twoich danych. Akceptując, zgadzasz się na ich pobieranie i wyświetlanie."
          },
          "altEndpoints": ["http://192.168.0.10:2137/manifest.json"],
          "linkPlatforms": [
            { "id": "mastodon", "name": "Mastodon", "hosts": ["mastodon.social"] }
          ],
          "push": {
            "register": "https://twojserwer.example/push/register",
            "unregister": "https://twojserwer.example/push/unregister"
          },
          "primaryFeed": {
            "creators": ["UCXXXXXXXXXXXXXXXXXXXXXX"],
            "videos": [
              { "channelId": "UCXXXXXXXXXXXXXXXXXXXXXX", "videoId": "dQw4w9WgXcQ", "publishedAtMillis": 1761980400000 }
            ]
          },
          "content": ["post/2026-11-01-a1b2.json", "post/2026-11-01-c3d4.json"],
          "contentVersions": { "post/2026-11-01-a1b2.json": "2", "post/2026-11-01-c3d4.json": "1" },
          "deletions": ["https://twojserwer.example/p/usuniety-wpis"]
        }
    """.trimIndent()

    private val contentFileJson = """
        {
          "posts": [
            {
              "author": "Kolektyw Przykładowy",
              "text": "Nowy odcinek podcastu.",
              "url": "https://twojserwer.example/p/odcinek-12",
              "update": "significant",
              "publishedAtMillis": 1761980400000,
              "audio": { "url": "https://twojserwer.example/media/odcinek-12.opus", "durationMs": 1834000, "title": "Odcinek 12" }
            }
          ],
          "memes": [
            {
              "title": "Mem tygodnia",
              "source": "Kolektyw Przykładowy",
              "url": "https://twojserwer.example/m/42",
              "publishedAtMillis": 1761984000000,
              "images": [
                { "imageUrl": "https://twojserwer.example/media/m42-1.jxl", "notifyImageUrl": "https://twojserwer.example/media/m42-1.jpg", "imageQuality": 80 },
                { "imageUrl": "https://twojserwer.example/media/m42-2.jxl", "imageQuality": 80 }
              ]
            }
          ],
          "articles": [
            {
              "kind": "article",
              "source": "Kolektyw Przykładowy",
              "title": "Relacja z protestu",
              "url": "https://twojserwer.example/a/protest",
              "text": "Krótka zajawka relacji.",
              "publishedAtMillis": 1761987600000,
              "video": {
                "hevc": { "url": "https://twojserwer.example/media/protest-h265.mp4", "width": 1280, "height": 720, "fps": 30, "bytes": 4200000 },
                "avc":  { "url": "https://twojserwer.example/media/protest-h264.mp4", "width": 854, "height": 480, "fps": 30, "bytes": 3100000 },
                "durationMs": 95000,
                "title": "Relacja z protestu"
              }
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `full example manifest from protocol doc parses completely`() {
        val manifest = CommunityManifestParser.parse(manifestJson, source)
        assertNotNull(manifest)
        manifest!!
        assertTrue(manifest.canBePrimary)
        assertEquals(4, manifest.capabilities.size)
        assertNotNull(manifest.push)
        // J-02: dokument podaje pełny adres LAN (ze ścieżką) — zostaje bez zmian po normalizacji
        assertEquals(listOf("http://192.168.0.10:2137/manifest.json"), manifest.altEndpoints)
        assertEquals(1, manifest.linkPlatforms.size)
        assertEquals(1, manifest.primaryFeed?.creators?.size)
        assertEquals(1, manifest.primaryFeed?.videos?.size)
        assertEquals(
            listOf(CommunityContentFile("post/2026-11-01-a1b2.json", "2"), CommunityContentFile("post/2026-11-01-c3d4.json", "1")),
            manifest.contentFiles,
        )
        // wymóg protokołu: manifest nie niesie deletions (ani treści) — przykład celowo je tu ma, parser je ignoruje
        assertTrue(manifest.deletions.isEmpty())
    }

    @Test
    fun `full example content file from protocol doc keeps every item and media`() {
        val manifest = CommunityManifestParser.parse(manifestJson, source)!!
        val file = CommunityManifestParser.parseContentFile(contentFileJson, manifest, source)!!
        assertEquals(1, file.posts.size)
        assertNotNull(file.posts.single().audio)
        assertEquals(1, file.memes.size)
        assertEquals(2, file.memes.single().images.size)
        assertEquals(1, file.articles.size)
        val video = file.articles.single().video
        assertNotNull(video?.hevc)
        assertNotNull(video?.avc)
    }
}
