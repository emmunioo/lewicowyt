package pl.lewicowyt.notifier.community

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * manifest serwera społeczności jest niezaufany i wersjonowany: bez regulaminu,
 * z nieznanym transportem albo z obcym kontraktem serwer po prostu nie wchodzi,
 * a nieznane zdolności są pomijane bez błędu.
 */
class CommunityManifestParserTest {

    /** treść w manifeście jest ignorowana (wymóg protokołu) — test czyta ją więc tak, jak z pliku treści */
    private fun parseInline(json: String, sourceEndpoint: String? = null): CommunityManifest? {
        val m = CommunityManifestParser.parse(json, sourceEndpoint) ?: return null
        return CommunityManifestParser.parseContentFile(json, m, sourceEndpoint)?.copy(contentFiles = m.contentFiles)
    }

    private fun manifest(
        contract: Int = 1,
        transport: String = "https-poll",
        name: String = "Serwer testowy",
        capabilities: String = """["community_creators", "foreign_platforms"]""",
        terms: String = """{ "version": "3", "text": "Regulamin serwera. Bądź grzeczny." }""",
    ) = """
        {
          "contractVersion": $contract,
          "name": "$name",
          "description": "opis",
          "transport": "$transport",
          "capabilities": $capabilities,
          "terms": $terms
        }
    """.trimIndent()

    @Test
    fun `manifest never carries content - inline lists and deletions are ignored`() {
        val json = """
            {
              "contractVersion": 1, "name": "S", "transport": "https",
              "capabilities": ["youtube_community_posts", "memes", "community_creators"],
              "terms": { "version": "1", "text": "Regulamin." },
              "posts": [ { "author": "A", "text": "t", "url": "https://www.youtube.com/post/P1" } ],
              "memes": [ { "title": "m", "source": "s", "url": "https://serwer.example/m/1", "images": [] } ],
              "creators": [ { "id": "c", "name": "C", "channelUrl": "https://www.youtube.com/@c" } ],
              "deletions": [ "https://www.youtube.com/post/P0" ],
              "content": ["post/1.json"]
            }
        """.trimIndent()
        val parsed = CommunityManifestParser.parse(json, "https://serwer.example/manifest.json")!!
        assertTrue(parsed.posts.isEmpty())
        assertTrue(parsed.memes.isEmpty())
        assertTrue(parsed.creators.isEmpty())
        assertTrue(parsed.deletions.isEmpty())
        assertEquals(listOf("post/1.json"), parsed.contentFiles.map { it.name })
    }

    @Test
    fun `valid manifest parses with known capabilities`() {
        val parsed = parseInline(manifest())
        assertNotNull(parsed)
        parsed!!
        assertEquals("Serwer testowy", parsed.name)
        assertEquals(CommunityTransportKind.HTTPS_POLL, parsed.transport)
        assertEquals(
            setOf(CommunityCapability.COMMUNITY_CREATORS, CommunityCapability.FOREIGN_PLATFORM_ITEMS),
            parsed.capabilities,
        )
        assertEquals("3", parsed.terms.versionLabel)
    }

    @Test
    fun `unknown transport rejects the whole manifest`() {
        assertNull(parseInline(manifest(transport = "carrier-pigeon")))
    }

    @Test
    fun `all three known transports parse`() {
        assertEquals(
            CommunityTransportKind.FCM,
            parseInline(manifest(transport = "fcm"))?.transport,
        )
        assertEquals(
            CommunityTransportKind.HTTPS_POLL,
            parseInline(manifest(transport = "https"))?.transport,
        )
        assertEquals(
            CommunityTransportKind.ONION_POLL,
            parseInline(manifest(transport = "onion"))?.transport,
        )
    }

    @Test
    fun `future contract version is rejected`() {
        assertNull(parseInline(manifest(contract = 2)))
    }

    @Test
    fun `missing terms rejects the manifest`() {
        val json = """
            {
              "contractVersion": 1,
              "name": "Serwer",
              "transport": "https"
            }
        """.trimIndent()
        assertNull(parseInline(json))
    }

    @Test
    fun `blank terms text rejects the manifest`() {
        assertNull(parseInline(manifest(terms = """{ "text": "   " }""")))
    }

