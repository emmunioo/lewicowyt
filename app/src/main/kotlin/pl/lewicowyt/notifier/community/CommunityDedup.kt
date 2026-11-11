package pl.lewicowyt.notifier.community

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Wykrywanie duplikatów tej samej treści przychodzącej z RÓŻNYCH serwerów.
 * Pomysł: dwie pozycje to ten sam materiał, jeśli mają ten sam KANONICZNY klucz —
 * najczęściej wskazują ten sam zasób (URL). Z URL-a wyłuskujemy stabilne ID
 * (post/wideo/shorts YouTube, status Twittera/X), a dla reszty normalizujemy host+ścieżkę,
 * odcinając śmieciowe parametry. Gdy URL-a brak — platforma + autor + skrót tekstu.
 *
 * Ten sam klucz posłuży też do odsiewania zdublowanych POWIADOMIEŃ (gdy powstanie M4).
 */
object CommunityDedup {

    fun canonicalKey(url: String?, platform: String?, author: String?, text: String?): String {
        fromUrl(url)?.let { return it }
        val p = platform?.trim()?.lowercase().orEmpty()
        val a = author?.trim()?.lowercase().orEmpty()
        val t = normalizeText(text)
        return "t:$p|$a|" + communitySha256Hex(t).take(16)
    }

    private fun fromUrl(raw: String?): String? {
        val url = raw?.trim()?.toHttpUrlOrNull() ?: return null
        val host = url.host.lowercase().removePrefix("www.").removePrefix("m.")
        val seg = url.pathSegments.filter { it.isNotEmpty() }

        if (host == "youtube.com" || host.endsWith(".youtube.com")) {
            if (seg.size >= 2 && seg[0] == "post") return "yt-post:${seg[1]}"
            if (seg.size >= 2 && seg[0] == "shorts") return "yt-video:${seg[1]}"
            url.queryParameter("v")?.takeIf { it.isNotBlank() }?.let { return "yt-video:$it" }
        }
        if (host == "youtu.be") {
            seg.firstOrNull()?.let { return "yt-video:$it" }
        }
        if (host == "twitter.com" || host == "x.com" || host.endsWith(".twitter.com")) {
            val i = seg.indexOf("status")
            if (i >= 0 && seg.size > i + 1) return "tw:${seg[i + 1]}"
        }
        // generyczny: host + ścieżka + ZNACZĄCE query (M-05). Wcześniej query było pomijane, więc
        // np. "?p=1" i "?p=2" dawały ten sam klucz → replaceContent gubił jedną pozycję, a jedno
        // "deletions" kasowało obie. Odcinamy tylko parametry śledzące (utm_*, fbclid, …) i sortujemy
        // resztę po nazwie, żeby klucz był stabilny niezależnie od kolejności parametrów.
        val base = "u:$host/" + seg.joinToString("/")
        val meaningfulQuery = url.queryParameterNames
            .filter { name -> name.lowercase().let { it.isNotEmpty() && it !in TRACKING_PARAMS && !it.startsWith("utm_") } }
            .sorted()
            .flatMap { name -> url.queryParameterValues(name).map { "${name.lowercase()}=${it.orEmpty()}" } }
            .joinToString("&")
        return if (meaningfulQuery.isEmpty()) base else "$base?$meaningfulQuery"
    }

    private fun normalizeText(text: String?): String =
        text?.lowercase()?.replace(WHITESPACE, " ")?.trim()?.take(200).orEmpty()

    private val WHITESPACE = Regex("\\s+")

    // parametry śledzące/analityczne — nie zmieniają tożsamości treści, więc nie wchodzą do klucza
    // (utm_* obsługiwane prefiksem). Lista pokrywa najczęstsze źródła „fałszywych duplikatów na odwrót".
    private val TRACKING_PARAMS = setOf(
        "fbclid", "gclid", "dclid", "gbraid", "wbraid", "msclkid", "yclid", "igshid", "igsh",
        "mc_cid", "mc_eid", "_ga", "_gl", "ref", "ref_src", "ref_url", "source", "spm", "cmpid",
    )
}
