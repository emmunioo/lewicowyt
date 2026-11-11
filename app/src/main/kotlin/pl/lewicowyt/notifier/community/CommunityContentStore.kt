package pl.lewicowyt.notifier.community

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Trwały, WSPÓLNY magazyn treści serwerów społeczności — z myślą o oszczędności miejsca:
 *  - tekst każdej pozycji jest ściskany ZSTD-em ([CommunityBlob]) i leży w jednej tabeli,
 *  - powtarzalne dane autora (nazwa + avatar twórcy/źródła) trzymamy RAZ w słowniku
 *    [CommunityAuthorRecord] i w wierszach referencjonujemy je krótkim [authorId],
 *  - w wierszach używamy id: server_id (z endpointu) i author_id — nie powtarzamy stringów.
 *
 * Obrazy (url + jakość) trzymamy jako osobne kolumny, nie w blobie — dzięki temu można je tanio
 * usunąć zachowując tekst (osobna retencja tekstu i obrazów) bez dekompresji/rekompresji blobu.
 */
enum class CommunitySection { CREATOR, FOREIGN, POST, AD, CONTEST, ARTICLE, MEME }

/** słownikowy wpis autora/twórcy/źródła — wspólny dla wszystkich serwerów. */
data class CommunityAuthorRecord(
    val authorId: String,
    val platform: String,
    val name: String,
    val avatarUrl: String?,
)

/**
 * jedna zapisana pozycja. [blob]/[codec]/[originalSize] to ściśnięty JSON pól tekstowych zależnych
 * od sekcji; obraz i autor są osobno (kolumny / słownik), by dało się je przycinać niezależnie.
 */
data class CommunityStoredItem(
    val serverId: String,
    val itemId: String,
    val section: CommunitySection,
    val authorId: String?,
    val publishedMs: Long?,
    val blob: ByteArray,
    val codec: Int,
    val originalSize: Int,
    val imageUrl: String? = null,
    val notifyImageUrl: String? = null,
    val imageQuality: Int? = null,
    val favorite: Boolean = false,
    // dołączane przy odczycie (LEFT JOIN ze słownikiem autorów)
    val authorName: String? = null,
    val authorPlatform: String? = null,
    val authorAvatarUrl: String? = null,
)

/** komplet do zapisu: słownik autorów + pozycje jednego serwera. */
data class CommunityStoredContent(
    val authors: List<CommunityAuthorRecord>,
    val items: List<CommunityStoredItem>,
)

/** odtworzone listy treści (do zbudowania manifestu offline). */
data class CommunityContentLists(
    val creators: List<CommunityCreator> = emptyList(),
    val foreignItems: List<CommunityForeignItem> = emptyList(),
    val posts: List<CommunityPost> = emptyList(),
    val ads: List<CommunityLinkItem> = emptyList(),
    val contests: List<CommunityLinkItem> = emptyList(),
    val articles: List<CommunityArticle> = emptyList(),
    val memes: List<CommunityMeme> = emptyList(),
) {
    val isEmpty: Boolean
        get() = creators.isEmpty() && foreignItems.isEmpty() && posts.isEmpty() &&
            ads.isEmpty() && contests.isEmpty() && articles.isEmpty() && memes.isEmpty()

    /** zdolności wynikające z tego, co realnie zapisano (do manifestu offline). */
    fun impliedCapabilities(): Set<CommunityCapability> = buildSet {
        if (creators.isNotEmpty()) add(CommunityCapability.COMMUNITY_CREATORS)
        if (foreignItems.isNotEmpty()) add(CommunityCapability.FOREIGN_PLATFORM_ITEMS)
        if (posts.isNotEmpty()) add(CommunityCapability.YOUTUBE_COMMUNITY_POSTS)
        if (ads.isNotEmpty()) add(CommunityCapability.ADS)
        if (contests.isNotEmpty()) add(CommunityCapability.CONTESTS)
        if (articles.isNotEmpty()) add(CommunityCapability.MEDIA_PUBLICATIONS)
        if (memes.isNotEmpty()) add(CommunityCapability.MEMES)
    }
}

