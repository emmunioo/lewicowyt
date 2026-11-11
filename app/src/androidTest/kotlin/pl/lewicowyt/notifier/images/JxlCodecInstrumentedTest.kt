package pl.lewicowyt.notifier.images

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JxlCodecInstrumentedTest {
    @Test
    // D-19k: test konfiguracji PRODUKCYJNEJ (JxlImageCache koduje z TORTOISE = effort 9, B-90), nie dawnego GLACIER
    fun encodesAndDecodesAtProductionEffortTortoise() {
        val source = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(210, 30, 45))
        }

        val encoded = NativeJxl.encode(source, JxlImageCache.JXL_QUALITY, 9)!!
        val decoded = NativeJxl.decode(encoded)!!

        assertTrue(NativeJxl.isJxl(encoded))
        assertEquals(source.width to source.height, NativeJxl.size(encoded))
        assertEquals(source.width, decoded.width)
        assertEquals(source.height, decoded.height)
    }
}
