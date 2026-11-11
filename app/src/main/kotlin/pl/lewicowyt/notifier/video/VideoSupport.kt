package pl.lewicowyt.notifier.video

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import java.io.File
import pl.lewicowyt.notifier.community.CommunityVideo
import pl.lewicowyt.notifier.community.CommunityVideoVariant

/** Wybór wariantu filmu pod możliwości telefonu i pomiar pobranego pliku. */
object VideoSupport {
    const val MIME_HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC
    const val MIME_AVC = MediaFormat.MIMETYPE_VIDEO_AVC

    data class Choice(val variant: CommunityVideoVariant, val mime: String)

    /**
     * H.265, gdy telefon ma do niego SPRZĘTOWY dekoder obsługujący ten rozmiar i 30 kl/s (najmniejszy plik,
     * lekkie dekodowanie). Bez sprzętowego H.265 programowe dekodowanie na słabym procesorze by się cięło —
     * wtedy wariant H.264 (dekoder ma każdy telefon). null, gdy nie ma czego bezpiecznie odtworzyć.
     */
    fun choose(video: CommunityVideo): Choice? {
        video.hevc?.let { v ->
            if (hasHardwareDecoder(MIME_HEVC, v.width ?: 0, v.height ?: 0)) return Choice(v, MIME_HEVC)
        }
        video.avc?.let { v -> if (hasAnyDecoder(MIME_AVC, v.width ?: 0, v.height ?: 0)) return Choice(v, MIME_AVC) }
        return null
    }

    private fun decoders(mime: String): List<MediaCodecInfo> =
        runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.toList() }.getOrDefault(emptyList())
            .filter { info -> !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }

    private fun supportsSize(info: MediaCodecInfo, mime: String, width: Int, height: Int): Boolean = runCatching {
        if (width <= 0 || height <= 0) return true // serwer nie podał rozmiaru — sprawdzi to sam dekoder
        info.getCapabilitiesForType(mime).videoCapabilities
            ?.areSizeAndRateSupported(width, height, VideoPolicy.MAX_FPS.toDouble()) == true
    }.getOrDefault(false)

    private fun isHardware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.isHardwareAccelerated && !info.isSoftwareOnly
        } else {
            val n = info.name.lowercase()
            !(n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains(".sw.") || n.endsWith(".sw"))
        }

    fun hasHardwareDecoder(mime: String, width: Int, height: Int): Boolean =
        decoders(mime).any { isHardware(it) && supportsSize(it, mime, width, height) }

    private fun hasAnyDecoder(mime: String, width: Int, height: Int): Boolean =
        decoders(mime).any { supportsSize(it, mime, width, height) }

    /** audioMime: kodek PIERWSZEJ ścieżki dźwiękowej (np. audio/opus, audio/mp4a-latm) albo null, gdy niemy */
    data class Probe(val mime: String, val width: Int, val height: Int, val fps: Float?, val audioMime: String? = null)

    /**
     * Kodek pierwszej ścieżki dźwiękowej pliku (Ogg/WebM/MP4) albo null, gdy jej brak / pliku nie da się odczytać.
     * Do wymuszenia „tylko Opus" — sprawdzamy zawartość, nie rozszerzenie.
     */
    fun audioTrackMime(file: File): String? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.path)
            (0 until extractor.trackCount)
                .mapNotNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
                .firstOrNull { it.startsWith("audio/") }
        } catch (_: Exception) {
            null
        } finally {
            extractor.release()
        }
    }

    /**
     * Mierzy PRAWDZIWE parametry pobranego pliku: kodek, rozmiar i liczbę klatek na sekundę — z czasów
     * pierwszych kilkuset próbek (nie z deklaracji serwera ani nagłówka, który da się wpisać dowolnie).
     */
    fun probe(file: File): Probe? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.path)
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return null
            val format = extractor.getTrackFormat(index)
            extractor.selectTrack(index)
            val times = ArrayList<Long>(MAX_PROBE_SAMPLES)
            while (times.size < MAX_PROBE_SAMPLES) {
                val t = extractor.sampleTime
                if (t < 0) break
                times += t
                if (!extractor.advance()) break
            }
            val measured = VideoPolicy.fpsFromSampleTimesUs(times)
            // nagłówek bywa pusty; gdy jest i deklaruje więcej niż pomiar — bierzemy wyższą wartość (ostrożnie)
            val declared = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }
                    .recoverCatching { format.getFloat(MediaFormat.KEY_FRAME_RATE) }.getOrNull()
            } else null
            Probe(
                mime = format.getString(MediaFormat.KEY_MIME).orEmpty(),
                width = format.getInteger(MediaFormat.KEY_WIDTH),
                height = format.getInteger(MediaFormat.KEY_HEIGHT),
                fps = listOfNotNull(measured, declared).maxOrNull(),
                audioMime = (0 until extractor.trackCount)
                    .mapNotNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
                    .firstOrNull { it.startsWith("audio/") },
            )
        } catch (_: Exception) {
            null
        } finally {
            extractor.release()
        }
    }

    private const val MAX_PROBE_SAMPLES = 300
}
