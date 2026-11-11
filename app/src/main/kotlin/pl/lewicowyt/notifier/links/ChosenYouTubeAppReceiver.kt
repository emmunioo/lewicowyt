package pl.lewicowyt.notifier.links

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import pl.lewicowyt.notifier.AppGraph
import pl.lewicowyt.notifier.data.isSafeAndroidPackageName

/**
 * Odbiera z systemowego choosera informację, którą aplikację wybrał użytkownik
 * (przez `EXTRA_CHOSEN_COMPONENT_INTENT_SENDER`), i zapamiętuje ten wybór jako
 * domyślną aplikację do otwierania YouTube. Dzięki temu przy trybie „Zapytaj i
 * zapamiętaj wybór" chooser pokazuje się raz, a potem link idzie wprost do
 * wybranej aplikacji — bez wpychania się oficjalnego YouTube.
 */
class ChosenYouTubeAppReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val component = chosenComponent(intent)
        val packageName = component?.packageName
            ?.takeIf(::isSafeAndroidPackageName)
            ?.takeUnless { it == context.applicationContext.packageName }
            ?: return
        val appContext = context.applicationContext
        AppGraph.initialize(appContext)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                AppGraph.preferences.setOtherYouTubeAppPackage(packageName)
            } catch (_: Exception) {
                // zapamiętanie wyboru to tylko wygoda – jak się nie uda, nic się nie stanie
            } finally {
                pending.finish()
            }
        }
    }

    private fun chosenComponent(intent: Intent): ComponentName? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT, ComponentName::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT)
        }
}
