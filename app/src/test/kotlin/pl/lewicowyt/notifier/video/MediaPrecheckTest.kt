package pl.lewicowyt.notifier.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pliki w resources/media to malutkie nagrania z ffmpeg (64×64, 1 s) — prawdziwe kontenery, nie ręczne bajty. */
class MediaPrecheckTest {
    private fun bytes(name: String) = javaClass.getResourceAsStream("/media/$name")!!.readBytes()

    /** symulacja pobierania: kawałki po 512 B, decyzja po każdym — zwraca decyzję i ile bajtów było potrzeba */
    private fun stream(check: MediaPrecheck, data: ByteArray): Pair<PrecheckDecision, Int> {
        var n = 0
        while (n < data.size) {
            n = minOf(n + 512, data.size)
            val d = check.check(data, n)
            if (d != PrecheckDecision.NeedMore) return d to n
        }
        return check.check(data, n) to n
    }

    @Test
    fun `Opus in Ogg and WebM is accepted from the first chunk`() {
        for (f in listOf("opus.ogg", "opus.webm")) {
            val (d, n) = stream(MediaPrechecks.opusAudio(), bytes(f))
            assertEquals(f, PrecheckDecision.Accept, d)
            assertEquals("$f: decyzja po pierwszym kawałku", 512, n)
        }
    }

    @Test
    fun `Vorbis is rejected after the first chunk`() {
        for (f in listOf("vorbis.ogg", "vorbis.webm")) {
            val (d, n) = stream(MediaPrechecks.opusAudio(), bytes(f))
            assertTrue(f, d is PrecheckDecision.Reject)
            assertTrue("$f: przerwane wcześnie ($n B)", n <= 1024)
        }
    }

    @Test
    fun `video file is not audio`() {
        assertTrue(stream(MediaPrechecks.opusAudio(), bytes("avc30_aac.mp4")).first is PrecheckDecision.Reject)
    }

    @Test
    fun `HEVC 30 fps with Opus is accepted`() {
        assertEquals(PrecheckDecision.Accept, stream(MediaPrechecks.mp4Video("hevc"), bytes("hevc30_opus.mp4")).first)
    }

    @Test
    fun `silent H264 is accepted`() {
        assertEquals(PrecheckDecision.Accept, stream(MediaPrechecks.mp4Video("avc"), bytes("avc30_silent.mp4")).first)
    }

    @Test
    fun `60 fps, AAC and wrong codec are rejected before the frames`() {
        val cases = listOf("hevc60.mp4" to "hevc", "avc30_aac.mp4" to "avc", "avc30_silent.mp4" to "hevc")
        for ((f, codec) in cases) {
            val data = bytes(f)
            val (d, n) = stream(MediaPrechecks.mp4Video(codec), data)
            assertTrue("$f/$codec: $d", d is PrecheckDecision.Reject)
            assertTrue("$f: przerwane przed końcem ($n z ${data.size})", n < data.size)
        }
    }

    @Test
    fun `header info is read from moov`() {
        val data = bytes("hevc60.mp4")
        val info = Mp4Header.inspect(data, data.size) as Mp4Header.Result.Info
        assertEquals(64, info.width)
        assertEquals(64, info.height)
        assertEquals(60f, info.fps!!, 0.5f)
        assertEquals(null, info.audioCodec)
    }

    @Test
    fun `file without faststart is refused`() {
        val data = bytes("avc30_nofaststart.mp4")
        assertEquals(Mp4Header.Result.NotFastStart, Mp4Header.inspect(data, data.size))
        assertTrue(stream(MediaPrechecks.mp4Video("avc"), data).first is PrecheckDecision.Reject)
    }

    @Test
    fun `C-13 huge or truncated boxes give a clear rejection, not an exception`() {
        fun box(type: String, size: Long, body: Int = 0): ByteArray {
            val b = ByteArray(8 + body)
            for (i in 0 until 4) b[i] = (size shr (24 - 8 * i)).toByte()
            type.toByteArray().copyInto(b, 4)
            return b
        }
        val ftyp = box("ftyp", 16, 8)
        val huge = ftyp + box("free", 0x7FFFFFF0L) + ByteArray(64)
        assertTrue(Mp4Header.inspect(huge, huge.size) is Mp4Header.Result.Invalid)
        // moov z obciętym hdlr (4 B zamiast >= 12)
        val hdlr = box("hdlr", 12, 4)
        val mdia = box("mdia", (8 + hdlr.size).toLong()) + hdlr
        val trak = box("trak", (8 + mdia.size).toLong()) + mdia
        val moov = box("moov", (8 + trak.size).toLong()) + trak
        val broken = ftyp + moov
        assertTrue(Mp4Header.inspect(broken, broken.size) is Mp4Header.Result.Invalid)
    }

    @Test(timeout = 5_000)
    fun `64-bit box size near Long MAX does not loop forever`() {
        // Q-02: ftyp(24) + pudełko z size32 = 1 i rozmiarem Long.MAX_VALUE - 23 → dawniej p + size przepełniało
        // się do liczby ujemnej, a p wracało do 0 (nieskończona pętla na wątku pobierania)
        val buf = java.nio.ByteBuffer.allocate(48)
        buf.putInt(24).put("ftyp".toByteArray()).put(ByteArray(16))
        buf.putInt(1).put("free".toByteArray()).putLong(Long.MAX_VALUE - 23)
        buf.put(ByteArray(8))
        val data = buf.array()
        assertTrue(Mp4Header.inspect(data, data.size) is Mp4Header.Result.Invalid)
    }
}
