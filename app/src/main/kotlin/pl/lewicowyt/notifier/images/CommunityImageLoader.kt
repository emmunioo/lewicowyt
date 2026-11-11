package pl.lewicowyt.notifier.images

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import pl.lewicowyt.notifier.community.push.CommunityTorClient
import pl.lewicowyt.notifier.community.COMMUNITY_USER_AGENT
import pl.lewicowyt.notifier.community.toCommunityUrlOrNull
import pl.lewicowyt.notifier.data.DataRetentionPolicy
import pl.lewicowyt.notifier.network.DerivedHttpClients
import pl.lewicowyt.notifier.network.PrivacyHttpClient

/**
 * Ładowarka obrazów z serwerów społeczności (JXL/AVIF/WEBP). Osobna od [JxlImageCache],
 * które jest ZABLOKOWANE do hostów YouTube — tu host to zaufany serwer dodany przez usera.
 * JXL dekodujemy przez NativeJxl (własny most do libjxl) (Android nie ma natywnego dekodera); resztę BitmapFactory.
 *
 * Trwałość: obraz pobieramy RAZ i zapisujemy na dysk (cache aplikacji), adresowany treścią
 * (SHA-256), tak samo jak awatary YouTube. Dzięki temu po restarcie procesu nie pobieramy
 * go od nowa — wcześniej cache był tylko w RAM-ie, więc obrazy „znikały" i długo się ładowały.
 *
 * Prywatność: pobieramy przez prywatny klient (bez przekierowań, bez danych usera), tylko po
 * https albo http w LAN. Serwer widzi jedynie zwykły GET obrazka, który sam wystawił.
 */
object CommunityImageLoader {
    private const val MAX_BYTES = 6_000_000L

    // F-07: twardy limit wymiarów dekodowanego obrazu (4096 na bok ⇒ ≤ ~16,7 Mpx). Chroni przed
    // „pixel-bombą": mały plik deklarujący gigapiksele nie zaalokuje setek MB/GB i nie ubije procesu.
    private const val MAX_IMAGE_DIM = 4_096

    // Do WYŚWIETLANIA w feedzie nie potrzeba pełnej rozdzielczości — dekodujemy najwyżej do 1280 px na bok.
    // Zysk: ~kilkukrotnie mniej RAM-u na bitmapę (cache mieści dużo więcej) i szybszy dekod JXL. Pełną
    // jakość i tak pobieramy osobno przy zapisie oryginału (fetchOriginal) / otwarciu w przeglądarce.
    private const val DISPLAY_MAX_DIM = 1_280


    // Cache RAM dobierany do STERTY aplikacji (~¼ dostępnej pamięci, w granicach 48–256 MB). Stałe 32 MB
    // było za małe: jeden bitmap 1080×1350 to ~5,8 MB, więc feed z karuzelami stale eksmitował i zmuszał
    // do ciągłego ponownego dekodowania JXL. Większy cache = powrót do obrazu jest natychmiastowy.
    private fun memoryCacheKib(): Int {
        val maxKib = (Runtime.getRuntime().maxMemory() / 1024L).toInt()
        return (maxKib / 4).coerceIn(48 * 1024, 256 * 1024)
    }

    // Maksimum równoległych pobrań+dekodów naraz (jak przeglądarka otwierająca wiele połączeń). 10 = płynne
    // ładowanie na LAN; nad Torem i tak ogranicza nas latencja obwodów, ale nie szkodzi.
    private const val LOADER_PARALLELISM = 10
    // J-05: łączny limit jednego pobrania obrazu (clearnet i .onion)
    private const val CALL_TIMEOUT_SEC = 120L
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val loaderDispatcher = Dispatchers.IO.limitedParallelism(LOADER_PARALLELISM)
    private val loaderScope = CoroutineScope(SupervisorJob() + loaderDispatcher)

    // Deduplikacja: gdy ten sam URL jest potrzebny w kilku miejscach naraz (np. karuzela, powrót w feedzie),
    // pobieramy/dekodujemy go DOKŁADNIE RAZ, a wszyscy czekają na ten sam wynik — zamiast N kopii tej pracy.
    private val inFlight = HashMap<String, Deferred<Bitmap?>>()
    private val inFlightLock = Any()

    // gdy serwer przyśle „ciężki" plik, apka może go sama przekompresować do JXL, żeby
    // zajmował mniej miejsca na dysku (ustawienie w aplikacji, domyślnie włączone).
    @Volatile
    var recompressLargeImages: Boolean = true
    private const val RECOMPRESS_OVER_BYTES = 400 * 1024 // ~400 KB – powyżej próbujemy skompresować
    private const val RECOMPRESS_JXL_QUALITY = 60        // kompromis: akceptowalna jakość, małe KB
    private const val MAX_PARALLEL_ENCODES = 2           // A-42: enkody JXL naraz (pamięć/termika)

    // ustawienia z preferencji (synchronizowane w AppGraph):
    // czy w ogóle pobierać obrazy feedu; czy pobierać animacje WebP (transfer/pamięć).
    @Volatile
    var downloadImages: Boolean = true
    @Volatile
    var downloadWebpAnimations: Boolean = true

    // #1: maksymalny rozmiar pliku JXL w bajtach (0 = bez limitu). powyżej – stopniowa kompresja.
    @Volatile
    var maxJxlBytes: Long = 0L

    /**
     * T-05: trwały rejestr „czy obraz jest animowanym WebP" (baza aplikacji, po kluczu TREŚCI). Ustawiany
     * w AppGraph; null (np. testy) = sprawdzanie samego nagłówka pliku bez zapamiętywania na dysku.
     */
    interface AnimationRegistry {
        fun isAnimated(contentKey: String): Boolean?
        fun remember(contentKey: String, animated: Boolean)
        fun clear()
    }

    @Volatile
    var animationRegistry: AnimationRegistry? = null

    // T-05: wynik w RAM po adresie — kolejne wejścia obrazu na ekran w ogóle nie dotykają dysku ani bazy
    private val animatedByUrl = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private fun rememberAnimated(url: String, animated: Boolean) {
        if (animatedByUrl.size >= MAX_ANIMATION_FLAGS_IN_MEMORY) animatedByUrl.clear()
        animatedByUrl[url] = animated
    }