    @Test
    fun `unknown capabilities are skipped, known ones survive`() {
        val parsed = parseInline(
            manifest(capabilities = """["community_creators", "hologram_feed", "youtube_community_posts"]"""),
        )
        assertEquals(
            setOf(CommunityCapability.COMMUNITY_CREATORS, CommunityCapability.YOUTUBE_COMMUNITY_POSTS),
            parsed?.capabilities,
        )
    }

    @Test
    fun `malformed json returns null instead of throwing`() {
        assertNull(parseInline("{ this is not json"))
        assertNull(parseInline(""))
    }

    @Test
    fun `terms hash is stable for identical text and differs after a change`() {
        val a = parseInline(manifest(terms = """{ "text": "Ten sam regulamin." }"""))!!
        val b = parseInline(manifest(terms = """{ "text": "Ten sam regulamin." }"""))!!
        val c = parseInline(manifest(terms = """{ "text": "Ten sam regulamin!" }"""))!!
        assertEquals(a.terms.contentHash, b.terms.contentHash)
        assertTrue(a.terms.contentHash != c.terms.contentHash)
    }

    @Test
    fun `server id comes from the endpoint, not the manifest`() {
        // dwa różne serwery z identyczną nazwą w manifeście dostają różne id po endpointcie
        assertTrue(
            communityServerId("https://a.example/manifest") !=
                communityServerId("https://b.example/manifest"),
        )
        // ten sam endpoint = stabilne id
        assertEquals(
            communityServerId("https://a.example/manifest"),
            communityServerId("https://a.example/manifest"),
        )
    }

    @Test
    fun `canBePrimary defaults to false and parses true`() {
        assertFalse(parseInline(manifest())!!.canBePrimary)
        val json = """
            {
              "contractVersion": 1, "name": "S", "transport": "https",
              "canBePrimary": true, "terms": { "text": "R" }
            }
        """.trimIndent()
        assertTrue(parseInline(json)!!.canBePrimary)
    }

    @Test
    fun `content is parsed only for declared capabilities`() {
        val declared = parseInline(
            """
            {
              "contractVersion": 1, "name": "S", "transport": "https",
              "capabilities": ["community_creators"],
              "terms": { "text": "R" },
              "creators": [
                { "id": "c1", "name": "Twórca", "channelUrl": "https://youtube.com/@x" },
                { "id": "", "name": "bez id" }
              ]
            }
            """.trimIndent(),
        )!!
        assertEquals(1, declared.creators.size)
        assertEquals("Twórca", declared.creators[0].name)
        assertEquals("https://youtube.com/@x", declared.creators[0].channelUrl)

        val undeclared = parseInline(
            """
            {
              "contractVersion": 1, "name": "S", "transport": "https",
              "capabilities": [],
              "terms": { "text": "R" },
              "creators": [ { "id": "c1", "name": "Twórca" } ]
            }
            """.trimIndent(),
        )!!
        assertTrue(undeclared.creators.isEmpty())
    }

    @Test
    fun `non https creator url is dropped but the creator stays`() {
        val parsed = parseInline(
            """
            {
              "contractVersion": 1, "name": "S", "transport": "https",
              "capabilities": ["community_creators"], "terms": { "text": "R" },
              "creators": [ { "id": "c1", "name": "T", "channelUrl": "http://x" } ]
            }
            """.trimIndent(),
        )!!
        assertEquals(1, parsed.creators.size)
        assertNull(parsed.creators[0].channelUrl)
    }

    @Test
    fun `foreign items need a platform and some content`() {
        val parsed = parseInline(
            """
            {
              "contractVersion": 1, "name": "S", "transport": "https",
              "capabilities": ["foreign_platform_items"], "terms": { "text": "R" },
              "foreignItems": [
                { "platform": "twitter", "text": "cześć", "url": "https://x.example/1" },
                { "platform": "", "text": "brak platformy" },
                { "platform": "twitter" }
              ]
            }
            """.trimIndent(),
        )!!
        assertEquals(1, parsed.foreignItems.size)
        assertEquals("twitter", parsed.foreignItems[0].platform)
        assertEquals("https://x.example/1", parsed.foreignItems[0].url)
    }

