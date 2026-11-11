package pl.lewicowyt.notifier.network

import android.content.Context

/**
 * Ustawienia prywatności sieci czytane synchronicznie (SharedPreferences) z warstwy sieci i push — bez korutyn.
 *
 * - [strictDns]: awaria szyfrowanego DNS (AdGuard DoH) NIE przełącza już na jawny DNS systemu; kto blokuje dwa adresy
 *   IP resolvera, nie dowie się wtedy nazw hostów (audyt T-09). Domyślnie wyłączone — priorytetem jest łączność.
 * - [registerViaTor]: rejestracja i wyrejestrowanie push także u serwerów w zwykłym internecie idą przez wbudowany
 *   Tor, więc operator nie połączy starej i nowej tożsamości po adresie IP (audyt T-10). Gdy Tor nie jest gotowy,
 *   rejestracja jest pomijana (fail-closed). Domyślnie wyłączone.
 */
object PrivacySettings {
    private const val PREFS = "privacy_settings"
    private const val KEY_STRICT_DNS = "strict_dns"
    private const val KEY_REGISTER_VIA_TOR = "register_via_tor"
    private const val KEY_TRUST_USER_CERTS = "trust_user_certificates"

    private fun p(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun strictDns(c: Context): Boolean = p(c).getBoolean(KEY_STRICT_DNS, false)
    fun setStrictDns(c: Context, v: Boolean) = p(c).edit().putBoolean(KEY_STRICT_DNS, v).apply()

    /**
     * UKRYTE (diagnostyka): zaufanie do certyfikatów CA zainstalowanych przez użytkownika, żeby aplikacja działała
     * przez proxy z inspekcją TLS (np. do debugowania własnym proxy). Domyślnie WYŁĄCZONE. Pełne uzasadnienie i zakres
     * — nota dla audytorów w [UserCertificateTrust.kt].
     */
    fun trustUserCertificates(c: Context): Boolean = p(c).getBoolean(KEY_TRUST_USER_CERTS, false)

    fun setTrustUserCertificates(c: Context, v: Boolean) = p(c).edit().putBoolean(KEY_TRUST_USER_CERTS, v).apply()

    fun registerViaTor(c: Context): Boolean = p(c).getBoolean(KEY_REGISTER_VIA_TOR, false)
    fun setRegisterViaTor(c: Context, v: Boolean) = p(c).edit().putBoolean(KEY_REGISTER_VIA_TOR, v).apply()
}
