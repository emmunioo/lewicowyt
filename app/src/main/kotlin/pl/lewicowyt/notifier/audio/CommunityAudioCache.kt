package pl.lewicowyt.notifier.audio

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import pl.lewicowyt.notifier.community.COMMUNITY_USER_AGENT
import pl.lewicowyt.notifier.community.push.CommunityTorClient
import pl.lewicowyt.notifier.network.DerivedHttpClients
import pl.lewicowyt.notifier.network.PrivacyHttpClient
import pl.lewicowyt.notifier.video.MediaPrecheck
import pl.lewicowyt.notifier.video.MediaPrechecks
import pl.lewicowyt.notifier.video.PrecheckDecision

/**
 * Pliki multimedialne (dźwięk Opus, filmy MP4) z serwerów społeczności. Pobieramy je CAŁE do pamięci
 * podręcznej aplikacji i dopiero wtedy odtwarzamy z dysku — z tych samych powodów co obrazy:
 *  - ta sama prywatna ścieżka sieciowa: prywatny klient bez przekierowań, a dla .onion WYŁĄCZNIE Tor
 *    (fail-closed: Tor niegotowy = brak pobrania, nigdy połączenie na ślepo);
 *  - odtwarzacz nie łączy się sam z siecią (brak strumieniowania z zapytaniami Range po kawałku, które
 *    zdradzałyby serwerowi, w którym momencie ktoś przewija, słucha w pętli albo przyspiesza);
 *  - odtwarzanie w tle, w pętli i przyspieszone nie zależy od zasięgu.
 * Plik musi zaczynać się nagłówkiem swojego kontenera — inaczej go odrzucamy.
 */
object CommunityMediaCache {
    enum class Kind(val dir: String, val maxBytes: Long, val maxCacheBytes: Long) {
        AUDIO("community-audio", 80L * 1024 * 1024, 400L * 1024 * 1024),  // ok. 3 h mowy w Opusie
        VIDEO("community-video", 200L * 1024 * 1024, 1024L * 1024 * 1024),
    }

    // C-06: blokada per plik — pobieranie dużego filmu nie wstrzymuje odtworzenia innego nagrania.
    // E-08: stała tablica blokad (striping po hashu nazwy) zamiast mapy ze sprzątaniem (D-19l) — sprzątanie
    // mogło usunąć blokadę już pobraną, a niezałożoną, i dwa pobrania pisały do jednego .part. Inne pliki
    // trafiają do tej samej blokady tylko z prawdopodobieństwem 1/LOCK_STRIPES.
    private val locks = Array(LOCK_STRIPES) { Mutex() }
    private val trimLock = Any()
    private const val STALE_PART_MS = 60L * 60L * 1000L // osierocone .part (ubity proces) starsze niż 1 h
    private const val LOCK_STRIPES = 32
    private const val PROGRESS_WINDOW_NS = 60L * 1_000_000_000L
    private const val MIN_PROGRESS_BYTES = 16L * 1024

    /** wynik pobrania: plik, odmowa z powodem (zły format wykryty na początku pliku) albo błąd sieci/limitu */
    sealed interface Fetch {
        data class Ok(val file: File) : Fetch
        data class Rejected(val reason: String) : Fetch
        data object Failed : Fetch
    }

