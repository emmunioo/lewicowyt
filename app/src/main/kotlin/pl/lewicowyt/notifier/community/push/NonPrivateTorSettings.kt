package pl.lewicowyt.notifier.community.push

import android.content.Context

/**
 * JEDNO źródło prawdy dla przełącznika „Nieprywatny Tor".
 *
 * A-27 (aktualny stan): flaga jest domyślnie WŁĄCZONA, ale DZIAŁA tylko razem z „Awaryjnym push przez Google
 * (FCM)" ([PushSettings.fcmFallbackEnabled], domyślnie WYŁĄCZONY) — bez FCM nie ma czego dopuszczać. Gdy obie
 * są włączone, serwer .onion, który tego zażąda w manifeście (`push.requiresNonPrivateTor`) i napisze to w
 * regulaminie, może dostarczać budziki przez FCM: Tor służy wtedy TYLKO do omijania CGNAT / ukrycia IP
 * serwera, a NIE do anonimowości subskrybenta (Google widzi, że urządzenie subskrybuje ten serwer).
 *
 * Serwer .onion, który NIE wymaga tego trybu, jest w pełni prywatny NIEZALEŻNIE od tej flagi — jego config
 * FCM jest odrzucany już przy parsowaniu manifestu (CommunityManifestParser). Głównym torem push dla
 * WSZYSTKICH serwerów (także domyślnego) jest UnifiedPush (wbudowany LEP albo zewnętrzny dystrybutor).
 *
 * Trzymamy to w SharedPreferences (synchronicznie), bo czyta to warstwa push z workerów/tła, bez korutyn.
 */
object NonPrivateTorSettings {
    private const val PREFS = "push_settings"
    private const val KEY = "nonprivate_tor_enabled"

    fun isEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, enabled).apply()
    }
}
