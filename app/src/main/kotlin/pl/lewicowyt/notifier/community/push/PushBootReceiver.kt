package pl.lewicowyt.notifier.community.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * B-06: po restarcie telefonu i po aktualizacji aplikacji podnosi odbiór wbudowanego push (usługa instant
 * albo worker oszczędny). BOOT_COMPLETED i MY_PACKAGE_REPLACED to wyjątki od zakazu startu usługi
 * pierwszoplanowej z tła, a typ specialUse (inaczej niż dataSync od Androida 15) wolno z nich uruchomić.
 * Gdyby start mimo to się nie udał, odbiór przejmuje worker-siatka (WorkManager wznawia się po restarcie sam).
 */
class PushBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> runCatching { EmbeddedPush.applyRunState(context.applicationContext) }
        }
    }
}
