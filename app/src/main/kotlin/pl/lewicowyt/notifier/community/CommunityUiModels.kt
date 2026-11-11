package pl.lewicowyt.notifier.community

/** stan warstwy społeczności dla UI. zakładka zapala się dopiero, gdy któryś serwer coś wnosi. */
data class CommunityUiState(
    val servers: List<CommunityServerState> = emptyList(),
    val pendingTerms: CommunityTermsPrompt? = null,
    val message: String? = null,
    val isBusy: Boolean = false,
    // pull-to-refresh: pokazuje wskaźnik odświeżania na zakładce Społeczność
    val isRefreshing: Boolean = false,
    // id serwera głównego; serwer domyślny (oficjalny) = CommunityDefaults.SERVER_ID (od B-11 nigdy "")
    val primaryServerId: String = "",
    // stan weryfikacji serwera głównego (baner z różnicami vs RSS); null = nie sprawdzano / brak serwera głównego
    val primaryHealth: CommunityPrimaryHealth? = null,
) {
    val tabVisible: Boolean get() = servers.any { it.contributesToTab }

    /**
     * T-11: regulamin serwera domyślnego zmienił się od zgody użytkownika. Nie null = UI pokazuje zasłonę z nowym
     * regulaminem. Tylko gdy serwer domyślny jest aktywny (serwer główny to on albo jeszcze nie wybrano innego) —
     * po wybraniu innego serwera głównego domyślny jest wyłączony i nic nie trzeba akceptować.
     */
    val defaultServerTermsToAccept: CommunityTerms?
        get() {
            val state = servers.firstOrNull { CommunityDefaults.isDefault(it.record.serverId) } ?: return null
            val active = primaryServerId.isEmpty() || CommunityDefaults.isDefault(primaryServerId)
            if (!active || !state.record.enabled || state.consentState != CommunityConsentState.CONSENT_REQUIRED) return null
            return state.manifest?.terms ?: state.reviewableTerms
        }
}

/** regulamin czekający na decyzję usera – napędza dialog zgody/odmowy. */
data class CommunityTermsPrompt(
    val serverId: String,
    val serverName: String,
    val terms: CommunityTerms,
    val presentedAtMillis: Long,
)
