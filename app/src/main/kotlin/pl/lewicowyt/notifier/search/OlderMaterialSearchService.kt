package pl.lewicowyt.notifier.search

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.lewicowyt.notifier.data.LocalDatabase
import pl.lewicowyt.notifier.diagnostics.DiagnosticCategory
import pl.lewicowyt.notifier.diagnostics.DiagnosticLevel
import pl.lewicowyt.notifier.diagnostics.DiagnosticLogStore
import pl.lewicowyt.notifier.diagnostics.DiagnosticNetworkOperation
import pl.lewicowyt.notifier.diagnostics.DiagnosticNetworkSnapshot
import pl.lewicowyt.notifier.diagnostics.DiagnosticNetworkUsage
import pl.lewicowyt.notifier.diagnostics.DiagnosticReasonCode
import pl.lewicowyt.notifier.diagnostics.DiagnosticSyncIdGenerator
import pl.lewicowyt.notifier.diagnostics.diagnosticYouTubeVideoUrl
import pl.lewicowyt.notifier.model.ConfirmedOlderMaterial
import pl.lewicowyt.notifier.model.Creator
import pl.lewicowyt.notifier.model.OlderMaterialCandidate
import pl.lewicowyt.notifier.model.SourceType
import pl.lewicowyt.notifier.network.HttpTextClient
import pl.lewicowyt.notifier.network.YouTubePageClassifier
import pl.lewicowyt.notifier.network.YouTubeSourceResolver

