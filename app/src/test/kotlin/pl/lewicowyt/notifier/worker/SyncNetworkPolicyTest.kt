package pl.lewicowyt.notifier.worker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncNetworkPolicyTest {
    @Test
    fun unavailableNetworkNeverAllowsSynchronization() {
        assertFalse(SyncNetworkAccess.UNAVAILABLE.allowsSync(allowMobileData = false))
        assertFalse(SyncNetworkAccess.UNAVAILABLE.allowsSync(allowMobileData = true))
    }

    @Test
    fun meteredNetworkRequiresMobileDataPermission() {
        assertFalse(SyncNetworkAccess.METERED.allowsSync(allowMobileData = false))
        assertTrue(SyncNetworkAccess.METERED.allowsSync(allowMobileData = true))
    }

    @Test
    fun unmeteredNetworkIsAlwaysAllowed() {
        assertTrue(SyncNetworkAccess.UNMETERED.allowsSync(allowMobileData = false))
        assertTrue(SyncNetworkAccess.UNMETERED.allowsSync(allowMobileData = true))
    }
}
