package pl.lewicowyt.notifier.messaging

import java.util.Locale

/**
 * kontrakt ładunku FCM z serwera 1.9 – niezaufany i wersjonowany.
 *
 * serwer publikuje na jeden broadcast "feed" minimalny data-only { c: channelId,
 * v: videoId } (opcjonalnie p: platform). ta wersja klienta rozumie tylko YouTube.
 * jeśli serwer kiedyś zacznie tu słać inne platformy (Twitch, TikTok…), więcej
 * twórców, nowe pola albo zmieni format – klient ma to po cichu zignorować, bez
 * błędu i bez wywalania się.
 *
 * zasada: przyjmuj tylko to, co jednoznacznie znane (allowlista, nie blocklista),
 * resztę odrzuć bez efektu ubocznego i bez wyjątku.
 */
object FeedPayload {

    const val KEY_PLATFORM = "p"
    const val KEY_CHANNEL = "c"
    const val KEY_VIDEO = "v"

    // platformy, które ta wersja klienta ogarnia. dopisywać dopiero, gdy apka
    // naprawdę umie odebrać powiadomienie danej platformy (katalog, metadane,
    // powiadomienie). do tego czasu nieznana platforma leci do kosza.
    private val SUPPORTED_PLATFORMS = setOf("youtube")

    // ID YouTube: kanał = UC + 22 znaki base64url, film = 11 znaków
    private val CHANNEL_ID = Regex("""UC[A-Za-z0-9_-]{22}""")
    private val VIDEO_ID = Regex("""[A-Za-z0-9_-]{11}""")

    data class YouTubeVideo(val channelId: String, val videoId: String)

    /**
     * zwraca zdarzenie YouTube albo null, gdy ładunek jest z nieznanej platformy,
     * zepsuty lub niepełny (→ ignorujemy). nadmiarowe, nieznane klucze pomijamy.
     */
    fun parse(data: Map<String, String?>): YouTubeVideo? {
        if (!isSupportedPlatform(data[KEY_PLATFORM])) return null
        val channelId = data[KEY_CHANNEL]?.trim().orEmpty()
        val videoId = data[KEY_VIDEO]?.trim().orEmpty()
        if (!CHANNEL_ID.matches(channelId) || !VIDEO_ID.matches(videoId)) return null
        return YouTubeVideo(channelId, videoId)
    }

    /**
     * brak/puste pole platformy = zgodność wstecz z serwerem 1.9 (śle samo { c, v }
     * bez p, więc bierzemy jak YouTube). każda inna, nieznana wartość = ignoruj.
     */
    fun isSupportedPlatform(raw: String?): Boolean {
        val platform = raw?.trim()?.lowercase(Locale.ROOT)
        return platform.isNullOrEmpty() || platform in SUPPORTED_PLATFORMS
    }
}