    @Test
    fun `posts require non blank text`() {
        val parsed = parseInline(
            """
            {
              "contractVersion": 1, "name": "S", "transport": "https",
              "capabilities": ["youtube_community_posts"], "terms": { "text": "R" },
              "posts": [ { "author": "A", "text": "post" }, { "author": "B", "text": "   " } ]
            }
            """.trimIndent(),
        )!!
        assertEquals(1, parsed.posts.size)
        assertEquals("post", parsed.posts[0].text)
    }

    @Test
    fun `ads and contests parse only with capability and require a url`() {
        val parsed = parseInline(
            """
            {
              "contractVersion": 1, "name": "S", "transport": "https",
              "capabilities": ["ads", "contests"], "terms": { "text": "R" },
              "ads": [
                { "title": "Sponsor", "url": "https://sponsor.example" },
                { "title": "bez url — odrzucony" }
              ],
              "contests": [ { "title": "Konkurs", "url": "https://c.example", "thirdParty": true } ]
            }
            """.trimIndent(),
        )!!
        assertEquals(1, parsed.ads.size)
        assertEquals(CommunityLinkKind.AD, parsed.ads[0].kind)
        assertEquals("https://sponsor.example", parsed.ads[0].url)
        assertEquals(1, parsed.contests.size)
        assertEquals(CommunityLinkKind.CONTEST, parsed.contests[0].kind)
        assertTrue(parsed.contests[0].thirdParty)

        val noCap = parseInline(
            """
            {
              "contractVersion": 1, "name": "S", "transport": "https",
              "capabilities": [], "terms": { "text": "R" },
              "ads": [ { "title": "x", "url": "https://x.example" } ],
              "contests": [ { "title": "y", "url": "https://y.example" } ]
            }
            """.trimIndent(),
        )!!
        assertTrue(noCap.ads.isEmpty())
        assertTrue(noCap.contests.isEmpty())
    }

    @Test
    fun `only jxl avif webp images are accepted`() {
        fun imageOfPost(ext: String): String? = parseInline(
            """
            {
              "contractVersion": 1, "name": "S", "transport": "https",
              "capabilities": ["youtube_community_posts"], "terms": { "text": "R" },
              "posts": [ { "author": "A", "text": "p", "imageUrl": "https://img.example/a.$ext" } ]
            }
            """.trimIndent(),
            sourceEndpoint = "https://img.example/manifest.json", // M-06: host obrazów = host serwera
        )!!.posts[0].imageUrl

        assertEquals("https://img.example/a.jxl", imageOfPost("jxl"))
        assertEquals("https://img.example/a.avif", imageOfPost("avif"))
        assertEquals("https://img.example/a.webp", imageOfPost("webp"))
        assertNull(imageOfPost("png"))
        assertNull(imageOfPost("jpg"))
        assertNull(imageOfPost("gif"))
        assertNull(imageOfPost("mp4"))
    }

    @Test
    fun `media articles parse only with capability and require url title and source`() {
        val parsed = parseInline(
            """
            {
              "contractVersion": 1, "name": "S", "transport": "https",
              "capabilities": ["media_publications"], "terms": { "text": "R" },
              "articles": [
                { "kind": "newsletter", "source": "OKO.press", "title": "Poranny newsletter",
                  "text": "zajawka", "url": "https://oko.press/n/1" },
                { "source": "OKO.press", "title": "Artykuł bez kind", "url": "https://oko.press/a/2" },
                { "title": "bez źródła", "url": "https://oko.press/a/3" },
                { "source": "X", "title": "bez url" }
              ]
            }
            """.trimIndent(),
        )!!
        assertEquals(2, parsed.articles.size)
        assertEquals(CommunityArticleKind.NEWSLETTER, parsed.articles[0].kind)
        assertEquals("OKO.press", parsed.articles[0].source)
        assertEquals("https://oko.press/n/1", parsed.articles[0].url)
        // brak "kind" => domyślnie ARTICLE
        assertEquals(CommunityArticleKind.ARTICLE, parsed.articles[1].kind)

        val noCap = parseInline(
            """
            {
              "contractVersion": 1, "name": "S", "transport": "https",
              "capabilities": [], "terms": { "text": "R" },
              "articles": [ { "source": "X", "title": "T", "url": "https://x.example" } ]
            }
            """.trimIndent(),
        )!!
        assertTrue(noCap.articles.isEmpty())
    }

