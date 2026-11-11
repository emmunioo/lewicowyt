package pl.lewicowyt.notifier.video

/**
 * Sprawdzanie formatu PODCZAS pobierania — na pierwszych bajtach pliku, zanim ściągniemy resztę.
 * Ograniczenia (tylko Opus, H.265/H.264, najwyżej 30 kl/s) są po to, żeby oszczędzać transfer — więc plik,
 * który ich nie spełnia, przerywamy po kilkuset bajtach / kilkudziesięciu KB zamiast pobierać całe MB.
 * Wciąż jedno zwykłe zapytanie GET (bez zapytań Range o kawałki) — serwer widzi najwyżej przerwane połączenie.
 * Czysta logika bez Androida (testowalna na JVM).
 */
sealed interface PrecheckDecision {
    /** za mało bajtów, żeby zdecydować — czytaj dalej */
    data object NeedMore : PrecheckDecision
    /** format w porządku — pobierz resztę bez dalszych sprawdzeń */
    data object Accept : PrecheckDecision
    /** format niedozwolony — przerwij pobieranie i skasuj to, co już przyszło */
    data class Reject(val reason: String) : PrecheckDecision
}

/** prefix = pierwsze [length] bajtów pliku (tablica może być większa) */
fun interface MediaPrecheck {
    fun check(prefix: ByteArray, length: Int): PrecheckDecision
}

object MediaPrechecks {
    /** najwięcej bajtów, jakie trzymamy w pamięci do decyzji (spis MP4 długiego filmu bywa kilka MB) */
    const val MAX_PREFIX_BYTES = 8 * 1024 * 1024
    private const val OGG_ID_PAGE_BYTES = 512
    private const val WEBM_TRACKS_BYTES = 512 * 1024

    /** pole `audio`: Opus w Ogg albo w WebM/Matroska. */
    fun opusAudio(): MediaPrecheck = MediaPrecheck { b, n ->
        when {
            n < 4 -> PrecheckDecision.NeedMore
            ascii(b, 0, 4) == "OggS" -> {
                // pierwsza strona Ogg niesie pakiet identyfikacyjny kodeka: "OpusHead" dla Opusa
                val window = minOf(n, OGG_ID_PAGE_BYTES)
                when {
                    indexOf(b, window, "OpusHead") >= 0 -> PrecheckDecision.Accept
                    n >= OGG_ID_PAGE_BYTES -> PrecheckDecision.Reject(VideoPolicy.notOpusMessage(oggCodecName(b, window)))
                    else -> PrecheckDecision.NeedMore
                }
            }
            isEbml(b) -> webmAudioDecision(b, n, requireAudio = true)
            else -> PrecheckDecision.Reject("To nie jest plik dźwiękowy Ogg ani WebM.")
        }
    }

    /** film: MP4 z kodekiem [expected] („hevc" albo „avc"), ≤ 30 kl/s, dźwięk tylko Opus (albo niemy). */
    fun mp4Video(expected: String): MediaPrecheck = MediaPrecheck { b, n ->
        when (val r = Mp4Header.inspect(b, n)) {
            Mp4Header.Result.NeedMore -> PrecheckDecision.NeedMore
            Mp4Header.Result.NotFastStart -> PrecheckDecision.Reject(
                "Ten film ma spis zawartości na końcu pliku — nie da się go sprawdzić bez pobrania całości. " +
                    "Poproś serwer o wersję z „faststart”.",
            )
            is Mp4Header.Result.Invalid -> PrecheckDecision.Reject(r.reason)
            is Mp4Header.Result.Info -> decideVideo(r, expected)
        }
    }

    internal fun decideVideo(info: Mp4Header.Result.Info, expected: String): PrecheckDecision {
        val codec = info.videoCodec
        val ok = when (expected) {
            "hevc" -> codec == "hvc1" || codec == "hev1"
            "avc" -> codec == "avc1" || codec == "avc3"
            else -> false
        }
        if (!ok) return PrecheckDecision.Reject("Film ma kodek $codec, a serwer zadeklarował $expected — nie pobieram.")
        val fps = info.fps ?: return PrecheckDecision.Reject("Nie da się sprawdzić liczby klatek — nie pobieram.")
        if (!VideoPolicy.isAllowedFps(fps)) return PrecheckDecision.Reject(VideoPolicy.tooManyFpsMessage(fps))
        val sound = info.audioCodec
        if (sound != null && sound != "Opus") {
            return PrecheckDecision.Reject(VideoPolicy.notOpusMessage(if (sound == "mp4a") "AAC" else sound))
        }
        return PrecheckDecision.Accept
    }

