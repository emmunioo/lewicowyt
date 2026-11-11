package pl.lewicowyt.notifier.images

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Podgląd niepełnego progresywnego JPEG i PNG z przeplotem (ImageDecoder, Android 9+). */
@RunWith(AndroidJUnit4::class)
class PartialImageDecoderInstrumentedTest {
    private val w = 640
    private val h = 480

    private fun pixels() = IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        val noise = ((x * 7919 + y * 104729) xor (x * y)) and 0x3F
        (0xFF shl 24) or (((x * 255 / w + noise) and 0xFF) shl 16) or (((y * 255 / h + noise) and 0xFF) shl 8) or 0x80
    }

    @Test
    fun truncatedProgressiveJpegGivesWholeImagePreview() {
        assumeTrue(PartialImageDecoder.isSupported() && MozJpeg.available)
        val bitmap = Bitmap.createBitmap(pixels(), w, h, Bitmap.Config.ARGB_8888)
        val jpeg = MozJpeg.encode(bitmap, quality = 85, effort = 100)!! // effort ≥ progu = progresywny
        assertEquals(ProgressiveImageFormat.Kind.PROGRESSIVE_JPEG, ProgressiveImageFormat.detect(jpeg))
        val preview = PartialImageDecoder.decode(jpeg.copyOf(jpeg.size / 3), maxDim = 1280, maxImageDim = 4096)
        assertNotNull("podgląd z 1/3 pliku", preview)
        assertEquals(w, preview!!.width)
        assertEquals(h, preview.height)
    }

    @Test
    fun truncatedInterlacedPngGivesWholeImagePreview() {
        assumeTrue(PartialImageDecoder.isSupported())
        val png = interlacedPng(pixels())
        assertEquals(ProgressiveImageFormat.Kind.INTERLACED_PNG, ProgressiveImageFormat.detect(png))
        val preview = PartialImageDecoder.decode(png.copyOf(png.size / 3), maxDim = 1280, maxImageDim = 4096)
        assertNotNull("podgląd z 1/3 pliku", preview)
        assertEquals(w, preview!!.width)
        assertEquals(h, preview.height)
    }

    @Test
    fun largeImageIsScaledToDisplaySizeAndBombsAreRejected() {
        assumeTrue(PartialImageDecoder.isSupported())
        val png = interlacedPng(pixels())
        val small = PartialImageDecoder.decode(png, maxDim = 320, maxImageDim = 4096)!!
        assertEquals(320, small.width)
        assertEquals(null, PartialImageDecoder.decode(png, maxDim = 1280, maxImageDim = 100))
    }

    /** Minimalny enkoder PNG RGB8 z przeplotem Adam7 (do testu — Android sam nie zapisuje przeplotu). */
    private fun interlacedPng(argb: IntArray): ByteArray {
        val passes = listOf(intArrayOf(0, 0, 8, 8), intArrayOf(4, 0, 8, 8), intArrayOf(0, 4, 4, 8), intArrayOf(2, 0, 4, 4),
            intArrayOf(0, 2, 2, 4), intArrayOf(1, 0, 2, 2), intArrayOf(0, 1, 1, 2))
        val raw = ByteArrayOutputStream()
        for ((x0, y0, dx, dy) in passes) {
            if (x0 >= w || y0 >= h) continue
            for (y in y0 until h step dy) {
                raw.write(0) // filtr „None"
                for (x in x0 until w step dx) {
                    val p = argb[y * w + x]
                    raw.write(p shr 16 and 0xFF); raw.write(p shr 8 and 0xFF); raw.write(p and 0xFF)
                }
            }
        }
        val deflater = Deflater(6).apply { setInput(raw.toByteArray()); finish() }
        val idat = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!deflater.finished()) idat.write(buf, 0, deflater.deflate(buf))
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        fun chunk(type: String, data: ByteArray) {
            fun int(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
            out.write(int(data.size))
            val td = type.toByteArray() + data
            out.write(td)
            out.write(int(CRC32().apply { update(td) }.value.toInt()))
        }
        val ihdr = ByteArrayOutputStream().apply {
            write(byteArrayOf(0, 0, (w shr 8).toByte(), w.toByte(), 0, 0, (h shr 8).toByte(), h.toByte()))
            write(byteArrayOf(8, 2, 0, 0, 1)) // 8 bit, RGB, deflate, filtr adaptacyjny, PRZEPLOT Adam7
        }.toByteArray()
        chunk("IHDR", ihdr)
        chunk("IDAT", idat.toByteArray())
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }
}
