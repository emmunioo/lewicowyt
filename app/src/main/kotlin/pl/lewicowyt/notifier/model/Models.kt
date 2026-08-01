package pl.lewicowyt.notifier.model

enum class SourceType {
    CHANNEL,
    PLAYLIST,
}

data class CreatorSource(
    val type: SourceType,
    val url: String,
    val externalId: String?,
)

data class Creator(
    val id: String,
    val name: String,
    val sources: List<CreatorSource>,
)

enum class VideoKind {
    VIDEO,
    SHORT,
    LIVE,
    UPCOMING,
    STREAM_ARCHIVE,
    UNKNOWN,
}

/**
 * Jakość dowodu użytego do rozpoznania rodzaju materiału.
 *
 * Kolejność jest celowa: brak danych nie może nadpisać rozpoznanego typu,
 * a potwierdzona karta kanału ma pierwszeństwo przed heurystyką odtwarzacza.
 */
enum class VideoKindEvidence(val rank: Int, val isFinal: Boolean) {
    NONE(rank = 0, isFinal = false),
    // RSS nie podaje rodzaju materiału. Zwykły film jest natychmiastowym,
    // odwracalnym fallbackiem; każdy rzeczywisty dowód ma wyższy priorytet.
    DEFAULT_VIDEO_FALLBACK(rank = 1, isFinal = false),
    PLAYER_METADATA(rank = 10, isFinal = false),
    API_METADATA(rank = 20, isFinal = true),
    RSS_SHORT_URL(rank = 30, isFinal = true),
    CHANNEL_TAB(rank = 40, isFinal = true),
    API_CURRENT_STATE(rank = 50, isFinal = true),
}

data class VideoKindDecision(
    val kind: VideoKind,
    val evidence: VideoKindEvidence,
) {
    companion object {
        val Unknown = VideoKindDecision(
            kind = VideoKind.UNKNOWN,
            evidence = VideoKindEvidence.NONE,
        )
    }
}

/**
 * Łączy dwa wyniki niezależnie od kolejności odpowiedzi sieciowych.
 *
 * Aktywny LIVE/UPCOMING z API wygrywa w trakcie emisji. Po jej zakończeniu
 * potwierdzona karta kanału może zmienić ten stan w film albo archiwum streamu.
 * Konflikt równorzędnych, ale różnych dowodów pozostawia wcześniejszy wynik,
 * zamiast uzależniać typ od kolejności równoległych żądań.
 */
fun chooseVideoKindDecision(
    current: VideoKindDecision,
    incoming: VideoKindDecision,
): VideoKindDecision {
    if (incoming.kind == VideoKind.UNKNOWN || incoming.evidence == VideoKindEvidence.NONE) {
        return current
    }
    if (current.kind == VideoKind.UNKNOWN || current.evidence == VideoKindEvidence.NONE) {
        return incoming
    }
    if (
        current.kind in ACTIVE_STREAM_KINDS &&
        incoming.kind !in ACTIVE_STREAM_KINDS &&
        incoming.evidence == VideoKindEvidence.CHANNEL_TAB
    ) {
        return incoming
    }
    if (
        incoming.kind in ACTIVE_STREAM_KINDS &&
        incoming.evidence == VideoKindEvidence.API_CURRENT_STATE
    ) {
        return incoming
    }
    return when {
        incoming.evidence.rank > current.evidence.rank -> incoming
        incoming.evidence.rank < current.evidence.rank -> current
        incoming.kind == current.kind -> incoming
        else -> current
    }
}

private val ACTIVE_STREAM_KINDS = setOf(
    VideoKind.LIVE,
    VideoKind.UPCOMING,
)

enum class VideoOrigin {
    YOUTUBE,
}

/**
 * Wiarygodność czasu publikacji. Data względna z kafelka YouTube Web nie może
 * zastąpić dokładnego znacznika RSS lub Data API.
 */
enum class PublishedAtEvidence(
    val rank: Int,
    val canRefreshAtSameRank: Boolean,
) {
    UNKNOWN(rank = 0, canRefreshAtSameRank = false),
    PLAYLIST_ITEM(rank = 5, canRefreshAtSameRank = false),
    WEB_RELATIVE(rank = 10, canRefreshAtSameRank = false),
    WEB_DATE(rank = 20, canRefreshAtSameRank = false),
    WEB_TIMESTAMP(rank = 30, canRefreshAtSameRank = true),
    RSS(rank = 40, canRefreshAtSameRank = true),
    DATA_API(rank = 40, canRefreshAtSameRank = true),
}

data class PublishedAtDecision(
    val millis: Long,
    val evidence: PublishedAtEvidence,
)

fun choosePublishedAtDecision(
    current: PublishedAtDecision,
    incoming: PublishedAtDecision,
): PublishedAtDecision = when {
    incoming.evidence.rank > current.evidence.rank -> incoming
    incoming.evidence.rank < current.evidence.rank -> current
    incoming.evidence.canRefreshAtSameRank -> incoming
    else -> current
}

enum class HistoryFilter {
    VIDEOS,
    STREAMS,
    SHORTS,
}

data class VideoEntry(
    val id: String,
    val title: String,
    val url: String,
    val publishedAtMillis: Long,
    val author: String,
    val origin: VideoOrigin = VideoOrigin.YOUTUBE,
)

data class HistoryItem(
    val videoId: String,
    val creatorId: String,
    val creatorName: String,
    val title: String,
    val url: String,
    val publishedAtMillis: Long,
    val detectedAtMillis: Long,
    val kind: VideoKind,
    val notified: Boolean,
    val origin: VideoOrigin = VideoOrigin.YOUTUBE,
)

data class SyncOutcome(
    val checkedSources: Int,
    val detectedItems: Int,
    val notificationsSent: Int,
    val errors: List<String>,
) {
    fun toPolishSummary(): String = buildString {
        append("Sprawdzono źródeł: ")
        append(checkedSources)
        append(" · nowe: ")
        append(detectedItems)
        append(" · powiadomienia: ")
        append(notificationsSent)
        if (errors.isNotEmpty()) {
            append(" · błędy: ")
            append(errors.size)
        }
    }
}