    @Test
    fun `notification image accepts jpg png but feed image does not`() {
        fun post(field: String, ext: String): CommunityPost = parseInline(
            """
            {
              "contractVersion": 1, "name": "S", "transport": "https",
              "capabilities": ["youtube_community_posts"], "terms": { "text": "R" },
              "posts": [ { "author": "A", "text": "p", "$field": "https://img.example/a.$ext" } ]
            }
            """.trimIndent(),
            sourceEndpoint = "https://img.example/manifest.json", // M-06: host obrazów = host serwera
        )!!.posts[0]

        // JXL wchodzi do feedu, ale NIE jako obraz powiadomienia
        assertEquals("https://img.example/a.jxl", post("imageUrl", "jxl").imageUrl)
        assertNull(post("notifyImageUrl", "jxl").notifyImageUrl)
        // JPG/PNG wchodzą jako obraz powiadomienia, ale NIE do feedu
        assertEquals("https://img.example/a.jpg", post("notifyImageUrl", "jpg").notifyImageUrl)
        assertEquals("https://img.example/a.png", post("notifyImageUrl", "png").notifyImageUrl)
        assertNull(post("imageUrl", "jpg").imageUrl)
    }

    @Test
    fun `image quality is parsed within 1 to 100 else null`() {
        fun q(v: String): Int? = parseInline(
            """
            {
              "contractVersion": 1, "name": "S", "transport": "https",
              "capabilities": ["youtube_community_posts"], "terms": { "text": "R" },
              "posts": [ { "author": "A", "text": "p", "imageUrl": "https://s/a.jxl", "imageQuality": $v } ]
            }
            """.trimIndent(),
        )!!.posts[0].imageQuality
        assertEquals(78, q("78"))
        assertNull(q("0"))
        assertNull(q("101"))
    }

    @Test
    fun `deletions become canonical item ids from urls`() {
        val parsed = parseInline(
            """
            {
              "contractVersion": 1, "name": "S", "transport": "https", "terms": { "text": "R" },
              "deletions": [ "https://www.youtube.com/post/OLD1", "  ", "https://x.com/u/status/9" ]
            }
            """.trimIndent(),
        )!!
        assertEquals(
            listOf("yt-post:OLD1", "tw:9"),
            parsed.deletions,
        )
    }

    @Test
    fun `auto loaded images are restricted to the server host to prevent tracking`() {
        val json = """
            {
              "contractVersion": 1, "name": "S", "transport": "https",
              "capabilities": ["youtube_community_posts"], "terms": { "text": "R" },
              "posts": [
                {
                  "author": "A", "text": "p", "url": "https://oko.press/artykul",
                  "imageUrl": "https://tracker.evil/beacon.jxl",
                  "authorImageUrl": "https://serwer.example/media/avatar.jxl"
                }
              ]
            }
        """.trimIndent()
        val parsed = parseInline(json, sourceEndpoint = "https://serwer.example/manifest.json")!!
        val post = parsed.posts.single()
        // obraz z obcego hosta (beacon) odrzucony, obraz z hosta serwera zostaje
        assertNull(post.imageUrl)
        assertEquals("https://serwer.example/media/avatar.jxl", post.authorImageUrl)
        // link treści (otwierany ręcznie przez usera) NIE jest ograniczany do hosta serwera
        assertEquals("https://oko.press/artykul", post.url)
    }

    @Test
    fun `lan server images must be on the same host (L-2 anti-SSRF)`() {
        // obraz z TEGO SAMEGO hosta LAN — dozwolony
        val same = parseInline(
            """
            {
              "contractVersion": 1, "name": "S", "transport": "https",
              "capabilities": ["youtube_community_posts"], "terms": { "text": "R" },
              "posts": [ { "author": "A", "text": "p", "imageUrl": "http://10.0.2.2:2137/media/p.jxl" } ]
            }
            """.trimIndent(),
            sourceEndpoint = "http://10.0.2.2:2137/manifest.json",
        )!!
        assertEquals("http://10.0.2.2:2137/media/p.jxl", same.posts.single().imageUrl)

        // obraz z INNEGO hosta prywatnego (router/IoT) — ODRZUCONY (ochrona przed SSRF po LAN, L-2)
        val cross = parseInline(
            """
            {
              "contractVersion": 1, "name": "S", "transport": "https",
              "capabilities": ["youtube_community_posts"], "terms": { "text": "R" },
              "posts": [ { "author": "A", "text": "p", "imageUrl": "http://192.168.0.10:2137/media/p.jxl" } ]
            }
            """.trimIndent(),
            sourceEndpoint = "http://10.0.2.2:2137/manifest.json",
        )!!
        assertNull(cross.posts.single().imageUrl)
    }