    private fun webmAudioDecision(b: ByteArray, n: Int, requireAudio: Boolean): PrecheckDecision {
        // CodecID ścieżek („A_OPUS", „A_VORBIS", „A_AAC"…) siedzi w elemencie Tracks na początku pliku
        val codecs = audioCodecIds(b, n)
        return when {
            codecs.isNotEmpty() && codecs.all { it.startsWith("A_OPUS") } -> PrecheckDecision.Accept
            codecs.isNotEmpty() -> PrecheckDecision.Reject(VideoPolicy.notOpusMessage(codecs.first { !it.startsWith("A_OPUS") }))
            n >= WEBM_TRACKS_BYTES || n >= MAX_PREFIX_BYTES ->
                if (requireAudio) PrecheckDecision.Reject("W pliku nie ma ścieżki dźwiękowej Opus.") else PrecheckDecision.Accept
            else -> PrecheckDecision.NeedMore
        }
    }

    private fun audioCodecIds(b: ByteArray, n: Int): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i + 4 <= n) {
            if (b[i] == 'A'.code.toByte() && b[i + 1] == '_'.code.toByte() && b[i + 2] in 'A'.code.toByte()..'Z'.code.toByte()) {
                var j = i + 2
                while (j < n && j - i < 24 && (b[j].toInt().toChar().let { it.isLetterOrDigit() || it == '_' || it == '/' || it == '.' })) j++
                if (j - i >= 4) out += String(b, i, j - i, Charsets.ISO_8859_1)
                i = j
            } else {
                i++
            }
        }
        return out
    }

    private fun oggCodecName(b: ByteArray, n: Int): String? = when {
        indexOf(b, n, "vorbis") >= 0 -> "Vorbis"
        indexOf(b, n, "FLAC") >= 0 -> "FLAC"
        indexOf(b, n, "Speex") >= 0 -> "Speex"
        else -> null
    }

    private fun isEbml(b: ByteArray) =
        b[0] == 0x1A.toByte() && b[1] == 0x45.toByte() && b[2] == 0xDF.toByte() && b[3] == 0xA3.toByte()

    internal fun ascii(b: ByteArray, from: Int, len: Int): String = String(b, from, len, Charsets.ISO_8859_1)

    internal fun indexOf(b: ByteArray, n: Int, needle: String): Int {
        val nb = needle.toByteArray(Charsets.ISO_8859_1)
        outer@ for (i in 0..(n - nb.size)) {
            for (k in nb.indices) if (b[i + k] != nb[k]) continue@outer
            return i
        }
        return -1
    }
}

/**
 * Minimalny czytnik nagłówków MP4: pudełka najwyższego poziomu aż do `moov` i z niego kodeki, rozmiar,
 * czas trwania i liczba próbek ścieżki wideo. `mdat` przed `moov` = plik bez „faststart" (spis na końcu).
 */
object Mp4Header {
    sealed interface Result {
        data object NeedMore : Result
        data object NotFastStart : Result
        data class Invalid(val reason: String) : Result
        data class Info(val videoCodec: String, val width: Int, val height: Int, val fps: Float?, val audioCodec: String?) : Result
    }

    private data class Box(val type: String, val start: Int, val end: Int)

    fun inspect(b: ByteArray, n: Int): Result {
        if (n < 8) return Result.NeedMore
        var p = 0
        var first = true
        while (true) {
            if (p + 8 > n) return Result.NeedMore
            var size = u32(b, p)
            val type = MediaPrechecks.ascii(b, p + 4, 4)
            var header = 8
            if (size == 1L) {
                if (p + 16 > n) return Result.NeedMore
                size = u64(b, p + 8)
                header = 16
            }
            if (first && type != "ftyp") return Result.Invalid("To nie jest plik MP4.")
            first = false
            if (size < header) return Result.Invalid("Uszkodzony plik MP4.")
            when (type) {
                "mdat" -> return Result.NotFastStart
                "moov" -> {
                    if (size > MediaPrechecks.MAX_PREFIX_BYTES) return Result.Invalid("Spis zawartości filmu jest za duży.")
                    val end = p + size.toInt()
                    if (end > n) return Result.NeedMore
                    return parseMoov(b, p + header, end)
                }
            }
            // C-13: sprawdzenie w Long PRZED przesunięciem — ogromne pudełko (np. free 0x7FFFFFF0) nie przepełni Int
            // Q-02: bez dodawania (p + size) — 64-bitowy rozmiar bliski Long.MAX_VALUE przepełniał sumę do liczby
            // ujemnej, a p += size.toInt() cofało p → nieskończona pętla na spreparowanym pliku
            if (size > MediaPrechecks.MAX_PREFIX_BYTES - p) return Result.Invalid("Nie znaleziono spisu zawartości filmu.")
            p += size.toInt()
        }
    }

