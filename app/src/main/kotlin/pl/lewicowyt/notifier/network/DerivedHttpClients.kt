package pl.lewicowyt.notifier.network

import java.util.WeakHashMap
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * T-11: klienci HTTP „pochodni" — ten sam klient bazowy (pula połączeń, dispatcher, DNS, Tor), ale inne limity
 * czasu albo bez podążania za przekierowaniami. Dawniej budowane `newBuilder()…build()` przy KAŻDYM żądaniu
 * (manifest, obraz, plik dźwiękowy, tekst z YouTube); teraz raz na parę (klient bazowy, ustawienia).
 * Wynik jest identyczny z dawnym `newBuilder()` — to samo ustawienie daje tego samego klienta.
 *
 * Klucz bazowy jest słaby: po restarcie wbudowanego Tora stary klient Tora i jego pochodne znikają z pamięci.
 */
internal object DerivedHttpClients {

    /** Ustawienia klienta pochodnego. null = jak w kliencie bazowym (dokładnie jak brak wywołania w builderze). */
    data class Spec(
        val connectTimeoutMillis: Long? = null,
        val readTimeoutMillis: Long? = null,
        val callTimeoutMillis: Long? = null,
        // true = bez podążania za przekierowaniami (false = jak w kliencie bazowym)
        val noRedirects: Boolean = false,
    )

    private val cache = WeakHashMap<OkHttpClient, HashMap<Spec, OkHttpClient>>()

    fun get(base: OkHttpClient, spec: Spec): OkHttpClient = synchronized(cache) {
        cache.getOrPut(base) { HashMap() }.getOrPut(spec) { build(base, spec) }
    }

    internal fun build(base: OkHttpClient, spec: Spec): OkHttpClient = base.newBuilder().apply {
        spec.connectTimeoutMillis?.let { connectTimeout(it, TimeUnit.MILLISECONDS) }
        spec.readTimeoutMillis?.let { readTimeout(it, TimeUnit.MILLISECONDS) }
        spec.callTimeoutMillis?.let { callTimeout(it, TimeUnit.MILLISECONDS) }
        if (spec.noRedirects) {
            followRedirects(false)
            followSslRedirects(false)
        }
    }.build()
}
