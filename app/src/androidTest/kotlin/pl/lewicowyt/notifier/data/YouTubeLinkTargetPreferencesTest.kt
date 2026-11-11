package pl.lewicowyt.notifier.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubeLinkTargetPreferencesTest {
    @Test
    fun selectedTargetIsStoredAndReadFromDataStore() = runBlocking {
        val repository = PreferencesRepository(ApplicationProvider.getApplicationContext())
        // A-58: test pisze do PRAWDZIWEGO DataStore aplikacji — zapamiętujemy i przywracamy PEŁNY stan
        // (cel linków ORAZ wybrany pakiet), żeby connectedAndroidTest nie nadpisał wyboru użytkownika.
        val before = repository.current()
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
            repository.restoreYouTubeLinkState(before.youtubeLinkTarget, before.otherYouTubeAppPackage)
        }
    }
}
