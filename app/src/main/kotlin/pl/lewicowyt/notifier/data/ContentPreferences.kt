package pl.lewicowyt.notifier.data

import pl.lewicowyt.notifier.model.HistoryFilter
import pl.lewicowyt.notifier.model.VideoKind

internal val ALL_CONTENT_TYPES: Set<HistoryFilter> = HistoryFilter.entries.toSet()

fun AppSettings.historyTypesFor(creatorId: String): Set<HistoryFilter> =
    globalHistoryTypes - creatorHistoryDisabledTypes[creatorId].orEmpty()

fun AppSettings.notificationTypesFor(creatorId: String): Set<HistoryFilter> =
    historyTypesFor(creatorId)
        .intersect(globalNotificationTypes)
        .minus(creatorNotificationDisabledTypes[creatorId].orEmpty())

fun AppSettings.isHistoryEnabledFor(creatorId: String, type: HistoryFilter): Boolean =
    type in historyTypesFor(creatorId)

fun AppSettings.isNotificationEnabledFor(creatorId: String, type: HistoryFilter): Boolean =
    type in notificationTypesFor(creatorId)

fun AppSettings.isHistoryEnabledFor(creatorId: String, kind: VideoKind): Boolean =
    kind.contentType()?.let { isHistoryEnabledFor(creatorId, it) } == true

fun AppSettings.isNotificationEnabledFor(creatorId: String, kind: VideoKind): Boolean =
    kind.contentType()?.let { isNotificationEnabledFor(creatorId, it) } == true

fun AppSettings.hasEnabledContentForSelectedCreators(): Boolean =
    selectedCreatorIds.any { creatorId -> historyTypesFor(creatorId).isNotEmpty() }

fun VideoKind.contentType(): HistoryFilter? = when (this) {
    VideoKind.VIDEO -> HistoryFilter.VIDEOS
    VideoKind.SHORT -> HistoryFilter.SHORTS
    VideoKind.LIVE,
    VideoKind.UPCOMING,
    VideoKind.STREAM_ARCHIVE -> HistoryFilter.STREAMS
    VideoKind.UNKNOWN -> null
}

internal fun decodeCreatorContentTypes(values: Set<String>): Map<String, Set<HistoryFilter>> =
    values.mapNotNull { encoded ->
        val separator = encoded.lastIndexOf('|')
        if (separator <= 0) return@mapNotNull null
        val creatorId = encoded.substring(0, separator).takeIf(String::isNotBlank)
            ?: return@mapNotNull null
        val type = runCatching {
            HistoryFilter.valueOf(encoded.substring(separator + 1))
        }.getOrNull() ?: return@mapNotNull null
        creatorId to type
    }.groupBy({ it.first }, { it.second })
        .mapValues { (_, types) -> types.toSet() }

internal fun encodeCreatorContentTypes(
    values: Map<String, Set<HistoryFilter>>,
): Set<String> = values.flatMapTo(mutableSetOf()) { (creatorId, types) ->
    if (creatorId.isBlank() || '|' in creatorId) {
        emptyList()
    } else {
        types.map { type -> "$creatorId|${type.name}" }
    }
}

internal fun contentSettingsSignature(settings: AppSettings): String = buildString {
    append(settings.globalHistoryTypes.map(HistoryFilter::name).sorted().joinToString(","))
    append('|')
    append(settings.globalNotificationTypes.map(HistoryFilter::name).sorted().joinToString(","))
    append('|')
    append(encodeCreatorContentTypes(settings.creatorHistoryDisabledTypes).sorted().joinToString(","))
    append('|')
    append(
        encodeCreatorContentTypes(settings.creatorNotificationDisabledTypes)
            .sorted()
            .joinToString(","),
    )
}
