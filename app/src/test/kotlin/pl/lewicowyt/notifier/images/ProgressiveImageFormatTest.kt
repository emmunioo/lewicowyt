package pl.lewicowyt.notifier.images

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import pl.lewicowyt.notifier.images.ProgressiveImageFormat.Kind

class ProgressiveImageFormatTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    // JPEG: SOI, APP0 (JFIF, 16 B), DQT (4 B), potem SOF o podanym znaczniku
    private fun jpeg(sof: Int) = bytes(
        0xFF, 0xD8,
        0xFF, 0xE0, 0x00, 0x10, 'J'.code, 'F'.code, 'I'.code, 'F'.code, 0, 1, 1, 0, 0, 1, 0, 1, 0, 0,
        0xFF, 0xDB, 0x00, 0x04, 0x00, 0x01,
        0xFF, sof, 0x00, 0x0B, 8, 0, 16, 0, 16, 1, 1, 0x11, 0,
    )

    private fun png(interlace: Int) = bytes(
        0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0, 0, 0, 13, 'I'.code, 'H'.code, 'D'.code, 'R'.code,
        0, 0, 1, 0, 0, 0, 0, 0xC0, 8, 2, 0, 0, interlace,
    )

    @Test
    fun `jpeg xl codestream and container`() {
        assertEquals(Kind.JXL, ProgressiveImageFormat.detect(bytes(0xFF, 0x0A, 0x00)))
        assertEquals(
            Kind.JXL,
            ProgressiveImageFormat.detect(bytes(0, 0, 0, 0x0C, 0x4A, 0x58, 0x4C, 0x20, 0x0D, 0x0A, 0x87, 0x0A)),
        )
    }

    @Test
    fun `progressive jpeg is found behind header segments, baseline is not progressive`() {
        assertEquals(Kind.PROGRESSIVE_JPEG, ProgressiveImageFormat.detect(jpeg(0xC2)))
        assertEquals(Kind.NONE, ProgressiveImageFormat.detect(jpeg(0xC0)))
        assertEquals(Kind.NONE, ProgressiveImageFormat.detect(jpeg(0xC1)))
    }

    @Test
    fun `jpeg needs more bytes until the frame header arrives`() {
        val full = jpeg(0xC2)
        assertNull(ProgressiveImageFormat.detect(full.copyOf(22)))
        assertEquals(Kind.PROGRESSIVE_JPEG, ProgressiveImageFormat.detect(full))
    }

    @Test
    fun `huffman table marker is not mistaken for a frame header`() {
        val withDht = bytes(0xFF, 0xD8, 0xFF, 0xC4, 0x00, 0x03, 0x00, 0xFF, 0xC2, 0x00, 0x02)
        assertEquals(Kind.PROGRESSIVE_JPEG, ProgressiveImageFormat.detect(withDht))
    }

    @Test
    fun `interlaced png only`() {
        assertEquals(Kind.INTERLACED_PNG, ProgressiveImageFormat.detect(png(1)))
        assertEquals(Kind.NONE, ProgressiveImageFormat.detect(png(0)))
        assertNull(ProgressiveImageFormat.detect(png(1).copyOf(20)))
    }

    @Test
    fun `webp, avif and garbage have no preview`() {
        val webp = bytes('R'.code, 'I'.code, 'F'.code, 'F'.code, 0, 0, 0, 0, 'W'.code, 'E'.code, 'B'.code, 'P'.code)
        assertEquals(Kind.NONE, ProgressiveImageFormat.detect(webp))
        val avif = bytes(0, 0, 0, 0x1C, 'f'.code, 't'.code, 'y'.code, 'p'.code, 'a'.code, 'v'.code, 'i'.code, 'f'.code)
        assertEquals(Kind.NONE, ProgressiveImageFormat.detect(avif))
        assertEquals(Kind.NONE, ProgressiveImageFormat.detect(ByteArray(16) { 7 }))
        assertNull(ProgressiveImageFormat.detect(bytes(0x12)))
    }

    @Test
    fun `broken jpeg structure is not scanned forever`() {
        assertEquals(Kind.NONE, ProgressiveImageFormat.detect(bytes(0xFF, 0xD8, 0x00, 0x00, 0x00)))
        assertEquals(Kind.NONE, ProgressiveImageFormat.detect(bytes(0xFF, 0xD8, 0xFF, 0xE1, 0x00, 0x01)))
    }
}
