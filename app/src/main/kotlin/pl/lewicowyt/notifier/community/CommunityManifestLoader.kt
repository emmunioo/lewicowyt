package pl.lewicowyt.notifier.community

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface CommunityManifestLoadResult {
    data class Loaded(
        val manifest: CommunityManifest,
        val etag: String?,
        val lastModified: String?,
        // A-09: adres, który FAKTYCZNIE dostarczył manifest (główny albo alternatywa LAN) — względem niego
        // rozwiązujemy pliki treści. null = adres główny.
        val sourceEndpoint: String? = null,
    ) : CommunityManifestLoadResult

    object NotModified : CommunityManifestLoadResult

    data class Failed(val reason: String) : CommunityManifestLoadResult
}

/**
 * spina pobranie (dowolny [CommunityFetcher]) z tolerancyjnym parserem. manifest z
 * serwera jest niezaufany: gdy parser go odrzuci, dostajemy Failed, nie wyjątek.
 * ETag/Last-Modified z Loaded warto zapisać i oddać przy kolejnym GET (mniej transferu).
 */
class CommunityManifestLoader(
    private val fetcher: CommunityFetcher,
) {
    suspend fun load(
        endpoint: String,
        conditional: CommunityConditional? = null,
        // R-08: host do walidacji obrazów/push bierzemy z adresu KANONICZNEGO serwera, nie z adresu, który
        // akurat dostarczył manifest. Inaczej gdy wygra alternatywa LAN (altEndpoints), obrazy i push z
        // publicznej domeny serwera byłyby odrzucane (a push=null → łańcuch R-01). Domyślnie = endpoint.
        parseEndpoint: String = endpoint,
    ): CommunityManifestLoadResult = when (val result = fetcher.fetch(endpoint, conditional)) {
        is CommunityFetchResult.Success -> {
            // M-6: parsowanie manifestu (do ~1 MB JSON z NIEZAUFANEGO serwera) jest kosztowne — robimy je
            // na Dispatchers.Default, nigdy na głównym wątku, żeby serwer nie mógł wywołać ANR.
            val manifest = withContext(Dispatchers.Default) {
                CommunityManifestParser.parse(result.body, sourceEndpoint = parseEndpoint)
            }
            if (manifest == null) {
                CommunityManifestLoadResult.Failed("Manifest odrzucony przez parser (niezgodny/niezaufany)")
            } else {
                CommunityManifestLoadResult.Loaded(manifest, result.etag, result.lastModified)
            }
        }
        CommunityFetchResult.NotModified -> CommunityManifestLoadResult.NotModified
        is CommunityFetchResult.Failure -> CommunityManifestLoadResult.Failed(result.reason)
    }
}
