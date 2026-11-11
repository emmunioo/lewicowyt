package pl.lewicowyt.notifier.images

import android.graphics.Bitmap

/**
 * Własny most JNI do dekodera JPEG XL (libjxl, decode-only) — `liblewicowyt_jxl.so` (patrz cpp/jxl_jni.c).
 * Jedyny dekoder/enkoder JXL w aplikacji (biblioteka `jxl-coder` została usunięta — patrz audyt obrazów):
 * dekodowanie z limitami, **progresywne** dekodowanie (kolejne przebiegi: najpierw
 * zgrubny obraz, potem coraz ostrzejszy). Gdy biblioteki nie da się załadować (np. ABI bez zbudowanego
 * .so), [isAvailable] = false i JXL jest po prostu nieobsługiwany (obraz pomijany) — nie ma już zapasowego dekodera,
 * który omijałby limity rozmiaru.
 */
object NativeJxl {

    /** callback przebiegu progresywnego — MUSI mieć metodę `onPass(Bitmap)` (zgodnie z jxl_jni.c). */
    fun interface PassListener {
        fun onPass(bitmap: Bitmap)
    }

    private val available: Boolean = runCatching {
        System.loadLibrary("lewicowyt_jxl")
        true
    }.getOrDefault(false)

    fun isAvailable(): Boolean = available

    /** szybkie sprawdzenie sygnatury JXL (kodestrumień albo kontener). */
    fun isJxl(bytes: ByteArray): Boolean =
        available && runCatching { nativeIsJxl(bytes) }.getOrDefault(false)

    /**
     * Wymiary PO orientacji bez dekodowania pikseli (czyta tylko nagłówek) albo null: to nie JXL, nagłówek niepełny
     * albo obraz przekracza limity (4096 px na bok, 16 Mpx, ≤4 kanały dodatkowe). Zastępuje dawne JxlCoder.getSize.
     */
    fun size(bytes: ByteArray): Pair<Int, Int>? {
        if (!available) return null
        val packed = runCatching { nativeGetSize(bytes) }.getOrDefault(0L)
        return if (packed == 0L) null else (packed ushr 32).toInt() to (packed and 0xFFFFFFFFL).toInt()
    }

    /**
     * Kodowanie STRATNE bitmapy ARGB_8888 do JXL (RGB, bez alfy): [quality] 1..100, [effort] 1..10 (1 = najszybciej,
     * 7 = domyślny libjxl, 10 = najmocniej). Zastępuje enkoder z usuniętej biblioteki jxl-coder. Enkoder dostaje
     * wyłącznie piksele już zdekodowane przez nasze dekodery z limitami — NIE nieufne bajty. null = błąd/niedostępne.
     */
    fun encode(bitmap: Bitmap, quality: Int, effort: Int): ByteArray? =
        if (!available) null else runCatching { nativeEncode(bitmap, quality, effort) }.getOrNull()

    /** pełne, jednorazowe dekodowanie do ARGB_8888; null przy błędzie/niedostępności. */
    fun decode(bytes: ByteArray): Bitmap? =
        if (!available) null else runCatching { nativeDecode(bytes) }.getOrNull()

    /**
     * D-19f: obecnie NIEużywane w produkcji — obrazy społeczności pobieramy w całości (jedno GET, bez
     * strumieniowania częściowych danych do dekodera), więc przebiegi pośrednie nie skróciłyby czasu do obrazu.
     * Zostaje jako gotowy most na przyszłe wyświetlanie w trakcie pobierania (pokryte testem degradacji).
     *
     * Dekodowanie PROGRESYWNE: po każdym przebiegu woła [listener].onPass(bitmap) z częściowym obrazem,
     * a na końcu zwraca pełny (albo null). Callbacki przychodzą na wątku wołającego (dekodowanie jest
     * synchroniczne), więc wołaj to z wątku IO, a aktualizacje UI przekazuj dalej do głównego wątku.
     */
    fun decodeProgressive(bytes: ByteArray, listener: PassListener): Bitmap? =
        if (!available) null else runCatching { nativeDecodeProgressive(bytes, listener) }.getOrNull()

    /**
     * Dekoder STRUMIENIOWY: dane podaje się kawałkami w trakcie pobierania ([Stream.feed]); po każdym nowym
     * przebiegu (najpierw zgrubny obraz, potem ostrzejsze) można wziąć podgląd ([Stream.preview]), a na końcu pełny
     * obraz ([Stream.result]) — ten sam, który dałoby [decode], bez dekodowania pliku drugi raz.
     * null = biblioteka niedostępna. Uchwyt NIE jest wielowątkowy: używaj go z jednego wątku i zawsze zamknij.
     */
    fun openStream(): Stream? {
        if (!available) return null
        val handle = runCatching { nativeStreamCreate() }.getOrDefault(0L)
        return if (handle == 0L) null else Stream(handle)
    }

    class Stream internal constructor(private var handle: Long) : AutoCloseable {
        enum class Status { RUNNING, NEW_PASS, DONE, FAILED }

        fun feed(data: ByteArray, offset: Int, length: Int, last: Boolean): Status {
            if (handle == 0L) return Status.FAILED
            return when (runCatching { nativeStreamFeed(handle, data, offset, length, last) }.getOrDefault(-1)) {
                0 -> Status.RUNNING
                1 -> Status.NEW_PASS
                2 -> Status.DONE
                else -> Status.FAILED
            }
        }

        /** (szerokość, wysokość) po wyjściowej orientacji albo null, gdy nagłówek jeszcze nie dotarł. */
        fun size(): Pair<Int, Int>? {
            if (handle == 0L) return null
            val packed = runCatching { nativeStreamSize(handle) }.getOrDefault(0L)
            return if (packed == 0L) null else (packed ushr 32).toInt() to (packed and 0xFFFFFFFFL).toInt()
        }

        fun preview(): Bitmap? = if (handle == 0L) null else runCatching { nativeStreamPreview(handle) }.getOrNull()

        fun result(): Bitmap? = if (handle == 0L) null else runCatching { nativeStreamResult(handle) }.getOrNull()

        override fun close() {
            if (handle != 0L) {
                runCatching { nativeStreamDestroy(handle) }
                handle = 0L
            }
        }
    }

    private external fun nativeDecode(data: ByteArray): Bitmap?
    private external fun nativeDecodeProgressive(data: ByteArray, listener: PassListener): Bitmap?
    private external fun nativeIsJxl(data: ByteArray): Boolean
    private external fun nativeGetSize(data: ByteArray): Long
    private external fun nativeEncode(bitmap: Bitmap, quality: Int, effort: Int): ByteArray?
    private external fun nativeStreamCreate(): Long

    private external fun nativeStreamFeed(handle: Long, data: ByteArray, offset: Int, length: Int, last: Boolean): Int
    private external fun nativeStreamSize(handle: Long): Long
    private external fun nativeStreamPreview(handle: Long): Bitmap?
    private external fun nativeStreamResult(handle: Long): Bitmap?
    private external fun nativeStreamDestroy(handle: Long)
}
