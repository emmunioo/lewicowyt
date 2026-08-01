package pl.lewicowyt.notifier.images

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledAvatarStoreTest {
    @Test
    fun `accepts only safe jxl asset identifiers`() {
        assertTrue(
            BundledAvatarStore.isBundledAvatarUrl(
                "asset://bundled-avatars/ralindel.jxl",
            ),
        )
        assertFalse(
            BundledAvatarStore.isBundledAvatarUrl(
                "asset://bundled-avatars/../creators.json",
            ),
        )
        assertFalse(
            BundledAvatarStore.isBundledAvatarUrl(
                "https://example.org/ralindel.jxl",
            ),
        )
    }
}
