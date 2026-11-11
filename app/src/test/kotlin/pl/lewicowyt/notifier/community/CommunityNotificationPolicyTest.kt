package pl.lewicowyt.notifier.community

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * to APKA decyduje o powiadomieniu, nie serwer: domyślnie powiadamiamy ze wszystkiego,
 * a user wycisza per platforma lub wyłącza cały serwer. na główny można wybrać tylko
 * serwer, który sam się do tego w manifeście zgłosił.
 */
class CommunityNotificationPolicyTest {

    private fun server(
        enabled: Boolean = true,
        notifyEnabled: Boolean = true,
        muted: Set<String> = emptySet(),
        canBePrimary: Boolean = false,
    ) = CommunityServerRecord(
        serverId = "s",
        endpoint = "https://s.example/m",
        addedAtMillis = 0L,
        enabled = enabled,
        displayName = null,
        transport = CommunityTransportKind.HTTPS_POLL,
        lastManifestFetchMillis = 0L,
        notifyEnabled = notifyEnabled,
        notifyMuted = muted,
        canBePrimary = canBePrimary,
    )

    @Test
    fun `notifies from any platform by default`() {
        assertTrue(CommunityNotificationPolicy.shouldNotify(server(), "youtube"))
        assertTrue(CommunityNotificationPolicy.shouldNotify(server(), "twitter"))
    }

    @Test
    fun `muted platform is silenced case-insensitively, others still notify`() {
        val s = server(muted = setOf("twitter"))
        assertFalse(CommunityNotificationPolicy.shouldNotify(s, "Twitter"))
        assertTrue(CommunityNotificationPolicy.shouldNotify(s, "youtube"))
    }

    @Test
    fun `master notify off silences everything`() {
        assertFalse(CommunityNotificationPolicy.shouldNotify(server(notifyEnabled = false), "youtube"))
    }

    @Test
    fun `disabled server never notifies`() {
        assertFalse(CommunityNotificationPolicy.shouldNotify(server(enabled = false), "youtube"))
    }

    @Test
    fun `only servers declaring canBePrimary can be chosen as primary`() {
        assertFalse(CommunityNotificationPolicy.canSelectAsPrimary(server(canBePrimary = false)))
        assertTrue(CommunityNotificationPolicy.canSelectAsPrimary(server(canBePrimary = true)))
        assertFalse(
            CommunityNotificationPolicy.canSelectAsPrimary(server(enabled = false, canBePrimary = true)),
        )
    }
}
