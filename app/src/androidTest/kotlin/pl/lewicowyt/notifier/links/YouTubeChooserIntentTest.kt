package pl.lewicowyt.notifier.links

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class YouTubeChooserIntentTest {
    @Test
    fun alwaysAskDisablesAutomaticLaunchForSingleCandidate() {
        val target = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))

        val chooser = createAlwaysAskChooser(target)

        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertFalse(
            chooser.getBooleanExtra(Intent.EXTRA_AUTO_LAUNCH_SINGLE_CHOICE, true),
        )
        assertNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
    }

    @Test
    fun alwaysAskPassesThroughExplicitInitialIntents() {
        val target = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        val player = Intent(target).setPackage("com.example.player")
        val browser = Intent(target).setPackage("com.android.chrome")

        val chooser = createAlwaysAskChooser(target, listOf(player, browser))

        val alternatives = chooser.getParcelableArrayExtra(Intent.EXTRA_INITIAL_INTENTS)
            ?.mapNotNull { (it as? Intent)?.`package` }
        assertEquals(listOf("com.example.player", "com.android.chrome"), alternatives)
    }
}
