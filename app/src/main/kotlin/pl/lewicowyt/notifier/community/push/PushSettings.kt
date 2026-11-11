package pl.lewicowyt.notifier.community.push

import android.content.Context

/**
 * JEDNO źródło prawdy dla konfiguracji powiadomień push. UnifiedPush jest GŁÓWNYM torem, FCM (Google) to
 * tylko AWARYJNY fallback — domyślnie wyłączony (apka nie kontaktuje Google).
 *
 * - [Mode.EMBEDDED] (domyślny): dystrybutor UnifiedPush WBUDOWANY w lewicowYT (bez osobnej apki, bez Google).
 *   Łączy się przez protokół LEP (long-poll) z serwerem/relayem i oddaje budziki do CommunityPushWorker.
 * - [Mode.EXTERNAL]: dystrybutor ZEWNĘTRZNY (np. ntfy). Moduł wbudowany jest wtedy całkowicie wyłączony.
 * - [Embedded.INSTANT]: usługa pierwszoplanowa trzyma long-poll → natychmiastowe budziki (stały pasek + bateria).
 * - [Embedded.SAVER]: WorkManager co ~15 min drenuje budziki (bez stałego paska i baterii, z opóźnieniem).
 *
 * SharedPreferences (synchronicznie), bo czyta to warstwa push z workerów/usługi/tła bez korutyn.
 */
object PushSettings {
    private const val PREFS = "push_settings"
    private const val KEY_MODE = "push_mode"
    private const val KEY_EMBEDDED = "embedded_mode"
    private const val KEY_FCM_FALLBACK = "fcm_fallback_enabled"

    enum class Mode { EMBEDDED, EXTERNAL }
    enum class Embedded { INSTANT, SAVER }

    private fun p(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun mode(c: Context): Mode =
        if (p(c).getString(KEY_MODE, Mode.EMBEDDED.name) == Mode.EXTERNAL.name) Mode.EXTERNAL else Mode.EMBEDDED

    fun setMode(c: Context, m: Mode) = p(c).edit().putString(KEY_MODE, m.name).apply()

    fun embedded(c: Context): Embedded =
        if (p(c).getString(KEY_EMBEDDED, Embedded.INSTANT.name) == Embedded.SAVER.name) Embedded.SAVER else Embedded.INSTANT

    fun setEmbedded(c: Context, e: Embedded) = p(c).edit().putString(KEY_EMBEDDED, e.name).apply()

    /** FCM = tryb AWARYJNY. Domyślnie WYŁĄCZONY — apka nie kontaktuje Google, dopóki user tego nie włączy. */
    fun fcmFallbackEnabled(c: Context): Boolean = p(c).getBoolean(KEY_FCM_FALLBACK, false)

    fun setFcmFallbackEnabled(c: Context, v: Boolean) = p(c).edit().putBoolean(KEY_FCM_FALLBACK, v).apply()
}