    @Test
    fun `M-08 altEndpoints accept only LAN http and reject everything else`() {
        val parsed = parseInline(
            """
            {
              "contractVersion": 1, "name": "S", "transport": "https", "terms": { "text": "R" },
              "altEndpoints": [
                "http://192.168.0.10:2137",
                "https://twojserwer.example",
                "https://10.0.2.2:2137",
                "http://tracker.evil"
              ]
            }
            """.trimIndent(),
            sourceEndpoint = "https://twojserwer.example/manifest.json",
        )!!
        // zostaje TYLKO adres LAN po http; publiczne https, https-na-LAN i obcy host — odrzucone.
        // J-02: wpis znormalizowany jak adres główny (pusta ścieżka → /manifest.json)
        assertEquals(listOf("http://192.168.0.10:2137/manifest.json"), parsed.altEndpoints)
    }

    @Test
    fun `J-02 altEndpoints get default port and manifest path, explicit path stays`() {
        val parsed = parseInline(
            """
            {
              "contractVersion": 1, "name": "S", "transport": "https", "terms": { "text": "R" },
              "altEndpoints": [ "http://192.168.0.10", "http://10.0.0.5:8080/api/m.json" ]
            }
            """.trimIndent(),
            sourceEndpoint = "https://twojserwer.example/manifest.json",
        )!!
        assertEquals(
            listOf("http://192.168.0.10:2137/manifest.json", "http://10.0.0.5:8080/api/m.json"),
            parsed.altEndpoints,
        )
    }

    @Test
    fun `audio is accepted only from the server host and only as opus ogg or webm`() {
        fun postWith(audio: String) = """{ "author": "A", "text": "t", "audio": $audio }"""
        val parsed = parseInline(
            """
            {
              "contractVersion": 1, "name": "S", "transport": "https", "terms": { "text": "R" },
              "capabilities": ["youtube_community_posts"],
              "posts": [
                ${postWith("""{ "url": "https://twojserwer.example/media/a.opus", "durationMs": 61000, "title": "Odcinek" }""")},
                ${postWith("""{ "url": "https://obcy.example/media/b.opus" }""")},
                ${postWith("""{ "url": "https://twojserwer.example/media/c.mp3" }""")},
                ${postWith("""{ "url": "https://twojserwer.example/media/d.webm", "durationMs": -5 }""")}
              ]
            }
            """.trimIndent(),
            sourceEndpoint = "https://twojserwer.example/manifest.json",
        )!!
        val audio = parsed.posts.map { it.audio }
        assertEquals(CommunityAudio("https://twojserwer.example/media/a.opus", 61_000L, "Odcinek"), audio[0])
        assertNull("obcy host — anty-beacon", audio[1])
        assertNull("MP3 nie jest obsługiwanym formatem", audio[2])
        assertEquals(CommunityAudio("https://twojserwer.example/media/d.webm", null, null), audio[3])
        assertEquals("pozycje bez poprawnego dźwięku zostają", 4, parsed.posts.size)
    }

    @Test
    fun `video variants above 30 fps or off-host are dropped`() {
        fun postWith(video: String) = """{ "author": "A", "text": "t", "video": $video }"""
        val h = "https://twojserwer.example/media"
        val parsed = parseInline(
            """
            {
              "contractVersion": 1, "name": "S", "transport": "https", "terms": { "text": "R" },
              "capabilities": ["youtube_community_posts"],
              "posts": [
                ${postWith("""{ "hevc": { "url": "$h/a.mp4", "width": 1280, "height": 720, "fps": 30 }, "avc": { "url": "$h/a-264.mp4", "fps": 25 }, "durationMs": 4000 }""")},
                ${postWith("""{ "hevc": { "url": "$h/b.mp4", "fps": 60 }, "avc": { "url": "$h/b-264.mp4", "fps": 30 } }""")},
                ${postWith("""{ "hevc": { "url": "https://obcy.example/c.mp4", "fps": 30 } }""")},
                ${postWith("""{ "hevc": { "url": "$h/d.mkv", "fps": 30 } }""")}
              ]
            }
            """.trimIndent(),
            sourceEndpoint = "https://twojserwer.example/manifest.json",
        )!!
        val v = parsed.posts.map { it.video }
        assertEquals(CommunityVideoVariant("$h/a.mp4", 1280, 720, 30f), v[0]!!.hevc)
        assertEquals(4000L, v[0]!!.durationMs)
        assertNull("60 kl/s — wariant odrzucony", v[1]!!.hevc)
        assertEquals("$h/b-264.mp4", v[1]!!.avc?.url)
        assertNull("obcy host — anty-beacon", v[2])
        assertNull("tylko MP4", v[3])
    }

