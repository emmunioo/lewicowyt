package pl.lewicowyt.notifier.images

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import java.nio.ByteBuffer

/**
 * Podgląd NIEPEŁNEGO pliku progresywnego JPEG / PNG z przeplotem (Android 9+, systemowy ImageDecoder): dekoder
 * oddaje to, co da się złożyć z dotychczasowych skanów/przebiegów (setOnPartialImageListener → true), w rozmiarze
 * wyświetlania. Każde wywołanie dekoduje od początku — wołający ogranicza liczbę i częstotliwość podglądów.
 */
internal object PartialImageDecoder {
    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    /** null = za mało danych, błąd albo obraz poza limitami (anty-DoS jak w zwykłym dekodowaniu). */
    fun decode(bytes: ByteArray, maxDim: Int, maxImageDim: Int): Bitmap? {
        if (!isSupported()) return null
        return runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                require(w in 1..maxImageDim && h in 1..maxImageDim) { "rozmiar poza limitem" }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setOnPartialImageListener { true } // niepełny plik → oddaj częściowy obraz
                val longest = maxOf(w, h)
                if (longest > maxDim) {
                    val scale = maxDim.toFloat() / longest
                    decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                }
            }
        }.getOrNull()
    }
}
