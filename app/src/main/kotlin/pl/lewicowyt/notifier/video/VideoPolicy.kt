package pl.lewicowyt.notifier.video

/**
 * Zasady odtwarzania filmów z serwerów społeczności (bez zależności od Androida — testowalne na JVM).
 *
 * Twardy limit 30 kl/s: apka NIE odtwarza filmu, który ma więcej klatek na sekundę — nawet jeśli serwer
 * zadeklarował mniej. Mierzymy to sami w pobranym pliku. Dzięki temu serwery nie mają jak „przemycić"
 * 60 kl/s (dwa razy więcej pracy dekodera i baterii na słabym telefonie) — limit jest wymuszony po stronie
 * klienta, więc operatorzy muszą kodować w 30 kl/s.
 */
object VideoPolicy {
    const val MAX_FPS = 30f
    // 29,97 / 30,00 i zaokrąglenia z kontenera mieszczą się w limicie; 31+ już nie
    private const val TOLERANCE = 0.5f

    fun isAllowedFps(fps: Float?): Boolean = fps != null && fps > 0f && fps <= MAX_FPS + TOLERANCE

    /**
     * Liczba klatek na sekundę z czasów prezentacji próbek (µs) — mediana odstępów między kolejnymi klatkami
     * (po posortowaniu, bo przy klatkach B kolejność dekodowania ≠ kolejność wyświetlania). Mediana nie daje
     * się oszukać pojedynczą dziurą ani duplikatem; null, gdy próbek jest za mało.
     */
    fun fpsFromSampleTimesUs(times: List<Long>): Float? {
        val sorted = times.filter { it >= 0 }.distinct().sorted()
        if (sorted.size < 3) return null
        val deltas = sorted.zipWithNext { a, b -> b - a }.filter { it > 0 }.sorted()
        if (deltas.isEmpty()) return null
        val median = deltas[deltas.size / 2]
        return 1_000_000f / median
    }

    /**
     * Jedyny dopuszczony kodek dźwięku w całej aplikacji: Opus (pole `audio` i ścieżka dźwiękowa filmu).
     * Sprawdzamy PRAWDZIWY kodek ścieżki w pobranym pliku, nie rozszerzenie ani kontener.
     */
    const val MIME_OPUS = "audio/opus"

    /** dźwięk z pola `audio`: musi być ścieżka i musi to być Opus */
    fun isAllowedAudioTrack(mime: String?): Boolean = mime != null && mime.equals(MIME_OPUS, ignoreCase = true)

    /** ścieżka dźwiękowa filmu: film niemy jest w porządku, a jeśli dźwięk jest — tylko Opus */
    fun isAllowedVideoSoundtrack(mime: String?): Boolean = mime == null || isAllowedAudioTrack(mime)

    fun notOpusMessage(mime: String?): String =
        "Dźwięk w tym pliku nie jest w formacie Opus" + (mime?.let { " ($it)" } ?: "") +
            ". lewicowYT odtwarza dźwięk wyłącznie w Opusie — poproś serwer o wersję Opus."

    /** komunikat dla użytkownika, gdy film łamie limit */
    fun tooManyFpsMessage(fps: Float): String =
        "Ten film ma %.0f kl/s. lewicowYT odtwarza filmy najwyżej 30 kl/s — poproś serwer o wersję 30 kl/s.".format(fps)
}