    /**
     * Pobiera plik, SPRAWDZAJĄC format w trakcie: [precheck] dostaje kolejne początkowe bajty i decyduje,
     * czy plik się nadaje. Niedozwolony format = przerwanie pobierania po kilkuset bajtach / KB (oszczędność
     * transferu — po to są limity Opus / H.265 / 30 kl/s) i skasowanie tego, co już przyszło.
     * Wciąż JEDNO zwykłe zapytanie GET, bez zapytań o fragmenty (Range).
     */
    suspend fun fetch(
        context: Context,
        url: String,
        kind: Kind,
        precheck: MediaPrecheck?,
        declaredBytes: Long? = null,
    ): Fetch = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, kind.dir).apply { mkdirs() }
        val target = File(dir, sha256(url))
        // D-09: zadeklarowany rozmiar jest WIĄŻĄCY (+10% i 64 KB zapasu) — serwer nie przemyci większego pliku
        // na danych komórkowych pod deklaracją „1 MB"
        val limit = declaredBytes?.takeIf { it > 0 }?.let { minOf(kind.maxBytes, it + it / 10 + 64 * 1024) } ?: kind.maxBytes
        locks[Math.floorMod(target.name.hashCode(), LOCK_STRIPES)].withLock {
            if (target.isFile && target.length() > 0) {
                target.setLastModified(System.currentTimeMillis()) // LRU: ostatnio odtwarzany zostaje
                return@withContext Fetch.Ok(target)
            }
            val tmp = File(dir, target.name + ".part")
            // C-06: anulowanie (zamknięcie okna filmu, inne nagranie) PRZERYWA pobieranie: nie łapiemy
            // CancellationException — kasujemy fragment i przekazujemy anulowanie dalej
            val result = try {
                download(context, url, tmp, limit, precheck)
            } catch (e: CancellationException) {
                tmp.delete()
                throw e
            } catch (_: Exception) {
                tmp.delete() // D-07: call.cancel() daje IOException — fragment kasujemy, zanim zgłosimy anulowanie
                currentCoroutineContext().ensureActive() // przerwane przez cancel() = anulowanie, nie „błąd sieci"
                Fetch.Failed
            }
            if (result !is Fetch.Ok || !hasContainerHeader(tmp, kind)) {
                tmp.delete()
                return@withContext if (result is Fetch.Rejected) result else Fetch.Failed
            }
            if (!tmp.renameTo(target)) { tmp.delete(); return@withContext Fetch.Failed }
            trim(dir, kind.maxCacheBytes)
            Fetch.Ok(target)
        }
    }

    /** Usuwa plik z pamięci podręcznej (np. odrzucony po pełnym sprawdzeniu) — nie zajmuje miejsca. */
    fun discard(context: Context, url: String, kind: Kind) {
        File(File(context.cacheDir, kind.dir), sha256(url)).delete()
    }

    private suspend fun download(context: Context, url: String, out: File, maxBytes: Long, precheck: MediaPrecheck?): Fetch {
        val target = url.toHttpUrlOrNull() ?: return Fetch.Failed
        val onion = CommunityTorClient.isOnion(url)
        val base = if (onion) CommunityTorClient.get(context) ?: return Fetch.Failed else PrivacyHttpClient.get(context)
        // T-11: klient budowany raz na klienta bazowego (nie przy każdym pliku)
        val client = DerivedHttpClients.get(
            base,
            DerivedHttpClients.Spec(
                connectTimeoutMillis = TimeUnit.SECONDS.toMillis(if (onion) 60L else 15L),
                readTimeoutMillis = TimeUnit.SECONDS.toMillis(if (onion) 90L else 30L),
                // D-06: bez własnego limitu całości (film 200 MB na wolnym łączu nigdy by się nie pobrał) — zamiast
                // tego w readBody przerywamy, gdy przez minutę przychodzi mniej niż MIN_PROGRESS_BYTES (zawieszony transfer)
                callTimeoutMillis = null,
                noRedirects = true,
            ),
        )
        val request = Request.Builder().url(target).get().header("User-Agent", COMMUNITY_USER_AGENT).build()
        val call = client.newCall(request)
        // C-06: anulowanie korutyny zrywa połączenie (blokujący read() wychodzi od razu, bez dociągania reszty)
        return coroutineScope {
            val watcher = launch(Dispatchers.IO) { try { awaitCancellation() } finally { call.cancel() } }
            try { readBody(call, out, maxBytes, precheck) } finally { watcher.cancel() }
        }
    }

    private suspend fun readBody(call: okhttp3.Call, out: File, maxBytes: Long, precheck: MediaPrecheck?): Fetch {
        call.execute().use { response ->
            if (!response.isSuccessful) return Fetch.Failed
            val body = response.body
            if (body.contentLength() > maxBytes) return Fetch.Failed
            body.byteStream().use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(16 * 1024)
                    var total = 0L
                    // początek pliku w pamięci do decyzji precheck (rośnie do MAX_PREFIX_BYTES)
                    var prefix = if (precheck != null) ByteArray(64 * 1024) else ByteArray(0)
                    var prefixLen = 0
                    var decided = precheck == null
                    var windowStart = System.nanoTime()
                    var windowBytes = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        windowBytes += n
                        val now = System.nanoTime()
                        if (now - windowStart >= PROGRESS_WINDOW_NS) {
                            if (windowBytes < MIN_PROGRESS_BYTES) return Fetch.Failed // D-06: transfer stoi
                            windowStart = now
                            windowBytes = 0L
                        }
                        if (total > maxBytes) return Fetch.Failed
                        output.write(buf, 0, n)
                        if (!decided) {
                            if (prefixLen + n > prefix.size) prefix = prefix.copyOf(minOf(maxOf(prefix.size * 2, prefixLen + n), MediaPrechecks.MAX_PREFIX_BYTES + buf.size))
                            System.arraycopy(buf, 0, prefix, prefixLen, n)
                            prefixLen += n
                            when (val d = safeCheck(precheck!!, prefix, prefixLen)) {
                                PrecheckDecision.Accept -> { decided = true; prefix = ByteArray(0) }
                                is PrecheckDecision.Reject -> return Fetch.Rejected(d.reason) // przerwij: reszty nie pobieramy
                                PrecheckDecision.NeedMore -> if (prefixLen >= MediaPrechecks.MAX_PREFIX_BYTES) {
                                    return Fetch.Rejected("Nie da się sprawdzić formatu pliku — nie pobieram.")
                                }
                            }
                        }
                    }
                    // plik krótszy niż potrzeba do decyzji — ostatnia próba na całości
                    if (!decided) {
                        when (val d = safeCheck(precheck!!, prefix, prefixLen)) {
                            PrecheckDecision.Accept -> Unit
                            is PrecheckDecision.Reject -> return Fetch.Rejected(d.reason)
                            PrecheckDecision.NeedMore -> return Fetch.Rejected("Plik jest niepełny albo uszkodzony.")
                        }
                    }
                }
            }
        }
        return Fetch.Ok(out)
    }

    /** C-13: wyjątek parsera (uszkodzony nagłówek) = odmowa z czytelnym powodem, nie „błąd sieci". */
    private fun safeCheck(precheck: MediaPrecheck, prefix: ByteArray, length: Int): PrecheckDecision =
        try { precheck.check(prefix, length) } catch (_: RuntimeException) { PrecheckDecision.Reject("Uszkodzony plik — nie pobieram.") }

    /** dźwięk: Ogg („OggS") albo EBML (WebM/Matroska); film: MP4 (pudełko „ftyp" na początku). */
    internal fun hasContainerHeader(file: File, kind: Kind): Boolean = runCatching {
        val head = ByteArray(8)
        file.inputStream().use { if (it.read(head) != 8) return false }
        when (kind) {
            Kind.AUDIO -> String(head, 0, 4, Charsets.ISO_8859_1) == "OggS" ||
                (head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() && head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte())
            Kind.VIDEO -> String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp"
        }
    }.getOrDefault(false)

    /** Najdawniej odtwarzane pliki wypadają, gdy cache przekroczy limit; osierocone .part (C-12) też. */
    private fun trim(dir: File, maxCacheBytes: Long) = synchronized(trimLock) {
        val staleBefore = System.currentTimeMillis() - STALE_PART_MS
        dir.listFiles { f -> f.isFile && f.name.endsWith(".part") && f.lastModified() < staleBefore }?.forEach { it.delete() }
        val files = dir.listFiles { f -> f.isFile && !f.name.endsWith(".part") }?.sortedBy { it.lastModified() } ?: return
        var size = files.sumOf { it.length() }
        for (f in files) {
            if (size <= maxCacheBytes) break
            size -= f.length()
            f.delete()
        }
    }

    /** Czyści pamięć podręczną multimediów (C-12: przy czyszczeniu historii i usuwaniu serwera). */
    fun clear(context: Context) {
        Kind.entries.forEach { File(context.cacheDir, it.dir).deleteRecursively() }
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** Dźwięk (Opus) — skrót do [CommunityMediaCache] dla odtwarzacza w tle; nie-Opus przerywany na początku pliku. */
object CommunityAudioCache {
    suspend fun fetch(context: Context, url: String): CommunityMediaCache.Fetch =
        CommunityMediaCache.fetch(context, url, CommunityMediaCache.Kind.AUDIO, MediaPrechecks.opusAudio())
}