    @Test
    fun `content file versions come from contentVersions map or object entries`() {
        val parsed = parseInline(
            """
            {
              "contractVersion": 1, "name": "S", "transport": "https", "terms": { "text": "R" },
              "content": ["post/a.json", { "name": "post/b.json", "v": "7" }, "post/c.json", "post/a.json"],
              "contentVersions": { "post/a.json": "3", "post/b.json": "ignored", "post/c.json": "bad\nversion" }
            }
            """.trimIndent(),
        )!!
        assertEquals(
            listOf(
                CommunityContentFile("post/a.json", "3"),
                CommunityContentFile("post/b.json", "7"), // wersja z obiektu ma pierwszeństwo
                CommunityContentFile("post/c.json", null), // wersja ze znakiem sterującym odrzucona
            ),
            parsed.contentFiles,
        )
        assertEquals("post/a.json\t3", parsed.contentFiles[0].key)
        assertEquals("post/c.json", parsed.contentFiles[2].key)
    }

    @Test
    fun `content lists are capped`() {
        val posts = (1..250).joinToString(",") { """{ "author": "A", "text": "p$it" }""" }
        val parsed = parseInline(
            """
            {
              "contractVersion": 1, "name": "S", "transport": "https",
              "capabilities": ["youtube_community_posts"], "terms": { "text": "R" },
              "posts": [ $posts ]
            }
            """.trimIndent(),
        )!!
        assertEquals(CommunityManifestParser.MAX_CONTENT_ITEMS, parsed.posts.size)
    }

    // --- Nieprywatny Tor: config FCM dla serwerów .onion ---

    private val fcmBlock = """"fcm": { "projectId": "p", "appId": "a", "apiKey": "k", "senderId": "s" }"""

    // --- sekcja 7 audytu: brakujące testy push.relay (A-05), dat (A-13), długości URL (A-14) ---

