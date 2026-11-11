package pl.lewicowyt.notifier.community

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * loader spina pobranie z tolerancyjnym parserem: poprawny manifest → Loaded, cokolwiek
 * niezgodnego/niezaufanego → Failed (nie wyjątek), a NotModified/Failure przechodzą dalej.
 */
class CommunityManifestLoaderTest {

    private class FakeFetcher(private val result: CommunityFetchResult) : CommunityFetcher {
        var lastUrl: String? = null
        var lastConditional: CommunityConditional? = null
        override val kind = CommunityTransportKind.HTTPS_POLL
        override suspend fun fetch(url: String, conditional: CommunityConditional?): CommunityFetchResult {
            lastUrl = url
            lastConditional = conditional
            return result
        }
    }

    private val validManifest = """
        {
          "contractVersion": 1,
          "name": "Serwer",
          "transport": "https",
          "terms": { "text": "Regulamin." }
        }
    """.trimIndent()

    @Test
    fun `valid manifest is loaded and passes through etag`() = runBlocking {
        val fetcher = FakeFetcher(
            CommunityFetchResult.Success(body = validManifest, etag = "\"v1\"", lastModified = null),
        )
        val result = CommunityManifestLoader(fetcher).load("https://serwer.example/manifest")
        assertTrue(result is CommunityManifestLoadResult.Loaded)
        result as CommunityManifestLoadResult.Loaded
        assertEquals("Serwer", result.manifest.name)
        assertEquals(CommunityTransportKind.HTTPS_POLL, result.manifest.transport)
        assertEquals("\"v1\"", result.etag)
        assertEquals("https://serwer.example/manifest", fetcher.lastUrl)
    }

    @Test
    fun `rejected manifest becomes failed, not a crash`() = runBlocking {
        val fetcher = FakeFetcher(
            CommunityFetchResult.Success(body = "{ \"contractVersion\": 99 }", etag = null, lastModified = null),
        )
        val result = CommunityManifestLoader(fetcher).load("https://serwer.example/manifest")
        assertTrue(result is CommunityManifestLoadResult.Failed)
    }

    @Test
    fun `garbage body becomes failed`() = runBlocking {
        val fetcher = FakeFetcher(CommunityFetchResult.Success(body = "{ not json", etag = null, lastModified = null))
        assertTrue(
            CommunityManifestLoader(fetcher).load("https://serwer.example/manifest")
                is CommunityManifestLoadResult.Failed,
        )
    }

    @Test
    fun `not modified passes through`() = runBlocking {
        val fetcher = FakeFetcher(CommunityFetchResult.NotModified)
        assertTrue(
            CommunityManifestLoader(fetcher).load("https://serwer.example/manifest")
                is CommunityManifestLoadResult.NotModified,
        )
    }

    @Test
    fun `fetch failure passes through with reason`() = runBlocking {
        val fetcher = FakeFetcher(CommunityFetchResult.Failure("HTTP 503"))
        val result = CommunityManifestLoader(fetcher).load("https://serwer.example/manifest")
        assertTrue(result is CommunityManifestLoadResult.Failed)
        assertEquals("HTTP 503", (result as CommunityManifestLoadResult.Failed).reason)
    }

    @Test
    fun `conditional is forwarded to the fetcher`() = runBlocking {
        val fetcher = FakeFetcher(CommunityFetchResult.NotModified)
        val conditional = CommunityConditional(etag = "\"v1\"", lastModified = null)
        CommunityManifestLoader(fetcher).load("https://serwer.example/manifest", conditional)
        assertEquals(conditional, fetcher.lastConditional)
    }
}
