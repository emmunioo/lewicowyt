package pl.lewicowyt.notifier.community

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * kodek magazynu treści: normalizacja autora (jeden wpis na twórcę/źródło) + round-trip
 * pozycji przez ściśnięty blob. Na JVM (bez natywnego ZSTD) blob schodzi do UTF-8, więc
 * round-trip i tak przechodzi; ścieżkę ZSTD sprawdza test instrumentalny.
 */
class CommunityContentCodecTest {

    private val serverId = "srv1"

    private fun manifest(
        posts: List<CommunityPost> = emptyList(),
        foreign: List<CommunityForeignItem> = emptyList(),
        articles: List<CommunityArticle> = emptyList(),
        ads: List<CommunityLinkItem> = emptyList(),
        creators: List<CommunityCreator> = emptyList(),
    ) = CommunityManifest(
        contractVersion = 1,
        name = "S",
        description = null,
        transport = CommunityTransportKind.HTTPS_POLL,
        capabilities = emptySet(),
        terms = CommunityTerms.of("1", "R"),
        creators = creators,
        foreignItems = foreign,
        posts = posts,
        ads = ads,
        contests = emptyList(),
        articles = articles,
    )

    @Test
    fun `author id is stable and case insensitive per platform`() {
        assertEquals(
            communityAuthorId("srv1", "youtube", "Ralindel"),
            communityAuthorId("srv1", "youtube", "ralindel  "),
        )
        assertTrue(
            communityAuthorId("srv1", "youtube", "Ralindel") != communityAuthorId("srv1", "twitter", "Ralindel"),
        )
    }

    @Test
    fun `R-02 author id is isolated per server`() {
        // ten sam autor na dwóch serwerach -> RÓŻNE id (serwer B nie nadpisze wiersza/awatara autora serwera A)
        assertTrue(
            communityAuthorId("srvA", "youtube", "Ralindel") != communityAuthorId("srvB", "youtube", "Ralindel"),
        )
    }

    @Test
    fun `same youtuber across items is stored once in the author dictionary`() {
        val stored = CommunityContentCodec.toStored(
            serverId,
            manifest(
                posts = listOf(
                    CommunityPost("Ralindel", "https://s/a.jxl", "post", "pierwszy", "https://youtube.com/post/1", null, null, publishedAtMillis = 10L),
                    CommunityPost("Ralindel", "https://s/a.jxl", "post", "drugi", "https://youtube.com/post/2", null, null, publishedAtMillis = 20L),
                ),
            ),
        )
        // dwa posty, ale jeden autor w słowniku
        assertEquals(2, stored.items.size)
        assertEquals(1, stored.authors.size)
        assertEquals("Ralindel", stored.authors[0].name)
        assertEquals("youtube", stored.authors[0].platform)
    }

    @Test
    fun `audio attachment survives the stored form`() {
        val post = CommunityPost(
            author = "Ralindel", authorImageUrl = null, type = null, text = "z dźwiękiem", url = null,
            imageUrl = null, publishedAtMillis = 1L,
            audio = CommunityAudio("https://s/media/a.opus", durationMs = 61_000L, title = "Odcinek 1"),
        )
        val back = CommunityContentCodec.fromStored(joinAuthors(CommunityContentCodec.toStored(serverId, manifest(posts = listOf(post)))))
        assertEquals(CommunityAudio("https://s/media/a.opus", 61_000L, "Odcinek 1"), back.posts.single().audio)
    }

    @Test
    fun `video attachment survives the stored form`() {
        val video = CommunityVideo(
            hevc = CommunityVideoVariant("https://s/media/v.mp4", 1280, 720, 30f, 123_456L),
            avc = CommunityVideoVariant("https://s/media/v-264.mp4", 854, 480, 25f),
            durationMs = 4_000L,
            title = "Film",
        )
        val post = CommunityPost(
            author = "Ralindel", authorImageUrl = null, type = null, text = "z filmem", url = null,
            imageUrl = null, publishedAtMillis = 1L, video = video,
        )
        val back = CommunityContentCodec.fromStored(joinAuthors(CommunityContentCodec.toStored(serverId, manifest(posts = listOf(post)))))
        assertEquals(video, back.posts.single().video)
    }

