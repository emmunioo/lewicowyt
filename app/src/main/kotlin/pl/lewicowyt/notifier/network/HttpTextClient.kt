package pl.lewicowyt.notifier.network

import java.io.IOException
import java.io.Reader
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal class HttpStatusException(
    val statusCode: Int,
    val responseBody: String,
    safeUrl: String,
) : IOException("HTTP $statusCode dla $safeUrl")

class HttpTextClient(
    // D-19g: bez wartości domyślnej — domyślny OkHttpClient() to systemowy DNS i automatyczne przekierowania;
    // produkcja zawsze podaje PrivacyHttpClient (AppGraph), testy podają klienta jawnie
    private val baseClient: OkHttpClient,
) {
    fun getText(
        url: String,
        maxChars: Int = 4_000_000,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMillis: Int = 15_000,
        readTimeoutMillis: Int = 20_000,
        callTimeoutMillis: Int = 35_000,
    ): String {
        require(connectTimeoutMillis > 0 && readTimeoutMillis > 0 && callTimeoutMillis > 0)
        val targetUrl = requireHttpsUrl(url)
        val request = commonRequestBuilder(targetUrl, headers)
            .get()
            .build()
        val client = DerivedHttpClients.get( // T-11: raz na zestaw limitów, nie przy każdym żądaniu
            baseClient,
            DerivedHttpClients.Spec(
                connectTimeoutMillis = connectTimeoutMillis.toLong(),
                readTimeoutMillis = readTimeoutMillis.toLong(),
                callTimeoutMillis = callTimeoutMillis.toLong(),
            ),
        )
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw HttpStatusException(
                    statusCode = response.code,
                    responseBody = readErrorBody(response.body.charStream()),
                    safeUrl = targetUrl.safeDescription(),
                )
            }
            rejectBinaryResponse(response.body.contentType()?.toString())
            readLimited(response.body.charStream(), maxChars)
        }
    }

    fun postJson(
        url: String,
        json: String,
        maxChars: Int = 4_000_000,
        headers: Map<String, String> = emptyMap(),
    ): String {
        val targetUrl = requireHttpsUrl(url)
        val request = commonRequestBuilder(targetUrl, headers)
            .header("Accept", JSON_RESPONSE_ACCEPT)
            .post(json.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val client = DerivedHttpClients.get(baseClient, POST_JSON_SPEC) // T-11
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw HttpStatusException(
                    statusCode = response.code,
                    responseBody = readErrorBody(response.body.charStream()),
                    safeUrl = targetUrl.safeDescription(),
                )
            }
            rejectBinaryResponse(response.body.contentType()?.toString())
            readLimited(response.body.charStream(), maxChars)
        }
    }

    private fun readErrorBody(reader: Reader): String =
        runCatching { readLimited(reader, MAX_ERROR_BODY_CHARS) }.getOrDefault("")

    private fun rejectBinaryResponse(contentType: String?) {
        if (!isAllowedTextResponseMime(contentType)) {
            throw IOException("Serwer zwrócił dane binarne zamiast metadanych tekstowych")
        }
    }

    private fun commonRequestBuilder(
        targetUrl: HttpUrl,
        headers: Map<String, String>,
    ): Request.Builder = Request.Builder()
        .url(targetUrl)
        // B-14: bez sufiksu „lewicowYT/<wersja>” — YouTube/Google/GitHub nie odróżniają ruchu aplikacji
        .header("User-Agent", BROWSER_USER_AGENT)
        .header("Accept-Language", "pl-PL,pl;q=0.9,en;q=0.5")
        .header("Accept", TEXT_RESPONSE_ACCEPT)
        .apply {
            if (targetUrl.host.isYouTubeHost()) {
                header("Cookie", YOUTUBE_PRIVACY_COOKIE)
            }
            headers.forEach { (name, value) -> header(name, value) }
        }

    private fun readLimited(reader: Reader, maxChars: Int): String = reader.use {
        val buffer = CharArray(8_192)
        val result = StringBuilder()
        while (true) {
            val count = it.read(buffer)
            if (count < 0) break
            result.append(buffer, 0, count)
            if (result.length > maxChars) {
                throw IOException("Odpowiedź przekracza limit $maxChars znaków")
            }
        }
        result.toString()
    }

    private fun String.isYouTubeHost(): Boolean =
        this == "youtube.com" || endsWith(".youtube.com")

    private fun requireHttpsUrl(value: String): HttpUrl {
        val url = value.toHttpUrlOrNull()
        if (url == null || !url.isHttps || url.host.isBlank()) {
            throw IOException("Dozwolone są wyłącznie poprawne adresy HTTPS")
        }
        return url
    }

    private fun HttpUrl.safeDescription(): String = buildString {
        append(scheme)
        append("://")
        append(host)
        if (port != 443) {
            append(':')
            append(port)
        }
        append(encodedPath.ifBlank { "/" })
    }

    private companion object {
        val POST_JSON_SPEC = DerivedHttpClients.Spec(
            connectTimeoutMillis = 15_000L,
            readTimeoutMillis = 20_000L,
            callTimeoutMillis = 35_000L,
        )
        const val MAX_ERROR_BODY_CHARS = 64_000
        val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()
        const val JSON_RESPONSE_ACCEPT = "application/json"
        const val TEXT_RESPONSE_ACCEPT =
            "application/json,text/html,application/xhtml+xml," +
                "application/xml,text/xml,application/atom+xml,application/rss+xml;q=0.9"

        // Stan zgody bez personalizacji. Zapobiega zwracaniu strony consent.youtube.com
        // zamiast publicznej strony kanału w krajach EOG.
        const val YOUTUBE_PRIVACY_COOKIE =
            "SOCS=CAESEwgDEgk0ODE3Nzk3MjQaAmVuIAEaBgiA_LyaBg"
    }
}

internal fun isAllowedTextResponseMime(contentType: String?): Boolean {
    if (contentType.isNullOrBlank()) return true
    val mime = contentType.substringBefore(';').trim().lowercase()
    return mime.startsWith("text/") ||
        mime == "application/json" ||
        mime == "application/xml" ||
        mime == "application/atom+xml" ||
        mime == "application/rss+xml" ||
        mime.endsWith("+json") ||
        mime.endsWith("+xml")
}

/**
 * B-14: wspólny, zwykły UA przeglądarki mobilnej dla żądań do YouTube/CDN Google/GitHub — bez identyfikatora
 * aplikacji ani jej wersji (te wyróżniały użytkowników lewicowYT w logach dostawców).
 */
internal const val BROWSER_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/136.0.0.0 Mobile Safari/537.36"