/** id autora w słowniku — z platformy i znormalizowanej nazwy (te same dane = ten sam wpis). */
fun communityAuthorId(serverId: String, platform: String, name: String): String =
    communitySha256Hex(serverId.trim() + "\u0000" + platform.trim().lowercase() + "\u0000" + name.trim().lowercase()).take(16)

/** stabilny klucz pozycji per serwer – po nim rozpoznajemy aktualizacje i usunięcia. */
fun communityItemId(url: String?, platform: String, author: String?, fingerprint: String?): String =
    CommunityDedup.canonicalKey(url, platform, author, fingerprint)

/**
 * SHA-256 całej zapisanej treści serwera – „odcisk" bazy dla danego serwera. Liczony z pól
 * trwałych (itemId, sekcja, autor, data, obraz, blob tekstu) w stałej kolejności (sort po itemId),
 * więc jest deterministyczny. Służy do weryfikacji przy przeładowaniu bazy: równy hash = treść
 * bez zmian, różny = coś się zmieniło i wiersz został odświeżony.
 */
fun communityContentSha256(items: List<CommunityStoredItem>): String {
    val md = MessageDigest.getInstance("SHA-256")
    items.sortedBy { it.itemId }.forEach { i ->
        val header = "${i.itemId}|${i.section}|${i.authorId}|${i.publishedMs}|${i.imageUrl}|" +
            "${i.notifyImageUrl}|${i.imageQuality}|${i.codec}|${i.originalSize}|"
        md.update(header.toByteArray(Charsets.UTF_8))
        md.update(i.blob)
        md.update("\n".toByteArray(Charsets.UTF_8))
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

/**
 * Tłumaczy między manifestem (typowane listy) a zapisem (słownik autorów + ściśnięte pozycje).
 * Nic tu nie sięga sieci ani bazy — czysta (de)serializacja, łatwa do testu instrumentalnego.
 */
object CommunityContentCodec {

    const val PLATFORM_YT = "youtube"
    const val PLATFORM_MEDIA = "media"
    const val PLATFORM_MEME = "meme"

    fun toStored(serverId: String, manifest: CommunityManifest): CommunityStoredContent {
        val authors = LinkedHashMap<String, CommunityAuthorRecord>()
        val items = ArrayList<CommunityStoredItem>()

        fun author(platform: String, name: String?, avatar: String?): String? {
            val trimmed = name?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            val id = communityAuthorId(serverId, platform, trimmed)
            // A-12: późniejszy wpis bez awatara NIE kasuje awatara znanego z wcześniejszego wpisu tego autora
            val newAvatar = avatar?.trim()?.ifEmpty { null } ?: authors[id]?.avatarUrl
            authors[id] = CommunityAuthorRecord(id, platform, trimmed, newAvatar)
            return id
        }

        fun add(
            section: CommunitySection,
            authorId: String?,
            publishedMs: Long?,
            url: String?,
            platform: String,
            name: String?,
            fingerprint: String?,
            body: JSONObject,
            imageUrl: String? = null,
            notifyImageUrl: String? = null,
            imageQuality: Int? = null,
        ) {
            val blob = CommunityBlob.encode(body.toString())
            items += CommunityStoredItem(
                serverId = serverId,
                itemId = communityItemId(url, platform, name, fingerprint),
                section = section,
                authorId = authorId,
                publishedMs = publishedMs,
                blob = blob.data,
                codec = blob.codec,
                originalSize = blob.originalSize,
                imageUrl = imageUrl,
                notifyImageUrl = notifyImageUrl,
                imageQuality = imageQuality,
            )
        }

        manifest.creators.forEach { c ->
            add(
                CommunitySection.CREATOR, author(PLATFORM_YT, c.name, null), null, c.channelUrl,
                PLATFORM_YT, c.name, c.id,
                JSONObject().put("creatorId", c.id).putOpt("channelUrl", c.channelUrl),
            )
        }
        manifest.foreignItems.forEach { f ->
            add(
                CommunitySection.FOREIGN, author(f.platform, f.author, null), f.publishedAtMillis, f.url,
                f.platform, f.author, f.title ?: f.text,
                JSONObject().put("platform", f.platform)
                    .putOpt("type", f.type).putOpt("title", f.title).putOpt("text", f.text)
                    .putOpt("url", f.url).put("update", f.update.name).putOpt("video", videoJson(f.video)).putOpt("audio", audioJson(f.audio)),
                imageUrl = f.imageUrl, notifyImageUrl = f.notifyImageUrl, imageQuality = f.imageQuality,
            )
        }
        manifest.posts.forEach { p ->
            add(
                CommunitySection.POST, author(PLATFORM_YT, p.author, p.authorImageUrl), p.publishedAtMillis, p.url,
                PLATFORM_YT, p.author, p.text,
                JSONObject().putOpt("type", p.type).put("text", p.text)
                    .putOpt("url", p.url).put("update", p.update.name).putOpt("video", videoJson(p.video)).putOpt("audio", audioJson(p.audio)),
                imageUrl = p.imageUrl, notifyImageUrl = p.notifyImageUrl, imageQuality = p.imageQuality,
            )
        }
        manifest.ads.forEach { link -> add(CommunitySection.AD, null, link.publishedAtMillis, link.url, link.kind.name, null, link.title ?: link.text, linkBody(link), link.imageUrl, link.notifyImageUrl, link.imageQuality) }
        manifest.contests.forEach { link -> add(CommunitySection.CONTEST, null, link.publishedAtMillis, link.url, link.kind.name, null, link.title ?: link.text, linkBody(link), link.imageUrl, link.notifyImageUrl, link.imageQuality) }
        manifest.articles.forEach { a ->
            add(
                CommunitySection.ARTICLE, author(PLATFORM_MEDIA, a.source, null), a.publishedAtMillis, a.url,
                PLATFORM_MEDIA, a.source, a.title,
                JSONObject().put("kind", a.kind.name).putOpt("byline", a.author)
                    .put("title", a.title).putOpt("text", a.text).putOpt("url", a.url).put("update", a.update.name)
                    .putOpt("video", videoJson(a.video)).putOpt("audio", audioJson(a.audio)),
                imageUrl = a.imageUrl, notifyImageUrl = a.notifyImageUrl, imageQuality = a.imageQuality,
            )
        }
        manifest.memes.forEach { m ->
            // wszystkie zdjęcia karuzeli trzymamy w body (content-addressed); pierwsze dubluje się w polach
            // itemu (imageUrl/notify/quality) dla content-addressingu, powiadomień i retencji.
            val imagesJson = JSONArray()
            m.images.forEach { img ->
                imagesJson.put(
                    JSONObject().put("imageUrl", img.imageUrl)
                        .putOpt("notifyImageUrl", img.notifyImageUrl)
                        .apply { img.imageQuality?.let { put("imageQuality", it) } },
                )
            }
            add(
                CommunitySection.MEME, author(PLATFORM_MEME, m.source, null), m.publishedAtMillis,
                m.url ?: m.imageUrl, PLATFORM_MEME, m.source, m.title,
                JSONObject().putOpt("title", m.title).putOpt("url", m.url).put("update", m.update.name)
                    .put("images", imagesJson).putOpt("video", videoJson(m.video)).putOpt("audio", audioJson(m.audio)),
                imageUrl = m.imageUrl, notifyImageUrl = m.notifyImageUrl, imageQuality = m.imageQuality,
            )
        }

        return CommunityStoredContent(authors.values.toList(), items)
    }

    private fun videoJson(video: CommunityVideo?): JSONObject? = video?.let { v ->
        fun variant(x: CommunityVideoVariant?) = x?.let {
            JSONObject().put("url", it.url).apply {
                it.width?.let { w -> put("width", w) }; it.height?.let { h -> put("height", h) }
                it.fps?.let { f -> put("fps", f.toDouble()) }; it.bytes?.let { b -> put("bytes", b) }
            }
        }
        JSONObject().putOpt("hevc", variant(v.hevc)).putOpt("avc", variant(v.avc)).putOpt("title", v.title)
            .apply { v.durationMs?.let { put("durationMs", it) } }
    }

    private fun videoOf(json: JSONObject): CommunityVideo? = json.optJSONObject("video")?.let { v ->
        fun variant(x: JSONObject?) = x?.optString("url")?.ifEmpty { null }?.let { url ->
            CommunityVideoVariant(
                url,
                x.optInt("width", 0).takeIf { it > 0 }, x.optInt("height", 0).takeIf { it > 0 },
                x.optDouble("fps", Double.NaN).takeIf { !it.isNaN() }?.toFloat(), x.optLong("bytes", 0L).takeIf { it > 0 },
            )
        }
        val hevc = variant(v.optJSONObject("hevc")); val avc = variant(v.optJSONObject("avc"))
        if (hevc == null && avc == null) null
        else CommunityVideo(hevc, avc, v.optLong("durationMs", 0L).takeIf { it > 0 }, v.optString("title").ifEmpty { null })
    }

    private fun audioJson(audio: CommunityAudio?): JSONObject? = audio?.let {
        JSONObject().put("url", it.url).putOpt("title", it.title).apply { it.durationMs?.let { d -> put("durationMs", d) } }
    }

    // dane z własnej bazy (już zwalidowane przy parsowaniu manifestu) — tylko odtwarzamy obiekt
    private fun audioOf(json: JSONObject): CommunityAudio? = json.optJSONObject("audio")?.let { a ->
        val url = a.optString("url").ifEmpty { return@let null }
        CommunityAudio(url, a.optLong("durationMs", 0L).takeIf { it > 0 }, a.optString("title").ifEmpty { null })
    }

    private fun linkBody(link: CommunityLinkItem): JSONObject = JSONObject()
        .putOpt("title", link.title).putOpt("text", link.text).put("url", link.url)
        .put("thirdParty", link.thirdParty).put("update", link.update.name)

    fun fromStored(items: List<CommunityStoredItem>): CommunityContentLists {
        val creators = ArrayList<CommunityCreator>()
        val foreign = ArrayList<CommunityForeignItem>()
        val posts = ArrayList<CommunityPost>()
        val ads = ArrayList<CommunityLinkItem>()
        val contests = ArrayList<CommunityLinkItem>()
        val articles = ArrayList<CommunityArticle>()
        val memes = ArrayList<CommunityMeme>()

        for (item in items) {
            val json = CommunityBlob.decode(item.blob, item.codec, item.originalSize)
                ?.let { runCatching { JSONObject(it) }.getOrNull() } ?: continue
            when (item.section) {
                CommunitySection.CREATOR -> creators += CommunityCreator(
                    id = json.optString("creatorId").ifEmpty { item.itemId },
                    name = item.authorName ?: continue,
                    channelUrl = json.optString("channelUrl").ifEmpty { null },
                )
                CommunitySection.FOREIGN -> foreign += CommunityForeignItem(
                    platform = json.optString("platform").ifEmpty { item.authorPlatform ?: continue },
                    author = item.authorName,
                    type = json.optString("type").ifEmpty { null },
                    title = json.optString("title").ifEmpty { null },
                    text = json.optString("text").ifEmpty { null },
                    url = json.optString("url").ifEmpty { null },
                    imageUrl = item.imageUrl,
                    notifyImageUrl = item.notifyImageUrl,
                    imageQuality = item.imageQuality,
                    publishedAtMillis = item.publishedMs,
                    update = updateOf(json),
                    audio = audioOf(json),
                    video = videoOf(json),
                )
                CommunitySection.POST -> posts += CommunityPost(
                    author = item.authorName ?: continue,
                    authorImageUrl = item.authorAvatarUrl,
                    type = json.optString("type").ifEmpty { null },
                    text = json.optString("text"),
                    url = json.optString("url").ifEmpty { null },
                    imageUrl = item.imageUrl,
                    notifyImageUrl = item.notifyImageUrl,
                    imageQuality = item.imageQuality,
                    publishedAtMillis = item.publishedMs,
                    update = updateOf(json),
                    audio = audioOf(json),
                    video = videoOf(json),
                )
                CommunitySection.AD -> ads += linkFrom(CommunityLinkKind.AD, json, item) ?: continue
                CommunitySection.CONTEST -> contests += linkFrom(CommunityLinkKind.CONTEST, json, item) ?: continue
                CommunitySection.ARTICLE -> articles += CommunityArticle(
                    kind = if (json.optString("kind") == CommunityArticleKind.NEWSLETTER.name) {
                        CommunityArticleKind.NEWSLETTER
                    } else {
                        CommunityArticleKind.ARTICLE
                    },
                    source = item.authorName ?: continue,
                    author = json.optString("byline").ifEmpty { null },
                    title = json.optString("title").ifEmpty { continue },
                    text = json.optString("text").ifEmpty { null },
                    // ARTYKUŁ wymaga url, ale NEWSLETTER może być bez linku — nie odrzucamy przy braku url
                    url = json.optString("url").ifEmpty { null },
                    imageUrl = item.imageUrl,
                    notifyImageUrl = item.notifyImageUrl,
                    imageQuality = item.imageQuality,
                    publishedAtMillis = item.publishedMs,
                    update = updateOf(json),
                    audio = audioOf(json),
                    video = videoOf(json),
                )
                CommunitySection.MEME -> {
                    val imgs = storedMemeImages(json, item)
                    if (imgs.isEmpty()) continue
                    memes += CommunityMeme(
                        source = item.authorName,
                        title = json.optString("title").ifEmpty { null },
                        images = imgs,
                        url = json.optString("url").ifEmpty { null },
                        publishedAtMillis = item.publishedMs,
                        update = updateOf(json),
                        audio = audioOf(json),
                        video = videoOf(json),
                    )
                }
            }
        }
        return CommunityContentLists(creators, foreign, posts, ads, contests, articles, memes)
    }

    // Odtwarza zdjęcia mema z body (`images`); gdy brak — pojedynczy obraz z pól itemu (stare wpisy).
    private fun storedMemeImages(json: JSONObject, item: CommunityStoredItem): List<CommunityMemeImage> {
        val out = ArrayList<CommunityMemeImage>()
        val arr = json.optJSONArray("images")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val io = arr.optJSONObject(i) ?: continue
                val url = io.optString("imageUrl").ifEmpty { null } ?: continue
                out += CommunityMemeImage(
                    imageUrl = url,
                    notifyImageUrl = io.optString("notifyImageUrl").ifEmpty { null },
                    imageQuality = io.optInt("imageQuality", 0).takeIf { it in 1..100 },
                )
            }
        }
        if (out.isEmpty()) {
            val url = item.imageUrl ?: return emptyList()
            out += CommunityMemeImage(url, item.notifyImageUrl, item.imageQuality)
        }
        return out
    }

    private fun linkFrom(kind: CommunityLinkKind, json: JSONObject, item: CommunityStoredItem): CommunityLinkItem? {
        val url = json.optString("url").ifEmpty { return null }
        return CommunityLinkItem(
            kind = kind,
            title = json.optString("title").ifEmpty { null },
            text = json.optString("text").ifEmpty { null },
            url = url,
            thirdParty = json.optBoolean("thirdParty", false),
            imageUrl = item.imageUrl,
            notifyImageUrl = item.notifyImageUrl,
            imageQuality = item.imageQuality,
            publishedAtMillis = item.publishedMs,
            update = updateOf(json),
        )
    }

    private fun updateOf(json: JSONObject): CommunityUpdateKind =
        runCatching { CommunityUpdateKind.valueOf(json.optString("update")) }
            .getOrDefault(CommunityUpdateKind.NONE)
}
