package pl.lewicowyt.notifier.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AccentColorPersistenceTest {
    @Test
    fun datastoreColorHasPriorityOverBackup() {
        assertEquals(
            0xFFFF69B4L,
            resolveAccentColor(stored = 0xFFFF69B4L, backup = 0xFFFF0000L),
        )
    }

    @Test
    fun backupRestoresColorWhenDatastoreKeyIsMissing() {
        assertEquals(
            0xFFFF69B4L,
            resolveAccentColor(stored = null, backup = 0xFFFF69B4L),
        )
    }

    @Test
    fun defaultIsUsedOnlyWhenBothCopiesAreUnavailable() {
        assertEquals(
            DEFAULT_ACCENT_COLOR_ARGB,
            resolveAccentColor(stored = null, backup = null),
        )
    }
}
