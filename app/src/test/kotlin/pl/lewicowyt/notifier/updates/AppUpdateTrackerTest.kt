package pl.lewicowyt.notifier.updates

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateTrackerTest {
    @Test
    fun `first installation does not show release notes`() {
        assertFalse(
            shouldShowWhatsNewAfterUpdate(
                currentVersionCode = 13,
                acknowledgedVersionCode = 0,
                installationWasUpdated = false,
            ),
        )
    }

    @Test
    fun `upgrade from a version without tracker shows release notes`() {
        assertTrue(
            shouldShowWhatsNewAfterUpdate(
                currentVersionCode = 13,
                acknowledgedVersionCode = 0,
                installationWasUpdated = true,
            ),
        )
    }

    @Test
    fun `newer version shows release notes only once`() {
        assertTrue(
            shouldShowWhatsNewAfterUpdate(
                currentVersionCode = 13,
                acknowledgedVersionCode = 12,
                installationWasUpdated = true,
            ),
        )
        assertFalse(
            shouldShowWhatsNewAfterUpdate(
                currentVersionCode = 13,
                acknowledgedVersionCode = 13,
                installationWasUpdated = true,
            ),
        )
    }

    @Test
    fun `downgrade does not show release notes`() {
        assertFalse(
            shouldShowWhatsNewAfterUpdate(
                currentVersionCode = 12,
                acknowledgedVersionCode = 13,
                installationWasUpdated = true,
            ),
        )
    }
}