class OlderMaterialSearchService(
    private val http: HttpTextClient,
    private val resolver: YouTubeSourceResolver,
    private val classifier: YouTubePageClassifier,
    private val database: LocalDatabase,
) {
    suspend fun search(
        creator: Creator,
        rawQuery: String,
        limit: Int = DEFAULT_LIMIT,
    ): List<OlderMaterialCandidate> = withContext(Dispatchers.IO) {
        val operationId = DiagnosticSyncIdGenerator.next()
        val startedAt = System.nanoTime()
        val networkBefore = DiagnosticNetworkUsage.snapshot(
            DiagnosticNetworkOperation.OLDER_SEARCH,
        )
        DiagnosticLogStore.event(
            category = DiagnosticCategory.HISTORY,
            level = DiagnosticLevel.INFO,
            name = "OLDER_SEARCH_START",
            fields = mapOf("operationId" to operationId, "creatorId" to creator.id),
        )
        try {
            val result = DiagnosticNetworkUsage.withOperation(
                DiagnosticNetworkOperation.OLDER_SEARCH,
            ) {
                val query = rawQuery.trim().take(MAX_QUERY_CHARS)
                require(query.length >= MIN_QUERY_CHARS) { "Wpisz co najmniej 2 znaki." }
                val channelIds = resolveChannelIds(creator)
                require(channelIds.isNotEmpty()) {
                    "Twórca nie ma kanału możliwego do przeszukania."
                }
                val found = linkedMapOf<String, OlderMaterialCandidate>()
                val searchEvidence = mutableMapOf<String, String>()
                channelIds.forEach { channelId ->
                    if (found.size >= MAX_RAW_CANDIDATES) return@forEach
                    val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
                    val html = http.getText(
                        "https://www.youtube.com/channel/$channelId/search?query=$encoded",
                        maxChars = MAX_SEARCH_HTML_CHARS,
                    )
                    val initialData = extractOlderMaterialInitialData(html) ?: return@forEach
                    parseOlderMaterialInitialData(
                        rawJson = initialData,
                        candidateLimit = MAX_RAW_CANDIDATES - found.size,
                    ).forEach { candidate ->
                        found.putIfAbsent(
                            candidate.videoId,
                            OlderMaterialCandidate(
                                candidate.videoId,
                                candidate.title,
                                creator.id,
                                creator.name,
                            ),
                        )
                        candidate.searchEvidence?.let { evidence ->
                            searchEvidence[candidate.videoId] = sequenceOf(
                                searchEvidence[candidate.videoId],
                                evidence,
                            ).filterNotNull().joinToString(" ").take(MAX_SEARCH_EVIDENCE_CHARS)
                        }
                    }
                }
                rankOlderMaterialCandidates(
                    query = query,
                    candidates = found.values,
                    evidenceByVideoId = searchEvidence,
                    limit = limit,
                )
            }
            val network = DiagnosticNetworkUsage.snapshot(
                DiagnosticNetworkOperation.OLDER_SEARCH,
            ).deltaSince(networkBefore)
            DiagnosticLogStore.event(
                category = DiagnosticCategory.HISTORY,
                level = DiagnosticLevel.INFO,
                name = "OLDER_SEARCH_SUCCESS",
                fields = olderSearchFields(
                    operationId,
                    creator.id,
                    result.size,
                    startedAt,
                    network,
                ),
            )
            result.forEach { candidate ->
                DiagnosticLogStore.event(
                    category = DiagnosticCategory.HISTORY,
                    level = DiagnosticLevel.INFO,
                    name = "OLDER_SEARCH_RESULT",
                    fields = mapOf(
                        "operationId" to operationId,
                        "creatorId" to creator.id,
                        "video" to diagnosticYouTubeVideoUrl(candidate.videoId),
                    ),
                )
            }
            result
        } catch (cancelled: CancellationException) {
            val network = DiagnosticNetworkUsage.snapshot(
                DiagnosticNetworkOperation.OLDER_SEARCH,
            ).deltaSince(networkBefore)
            DiagnosticLogStore.event(
                category = DiagnosticCategory.HISTORY,
                level = DiagnosticLevel.INFO,
                name = "OLDER_SEARCH_CANCELLED",
                fields = olderSearchFields(
                    operationId,
                    creator.id,
                    resultCount = 0,
                    startedAt = startedAt,
                    network = network,
                ),
            )
            throw cancelled
        } catch (error: Exception) {
            val network = DiagnosticNetworkUsage.snapshot(
                DiagnosticNetworkOperation.OLDER_SEARCH,
            ).deltaSince(networkBefore)
            DiagnosticLogStore.event(
                category = DiagnosticCategory.HISTORY,
                level = DiagnosticLevel.WARNING,
                name = "OLDER_SEARCH_FAILED",
                reason = DiagnosticReasonCode.OLDER_SEARCH_FAILED,
                fields = olderSearchFields(
                    operationId,
                    creator.id,
                    resultCount = 0,
                    startedAt = startedAt,
                    network = network,
                ) + ("errorType" to error.javaClass.simpleName),
            )
            throw error
        }
    }

    suspend fun confirm(
        creator: Creator,
        candidate: OlderMaterialCandidate,
    ): ConfirmedOlderMaterial = withContext(Dispatchers.IO) {
        val operationId = DiagnosticSyncIdGenerator.next()
        val startedAt = System.nanoTime()
        val networkBefore = DiagnosticNetworkUsage.snapshot(
            DiagnosticNetworkOperation.OLDER_CONFIRMATION,
        )
        try {
            val confirmed = DiagnosticNetworkUsage.withOperation(
                DiagnosticNetworkOperation.OLDER_CONFIRMATION,
            ) {
                require(candidate.creatorId == creator.id) {
                    "Wynik nie należy do wybranego twórcy."
                }
                val allowedChannelIds = resolveChannelIds(creator)
                val metadata = classifier.inspect(candidate.videoId)
                    ?: throw OlderMaterialUnavailableException()
                if (metadata.channelId !in allowedChannelIds) {
                    throw OlderMaterialChannelMismatchException()
                }
                ConfirmedOlderMaterial(
                    videoId = metadata.videoId,
                    title = metadata.title,
                    creatorId = creator.id,
                    creatorName = creator.name,
                    publishedAtMillis = metadata.publishedAtMillis,
                    kind = metadata.kind,
                    description = metadata.description,
                )
            }
            val network = DiagnosticNetworkUsage.snapshot(
                DiagnosticNetworkOperation.OLDER_CONFIRMATION,
            ).deltaSince(networkBefore)
            DiagnosticLogStore.event(
                category = DiagnosticCategory.HISTORY,
                level = DiagnosticLevel.INFO,
                name = "OLDER_CONFIRM_SUCCESS",
                fields = confirmationFields(
                    operationId,
                    creator.id,
                    candidate.videoId,
                    startedAt,
                    network,
                ),
            )
            confirmed
        } catch (cancelled: CancellationException) {
            val network = DiagnosticNetworkUsage.snapshot(
                DiagnosticNetworkOperation.OLDER_CONFIRMATION,
            ).deltaSince(networkBefore)
            DiagnosticLogStore.event(
                category = DiagnosticCategory.HISTORY,
                level = DiagnosticLevel.INFO,
                name = "OLDER_CONFIRM_CANCELLED",
                fields = confirmationFields(
                    operationId,
                    creator.id,
                    candidate.videoId,
                    startedAt,
                    network,
                ),
            )
            throw cancelled
        } catch (error: Exception) {
            val reason = when (error) {
                is OlderMaterialChannelMismatchException ->
                    DiagnosticReasonCode.OLDER_MATERIAL_CHANNEL_MISMATCH
                is OlderMaterialUnavailableException ->
                    DiagnosticReasonCode.OLDER_MATERIAL_UNAVAILABLE
                else -> DiagnosticReasonCode.OLDER_SEARCH_FAILED
            }
            val network = DiagnosticNetworkUsage.snapshot(
                DiagnosticNetworkOperation.OLDER_CONFIRMATION,
            ).deltaSince(networkBefore)
            DiagnosticLogStore.event(
                category = DiagnosticCategory.HISTORY,
                level = DiagnosticLevel.WARNING,
                name = "OLDER_CONFIRM_FAILED",
                reason = reason,
                fields = confirmationFields(
                    operationId,
                    creator.id,
                    candidate.videoId,
                    startedAt,
                    network,
                ) + ("errorType" to error.javaClass.simpleName),
            )
            when (error) {
                is OlderMaterialChannelMismatchException ->
                    throw IllegalStateException("YouTube przypisał materiał do innego kanału.")
                is OlderMaterialUnavailableException ->
                    throw IllegalStateException("YouTube nie potwierdził dostępności materiału.")
                else -> throw error
            }
        }
    }

    suspend fun addConfirmedFavorite(material: ConfirmedOlderMaterial): Boolean =
        withContext(Dispatchers.IO) {
            val saved = database.insertConfirmedFavorite(material)
            DiagnosticLogStore.event(
                category = DiagnosticCategory.HISTORY,
                level = if (saved) DiagnosticLevel.INFO else DiagnosticLevel.WARNING,
                name = if (saved) "OLDER_FAVORITE_SAVED" else "OLDER_FAVORITE_FAILED",
                reason = if (saved) null else DiagnosticReasonCode.OLDER_FAVORITE_SAVE_FAILED,
                fields = mapOf(
                    "creatorId" to material.creatorId,
                    "video" to diagnosticYouTubeVideoUrl(material.videoId),
                    "result" to if (saved) "SAVED" else "FAILED",
                ),
            )
            saved
        }

    private fun olderSearchFields(
        operationId: String,
        creatorId: String,
        resultCount: Int,
        startedAt: Long,
        network: DiagnosticNetworkSnapshot,
    ): Map<String, Any?> = mapOf(
        "operationId" to operationId,
        "creatorId" to creatorId,
        "resultCount" to resultCount,
        "durationMs" to ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0L),
        "uploadedHttpBodyBytes" to network.uploadedBytes,
        "downloadedHttpBodyBytes" to network.downloadedBytes,
        "totalHttpBodyBytes" to network.totalBytes,
        "scope" to "HTTP_BODY_ONLY",
    )

    private fun confirmationFields(
        operationId: String,
        creatorId: String,
        videoId: String,
        startedAt: Long,
        network: DiagnosticNetworkSnapshot,
    ): Map<String, Any?> = mapOf(
        "operationId" to operationId,
        "creatorId" to creatorId,
        "video" to diagnosticYouTubeVideoUrl(videoId),
        "durationMs" to ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0L),
        "uploadedHttpBodyBytes" to network.uploadedBytes,
        "downloadedHttpBodyBytes" to network.downloadedBytes,
        "totalHttpBodyBytes" to network.totalBytes,
        "scope" to "HTTP_BODY_ONLY",
    )

    private fun resolveChannelIds(creator: Creator): Set<String> = creator.sources
        .asSequence()
        .filter { it.type == SourceType.CHANNEL }
        .mapNotNull { source -> runCatching { resolver.resolve(creator, source).externalId }.getOrNull() }
        .filter(CHANNEL_ID::matches)
        .toSet()

    private companion object {
        const val DEFAULT_LIMIT = 20
        const val MIN_QUERY_CHARS = 2
        const val MAX_QUERY_CHARS = 100
        const val MAX_SEARCH_HTML_CHARS = 4_000_000
        const val MAX_SEARCH_EVIDENCE_CHARS = 4_000
        const val MAX_RAW_CANDIDATES = MAX_OLDER_SEARCH_CANDIDATES
        val CHANNEL_ID = Regex("UC[A-Za-z0-9_-]{22}")
    }
}

