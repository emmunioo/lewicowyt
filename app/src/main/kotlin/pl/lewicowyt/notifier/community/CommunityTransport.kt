package pl.lewicowyt.notifier.community

/** dane do warunkowego GET-a (ETag / Last-Modified) – oszczędzają transfer, nic o userze. */
data class CommunityConditional(
    val etag: String?,
    val lastModified: String?,
) {
    val isEmpty: Boolean get() = etag.isNullOrBlank() && lastModified.isNullOrBlank()
}

/** wynik pobrania zasobu społeczności. cała warstwa jest RECEIVE-ONLY – tylko czytamy. */
sealed interface CommunityFetchResult {
    data class Success(
        val body: String,
        val etag: String?,
        val lastModified: String?,
    ) : CommunityFetchResult

    /** serwer potwierdził, że od ostatniego ETag/Last-Modified nic się nie zmieniło. */
    object NotModified : CommunityFetchResult

    /**
     * [permanent] = serwer jednoznacznie odmówił zasobu (HTTP 4xx poza 408/429, dane binarne zamiast tekstu).
     * B-03: tylko takie porażki liczą się do trwałego pominięcia pliku treści — timeout, brak sieci czy
     * niegotowy Tor (permanent=false) nie mogą wyrzucić pliku na zawsze.
     */
    data class Failure(val reason: String, val permanent: Boolean = false) : CommunityFetchResult
}

/**
 * pobieracz zasobu społeczności (manifest/feed) transportem typu pull.
 * z definicji tylko POBIERA – nie ma tu metody, którą dałoby się cokolwiek wysłać.
 * HTTPS teraz; onion (przez Tor) dołączy jako osobna implementacja.
 */
interface CommunityFetcher {
    val kind: CommunityTransportKind
    suspend fun fetch(url: String, conditional: CommunityConditional?): CommunityFetchResult
}
