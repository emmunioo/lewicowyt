package pl.lewicowyt.notifier.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubeLinkTargetPreferencesTest {
    @Test
    fun selectedTargetIsStoredAndReadFromDataStore() = runBlocking {
        val repository = PreferencesRepository(ApplicationProvider.getApplicationContext())
        try {
            repository.setOtherYouTubeAppPackage("com.android.calculator2")
            assertEquals(
                YouTubeLinkTarget.OTHER_APP,
                repository.current().youtubeLinkTarget,
            )
            assertEquals(
                "com.android.calculator2",
                repository.current().otherYouTubeAppPackage,
            )
        } finally {
            repository.setYouTubeLinkTarget(YouTubeLinkTarget.SYSTEM_DEFAULT)
        }
    }
}
