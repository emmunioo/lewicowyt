package pl.lewicowyt.notifier.community

import pl.lewicowyt.notifier.BuildConfig

/**
 * WBUDOWANY serwer domyślny lewicowYT (powiadomienia o nowych filmach YouTube) — działa przez UnifiedPush
 * (E2E, bez FCM — serwer nie wysyła niczego przez Google push). Jest ZAWSZE obecny i NIEUSUWALNY; można go zastąpić serwerem
 * społeczności jako „główny", ale nie da się go usunąć. Auto-dodawany przy starcie, regulamin akceptowany
 * automatycznie (wbudowany, nic nie trzeba robić).
 *
 * A-17: [ENDPOINT] to REALNY publiczny URL manifestu funkcji `api` serwera domyślnego (GCP Cloud Run, projekt
 * lewicowyt-notifier). Musi to być pełny HTTPS URL manifestu; register/unregister/manifest serwera muszą być
 * na TYM SAMYM hoście (anty-beacon). Budziki LEP idą przez relay zadeklarowany w manifeście (`push.relay`).
 */
object CommunityDefaults {
    /** Produkcyjny manifest serwera domyślnego (GCP Cloud Run). */
    const val PRODUCTION_ENDPOINT = "https://api-zxsxrppk7a-lm.a.run.app/manifest.json"

    /**
     * Manifest serwera domyślnego. Wydania (release) ZAWSZE używają [PRODUCTION_ENDPOINT]. Tylko build debug
     * może wskazać lokalny serwer testowy: `-Plewicowyt.localTestServer=http://10.0.2.2:2140/manifest.json`
     * (app/build.gradle.kts → BuildConfig.LOCAL_TEST_DEFAULT_ENDPOINT). Dawniej adres testowy wpisywano tu
     * ręcznie i trzeba było pamiętać o przywróceniu go przed wydaniem — teraz nie da się go wydać przez pomyłkę.
     */
    val ENDPOINT: String = BuildConfig.LOCAL_TEST_DEFAULT_ENDPOINT.ifBlank { PRODUCTION_ENDPOINT }

    /** Stabilne id wbudowanego serwera domyślnego (z endpointu). */
    val SERVER_ID: String = communityServerId(ENDPOINT)

    fun isDefault(serverId: String): Boolean = serverId == SERVER_ID
}
