package pl.lewicowyt.notifier.community

import android.content.Context

/**
 * Regulamin APLIKACJI pokazywany przy pierwszym uruchomieniu (zasłona na całym ekranie, jak przy zmianie regulaminu
 * serwera domyślnego). Do chwili akceptacji aplikacja NIE łączy się z serwerem domyślnym (ani nie rejestruje u niego
 * push). Zmiana treści = podniesienie [VERSION] → zgoda zostanie poproszona ponownie.
 */
object AppTerms {
    const val VERSION = "1"

    val TEXT: String = """
Regulamin aplikacji lewicowYT

1. Czym jest aplikacja
lewicowYT pokazuje powiadomienia o nowych filmach wybranych twórców YouTube, prowadzi lokalną historię i wyświetla treści z serwerów społeczności. Jest projektem niezależnym — nie jest związana z YouTube ani z Google. Wersja beta jest udostępniana bez gwarancji.

2. Twoje dane
Historia, ustawienia i wybory twórców zostają na Twoim telefonie. Autor aplikacji nie zbiera identyfikatorów urządzenia ani konta, a aplikacja nie zawiera analityki ani reklam od autora.

3. Serwer domyślny
Powiadomienia o nowych filmach dostarcza domyślnie serwer autora aplikacji, działający w chmurze Google Cloud. Jest to serwer DOMYŚLNY — możesz go w każdej chwili zmienić na dowolny inny serwer społeczności (także dostępny przez Tor, adres .onion) w ustawieniach serwerów społeczności.
Akceptując ten regulamin ZEZWALASZ aplikacji na połączenia z serwerem domyślnym. Do czasu akceptacji aplikacja się z nim nie łączy.
Co widzi serwer domyślny i jego operator hostingu: Twój adres IP i czas połączeń oraz — po rejestracji powiadomień — ulotny adres dostarczania i Twój klucz publiczny. Treść powiadomień jest szyfrowana end-to-end i nie jest dla nich czytelna. Serwer nie zna wybranych przez Ciebie twórców.
Serwer domyślny ma też własny regulamin; gdy się zmieni, aplikacja poprosi Cię o ponowną zgodę, a do czasu zgody nie będzie z nim współpracować.

4. Inne połączenia sieciowe
Aby działać, aplikacja łączy się z publicznymi stronami i kanałami RSS YouTube, ze szyfrowanym DNS (AdGuard), z serwerami społeczności, które sama dodasz, oraz z GitHubem, gdy sprawdza dostępność aktualizacji. Każdy z tych podmiotów widzi Twój adres IP.

5. Zmiana decyzji
Serwer domyślny możesz zastąpić własnym serwerem głównym w ustawieniach. Brak akceptacji tego regulaminu oznacza, że aplikacja nie będzie korzystać z serwera domyślnego.
""".trimIndent()

    private const val PREFS = "app_terms"
    private const val KEY_ACCEPTED_VERSION = "accepted_version"

    private fun p(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun accepted(c: Context): Boolean = p(c).getString(KEY_ACCEPTED_VERSION, null) == VERSION

    fun accept(c: Context) {
        p(c).edit().putString(KEY_ACCEPTED_VERSION, VERSION).apply()
    }
}
