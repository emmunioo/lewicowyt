package pl.lewicowyt.notifier.images

import pl.lewicowyt.notifier.AppLog
import android.graphics.Bitmap
import android.util.Log

/**
 * Cienki binding do własnego, wąskiego modułu NDK z enkoderem MozJPEG (libjpeg-turbo + rozszerzenia
 * mozjpeg: trellis, optymalizacja Huffmana, progresywność). Bundlujemy TYLKO enkoder JPEG — najmniejszy
 * możliwy przyrost APK dla prawdziwego MozJPEG (bez całych bibliotek do przetwarzania obrazu).
 *
 * Bezpieczny fallback: jeśli natywna biblioteka nie jest jeszcze zbudowana/spakowana, [available] jest
 * false, a [encode] zwraca null — wtedy [CommunityImageExporter] użyje platformowego JPEG (baseline).
 */
object MozJpeg {
    private const val TAG = "MozJpeg"

    val available: Boolean by lazy {
        runCatching { System.loadLibrary("lewicowyt_mozjpeg") }
            .onFailure { AppLog.warning(TAG, "moduł mozjpeg niedostępny (fallback na platformowy JPEG)", it) } // C-22k
            .isSuccess
    }

    /**
     * Koduje bitmapę do JPEG MozJPEG.
     * @param quality 1..100
     * @param effort 0..100 „moc kompresji": im wyżej, tym mocniejsza optymalizacja (trellis + progresywny,
     *   wolniej). Poniżej progu — baseline optimized.
     * @return bajty JPEG albo null, gdy natywny moduł niedostępny lub kodowanie się nie powiodło.
     */
    fun encode(bitmap: Bitmap, quality: Int, effort: Int): ByteArray? {
        if (!available) return null
        val bmp = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
        else bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        return try {
            runCatching {
                nativeEncode(bmp, quality.coerceIn(1, 100), effort.coerceIn(0, 100))
            }.onFailure { Log.w(TAG, "encode: ${it.message}") }.getOrNull()
        } finally {
            // G-05f: kopia w ARGB_8888 (do 64 MB natywnej pamięci) zwalniana od razu, nie przy GC
            if (bmp !== bitmap) bmp.recycle()
        }
    }

    // implementacja w app/src/main/cpp/mozjpeg_jni.c (pakowana jako liblewicowyt_mozjpeg.so)
    private external fun nativeEncode(bitmap: Bitmap, quality: Int, effort: Int): ByteArray?
}
