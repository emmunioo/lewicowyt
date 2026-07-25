package pl.lewicowyt.notifier.images

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.awxkee.jxlcoder.JxlChannelsConfiguration
import com.awxkee.jxlcoder.JxlCoder
import com.awxkee.jxlcoder.JxlCompressionOption
import com.awxkee.jxlcoder.JxlEffort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JxlCodecInstrumentedTest {
    @Test
    fun encodesAndDecodesQuality69AtEffort10() {
        val source = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(210, 30, 45))
        }

        val encoded = JxlCoder.encode(
            source,
            JxlChannelsConfiguration.RGB,
            JxlCompressionOption.LOSSY,
            JxlEffort.GLACIER,
            JxlImageCache.JXL_QUALITY,
        )
        val decoded = JxlCoder.decode(encoded)

        assertEquals(10, JxlEffort.GLACIER.ordinal + 1)
        assertTrue(JxlCoder.isJXL(encoded))
        assertEquals(source.width, decoded.width)
        assertEquals(source.height, decoded.height)
    }
}
