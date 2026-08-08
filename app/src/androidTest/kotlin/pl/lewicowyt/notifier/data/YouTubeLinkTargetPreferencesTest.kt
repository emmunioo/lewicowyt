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
            repository.setYouTubeLinkTarget(YouTubeLinkTarget.NEWPIPE)
            assertEquals(YouTubeLinkTarget.NEWPIPE, repository.current().youtubeLinkTarget)
        } finally {
            repository.setYouTubeLinkTarget(YouTubeLinkTarget.SYSTEM_DEFAULT)
        }
    }
}