internal fun rankOlderMaterialCandidates(
    query: String,
    candidates: Collection<OlderMaterialCandidate>,
    evidenceByVideoId: Map<String, String> = emptyMap(),
    limit: Int,
): List<OlderMaterialCandidate> {
    val normalizedQuery = normalizeOlderSearchText(query)
    val allQueryTokens = olderSearchTokens(normalizedQuery)
    if (allQueryTokens.isEmpty()) return emptyList()
    val meaningfulTokens = allQueryTokens.filter { token ->
        token.length >= MIN_MEANINGFUL_TOKEN_LENGTH && token !in SEARCH_STOP_WORDS
    }.ifEmpty { allQueryTokens }
    val requiredMatches = when (meaningfulTokens.size) {
        1 -> 1
        2 -> 2
        else -> (meaningfulTokens.size * 2 + 2) / 3
    }
    return candidates.withIndex()
        .mapNotNull { indexed ->
            val normalizedTitle = normalizeOlderSearchText(indexed.value.title)
            val normalizedEvidence = normalizeOlderSearchText(
                evidenceByVideoId[indexed.value.videoId].orEmpty(),
            )
            val titleTokens = olderSearchTokens(normalizedTitle)
            val evidenceTokens = olderSearchTokens(normalizedEvidence)
            val titlePhraseMatch = normalizedTitle.contains(normalizedQuery)
            val evidencePhraseMatch = normalizedEvidence.contains(normalizedQuery)
            var titleTokenMatches = 0
            var evidenceTokenMatches = 0
            meaningfulTokens.forEach { queryToken ->
                when {
                    titleTokens.any { titleToken ->
                        olderSearchTokenMatches(queryToken, titleToken)
                    } -> titleTokenMatches += 1
                    evidenceTokens.any { evidenceToken ->
                        olderSearchTokenMatches(queryToken, evidenceToken)
                    } -> evidenceTokenMatches += 1
                }
            }
            val matchedTokens = titleTokenMatches + evidenceTokenMatches
            if (!titlePhraseMatch && !evidencePhraseMatch && matchedTokens < requiredMatches) {
                return@mapNotNull null
            }
            val score =
                (if (titlePhraseMatch) TITLE_PHRASE_SCORE else 0) +
                    (if (evidencePhraseMatch) EVIDENCE_PHRASE_SCORE else 0) +
                    titleTokenMatches * TITLE_TOKEN_SCORE +
                    evidenceTokenMatches * EVIDENCE_TOKEN_SCORE +
                    meaningfulTokens.sumOf { token ->
                        if (normalizedTitle.startsWith(token)) PREFIX_SCORE else 0
                    }
            Triple(indexed.value, score, indexed.index)
        }
        .sortedWith(compareByDescending<Triple<OlderMaterialCandidate, Int, Int>> { it.second }
            .thenBy { it.third })
        .asSequence()
        .map { it.first }
        .distinctBy(OlderMaterialCandidate::videoId)
        .take(limit.coerceIn(1, MAX_RANKED_RESULTS))
        .toList()
}

