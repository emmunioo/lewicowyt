package pl.lewicowyt.notifier.model

import androidx.compose.runtime.Immutable

enum class SourceType {
    CHANNEL,
    PLAYLIST,
}

@Immutable
data class CreatorSource(
    val type: SourceType,
    val url: String,
    val externalId: String?,
)

// @Immutable: przez listę `sources` Compose domyślnie brałby Creator za
// niestabilny (a używamy go w items() listy twórców). budujemy go raz z katalogu
// i nigdy nie mutujemy, więc adnotacja jest zgodna z prawdą i wraca pomijalność wiersza (#3)
@Immutable
data class Creator(
    val id: String,
    val name: String,
    val sources: List<CreatorSource>,
    val profileChannelId: String? = null,
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
 * jakość dowodu, na podstawie którego rozpoznajemy rodzaj materiału.
 *
 * kolejność celowa: brak danych nie może nadpisać rozpoznanego typu, a pewna
 * karta kanału bije heurystykę playera.
 */
enum class VideoKindEvidence(val rank: Int, val isFinal: Boolean) {
    NONE(rank = 0, isFinal = false),
    // RSS nie podaje rodzaju – zwykły film to natychmiastowy, odwracalny fallback;
    // każdy realny dowód go bije
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
 * łączy dwa wyniki niezależnie od kolejności odpowiedzi z sieci.
 *
 * aktywny LIVE/UPCOMING z API wygrywa w trakcie emisji. po jej końcu pewna karta
 * kanału może zmienić to na film albo archiwum streamu. konflikt równorzędnych,
 * ale różnych dowodów zostawia wcześniejszy wynik, zamiast wiązać typ z kolejnością
 * równoległych żądań.
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
 * wiarygodność czasu publikacji. względna data z kafelka YouTube Web nie zastąpi
 * dokładnego znacznika z RSS lub Data API.
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

enum class DescriptionAvailability {
    NONE,
    DOWNLOADED,
    MEMBERS_ONLY,
    SCHEDULED_STREAM,
}

internal const val MEMBERS_ONLY_DESCRIPTION_MARKER = "Tylko dla wspierających"
internal const val SCHEDULED_STREAM_DESCRIPTION_MARKER = "Zaplanowana transmisja"

data class VideoEntry(
    val id: String,
    val title: String,
    val url: String,
    val publishedAtMillis: Long,
    val author: String,
    val origin: VideoOrigin = VideoOrigin.YOUTUBE,
)

@Immutable
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
    val isFavorite: Boolean = false,
    val descriptionSnippet: String? = null,
    val descriptionAvailability: DescriptionAvailability = DescriptionAvailability.NONE,
)

enum class MaterialStatusBadge {
    SCHEDULED,
    DESCRIPTION,
    MEMBERS_ONLY,
    PRIVATE,
}

/**
 * UPCOMING to stan chwilowy. po zapisanym terminie nie pokazujemy materiału jako
 * wciąż zaplanowanego, nawet jeśli kolejny sync nie zdążył podmienić starej
 * klasyfikacji YouTube.
 */
fun HistoryItem.displayKindAt(nowMillis: Long): VideoKind =
    if (kind == VideoKind.UPCOMING && publishedAtMillis <= nowMillis) {
        VideoKind.STREAM_ARCHIVE
    } else {
        kind
    }

/** stan transmisji i dostępność opisu są niezależne, więc karta może mieć dwie ikony */
fun HistoryItem.statusBadgesAt(nowMillis: Long): List<MaterialStatusBadge> = buildList {
    val isStillScheduled = publishedAtMillis > nowMillis &&
        (kind == VideoKind.UPCOMING || descriptionAvailability == DescriptionAvailability.SCHEDULED_STREAM)
    if (isStillScheduled) add(MaterialStatusBadge.SCHEDULED)
    when (descriptionAvailability) {
        DescriptionAvailability.DOWNLOADED -> add(MaterialStatusBadge.DESCRIPTION)
        DescriptionAvailability.MEMBERS_ONLY -> add(MaterialStatusBadge.MEMBERS_ONLY)
        DescriptionAvailability.NONE,
        DescriptionAvailability.SCHEDULED_STREAM -> Unit
    }
}

@Immutable
data class OlderMaterialCandidate(
    val videoId: String,
    val title: String,
    val creatorId: String,
    val creatorName: String,
)

data class ConfirmedOlderMaterial(
    val videoId: String,
    val title: String,
    val creatorId: String,
    val creatorName: String,
    val publishedAtMillis: Long,
    val kind: VideoKind,
    val description: String?,
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
