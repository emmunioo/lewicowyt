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

enum class VideoOrigin {
    YOUTUBE,
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