private fun normalizeOlderSearchText(value: String): String = Normalizer
    .normalize(value.replace('Ł', 'L').replace('ł', 'l'), Normalizer.Form.NFD)
    .replace(COMBINING_MARKS, "")
    .lowercase(Locale.ROOT)
    .replace(NON_SEARCH_CHARS, " ")
    .replace(MULTIPLE_SPACES, " ")
    .trim()

private fun olderSearchTokens(value: String): List<String> = value
    .split(' ')
    .filter(String::isNotBlank)
    .distinct()

private fun olderSearchTokenMatches(queryToken: String, titleToken: String): Boolean {
    if (queryToken == titleToken) return true
    if (queryToken.length < STEM_PREFIX_LENGTH || titleToken.length < STEM_PREFIX_LENGTH) {
        return false
    }
    return queryToken.take(STEM_PREFIX_LENGTH) == titleToken.take(STEM_PREFIX_LENGTH)
}

private const val MIN_MEANINGFUL_TOKEN_LENGTH = 3
private const val STEM_PREFIX_LENGTH = 5
private const val TITLE_PHRASE_SCORE = 12_000
private const val EVIDENCE_PHRASE_SCORE = 9_000
private const val TITLE_TOKEN_SCORE = 1_000
private const val EVIDENCE_TOKEN_SCORE = 600
private const val PREFIX_SCORE = 25
private const val MAX_RANKED_RESULTS = 100
private val COMBINING_MARKS = Regex("\\p{M}+")
private val NON_SEARCH_CHARS = Regex("[^\\p{L}\\p{N}]+")
private val MULTIPLE_SPACES = Regex("\\s+")
private val SEARCH_STOP_WORDS = setOf(
    "a", "aby", "ale", "bo", "czy", "do", "i", "jest", "na", "nie", "o", "od", "oraz",
    "po", "sie", "to", "w", "we", "z", "za", "ze",
)

private class OlderMaterialUnavailableException : Exception()
private class OlderMaterialChannelMismatchException : Exception()
