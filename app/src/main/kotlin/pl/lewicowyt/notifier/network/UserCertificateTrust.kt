package pl.lewicowyt.notifier.network

import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/*
 * =====================================================================================================================
 * NOTA DLA AUDYTORÓW — po co istnieje ten plik
 * =====================================================================================================================
 * Domyślnie aplikacja ufa WYŁĄCZNIE certyfikatom z magazynu SYSTEMOWEGO (res/xml/network_security_config.xml:
 * <certificates src="system"/>), więc certyfikat CA zainstalowany przez użytkownika NIE jest honorowany i ruch TLS nie
 * może być podsłuchany przez proxy typu MITM. To świadoma, bezpieczna konfiguracja i POZOSTAJE DOMYŚLNĄ.
 *
 * Dla świadomego użytkownika (programista, analiza ruchu własnym proxy, sieć firmowa z inspekcją TLS) istnieje
 * UKRYTE ustawienie „Ufaj certyfikatom użytkownika” ([PrivacySettings.trustUserCertificates]):
 *   - ukryte: widoczne dopiero po odblokowaniu diagnostyki (kilka stuknięć w kartę DNS w Ustawieniach),
 *   - domyślnie WYŁĄCZONE; stan jest przechowywany lokalnie (SharedPreferences) i nigdzie nie wysyłany,
 *   - po włączeniu zdarzenie jest zapisywane w dzienniku diagnostycznym, a w ustawieniach widać ostrzeżenie.
 *
 * Mechanizm: zamiast statycznie dopisywać <certificates src="user"/> do network_security_config (co zmieniłoby
 * zachowanie dla WSZYSTKICH, na stałe, i nie dałoby się tego wyłączyć bez nowego wydania), klient HTTP używa
 * [SwitchableTrustManager]. Zawsze najpierw sprawdza łańcuch domyślnym menedżerem systemu (identycznie jak bez tej
 * klasy); tylko gdy ustawienie jest włączone I sprawdzenie systemowe się nie powiodło, próbuje magazynu
 * „AndroidCAStore” (zawiera CA systemowe + użytkownika). Weryfikacja nazwy hosta (OkHostnameVerifier) działa jak zwykle.
 *
 * ZAKRES (świadomie wąski):
 *   + wspólny klient [PrivacyHttpClient] (manifesty i obrazy serwerów społeczności po HTTPS, strony YouTube, GitHub),
 *   - NIE dotyczy zapytań DNS-over-HTTPS do AdGuard (osobne klienty z IP-bootstrapem — szyfrowany DNS ma pozostać
 *     odporny na MITM), ani ruchu .onion (Tor szyfruje i uwierzytelnia usługę, a TLS nie jest używany),
 *   - NIE dotyczy WebView (ma własny magazyn zaufania systemu).
 *
 * RYZYKO, które audytor powinien ocenić: przy włączonym ustawieniu każdy CA użytkownika może podszyć się pod dowolny
 * serwer, w tym pod serwer aktualizacji. Aktualizacje APK mają jednak niezależne zabezpieczenia (podpis APK zgodny
 * z zainstalowanym, wyższy versionCode, skrót SHA-256), więc podmiana pakietu przez MITM nie przejdzie walidacji;
 * natomiast treści serwerów społeczności, adresy dostarczania push i metadane wydań GitHub byłyby widoczne dla proxy.
 * Dlatego przełącznik jest ukryty, wyłączony domyślnie i opisany ostrzeżeniem.
 */
internal class SwitchableTrustManager(
    private val system: X509TrustManager,
    private val userCertificatesEnabled: () -> Boolean,
    // fabryka menedżera z magazynem „AndroidCAStore”; wołana przy KAŻDYM zapasowym sprawdzeniu, żeby uwzględnić CA
    // zainstalowane przez użytkownika już po starcie procesu
    private val userStoreTrustManager: () -> X509TrustManager? = { UserCertificateTrust.androidCaStoreTrustManager() },
) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
        system.checkClientTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        try {
            system.checkServerTrusted(chain, authType) // zawsze pierwszy: zachowanie domyślne bez zmian
        } catch (systemFailure: java.security.cert.CertificateException) {
            if (!runCatching(userCertificatesEnabled).getOrDefault(false)) throw systemFailure
            val extended = runCatching { userStoreTrustManager() }.getOrNull() ?: throw systemFailure
            try {
                extended.checkServerTrusted(chain, authType)
            } catch (userFailure: java.security.cert.CertificateException) {
                userFailure.addSuppressed(systemFailure)
                throw userFailure
            }
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> {
        val base = system.acceptedIssuers
        if (!runCatching(userCertificatesEnabled).getOrDefault(false)) return base
        val extra = runCatching { userStoreTrustManager()?.acceptedIssuers }.getOrNull() ?: return base
        return (base.toList() + extra.toList()).distinct().toTypedArray()
    }
}

internal object UserCertificateTrust {
    /** Domyślny menedżer systemu (honoruje network_security_config → tylko CA systemowe). */
    fun systemTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /** Menedżer oparty o „AndroidCAStore” — CA systemowe ORAZ zainstalowane przez użytkownika. */
    fun androidCaStoreTrustManager(): X509TrustManager? = runCatching {
        val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(store)
        factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
    }.getOrNull()

    /** Para (fabryka gniazd, menedżer zaufania) dla OkHttp; ustawienie czytane przy każdym uścisku dłoni. */
    fun forOkHttp(userCertificatesEnabled: () -> Boolean): Pair<SSLSocketFactory, X509TrustManager> {
        val trustManager = SwitchableTrustManager(systemTrustManager(), userCertificatesEnabled)
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
        return context.socketFactory to trustManager
    }
}
