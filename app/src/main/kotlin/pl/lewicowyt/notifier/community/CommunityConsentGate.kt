package pl.lewicowyt.notifier.community

/**
 * czysta logika bramki zgody – bez bazy i bez sieci, łatwa do testów.
 * tożsamością regulaminu jest hash treści, więc każda zmiana wymusza ponowną zgodę,
 * nawet gdy user wcześniej się zgodził albo odmówił.
 */
object CommunityConsentGate {

    fun evaluate(stored: CommunityConsentRecord?, currentTerms: CommunityTerms): CommunityConsentState = when {
        stored == null -> CommunityConsentState.CONSENT_REQUIRED
        stored.termsHash != currentTerms.contentHash -> CommunityConsentState.CONSENT_REQUIRED // regulamin się zmienił
        stored.accepted -> CommunityConsentState.ACCEPTED
        else -> CommunityConsentState.DECLINED
    }

    /** czy przy tym regulaminie wolno w ogóle łączyć się z serwerem. */
    fun allowsConnection(stored: CommunityConsentRecord?, currentTerms: CommunityTerms): Boolean =
        evaluate(stored, currentTerms) == CommunityConsentState.ACCEPTED

    /** buduje rekord do zapisania po decyzji usera. odmowę też przechowujemy. */
    fun recordFor(
        serverId: String,
        terms: CommunityTerms,
        accepted: Boolean,
        presentedAtMillis: Long,
        decidedAtMillis: Long,
    ): CommunityConsentRecord = CommunityConsentRecord(
        serverId = serverId,
        termsVersionLabel = terms.versionLabel,
        termsText = terms.text,
        termsHash = terms.contentHash,
        presentedAtMillis = presentedAtMillis,
        decidedAtMillis = decidedAtMillis,
        accepted = accepted,
    )
}