    private val memory = object : LruCache<String, Bitmap>(memoryCacheKib()) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }

    /**
     * @param declaredJxlQuality jakość JXL zadeklarowana przez serwer w metadanych (0..100).
     *   Gdy działa limit rozmiaru JXL, a jakości brak, obraz jest ignorowany (nie da się inaczej
     *   sprawdzić stopnia kompresji – NativeJxl tego nie odczytuje).
     */
    suspend fun load(context: Context, url: String, declaredJxlQuality: Int? = null): Bitmap? {
        synchronized(memory) { memory.get(url) }?.let { return it } // najszybsza ścieżka: już w RAM
        // DEDUPLIKACJA: jedno wspólne zadanie na dany URL. Gdy kilka miejsc poprosi o ten sam obraz naraz
        // (karuzela, powrót w feedzie, podwójna rekompozycja), pobieramy/dekodujemy go RAZ i wszyscy czekają
        // na ten sam wynik. Zadanie żyje w loaderScope (nie ginie, gdy wołający zniknie z ekranu) — dzięki
        // temu działa też jak mini-prefetch: po przewinięciu w tę i z powrotem obraz jest już w cache.
        val appContext = context.applicationContext
        val job = synchronized(inFlightLock) {
            inFlight[url] ?: loaderScope.async { loadUncached(appContext, url, declaredJxlQuality) }.also { d ->
                inFlight[url] = d
                d.invokeOnCompletion { synchronized(inFlightLock) { if (inFlight[url] === d) inFlight.remove(url) } }
            }
        }
        return job.await()
    }

    /**
     * Uruchamia ŁADOWANIE obrazu w tle (do cache) i NIE czeka na wynik — do prefetchu „z wyprzedzeniem"
     * (jak przeglądarka: pozycje tuż pod widokiem są gotowe, zanim do nich doscrollujesz). Dedup-owane
     * i ograniczone tym samym limitem równoległości co load() (nie zalewa sieci/Tora). Pomija, gdy obraz
     * już jest w RAM albo właśnie się ładuje.
     */
    fun prefetch(context: Context, url: String, declaredJxlQuality: Int? = null) {
        synchronized(memory) { if (memory.get(url) != null) return }
        if (!downloadImages) return
        val appContext = context.applicationContext
        synchronized(inFlightLock) {
            if (inFlight[url] != null) return
            val d = loaderScope.async { loadUncached(appContext, url, declaredJxlQuality) }
            inFlight[url] = d
            d.invokeOnCompletion { synchronized(inFlightLock) { if (inFlight[url] === d) inFlight.remove(url) } }
        }
    }

    private suspend fun loadUncached(
        context: Context,
        url: String,
        declaredJxlQuality: Int?,
    ): Bitmap? = withContext(loaderDispatcher) {
        synchronized(memory) { memory.get(url) }?.let { return@withContext it }
        if (!downloadImages) return@withContext null
        if (url.toCommunityUrlOrNull() == null) return@withContext null

        val directories = cacheDirectories(context)
        val referenceFile = ImageContentAddressing.referenceFile(
            directories.references,
            ImageContentAddressing.urlKey(url),
        )
        // 1) mamy już zapisany na dysku? – dekodujemy bez sieci
        loadFromDisk(referenceFile, directories.content)?.let { bitmap ->
            synchronized(memory) { memory.put(url, bitmap) }
            return@withContext bitmap
        }

        // 2) pobieramy raz, ewentualnie kompresujemy, zapisujemy na dysk
        // PROGRESYWNE OBRAZY (JXL, progresywny JPEG, PNG z przeplotem): w trakcie pobierania pokazujemy podglądy
        // (najpierw zgrubny obraz, potem ostrzejszy) zamiast pustego miejsca. Pozostałe formaty: bez zmian.
        val progressive = ProgressiveImageFeed(url)
        val downloaded = try {
            download(context, url, onChunk = progressive::onChunk)
        } catch (error: Throwable) {
            progressive.close()
            throw error
        } ?: return@withContext null.also { progressive.close() }
        val streamed = progressive.finish() // pełny obraz z TEGO SAMEGO dekodowania (albo null)
        // animacje WebP potrafią być ciężkie – jeśli user je wyłączył, nie zapisujemy ani nie pokazujemy
        if (!downloadWebpAnimations && isAnimatedWebp(downloaded)) return@withContext null
        // D-19e: limit JXL dotyczy tylko plików JXL — ciężkie WEBP/PNG dalej idą zwykłą kompresją („Kompresuj ciężkie obrazy")
        val stored = if (maxJxlBytes > 0L && isJxl(downloaded)) {
            stagedRecompress(downloaded, declaredJxlQuality, maxJxlBytes) ?: return@withContext null
        } else {
            maybeRecompress(downloaded)
        }
        // do WYŚWIETLANIA dekodujemy w rozmiarze ekranu (DISPLAY_MAX_DIM) — mniej RAM, szybciej. Na dysku
        // zostaje pełny plik (stored), więc zapis oryginału/rekompresja wciąż mają pełną rozdzielczość.
        // Gdy plik się nie zmienił, a dekodowanie strumieniowe dało pełny obraz w rozmiarze ekranu, bierzemy go —
        // to te same piksele, co dałoby decode() (ten sam dekoder libjxl), bez drugiego dekodowania.
        val bitmap = streamed?.takeIf { stored === downloaded } ?: decode(stored, DISPLAY_MAX_DIM) ?: return@withContext null
        val contentKey = persist(directories, referenceFile, stored)
        // T-05: rodzaj obrazu znamy od razu z bajtów w pamięci — zapisujemy go raz (RAM + baza)
        val animated = isAnimatedWebp(stored)
        rememberAnimated(url, animated)
        runCatching { animationRegistry?.remember(contentKey, animated) }
        synchronized(memory) { memory.put(url, bitmap) }
        bitmap
    }

    /** kasuje cały odtwarzalny cache obrazów społeczności (np. z „wyczyść dane"). */
    fun clear(context: Context) {
        synchronized(memory) { memory.evictAll() }
        animatedByUrl.clear()
        runCatching { animationRegistry?.clear() }
        val root = File(context.applicationContext.cacheDir, CACHE_DIRECTORY)
        if (root.parentFile == context.applicationContext.cacheDir) root.deleteRecursively()
    }

    /** sprząta pliki starsze niż maksymalny zakres historii (osobny katalog cache). */
    fun pruneExpired(context: Context, nowMillis: Long = System.currentTimeMillis()) {
        val cutoff = DataRetentionPolicy.cutoffs(nowMillis).historyBeforeMillis
        val directories = cacheDirectories(context)
        directories.references.listFiles().orEmpty()
            .filter { it.isFile && it.lastModified() < cutoff }
            .forEach { it.delete() }
        val referenced = ImageContentAddressing.referencedContentKeys(directories.references)
        directories.content.listFiles().orEmpty()
            .filter { it.isFile && it.lastModified() < cutoff && it.nameWithoutExtension !in referenced }
            .forEach { it.delete() }
    }

    /**
     * Retencja „trzymaj TYLKO te obrazy": zostawia na dysku obrazy o podanych adresach (np. zdjęcia 30
     * najnowszych postów + ulubione), a resztę kasuje. Dzięki temu po wyłączeniu i ponownym włączeniu apki
     * najnowsze posty są od razu z dysku (nie pobieramy ich ponownie), a cache nie puchnie bez końca.
     * Pusty [keepUrls] NIC nie kasuje (ochrona przed wyczyszczeniem całości, gdy feed jeszcze pusty).
     */
    suspend fun retainImages(context: Context, keepUrls: Set<String>): Unit = withContext(Dispatchers.IO) {
        if (keepUrls.isEmpty()) return@withContext
        val directories = cacheDirectories(context)
        val keepRefNames = keepUrls
            .map { ImageContentAddressing.referenceFile(directories.references, ImageContentAddressing.urlKey(it)).name }
            .toHashSet()
        // 1) kasujemy referencje spoza zestawu „zachowaj"
        directories.references.listFiles().orEmpty()
            .filter { it.isFile && it.name !in keepRefNames }
            .forEach { it.delete() }
        // 2) kasujemy pliki treści, do których nic już nie wskazuje (osierocone)
        val referenced = ImageContentAddressing.referencedContentKeys(directories.references)
        directories.content.listFiles().orEmpty()
            .filter { it.isFile && it.nameWithoutExtension !in referenced }
            .forEach { it.delete() }
    }

    /**
     * Masowa konwersja WSZYSTKICH zapisanych w cache obrazów społeczności do JXL — dokładnie wg ustawień
     * „Kompresuj ciężkie obrazy" / „Limit rozmiaru JXL" (ta sama drabinka co przy pobieraniu):
     *  - gdy ustawiono limit rozmiaru JXL: pełna rozdzielczość jakość 90→65, potem 75% i 50% rozdzielczości
     *    (jakość 80→65), ostatecznie 50% @ 65;
     *  - bez limitu: zwykła rekompresja do JXL (jakość [RECOMPRESS_JXL_QUALITY]).
     * Bierzemy pliki surowe (.img: webp/png/jpg/avif) ORAZ już-JXL, które przy aktywnym limicie są za duże.
     * Już-JXL mieszczące się w limicie pomijamy. Content-addressing zachowany: nowy klucz treści, przepięcie
     * wszystkich referencji, kasacja starego pliku.
     *
     * @param onProgress wołane po każdej pozycji: (przetworzone, wszystkie_do_przetworzenia).
     * @return liczba faktycznie przekonwertowanych/skompresowanych obrazów.
     */
    suspend fun convertCachedToJxl(
        context: Context,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        val dirs = cacheDirectories(context)
        // grupujemy referencje po kluczu treści (jeden obraz może być wskazywany przez wiele adresów)
        val groups = LinkedHashMap<String, MutableList<File>>()
        dirs.references.listFiles().orEmpty().filter { it.isFile }.forEach { ref ->
            val key = ImageContentAddressing.readReference(ref) ?: return@forEach
            groups.getOrPut(key) { mutableListOf() }.add(ref)
        }
        val limit = maxJxlBytes
        // co przetworzyć: surowe .img (zawsze) oraz .jxl przekraczające limit (tylko gdy limit aktywny)
        val jobs = groups.keys.mapNotNull { key ->
            val raw = ImageContentAddressing.contentFile(dirs.content, key, RAW_EXTENSION)
            val jxl = ImageContentAddressing.contentFile(dirs.content, key, JXL_EXTENSION)
            when {
                raw.isFile -> key to raw
                jxl.isFile && limit > 0L && jxl.length() > limit -> key to jxl
                else -> null
            }
        }
        val total = jobs.size
        var done = 0
        // E-04: liczymy tylko faktycznie podmienione pliki (wcześniej każdą próbę, także nieudaną i pominiętą)
        var converted = 0
        onProgress(0, total)
        for ((key, file) in jobs) {
            runCatching {
                val bytes = file.readBytes().takeIf { it.size.toLong() in 1..MAX_BYTES }
                    // E-04 (jak A-42): animowany WebP po konwersji zostałby na zawsze pierwszą klatką
                    ?.takeUnless { isAnimatedWebp(it) }
                val bitmap = bytes?.let { decode(it) }
                if (bitmap != null) {
                    val out = try {
                        if (limit > 0L) stagedEncodeJxl(bitmap, limit) else encodeJxl(bitmap, RECOMPRESS_JXL_QUALITY)
                    } finally {
                        bitmap.recycle()
                    }
                    // E-04: JXL tylko gdy mniejszy od źródła — chyba że źródło przekracza ustawiony limit
                    val worthIt = out != null && (out.size < bytes.size || (limit > 0L && bytes.size > limit))
                    if (out != null && worthIt) {
                        val newKey = ImageContentAddressing.contentKey(out)
                        val newFile = ImageContentAddressing.contentFile(dirs.content, newKey, JXL_EXTENSION)
                        if (writeAtomically(newFile, out)) {
                            groups[key]?.forEach { ref -> writeAtomically(ref, newKey.toByteArray(Charsets.US_ASCII)) }
                            if (file.path != newFile.path) file.delete() // kasujemy stary surowy/za duży plik
                            converted++
                        }
                    }
                }
            }
            done++
            onProgress(done, total)
        }
        synchronized(memory) { memory.evictAll() } // pozbywamy się bitmap pod starymi kluczami
        converted
    }

    /**
     * Ta sama drabinka co [stagedRecompress], ale ze źródłowej bitmapy (dla plików nie-JXL, które trzeba
     * najpierw zdekodować): pełna rozdzielczość jakość 90→65 → 75% i 50% rozdzielczości (80→65) → 50% @ 65.
     */
    private fun stagedEncodeJxl(source: Bitmap, maxBytes: Long): ByteArray? {
        for (q in 90 downTo 65 step 5) encodeJxl(source, q)?.let { if (it.size <= maxBytes) return it }
        var fallback: ByteArray? = null
        for (factor in doubleArrayOf(0.75, 0.50)) {
            val sw = (source.width * factor).toInt().coerceAtLeast(1)
            val sh = (source.height * factor).toInt().coerceAtLeast(1)
            val scaled = runCatching { Bitmap.createScaledBitmap(source, sw, sh, true) }.getOrNull() ?: continue
            try {
                for (q in 80 downTo 65 step 5) encodeJxl(scaled, q)?.let { if (it.size <= maxBytes) return it }
                if (factor == 0.50) fallback = encodeJxl(scaled, 65) // ostatecznie 50% @ 65 – bierzemy co wyjdzie
            } finally {
                if (scaled !== source) scaled.recycle()
            }
        }
        return fallback ?: encodeJxl(source, 65)
    }

    private fun loadFromDisk(referenceFile: File, contentDirectory: File): Bitmap? {
        val contentKey = ImageContentAddressing.readReference(referenceFile) ?: return null
        synchronized(memory) { memory.get(contentKey) }?.let {
            touch(referenceFile)
            return it
        }
        for (extension in listOf(JXL_EXTENSION, RAW_EXTENSION)) {
            val file = ImageContentAddressing.contentFile(contentDirectory, contentKey, extension)
            if (!file.isFile || file.length() !in 1..MAX_BYTES) continue
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: continue
            val bitmap = decode(bytes, DISPLAY_MAX_DIM) ?: continue // z dysku też dekodujemy do rozmiaru ekranu
            touch(referenceFile)
            touch(file)
            synchronized(memory) { memory.put(contentKey, bitmap) }
            return bitmap
        }
        // referencja bez pliku treści = śmieć, kasujemy
        referenceFile.delete()
        return null
    }

    /** zapisuje treść i referencję; zwraca klucz treści (T-05: rejestr animacji bez ponownego liczenia skrótu). */
    private fun persist(directories: CacheDirectories, referenceFile: File, bytes: ByteArray): String {
        val contentKey = ImageContentAddressing.contentKey(bytes)
        val extension = if (isJxl(bytes)) JXL_EXTENSION else RAW_EXTENSION
        val contentFile = ImageContentAddressing.contentFile(directories.content, contentKey, extension)
        val contentStored = contentFile.isFile && contentFile.length() == bytes.size.toLong() ||
            writeAtomically(contentFile, bytes)
        if (contentStored) {
            writeAtomically(referenceFile, contentKey.toByteArray(Charsets.US_ASCII))
        }
        return contentKey
    }

    /**
     * plik z serwera bywa cięższy niż trzeba – jak jest większy niż próg, próbujemy zejść
     * do JXL o akceptowalnej jakości. bierzemy wynik tylko gdy naprawdę mniejszy; inaczej
     * zostaje oryginał. best-effort: błąd kodeka nie psuje ładowania. RGB (bez alfy) – awatary
     * i zdjęcia postów są nieprzezroczyste, a to jeszcze zbija rozmiar.
     */
    private fun maybeRecompress(bytes: ByteArray): ByteArray {
        if (!recompressLargeImages || bytes.size <= RECOMPRESS_OVER_BYTES) return bytes
        // A-42: animowanego WebP NIE rekompresujemy do statycznego JXL — utrwalałoby to utratę animacji
        if (isAnimatedWebp(bytes)) return bytes
        val bitmap = decode(bytes) ?: return bytes
        return try {
            val encoded = runCatching {
                withEncodeGate {
                    NativeJxl.encode(bitmap, RECOMPRESS_JXL_QUALITY, RECOMPRESS_EFFORT)
                }
            }.getOrNull()
            if (encoded != null && encoded.size < bytes.size && isJxl(encoded)) encoded else bytes
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * #1 stopniowa kompresja JXL do limitu rozmiaru. Wymaga jakości zadeklarowanej przez serwer
     * (NativeJxl nie odczytuje jej z pliku) – bez niej obraz JXL ponad limit jest ignorowany.
     * Ladder: pełna rozdzielczość jakość <deklarowana> → 65 co 5; potem 75% rozdz. (LANCZOS,
     * najbliższy dostępny odpowiednik spline36) jakość 80→65; potem 50%; ostatecznie 50% @ 65.
     * Woła się przy pierwszym ładowaniu na wątku IO (niski priorytet); wynik trafia do cache.
     */
    private fun stagedRecompress(input: ByteArray, declaredQuality: Int?, maxBytes: Long): ByteArray? {
        if (!isJxl(input)) return input                 // limit dotyczy plików JXL
        if (input.size <= maxBytes) return input        // już mieści się w limicie
        val startQuality = declaredQuality?.takeIf { it in 1..100 } ?: return null // brak metadanych = ignoruj
        val (w, h) = NativeJxl.size(input) ?: return null
        val previousPriority = android.os.Process.getThreadPriority(android.os.Process.myTid())
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        try {
            // etap 1: pełna rozdzielczość, jakość poniżej zadeklarowanej, 95→65 co 5
            decode(input)?.let { full ->
                try {
                    for (q in (95 downTo 65 step 5)) {
                        if (q >= startQuality) continue
                        encodeJxl(full, q)?.let { if (it.size <= maxBytes) return it }
                    }
                } finally { full.recycle() }
            }
            // etap 2 i 3: 75% i 50% rozdzielczości (LANCZOS), jakość 80→65
            scaledStage(input, w, h, 0.75, maxBytes)?.let { return it }
            scaledStage(input, w, h, 0.50, maxBytes)?.let { return it }
            // ostatecznie: 50% rozdzielczości @ jakość 65 – bierzemy co wyjdzie
            sampled(input, w, h, 0.50)?.let { bmp ->
                try {
                    encodeJxl(bmp, 65)?.let { return it }
                } finally { bmp.recycle() }
            }
            return input
        } finally {
            android.os.Process.setThreadPriority(previousPriority)
        }
    }

    private fun scaledStage(input: ByteArray, w: Int, h: Int, factor: Double, maxBytes: Long): ByteArray? {
        val bmp = sampled(input, w, h, factor) ?: return null
        try {
            for (q in (80 downTo 65 step 5)) {
                encodeJxl(bmp, q)?.let { if (it.size <= maxBytes) return it }
            }
        } finally { bmp.recycle() }
        return null
    }

    private fun sampled(input: ByteArray, w: Int, h: Int, factor: Double): Bitmap? {
        val tw = (w * factor).toInt().coerceAtLeast(1)
        val th = (h * factor).toInt().coerceAtLeast(1)
        // jedyny dekoder to NativeJxl (z limitami) — dekodujemy w całości (≤16 Mpx) i skalujemy w dół z filtrem
        val full = NativeJxl.decode(input) ?: return null
        if (tw >= full.width && th >= full.height) return full
        return try {
            Bitmap.createScaledBitmap(full, tw.coerceAtMost(full.width), th.coerceAtMost(full.height), true)
        } catch (_: Throwable) {
            null
        } finally {
            full.recycle()
        }
    }

    private fun encodeJxl(bitmap: Bitmap, quality: Int): ByteArray? =
        runCatching {
            withEncodeGate {
                NativeJxl.encode(bitmap, quality, RECOMPRESS_EFFORT)
            }
        }.getOrNull()?.takeIf { isJxl(it) }

    // A-42: effort 9 (TORTOISE) × równoległość 10 × bitmapy do 4096² = OOM, bateria i termika. Effort 7
    // (SQUIRREL — domyślny libjxl) daje prawie ten sam rozmiar wielokrotnie szybciej; enkody ograniczamy
    // semaforem do 2 naraz (pobieranie i dekodowanie dalej idą równolegle).
    private val encodeGate = java.util.concurrent.Semaphore(MAX_PARALLEL_ENCODES)
    private const val RECOMPRESS_EFFORT = 7

    private inline fun <T> withEncodeGate(block: () -> T): T {
        encodeGate.acquire()
        try {
            return block()
        } finally {
            encodeGate.release()
        }
    }

    // maxDim: górny limit boku. Domyślnie MAX_IMAGE_DIM (ochrona przed pixel-bombą, pełna rozdzielczość do
    // rekompresji); ścieżki WYŚWIETLANIA podają DISPLAY_MAX_DIM, by dekodować mniejszy (szybciej, mniej RAM).
    private fun decode(bytes: ByteArray, maxDim: Int = MAX_IMAGE_DIM): Bitmap? = when {
        isJxl(bytes) -> decodeJxlBounded(bytes, maxDim)
        isAvif(bytes) -> decodeAvif(bytes, maxDim)
        else -> decodePlatformBounded(bytes, maxDim)
    }

    // JXL: odczytaj wymiary i NIGDY nie alokuj > MAX_IMAGE_DIM na bok — zbyt duży obraz próbkujemy w dół
    // zamiast dekodować w pełnej, deklarowanej rozdzielczości (F-07).
    private fun decodeJxlBounded(bytes: ByteArray, maxDim: Int = MAX_IMAGE_DIM): Bitmap? {
        val (w, h) = NativeJxl.size(bytes) ?: return null // null także gdy obraz przekracza limity natywne
        if (w <= 0 || h <= 0) return null
        if (w <= maxDim && h <= maxDim) {
            // jedyny dekoder: własny most JNI do libjxl (limity 4096 px / 16 Mpx); brak zapasowego dekodera bez limitów
            return NativeJxl.decode(bytes)
        }
        val factor = minOf(maxDim.toDouble() / w, maxDim.toDouble() / h)
        return sampled(bytes, w, h, factor)
    }

    // Platforma (WEBP/PNG/JPEG…): najpierw SAME wymiary (inJustDecodeBounds), potem dekoduj z inSampleSize
    // tak, by żaden bok nie przekroczył MAX_IMAGE_DIM — bez wcześniejszej alokacji całej bitmapy (F-07).
    private fun decodePlatformBounded(bytes: ByteArray, maxDim: Int = MAX_IMAGE_DIM): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds) }
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        var sample = 1
        while (w / sample > maxDim || h / sample > maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }.getOrNull()
    }

    /**
     * Dekoduje miniaturę DOSTARCZONĄ przez serwer główny w ładunku push (JXL/AVIF/WEBP/PNG/JPG) do bitmapy —
     * serwer sam decyduje, czy skonwertował ją do JXL. JXL/AVIF idą przez natywny dekoder (Android nie ma
     * własnego JXL, a AVIF tylko 12+), reszta przez BitmapFactory. Zero pobrania z sieci → zero korelacji IP.
     */
    fun decodePushThumbnail(bytes: ByteArray): Bitmap? {
        // T-05: miniatura z budzika jest MAŁA (do ~9 KB): odrzucamy większy plik i obraz deklarujący większy bok niż
        // PUSH_THUMB_MAX_DIM jeszcze PRZED alokacją bitmapy (JXL o kilkuset bajtach mógł deklarować 4096×4096)
        if (bytes.isEmpty() || bytes.size > PUSH_THUMB_MAX_BYTES) return null
        if (isJxl(bytes)) {
            val (sw, sh) = NativeJxl.size(bytes) ?: return null
            if (sw !in 1..PUSH_THUMB_MAX_DIM || sh !in 1..PUSH_THUMB_MAX_DIM) return null
        }
        val bitmap = decode(bytes, PUSH_THUMB_MAX_DIM) ?: return null
        return if (bitmap.width <= PUSH_THUMB_MAX_DIM && bitmap.height <= PUSH_THUMB_MAX_DIM) {
            bitmap
        } else {
            bitmap.recycle()
            null
        }
    }

    private const val PUSH_THUMB_MAX_BYTES = 16 * 1024
    private const val PUSH_THUMB_MAX_DIM = 512

    /**
     * AVIF: na Androidzie 12+ dekoduje platforma (BitmapFactory), na 8–11 własny natywny dekoder
     * (AvifDec). Zdekodowaną bitmapę i tak rekompresujemy potem do JXL (patrz maybeRecompress),
     * więc ten sam AVIF dekodujemy natywnie tylko raz — kolejne wczytania idą z cache JXL.
     */
    private fun decodeAvif(bytes: ByteArray, maxDim: Int = MAX_IMAGE_DIM): Bitmap? {
        // M-7: przekazujemy maxDim, by AVIF też respektował limit rozmiaru WYŚWIETLANIA (mniej RAM/szybciej).
        decodePlatformBounded(bytes, maxDim)?.let { return it } // Android 12+ dekoduje platformowo (z próbkowaniem)
        return AvifDec.decode(bytes)                            // 8–11: własny dekoder z limitami rozmiaru w JNI (F-07)
    }

    private fun isJxl(bytes: ByteArray): Boolean =
        NativeJxl.isJxl(bytes)

    /** rozpoznanie AVIF po pudełku ISOBMFF ftyp z marką avif/avis. */
    private fun isAvif(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        fun u(i: Int) = bytes[i].toInt() and 0xFF
        if (u(4) != 0x66 || u(5) != 0x74 || u(6) != 0x79 || u(7) != 0x70) return false // 'ftyp'
        val brand = String(bytes, 8, 4, Charsets.US_ASCII)
        return brand == "avif" || brand == "avis"
    }

    /**
     * Animowany WebP jako animacja (Android 9+: systemowy ImageDecoder → AnimatedImageDrawable, bez bibliotek).
     * Bierze plik z cache na dysku (najpierw wołamy [load], które go pobiera prywatną ścieżką i zapisuje bez
     * rekompresji — animacji nie przekodowujemy). null = obraz nie jest animowany, Android < 9 albo błąd;
     * wtedy UI pokazuje zwykłą (pierwszą) klatkę z [load]. Rozmiar dekodowania ograniczony jak przy feedzie.
     */
    suspend fun loadAnimated(context: Context, url: String): android.graphics.drawable.Drawable? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) return null
        if (!downloadImages || !downloadWebpAnimations) return null
        // T-05: zwykły (nieanimowany) obraz — wiemy to z RAM, bez dysku i bez ładowania
        if (animatedByUrl[url] == false) return null
        load(context, url) ?: return null // upewnia się, że plik jest w cache (i że to poprawny obraz)
        return withContext(loaderDispatcher) {
            val animated = animatedByUrl[url]
                ?: isAnimatedInCache(context, url)?.also { rememberAnimated(url, it) }
                ?: return@withContext null
            if (!animated) return@withContext null
            // pełny plik czytamy WYŁĄCZNIE dla prawdziwej animacji (do dekodera)
            val bytes = cachedBytes(context, url) ?: return@withContext null
            runCatching {
                val source = android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))
                android.graphics.ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
                    val w = info.size.width
                    val h = info.size.height
                    val longest = maxOf(w, h)
                    if (longest > DISPLAY_MAX_DIM) {
                        val scale = DISPLAY_MAX_DIM.toFloat() / longest
                        decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                    }
                }.takeIf { it is android.graphics.drawable.AnimatedImageDrawable }
            }.getOrNull()
        }
    }

    /**
     * Pobiera ORYGINALNE bajty obrazu (bez rekompresji, bez przerywania animacji WebP) — do zapisu
     * mema w oryginalnym kontenerze. Ta sama prywatna ścieżka sieciowa co feed (Tor dla .onion).
     */
    suspend fun fetchOriginal(context: Context, url: String, allowCache: Boolean = true): ByteArray? =
        withContext(Dispatchers.IO) {
            if (url.toCommunityUrlOrNull() == null) return@withContext null
            // R-16: przy zapisie „Oryginał” (allowCache=false) POMIJAMY cache — mógł zawierać wersję po
            // rekompresji ciężkich obrazów (inny format/jakość), a user chce bajt-w-bajt to, co na serwerze.
            // Dla rekompresji (allowCache=true) bierzemy z pamięci telefonu, by nie pobierać drugi raz.
            if (allowCache) cachedBytes(context, url)?.let { return@withContext it }
            download(context, url, forceFull = true) // pobranie tą samą prywatną ścieżką (Tor dla .onion)
        }

    /**
     * Bajty obrazu z CACHE NA DYSKU (bez sieci). null, gdy go tam nie ma. Treścią jest to, co zapisaliśmy
     * przy pierwszym wyświetleniu: oryginał z serwera albo — gdy włączona rekompresja ciężkich obrazów —
     * wersja po rekompresji. Content-addressing: url → referencja → klucz treści → plik .jxl/.img.
     */
    fun cachedBytes(context: Context, url: String): ByteArray? {
        val directories = cacheDirectories(context)
        val referenceFile = ImageContentAddressing.referenceFile(
            directories.references,
            ImageContentAddressing.urlKey(url),
        )
        val contentKey = ImageContentAddressing.readReference(referenceFile) ?: return null
        for (extension in listOf(JXL_EXTENSION, RAW_EXTENSION)) {
            val file = ImageContentAddressing.contentFile(directories.content, contentKey, extension)
            if (file.isFile && file.length() in 1..MAX_BYTES) {
                return runCatching { file.readBytes() }.getOrNull()
            }
        }
        return null
    }

    /**
     * T-05: czy obraz z cache jest animowanym WebP — z rejestru w bazie (po kluczu treści), a gdy go tam nie ma
     * (obraz zapisany przed T-05), z SAMEGO nagłówka pliku (21 bajtów zamiast całego pliku). Wynik trafia do
     * rejestru, więc każdy obraz sprawdzamy na dysku najwyżej raz. null = obrazu nie ma w cache.
     */
    private fun isAnimatedInCache(context: Context, url: String): Boolean? {
        val directories = cacheDirectories(context)
        val referenceFile = ImageContentAddressing.referenceFile(
            directories.references,
            ImageContentAddressing.urlKey(url),
        )
        val contentKey = ImageContentAddressing.readReference(referenceFile) ?: return null
        runCatching { animationRegistry?.isAnimated(contentKey) }.getOrNull()?.let { return it }
        // animacji nigdy nie przekodowujemy do JXL (zapis „surowy" .img) — plik JXL jest więc zawsze nieruchomy
        val raw = ImageContentAddressing.contentFile(directories.content, contentKey, RAW_EXTENSION)
        val animated = when {
            raw.isFile -> {
                val header = ByteArray(WEBP_HEADER_BYTES)
                val read = runCatching { raw.inputStream().use { it.readNBytesCompat(header) } }.getOrNull() ?: return null
                read == WEBP_HEADER_BYTES && isAnimatedWebp(header)
            }
            ImageContentAddressing.contentFile(directories.content, contentKey, JXL_EXTENSION).isFile -> false
            else -> return null
        }
        runCatching { animationRegistry?.remember(contentKey, animated) }
        return animated
    }

    // InputStream.readNBytes jest dopiero od Androida 13 — czytamy w pętli do zapełnienia bufora albo końca pliku
    private fun java.io.InputStream.readNBytesCompat(buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val n = read(buffer, total, buffer.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    /** Dekoduje bajty w PEŁNEJ rozdzielczości (do zapisu do galerii) — bez dławienia DISPLAY_MAX_DIM. */
    fun decodeFull(bytes: ByteArray): Bitmap? = decode(bytes)

    /**
     * Podglądy obrazów progresywnych (JXL, JPEG, PNG) w trakcie pobierania: (adres, częściowy obraz). UI ładujące dany adres zbiera je
     * do czasu pełnego obrazu (RemoteImages.rememberCommunityBitmap). Bufor z porzucaniem najstarszych — podgląd
     * jest tylko „przy okazji", nigdy nie blokuje pobierania.
     */
    val previews = kotlinx.coroutines.flow.MutableSharedFlow<Pair<String, Bitmap>>(
        extraBufferCapacity = 16,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    /**
     * Podglądy obrazów PROGRESYWNYCH w trakcie pobierania. Format rozpoznaje po pierwszych bajtach
     * ([ProgressiveImageFormat]):
     *  - JXL → dekoder strumieniowy (przyrostowy; pełny obraz z niego zastępuje drugie dekodowanie),
     *  - progresywny JPEG / PNG z przeplotem → [PartialImageDecoder] z dotychczasowych bajtów (Android 9+),
     *  - pozostałe formaty → nic (zero dodatkowej pracy).
     * Najwyżej [MAX_PREVIEWS] podglądów, pierwszy najwcześniej po [PREVIEW_INTERVAL_MS] od startu pobierania i nie
     * częściej niż co tyle (każdy podgląd to bitmapa — bez limitu kosztowałby CPU i baterię). JXL tylko w rozmiarze
     * wyświetlania; JPEG/PNG dekodowane od razu w rozmiarze wyświetlania.
     * Używany z jednego wątku (pętla pobierania), jak wymaga [NativeJxl.Stream].
     */
    private class ProgressiveImageFeed(private val url: String) : AutoCloseable {
        // JXL: własny dekoder strumieniowy (przyrostowy — każdy bajt dekodowany raz)
        private var stream: NativeJxl.Stream? = null
        // progresywny JPEG / PNG z przeplotem: dotychczasowe bajty, z których ImageDecoder składa podgląd
        private var partial: ByteArrayOutputStream? = null
        private var partialBytesAtLastPreview = 0
        private var kind: ProgressiveImageFormat.Kind? = null
        private var disabled = false
        private val head = ByteArrayOutputStream()
        private var previewsShown = 0
        // start liczony od początku pobierania: pierwszy podgląd najwcześniej po PREVIEW_INTERVAL_MS — szybkie
        // pobranie (cały plik w ułamku sekundy) nie robi żadnej dodatkowej pracy, podgląd i tak nie byłby widoczny
        private var lastPreviewAt = android.os.SystemClock.elapsedRealtime()
        private var done = false

        fun onChunk(data: ByteArray, offset: Int, length: Int) {
            if (disabled || done) return
            stream?.let { return handle(it.feed(data, offset, length, last = false)) }
            partial?.let {
                it.write(data, offset, length)
                return maybePartialPreview(it)
            }
            // format jeszcze nierozpoznany — zbieramy początek pliku
            head.write(data, offset, length)
            val start = head.toByteArray()
            val detected = ProgressiveImageFormat.detect(start)
            when (detected) {
                null -> if (start.size >= ProgressiveImageFormat.MAX_SCAN_BYTES) disable()
                ProgressiveImageFormat.Kind.JXL -> {
                    kind = ProgressiveImageFormat.Kind.JXL
                    head.reset()
                    val opened = NativeJxl.openStream() ?: return disable()
                    stream = opened
                    handle(opened.feed(start, 0, start.size, last = false))
                }
                ProgressiveImageFormat.Kind.PROGRESSIVE_JPEG, ProgressiveImageFormat.Kind.INTERLACED_PNG -> {
                    if (!PartialImageDecoder.isSupported()) return disable()
                    kind = detected
                    partial = head // już zawiera początek pliku — nie kopiujemy go drugi raz
                    maybePartialPreview(head)
                }
                ProgressiveImageFormat.Kind.NONE -> disable()
            }
        }

        private fun disable() {
            disabled = true
            head.reset()
            partial = null
            close()
        }

        /** progresywny JPEG / PNG z przeplotem: podgląd z dotychczasowych bajtów (gdy przyszło dość nowych danych). */
        private fun maybePartialPreview(buffer: ByteArrayOutputStream) {
            if (previewsShown >= MAX_PREVIEWS) return
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastPreviewAt < PREVIEW_INTERVAL_MS) return
            if (buffer.size() - partialBytesAtLastPreview < PARTIAL_PREVIEW_MIN_NEW_BYTES) return
            lastPreviewAt = now
            partialBytesAtLastPreview = buffer.size()
            PartialImageDecoder.decode(buffer.toByteArray(), DISPLAY_MAX_DIM, MAX_IMAGE_DIM)?.let {
                previews.tryEmit(url to it)
                previewsShown += 1
            }
        }

        private fun handle(status: NativeJxl.Stream.Status) {
            val s = stream ?: return
            when (status) {
                NativeJxl.Stream.Status.FAILED -> close()
                NativeJxl.Stream.Status.DONE -> done = true
                NativeJxl.Stream.Status.RUNNING -> Unit
                NativeJxl.Stream.Status.NEW_PASS -> {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (previewsShown >= MAX_PREVIEWS || now - lastPreviewAt < PREVIEW_INTERVAL_MS) return
                    val size = s.size() ?: return
                    if (size.first > DISPLAY_MAX_DIM || size.second > DISPLAY_MAX_DIM) { disabled = true; close(); return }
                    s.preview()?.let { previews.tryEmit(url to it) }
                    previewsShown += 1
                    lastPreviewAt = now
                }
            }
        }

        /** Domyka strumień; zwraca pełny obraz w rozmiarze wyświetlania albo null (wtedy zwykłe decode()). */
        fun finish(): Bitmap? {
            val s = stream ?: run {
                if (pl.lewicowyt.notifier.BuildConfig.DEBUG && partial != null) {
                    android.util.Log.d("ProgressiveImage", "$kind ${partial?.size()} B: podglądów $previewsShown")
                }
                partial = null // pełny obraz zdekoduje zwykła ścieżka (decode()) — jak dotąd
                return null
            }
            return try {
                val status = if (done) NativeJxl.Stream.Status.DONE else s.feed(ByteArray(0), 0, 0, last = true)
                val size = s.size()
                if (pl.lewicowyt.notifier.BuildConfig.DEBUG) {
                    android.util.Log.d("ProgressiveJxl", "JXL ${size?.first}x${size?.second}: podglądów $previewsShown, status $status")
                }
                if (status == NativeJxl.Stream.Status.DONE && size != null &&
                    size.first <= DISPLAY_MAX_DIM && size.second <= DISPLAY_MAX_DIM
                ) s.result() else null
            } finally {
                close()
            }
        }

        override fun close() {
            stream?.close()
            stream = null
            partial = null
        }
    }

    private suspend fun download(
        context: Context,
        url: String,
        forceFull: Boolean = false,
        onChunk: ((ByteArray, Int, Int) -> Unit)? = null,
    ): ByteArray? {
        // J-11f: reguła adresu (https / .onion / http tylko w LAN) sprawdzana także w samym ujściu sieciowym,
        // nie tylko u obecnych wywołujących — przyszły trzeci wywołujący jej nie ominie
        val target = url.toCommunityUrlOrNull() ?: return null
        val coroutine = currentCoroutineContext()
        // obrazy z serwera .onion pobieramy przez Tor (ten sam obwód co rejestracja/poll) — serwer
        // nie zobaczy IP, więc pobrania obrazów nie da się skorelować z pseudonimem po adresie.
        // H-3 (fail-closed): obraz .onion pobieramy TYLKO przez pewny port Tora; gdy go brak (Tor niegotowy),
        // get() = null → nie pobieramy (lepiej brak obrazu niż połączenie na ślepo na losowy port).
        val onion = CommunityTorClient.isOnion(url)
        val base = if (onion) {
            CommunityTorClient.get(context) ?: return null
        } else {
            PrivacyHttpClient.get(context)
        }
        // B-90: przez Tor budowa obwodu do usługi ukrytej trwa dziesiątki sekund — limity clearnetu (12/15 s)
        // dawały fałszywe porażki obrazów .onion. Wartości jak przy manifeście .onion.
        // J-05: limit CAŁOŚCI (120 s) także dla clearnetu — readTimeout dotyczy pojedynczego odczytu, więc serwer
        // „kapiący" po bajcie co 14 s trzymałby jedno z 10 miejsc loadera godzinami.
        // T-11: klient budowany raz na klienta bazowego (nie przy każdym obrazie)
        val client = DerivedHttpClients.get(
            base,
            DerivedHttpClients.Spec(
                connectTimeoutMillis = TimeUnit.SECONDS.toMillis(if (onion) 60L else 12L),
                readTimeoutMillis = TimeUnit.SECONDS.toMillis(if (onion) 60L else 15L),
                callTimeoutMillis = TimeUnit.SECONDS.toMillis(CALL_TIMEOUT_SEC),
                noRedirects = true,
            ),
        )
        return runCatching {
            // L-10: ten sam User-Agent co manifest ("lewicowYT") — bez niego obraz GET szedłby z domyślnym
            // okhttp/x.y, pozwalając serwerowi rozróżniać wersje/typy żądań.
            val request = Request.Builder().url(target).get()
                .header("User-Agent", COMMUNITY_USER_AGENT).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body
                if (body.contentLength() > MAX_BYTES) return@use null
                // gdy animacje WebP wyłączone, przerywamy pobieranie zaraz po nagłówku (oszczędza transfer);
                // przy zapisie „oryginału" (forceFull) pobieramy zawsze cały plik
                body.byteStream().use {
                    readLimited(it, abortAnimatedWebp = !downloadWebpAnimations && !forceFull, onChunk = onChunk) {
                        coroutine.isActive
                    }
                }
            }
        }.getOrNull()
    }

    private fun readLimited(
        input: InputStream,
        abortAnimatedWebp: Boolean,
        onChunk: ((ByteArray, Int, Int) -> Unit)? = null,
        active: () -> Boolean,
    ): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        var total = 0L
        var headerChecked = false
        while (true) {
            // J-05: anulowane pobranie (korutyna skasowana) przerywamy przy najbliższym odczycie
            if (!active()) return null
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_BYTES) return null
            output.write(buffer, 0, count)
            onChunk?.invoke(buffer, 0, count) // progresywny JXL: dekoder dostaje dane w trakcie pobierania
            if (abortAnimatedWebp && !headerChecked && output.size() >= WEBP_HEADER_BYTES) {
                headerChecked = true
                if (isAnimatedWebp(output.toByteArray())) return null // przerwij, nie ściągaj reszty
            }
        }
        return output.toByteArray()
    }

    /** animowany WebP: RIFF…WEBP z chunkiem VP8X, w którym ustawiony jest bit animacji (0x02). */
    private fun isAnimatedWebp(bytes: ByteArray): Boolean {
        if (bytes.size < WEBP_HEADER_BYTES) return false
        val riff = bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte()
        val webp = bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
        val vp8x = bytes[12] == 'V'.code.toByte() && bytes[13] == 'P'.code.toByte() &&
            bytes[14] == '8'.code.toByte() && bytes[15] == 'X'.code.toByte()
        if (!riff || !webp || !vp8x) return false
        return (bytes[20].toInt() and 0x02) != 0 // flaga ANIM w VP8X
    }

    private fun cacheDirectories(context: Context): CacheDirectories {
        val root = File(context.applicationContext.cacheDir, CACHE_DIRECTORY).apply { mkdirs() }
        return CacheDirectories(
            references = File(root, "references").apply { mkdirs() },
            content = File(root, "content").apply { mkdirs() },
        )
    }

    private fun touch(file: File) {
        if (file.isFile) file.setLastModified(System.currentTimeMillis())
    }

    private fun writeAtomically(target: File, bytes: ByteArray): Boolean {
        val temporary = File(target.parentFile, "${target.name}.tmp")
        return runCatching {
            temporary.writeBytes(bytes)
            if (target.exists() && !target.delete()) return@runCatching false
            if (!temporary.renameTo(target)) {
                target.writeBytes(bytes)
                temporary.delete()
            }
            target.isFile && target.length() == bytes.size.toLong()
        }.getOrDefault(false).also { if (!it) temporary.delete() }
    }

    private const val WEBP_HEADER_BYTES = 21
    private const val MAX_ANIMATION_FLAGS_IN_MEMORY = 4_096
    // progresywny JXL: najwyżej tyle podglądów na obraz i nie częściej niż co tyle ms (CPU/bateria)
    private const val MAX_PREVIEWS = 4
    private const val PREVIEW_INTERVAL_MS = 250L
    // progresywny JPEG / PNG z przeplotem: kolejny podgląd dopiero po tylu nowych bajtach (każdy to pełne dekodowanie)
    private const val PARTIAL_PREVIEW_MIN_NEW_BYTES = 16 * 1024
    private const val CACHE_DIRECTORY = "community_images"
    private const val JXL_EXTENSION = "jxl"
    private const val RAW_EXTENSION = "img"

    private data class CacheDirectories(val references: File, val content: File)
}
