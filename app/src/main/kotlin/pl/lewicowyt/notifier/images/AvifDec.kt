package pl.lewicowyt.notifier.images

import pl.lewicowyt.notifier.AppLog
import android.graphics.Bitmap
import android.util.Log

/**
 * Cienki binding do własnego, wąskiego modułu NDK z DEKODEREM AVIF (libavif + aom decode-only).
 * Potrzebny TYLKO na Androidzie < 12 (API < 31), bo od Androida 12 AVIF dekoduje platforma
 * (BitmapFactory/ImageDecoder). Dekoder AV1 jest dużo mniejszy od enkodera — pakujemy sam dekoder,
 * bez SIMD/NASM i bez stosu HEIC.
 *
 * Użycie: [CommunityImageLoader] dekoduje nim mem-AVIF na starszych Androidach, a wynikową bitmapę
 * (przy okazji, własnym enkoderem JXL — NativeJxl) zapisuje w cache jako JXL — więc ten sam AVIF
 * dekodujemy natywnie tylko raz.
 *
 * Bezpieczny fallback: gdy natywna biblioteka nie jest zbudowana/spakowana, [available] = false,
 * a [decode] zwraca null.
 */
object AvifDec {
    private const val TAG = "AvifDec"

    val available: Boolean by lazy {
        runCatching { System.loadLibrary("lewicowyt_avif") }
            .onFailure { AppLog.warning(TAG, "moduł avif (dekoder) niedostępny", it) } // C-22k: AppLog (bez logcat w release)
            .isSuccess
    }

    /** Dekoduje bajty AVIF do bitmapy ARGB_8888 albo null (moduł niedostępny / błąd). */
    fun decode(bytes: ByteArray): Bitmap? {
        if (!available) return null
        return runCatching { nativeDecode(bytes) }
            .onFailure { Log.w(TAG, "decode: ${it.message}") }.getOrNull()
    }

    // implementacja w app/src/main/cpp/avif_jni.c (pakowana jako liblewicowyt_avif.so)
    private external fun nativeDecode(data: ByteArray): Bitmap?
}
