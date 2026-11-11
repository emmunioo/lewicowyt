package pl.lewicowyt.notifier.community

/** transport HTTPS-poll: zwykły warunkowy GET po publiczny endpoint (CGNAT bije tunel operatora). */
class HttpsCommunityFetcher(
    private val http: CommunityHttpClient,
) : CommunityFetcher {
    override val kind = CommunityTransportKind.HTTPS_POLL

    override suspend fun fetch(url: String, conditional: CommunityConditional?): CommunityFetchResult =
        http.fetch(url, conditional)
}
