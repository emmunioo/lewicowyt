package pl.lewicowyt.notifier.images

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Dekoder strumieniowy JXL (podgląd w trakcie pobierania) — dane podawane kawałkami jak z sieci. */
@RunWith(AndroidJUnit4::class)
class NativeJxlStreamInstrumentedTest {

    private fun sampleJxl(): ByteArray {
        // obraz z detalem (gradienty + szum), żeby plik miał pasy DC i AC jak prawdziwe zdjęcie
        val w = 640
        val h = 480
        val pixels = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val noise = ((x * 7919 + y * 104729) xor (x * y)) and 0x3F
            val r = (x * 255 / w + noise) and 0xFF
            val g = (y * 255 / h + noise) and 0xFF
            val b = ((x + y) * 255 / (w + h)) and 0xFF
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val bitmap = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        return NativeJxl.encode(bitmap, 70, 7)!!
    }

    @Test
    fun streamedDecodeShowsPreviewsAndEndsWithTheSameImageAsFullDecode() {
        assumeTrue(NativeJxl.isAvailable())
        val jxl = sampleJxl()
        val reference = NativeJxl.decode(jxl)
        assertNotNull(reference)

        val stream = NativeJxl.openStream()
        assertNotNull(stream)
        var previews = 0
        var firstPreviewAtByte = -1
        var status = NativeJxl.Stream.Status.RUNNING
        stream!!.use { s ->
            var offset = 0
            while (offset < jxl.size && status != NativeJxl.Stream.Status.DONE) {
                val len = minOf(1024, jxl.size - offset)
                status = s.feed(jxl, offset, len, last = false)
                offset += len
                assertTrue("dekoder zgłosił błąd przy bajcie $offset", status != NativeJxl.Stream.Status.FAILED)
                if (status == NativeJxl.Stream.Status.NEW_PASS) {
                    val preview = s.preview()
                    if (preview != null) {
                        previews += 1
                        if (firstPreviewAtByte < 0) firstPreviewAtByte = offset
                        assertEquals(reference!!.width, preview.width)
                        assertEquals(reference.height, preview.height)
                    }
                }
            }
            if (status != NativeJxl.Stream.Status.DONE) status = s.feed(ByteArray(0), 0, 0, last = true)
            assertEquals(NativeJxl.Stream.Status.DONE, status)
            val result = s.result()
            assertNotNull(result)
            assertTrue("pełny obraz ze strumienia identyczny z dekodowaniem całego pliku", result!!.sameAs(reference))
        }
        assertTrue("brak podglądu przed końcem pobierania (plik ${jxl.size} B)", previews >= 1)
        assertTrue("pierwszy podgląd dopiero na końcu pliku", firstPreviewAtByte in 1 until jxl.size)
        println("JXL ${jxl.size} B: podglądów $previews, pierwszy po $firstPreviewAtByte B")
    }

    @Test
    fun truncatedFileFailsWithoutCrash() {
        assumeTrue(NativeJxl.isAvailable())
        val jxl = sampleJxl()
        NativeJxl.openStream()!!.use { s ->
            s.feed(jxl, 0, jxl.size / 2, last = false)
            assertEquals(NativeJxl.Stream.Status.FAILED, s.feed(ByteArray(0), 0, 0, last = true))
            assertEquals(null, s.result())
        }
    }

    @Test
    fun garbageIsRejected() {
        assumeTrue(NativeJxl.isAvailable())
        NativeJxl.openStream()!!.use { s ->
            val garbage = ByteArray(4096) { (it * 31).toByte() }
            assertEquals(NativeJxl.Stream.Status.FAILED, s.feed(garbage, 0, garbage.size, last = true))
        }
    }
}
