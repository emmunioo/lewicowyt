package pl.lewicowyt.notifier.images

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * R-23: kontrakt ŁAGODNEJ DEGRADACJI mostu JNI do libjxl. W teście JVM nie ma `liblewicowyt_jxl.so`
 * (System.loadLibrary rzuca UnsatisfiedLinkError), więc [NativeJxl.isAvailable] = false. Wołający
 * (JxlImageCache i CommunityImageLoader — D-19f) polega na tym, że przy niedostępnej bibliotece żadna metoda NIE rzuca, tylko
 * oddaje wartość neutralną (null/false) — obraz JXL jest pomijany (brak zapasowego dekodera). Ten test pilnuje tej gwarancji;
 * samego dekodowania pikseli nie da się sprawdzić bez urządzenia (to osobny test instrumentalny).
 */
class NativeJxlTest {

    @Test
    fun `library is unavailable on the JVM`() {
        assertFalse(NativeJxl.isAvailable())
    }

    @Test
    fun `decode degrades to null instead of throwing when native lib is missing`() {
        assertNull(NativeJxl.decode(byteArrayOf(0xFF.toByte(), 0x0A)))
        assertNull(NativeJxl.decode(ByteArray(0)))
    }

    @Test
    fun `isJxl degrades to false instead of throwing when native lib is missing`() {
        assertFalse(NativeJxl.isJxl(byteArrayOf(0xFF.toByte(), 0x0A)))
        assertFalse(NativeJxl.isJxl(ByteArray(0)))
    }

    @Test
    fun `progressive decode degrades to null and never calls the listener when native lib is missing`() {
        var passes = 0
        val result = NativeJxl.decodeProgressive(byteArrayOf(0xFF.toByte(), 0x0A)) { passes++ }
        assertNull(result)
        assertFalse(passes > 0)
    }
}
