package pl.lewicowyt.notifier.links

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import pl.lewicowyt.notifier.AppGraph

/** Niewyeksportowany trampoline dla kliknięć bezpośrednich powiadomień. */
class YouTubeLinkActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.initialize(applicationContext)
        val url = intent?.getStringExtra(EXTRA_URL)
        if (url == null) {
            finish()
            return
        }
        lifecycleScope.launch {
            val settings = AppGraph.preferences.current()
            AppGraph.youtubeLinks.open(
                url = url,
                target = settings.youtubeLinkTarget,
                otherAppPackage = settings.otherYouTubeAppPackage,
            )
            finish()
        }
    }

    companion object {
        const val EXTRA_URL = "youtube_url"
    }
}