    private fun parseMoov(b: ByteArray, start: Int, end: Int): Result {
        var video: Result.Info? = null
        var audio: String? = null
        for (trak in children(b, start, end).filter { it.type == "trak" }) {
            val mdia = child(b, trak, "mdia") ?: continue
            val hdlr = child(b, mdia, "hdlr") ?: continue
            if (hdlr.start + 12 > hdlr.end) return Result.Invalid("Uszkodzony plik MP4.") // C-13
            val handler = MediaPrechecks.ascii(b, hdlr.start + 8, 4)
            val stbl = child(b, child(b, mdia, "minf") ?: mdia, "stbl") ?: continue
            val stsd = child(b, stbl, "stsd") ?: continue
            val entry = stsd.start + 8 // fullbox(4) + entry_count(4)
            if (entry + 36 > stsd.end) continue
            val fourcc = MediaPrechecks.ascii(b, entry + 4, 4)
            when (handler) {
                "vide" -> if (video == null) {
                    val mdhd = child(b, mdia, "mdhd")
                    var timescale = 0L
                    var duration = 0L
                    if (mdhd != null) {
                        // C-13: pola mdhd tylko w granicach pudełka (wersja 1: 64-bitowe czasy)
                        val need = if (mdhd.start < mdhd.end && b[mdhd.start].toInt() == 1) 32 else 20
                        if (mdhd.start + need > mdhd.end) return Result.Invalid("Uszkodzony plik MP4.")
                        if (b[mdhd.start].toInt() == 1) { timescale = u32(b, mdhd.start + 20); duration = u64(b, mdhd.start + 24) }
                        else { timescale = u32(b, mdhd.start + 12); duration = u32(b, mdhd.start + 16) }
                    }
                    var samples = 0L
                    child(b, stbl, "stts")?.takeIf { it.start + 8 <= it.end }?.let { stts ->
                        val count = u32(b, stts.start + 4)
                        var i = 0L
                        while (i < count && stts.start + 8 + i * 8 + 8 <= stts.end) {
                            samples += u32(b, (stts.start + 8 + i * 8).toInt())
                            i++
                        }
                    }
                    val seconds = if (timescale > 0) duration.toDouble() / timescale else 0.0
                    video = Result.Info(
                        videoCodec = fourcc,
                        width = u16(b, entry + 32),
                        height = u16(b, entry + 34),
                        fps = if (samples > 0 && seconds > 0) (samples / seconds).toFloat() else null,
                        audioCodec = null,
                    )
                }
                "soun" -> if (audio == null) audio = fourcc
            }
        }
        return video?.copy(audioCodec = audio) ?: Result.Invalid("W pliku nie ma ścieżki wideo.")
    }

    private fun children(b: ByteArray, start: Int, end: Int): List<Box> {
        val out = ArrayList<Box>()
        var p = start
        while (p + 8 <= end) {
            var size = u32(b, p)
            var header = 8
            if (size == 1L) { if (p + 16 > end) break; size = u64(b, p + 8); header = 16 } else if (size == 0L) size = (end - p).toLong()
            if (size < header || size > (end - p).toLong()) break // Q-02: bez przepełnienia p + size
            out += Box(MediaPrechecks.ascii(b, p + 4, 4), p + header, (p + size).toInt())
            p += size.toInt()
        }
        return out
    }

    private fun child(b: ByteArray, box: Box, type: String): Box? = children(b, box.start, box.end).firstOrNull { it.type == type }

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
    private fun u32(b: ByteArray, i: Int): Long =
        ((b[i].toLong() and 0xFF) shl 24) or ((b[i + 1].toLong() and 0xFF) shl 16) or
            ((b[i + 2].toLong() and 0xFF) shl 8) or (b[i + 3].toLong() and 0xFF)
    private fun u64(b: ByteArray, i: Int): Long = (u32(b, i) shl 32) or u32(b, i + 4)
}
