package pl.lewicowyt.notifier.network

import java.io.IOException
import java.io.Reader
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import pl.lewicowyt.notifier.BuildConfig

internal class HttpStatusException(
    val statusCode: Int,
    val responseBody: String,
    safeUrl: String,
) : IOException("HTTP $statusCode dla $safeUrl")

class HttpTextClient(
    private val baseClient: OkHttpClient = OkHttpClient(),
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
        val client = baseClient.newBuilder()
            .connectTimeout(connectTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout(callTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            .build()
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
        val client = baseClient.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS)
            .build()
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
        .header(
            "User-Agent",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                "Chrome/136 Safari/537.36 lewicowYT/${BuildConfig.VERSION_NAME}",
        )
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