    @Test
    fun `post round trips through stored form with author from dictionary`() {
        val post = CommunityPost(
            author = "Ralindel",
            authorImageUrl = "https://s/a.jxl",
            type = "post",
            text = "treść posta społeczności",
            url = "https://youtube.com/post/abc",
            imageUrl = "https://s/p.jxl",
            notifyImageUrl = "https://s/p.jpg",
            publishedAtMillis = 123L,
            update = CommunityUpdateKind.SIGNIFICANT,
        )
        val stored = CommunityContentCodec.toStored(serverId, manifest(posts = listOf(post)))
        // symulujemy odczyt z bazy: dołączenie danych autora ze słownika (LEFT JOIN)
        val joined = joinAuthors(stored)
        val back = CommunityContentCodec.fromStored(joined)
        assertEquals(1, back.posts.size)
        val r = back.posts[0]
        assertEquals("Ralindel", r.author)
        assertEquals("https://s/a.jxl", r.authorImageUrl)
        assertEquals("treść posta społeczności", r.text)
        assertEquals("https://s/p.jpg", r.notifyImageUrl)
        assertEquals(CommunityUpdateKind.SIGNIFICANT, r.update)
        assertEquals(123L, r.publishedAtMillis)
    }

    @Test
    fun `article and foreign item round trip`() {
        val stored = CommunityContentCodec.toStored(
            serverId,
            manifest(
                foreign = listOf(
                    CommunityForeignItem("twitter", "@x", "tweet", null, "wpis", "https://x.com/x/status/9", null, null, publishedAtMillis = 5L),
                ),
                articles = listOf(
                    CommunityArticle(CommunityArticleKind.NEWSLETTER, "OKO.press", "Red.", "Tytuł", "zajawka", "https://oko.press/n/1", null, null, publishedAtMillis = 6L),
                ),
            ),
        )
        val back = CommunityContentCodec.fromStored(joinAuthors(stored))
        assertEquals(1, back.foreignItems.size)
        assertEquals("twitter", back.foreignItems[0].platform)
        assertEquals("@x", back.foreignItems[0].author)
        assertEquals(1, back.articles.size)
        assertEquals(CommunityArticleKind.NEWSLETTER, back.articles[0].kind)
        assertEquals("OKO.press", back.articles[0].source)
        assertEquals("Red.", back.articles[0].author)
        assertEquals("Tytuł", back.articles[0].title)
    }

    @Test
    fun `link item without author round trips and empty content is empty`() {
        val stored = CommunityContentCodec.toStored(
            serverId,
            manifest(ads = listOf(CommunityLinkItem(CommunityLinkKind.AD, "Sponsor", null, "https://s/ad", false, null, null, publishedAtMillis = 1L))),
        )
        assertTrue(stored.authors.isEmpty())
        val back = CommunityContentCodec.fromStored(joinAuthors(stored))
        assertEquals(1, back.ads.size)
        assertEquals("Sponsor", back.ads[0].title)

        assertTrue(CommunityContentCodec.fromStored(emptyList()).isEmpty)
        assertNull(back.ads[0].imageUrl)
    }

    /** odwzorowuje LEFT JOIN z bazy: dokleja nazwę/avatar/platformę autora do pozycji. */
    private fun joinAuthors(stored: CommunityStoredContent): List<CommunityStoredItem> {
        val byId = stored.authors.associateBy { it.authorId }
        return stored.items.map { item ->
            val a = item.authorId?.let { byId[it] }
            item.copy(authorName = a?.name, authorPlatform = a?.platform, authorAvatarUrl = a?.avatarUrl)
        }
    }
}
