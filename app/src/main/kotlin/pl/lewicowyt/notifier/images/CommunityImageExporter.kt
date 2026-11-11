package pl.lewicowyt.notifier.images

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fasada eksportu obrazów społeczności („nasza biblioteka" — jedno API nad najlepszymi enkoderami,
 * dobrane pod ROZMIAR APK: JXL kodujemy własnym enkoderem libjxl (NativeJxl), PNG/WEBP bierzemy z platformy (0 bajtów),
 * a JPG kodujemy MozJPEG-iem (wąski moduł NDK) z fallbackiem na platformowy baseline, gdy natywnej
 * biblioteki nie ma. AVIF świadomie bez enkodera (patrz niżej) — tylko odczyt.
 *
 * Wołane z długiego przytrzymania mema (zapis do galerii). Encode robimy z wątku IO.
 */
object CommunityImageExporter {

    /** Docelowy format zapisu. ORIGINAL = bajt-w-bajt to, co przysłał serwer (bez rekompresji). */
    // AVIF świadomie NIE ma tu enkodera: Android nie potrafi tworzyć AVIF, a osobny enkoder (aom)
    // ważyłby ~4,4 MB/ABI dla niszowej konwersji, którą i tak pokrywa JXL. Mem-AVIF zapiszesz jako
    // „ORIGINAL" (bajt-w-bajt). AVIF obsługujemy tylko przy ODCZYCIE (patrz AvifDec).
    enum class Format { ORIGINAL, JXL, WEBP, PNG, JPG }

    data class Encoded(val bytes: ByteArray, val extension: String, val mime: String)

    // kolejność efektu (od najszybszego/najsłabszego do najsilniejszego) — mapujemy na nią suwak „moc"
    // (libjxl effort 1..10: 1 = najszybciej, 7 = domyślny, 10 = najmocniej)
    private const val JXL_EFFORT_COUNT = 10

    /**
     * @param quality 1..100 (dla formatów stratnych; PNG/oryginał ignorują)
     * @param effort  0..100 „moc kompresji" — działa dla JXL, AVIF i MozJPEG; WEBP/PNG ją ignorują
     */
    suspend fun export(
        context: Context,
        url: String,
        format: Format,
        quality: Int,
        effort: Int,
    ): Encoded? = withContext(Dispatchers.IO) {
        val q = quality.coerceIn(1, 100)
        val e = effort.coerceIn(0, 100)

        if (format == Format.ORIGINAL) {
            // R-16: „Oryginał” = prawdziwe bajty z serwera — POMIJAMY cache (mógł być po rekompresji).
            val raw = CommunityImageLoader.fetchOriginal(context, url, allowCache = false) ?: return@withContext null
            val ext = sniffExtension(raw) ?: urlExtension(url) ?: return@withContext null
            return@withContext Encoded(raw, ext, mimeFor(ext))
        }

        // Do rekompresji bierzemy PEŁNOrozdzielczościową bitmapę z PAMIĘCI TELEFONU (cache na dysku) — bez
        // ponownego pobierania z serwera. (load() dawałby wersję zmniejszoną do ekranu — gorszą do galerii.)
        val src = CommunityImageLoader.fetchOriginal(context, url) ?: return@withContext null
        val bitmap = CommunityImageLoader.decodeFull(src) ?: return@withContext null
        runCatching {
            when (format) {
                Format.JXL -> Encoded(
                    NativeJxl.encode(bitmap, q, jxlEffort(e)) ?: error("JXL encode failed"),
                    "jxl", "image/jxl",
                )
                Format.WEBP -> Encoded(compressWebp(bitmap, q), "webp", "image/webp")
                Format.PNG -> Encoded(compressPng(bitmap), "png", "image/png")
                Format.JPG -> {
                    val bytes = MozJpeg.encode(bitmap, q, e) ?: compressJpeg(bitmap, q)
                    Encoded(bytes, "jpg", "image/jpeg")
                }
                Format.ORIGINAL -> null
            }
        }.getOrNull()
    }

    private fun jxlEffort(effort: Int): Int =
        1 + (effort.coerceIn(0, 100) * (JXL_EFFORT_COUNT - 1)) / 100

    private fun compressWebp(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        @Suppress("DEPRECATION")
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            Bitmap.CompressFormat.WEBP
        }
        bitmap.compress(format, quality, out)
        return out.toByteArray()
    }

    private fun compressPng(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) // PNG bezstratny — jakość bez znaczenia
        return out.toByteArray()
    }

    private fun compressJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    /** rozpoznanie kontenera po „magic bytes" (do zapisu oryginału z właściwym rozszerzeniem). */
    private fun sniffExtension(b: ByteArray): String? {
        if (b.size < 12) return null
        fun u(i: Int) = b[i].toInt() and 0xFF
        // PNG
        if (u(0) == 0x89 && u(1) == 0x50 && u(2) == 0x4E && u(3) == 0x47) return "png"
        // JPEG
        if (u(0) == 0xFF && u(1) == 0xD8 && u(2) == 0xFF) return "jpg"
        // RIFF....WEBP
        if (u(0) == 0x52 && u(1) == 0x49 && u(2) == 0x46 && u(3) == 0x46 &&
            u(8) == 0x57 && u(9) == 0x45 && u(10) == 0x42 && u(11) == 0x50
        ) return "webp"
        // ISO-BMFF ftyp z markami avif/avis → AVIF
        if (u(4) == 0x66 && u(5) == 0x74 && u(6) == 0x79 && u(7) == 0x70) {
            val brand = String(b, 8, 4, Charsets.US_ASCII)
            if (brand == "avif" || brand == "avis") return "avif"
            if (brand.startsWith("hei") || brand == "mif1" || brand == "msf1") return "heic"
        }
        // JPEG XL: codestream (FF 0A) albo kontener ISOBMFF (....JXL )
        if (u(0) == 0xFF && u(1) == 0x0A) return "jxl"
        if (u(4) == 0x4A && u(5) == 0x58 && u(6) == 0x4C && u(7) == 0x20) return "jxl"
        return runCatching { if (NativeJxl.isJxl(b)) "jxl" else null }.getOrNull()
    }

    private fun urlExtension(url: String): String? =
        url.substringBefore('?').substringBefore('#').substringAfterLast('.', "")
            .lowercase().takeIf { it in setOf("jxl", "avif", "webp", "png", "jpg", "jpeg") }
            ?.let { if (it == "jpeg") "jpg" else it }

    private fun mimeFor(ext: String): String = when (ext) {
        "jxl" -> "image/jxl"
        "avif" -> "image/avif"
        "webp" -> "image/webp"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "heic" -> "image/heic"
        else -> "application/octet-stream"
    }
}
