package pl.lewicowyt.notifier.community

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * bramka zgody: bez decyzji dla aktualnego regulaminu nie łączymy, a zmiana treści
 * regulaminu (nawet bez podbicia wersji) kasuje wcześniejszą zgodę i wymusza pytanie.
 */
class CommunityConsentGateTest {

    private val serverId = "abc123"
    private val terms = CommunityTerms.of(versionLabel = "1", text = "Regulamin serwera.")

    private fun record(accepted: Boolean, forTerms: CommunityTerms = terms) =
        CommunityConsentGate.recordFor(
            serverId = serverId,
            terms = forTerms,
            accepted = accepted,
            presentedAtMillis = 1_000L,
            decidedAtMillis = 2_000L,
        )

    @Test
    fun `no stored decision requires consent`() {
        assertEquals(CommunityConsentState.CONSENT_REQUIRED, CommunityConsentGate.evaluate(null, terms))
        assertFalse(CommunityConsentGate.allowsConnection(null, terms))
    }

    @Test
    fun `accepted current terms allow connection`() {
        val stored = record(accepted = true)
        assertEquals(CommunityConsentState.ACCEPTED, CommunityConsentGate.evaluate(stored, terms))
        assertTrue(CommunityConsentGate.allowsConnection(stored, terms))
    }

    @Test
    fun `declined current terms block connection`() {
        val stored = record(accepted = false)
        assertEquals(CommunityConsentState.DECLINED, CommunityConsentGate.evaluate(stored, terms))
        assertFalse(CommunityConsentGate.allowsConnection(stored, terms))
    }

    @Test
    fun `changed terms revoke a prior acceptance and require re-consent`() {
        val storedForOldTerms = record(accepted = true)
        val newTerms = CommunityTerms.of(versionLabel = "1", text = "Regulamin serwera. Nowy punkt 5.")
        assertEquals(
            CommunityConsentState.CONSENT_REQUIRED,
            CommunityConsentGate.evaluate(storedForOldTerms, newTerms),
        )
        assertFalse(CommunityConsentGate.allowsConnection(storedForOldTerms, newTerms))
    }

    @Test
    fun `record keeps the full terms text hash and decision`() {
        val stored = record(accepted = false)
        assertEquals(terms.text, stored.termsText)
        assertEquals(terms.contentHash, stored.termsHash)
        assertEquals("1", stored.termsVersionLabel)
        assertFalse(stored.accepted)
        assertEquals(2_000L, stored.decidedAtMillis)
    }
}