    @Test
    fun `A-05 clearnet server may use a relay on another https host`() {
        val base = "https://serwer.example"
        val parsed = parseInline(
            manifestWithPush(base, """"relay": "https://relay.other.example/"""", "https"),
            sourceEndpoint = "$base/manifest.json",
        )!!
        assertEquals("https://relay.other.example", parsed.push!!.relayBase)
    }

    @Test
    fun `A-05 clearnet server cannot point relay at onion or LAN`() {
        val base = "https://serwer.example"
        listOf("http://abcdefgh234567.onion:2137", "http://192.168.0.10:2139").forEach { relay ->
            val parsed = parseInline(
                manifestWithPush(base, """"relay": "$relay"""", "https"),
                sourceEndpoint = "$base/manifest.json",
            )!!
            assertNull(relay, parsed.push!!.relayBase)
        }
    }

    @Test
    fun `A-05 onion server relay must stay on the same onion host`() {
        val base = "http://abcdefgh234567.onion:2137"
        val same = parseInline(
            manifestWithPush(base, """"relay": "$base"""", "onion"),
            sourceEndpoint = "$base/manifest.json",
        )!!
        assertEquals(base, same.push!!.relayBase)
        val other = parseInline(
            manifestWithPush(base, """"relay": "https://relay.other.example"""", "onion"),
            sourceEndpoint = "$base/manifest.json",
        )!!
        assertNull(other.push!!.relayBase)
    }

    private fun postsManifest(postsJson: String) = """
        {
          "contractVersion": 1, "name": "S", "transport": "https",
          "capabilities": ["youtube_community_posts"], "terms": { "text": "R" },
          "posts": $postsJson
        }
    """.trimIndent()

    @Test
    fun `A-13 future publication date is clamped to now and ancient dates dropped`() {
        val farFuture = System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000
        val parsed = parseInline(
            postsManifest(
                """[ { "author": "A", "text": "f", "url": "https://www.youtube.com/post/FUT", "publishedAtMillis": $farFuture },
                     { "author": "A", "text": "o", "url": "https://www.youtube.com/post/OLD", "publishedAtMillis": 1000 } ]""",
            ),
            sourceEndpoint = "https://serwer.example/manifest.json",
        )!!
        val future = parsed.posts.first { it.url!!.endsWith("FUT") }
        assertTrue(future.publishedAtMillis!! <= System.currentTimeMillis() + 6L * 60 * 1000)
        assertNull(parsed.posts.first { it.url!!.endsWith("OLD") }.publishedAtMillis)
    }

    @Test
    fun `A-14 overlong url is dropped instead of breaking the list`() {
        val longUrl = "https://www.youtube.com/post/" + "x".repeat(5_000)
        val parsed = parseInline(
            postsManifest("""[ { "author": "A", "text": "długi", "url": "$longUrl" } ]"""),
            sourceEndpoint = "https://serwer.example/manifest.json",
        )!!
        assertTrue(parsed.posts.none { it.url == longUrl })
    }

    private fun manifestWithPush(base: String, pushExtra: String, transport: String) = """
        {
          "contractVersion": 1, "name": "S", "transport": "$transport",
          "capabilities": ["community_creators"], "terms": { "text": "R" },
          "push": { "register": "$base/register", "unregister": "$base/unregister", $pushExtra }
        }
    """.trimIndent()

    @Test
    fun `onion server without requiresNonPrivateTor never carries an fcm config`() {
        val base = "http://abcdefgh234567.onion:2137"
        val parsed = parseInline(
            manifestWithPush(base, fcmBlock, "onion"),
            sourceEndpoint = "$base/manifest.json",
        )!!
        assertNotNull(parsed.push)
        assertFalse(parsed.push!!.requiresNonPrivateTor)
        // utwardzenie: prywatny serwer .onion NIE dostaje configu FCM, nawet gdy manifest go zawierał
        assertNull(parsed.push!!.fcm)
    }

    @Test
    fun `onion server that requires non-private tor keeps its fcm config`() {
        val base = "http://abcdefgh234567.onion:2137"
        val parsed = parseInline(
            manifestWithPush(base, """"requiresNonPrivateTor": true, $fcmBlock""", "onion"),
            sourceEndpoint = "$base/manifest.json",
        )!!
        assertTrue(parsed.push!!.requiresNonPrivateTor)
        assertNotNull(parsed.push!!.fcm)
    }

    @Test
    fun `non-onion server keeps its fcm config regardless of the flag`() {
        val base = "https://serwer.example:2137"
        val parsed = parseInline(
            manifestWithPush(base, fcmBlock, "https"),
            sourceEndpoint = "$base/manifest.json",
        )!!
        assertFalse(parsed.push!!.requiresNonPrivateTor)
        assertNotNull(parsed.push!!.fcm) // clearnet: FCM działa zawsze
    }

    @Test
    fun `B-01 json null is treated as a missing value, never as text null`() {
        val parsed = parseInline(
            postsManifest(
                """[ { "author": "A", "text": "p", "type": null, "url": null, "imageUrl": null } ]""",
            ),
            sourceEndpoint = "https://serwer.example/manifest.json",
        )!!
        val post = parsed.posts.single()
        assertNull(post.type)
        assertNull(post.url)
        assertNull(post.imageUrl)
        // tekst wymagany jako null => pozycja odrzucona, a nie zapisana jako „null"
        val rejected = parseInline(
            postsManifest("""[ { "author": "A", "text": null } ]"""),
            sourceEndpoint = "https://serwer.example/manifest.json",
        )!!
        assertTrue(rejected.posts.isEmpty())
        // bezpośrednio: pomocnik nie zwraca "null" (na Androidzie zwracało to optString)
        assertEquals("", org.json.JSONObject("""{"k":null}""").optText("k"))
        assertEquals("", org.json.JSONArray("[null]").optText(0))
    }
}
