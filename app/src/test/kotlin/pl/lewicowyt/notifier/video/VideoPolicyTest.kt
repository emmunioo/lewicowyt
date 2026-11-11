package pl.lewicowyt.notifier.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPolicyTest {
    private fun times(fps: Double, n: Int = 120) = (0 until n).map { (it * 1_000_000.0 / fps).toLong() }

    @Test
    fun `30 fps and NTSC 29_97 are allowed, 31 and 60 are not`() {
        assertTrue(VideoPolicy.isAllowedFps(30f))
        assertTrue(VideoPolicy.isAllowedFps(29.97f))
        assertTrue(VideoPolicy.isAllowedFps(24f))
        assertFalse(VideoPolicy.isAllowedFps(31f))
        assertFalse(VideoPolicy.isAllowedFps(60f))
        assertFalse("brak pomiaru = nie odtwarzamy", VideoPolicy.isAllowedFps(null))
    }

    @Test
    fun `fps is measured from sample times`() {
        assertEquals(30f, VideoPolicy.fpsFromSampleTimesUs(times(30.0))!!, 0.05f)
        assertEquals(60f, VideoPolicy.fpsFromSampleTimesUs(times(60.0))!!, 0.05f)
        assertEquals(29.97f, VideoPolicy.fpsFromSampleTimesUs(times(29.97))!!, 0.05f)
    }

    @Test
    fun `B-frame decode order and a single gap do not fool the measurement`() {
        // kolejność dekodowania przy klatkach B (I P B B …) + jedna dziura 1 s w środku
        val t = times(60.0).toMutableList()
        for (i in 1 until t.size - 2 step 3) { val x = t[i]; t[i] = t[i + 1]; t[i + 1] = x }
        val withGap = t.filterIndexed { i, _ -> i !in 50..55 }
        assertEquals(60f, VideoPolicy.fpsFromSampleTimesUs(withGap)!!, 0.05f)
    }

    @Test
    fun `only Opus audio is accepted, silent video is fine`() {
        assertTrue(VideoPolicy.isAllowedAudioTrack("audio/opus"))
        assertFalse(VideoPolicy.isAllowedAudioTrack("audio/vorbis"))
        assertFalse(VideoPolicy.isAllowedAudioTrack("audio/mp4a-latm"))
        assertFalse(VideoPolicy.isAllowedAudioTrack("audio/mpeg"))
        assertFalse("pole audio bez ścieżki dźwiękowej", VideoPolicy.isAllowedAudioTrack(null))
        assertTrue("film niemy", VideoPolicy.isAllowedVideoSoundtrack(null))
        assertTrue(VideoPolicy.isAllowedVideoSoundtrack("audio/opus"))
        assertFalse("AAC w filmie", VideoPolicy.isAllowedVideoSoundtrack("audio/mp4a-latm"))
    }

    @Test
    fun `too few samples give no measurement`() {
        assertNull(VideoPolicy.fpsFromSampleTimesUs(listOf(0L, 33_333L)))
    }
}
