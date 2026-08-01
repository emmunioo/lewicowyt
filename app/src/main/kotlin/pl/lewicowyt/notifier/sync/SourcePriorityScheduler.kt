package pl.lewicowyt.notifier.sync

import android.content.Context
import java.util.concurrent.TimeUnit
import kotlin.math.exp
import kotlin.math.ln1p
import kotlin.math.pow
import kotlin.math.roundToLong
import org.json.JSONObject
import pl.lewicowyt.notifier.AppLog
import pl.lewicowyt.notifier.data.LocalDatabase
import pl.lewicowyt.notifier.data.SourcePrioritySeed
import pl.lewicowyt.notifier.data.SourcePriorityStats
import pl.lewicowyt.notifier.data.SourcePriorityUpdate
import pl.lewicowyt.notifier.model.Creator
import pl.lewicowyt.notifier.model.CreatorSource

data class SourcePriorityCandidate(
    val sourceKey: String,
    val creatorId: String,
    val stableSeedKey: String,
)

data class SourcePriorityObservation(
    val candidate: SourcePriorityCandidate,
    val successful: Boolean,
    val learnFromResult: Boolean,
    val previousSuccessfulCheckMillis: Long,
    val detectedVideoIds: Set<String> = emptySet(),
)

internal data class SourcePrioritySeedCatalog(
    val globalRatePerDay: Double,
    val sources: Map<String, SourcePrioritySeedValue>,
)

internal data class SourcePrioritySeedValue(
    val priorRatePerDay: Double,
    val priorExposureDays: Double,
)

internal data class SourcePriorityRank(
    val tier: Int,
    val scoreMicros: Long,
    val lastAttemptMillis: Long,
)

class SourcePriorityScheduler(
    context: Context,
    private val database: LocalDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val appContext = context.applicationContext
    private val seedCatalog: SourcePrioritySeedCatalog by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        loadSeedCatalog(appContext)
    }

    fun candidate(creator: Creator, source: CreatorSource): SourcePriorityCandidate =
        SourcePriorityCandidate(
            sourceKey = sourceKey(creator, source),
            creatorId = creator.id,
            stableSeedKey = stableSeedKey(source),
        )

    /**
     * Najpierw porządkuje twórców, a potem rozkłada ich dodatkowe źródła
     * rundami. Jeden twórca nie zajmie dzięki temu kilku pierwszych slotów.
     */
    fun prioritizeSources(
        creators: List<Creator>,
        intervalMinutes: Int,
    ): List<Pair<Creator, CreatorSource>> {
        if (creators.isEmpty()) return emptyList()
        val candidates = creators.flatMap { creator ->
            creator.sources.map { source -> candidate(creator, source) }
        }
        val ranks = loadRanks(candidates, intervalMinutes)
        val orderedCreators = creators
            .withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<Creator>> { indexed ->
                    val sourceRanks = indexed.value.sources.map { source ->
                        ranks.getValue(sourceKey(indexed.value, source))
                    }
                    sourceRanks.maxOfOrNull(SourcePriorityRank::tier) ?: 0
                }.thenByDescending { indexed ->
                    indexed.value.sources.sumOf { source ->
                        ranks.getValue(sourceKey(indexed.value, source)).scoreMicros
                    }
                }.thenBy { indexed ->
                    indexed.value.sources.minOfOrNull { source ->
                        ranks.getValue(sourceKey(indexed.value, source)).lastAttemptMillis
                    } ?: 0L
                }.thenBy { indexed -> indexed.value.id }
                    .thenBy(IndexedValue<Creator>::index),
            )
            .map(IndexedValue<Creator>::value)

        val orderedPerCreator = orderedCreators.associateWith { creator ->
            creator.sources.sortedWith(
                compareByDescending<CreatorSource> { source ->
                    ranks.getValue(sourceKey(creator, source)).tier
                }.thenByDescending { source ->
                    ranks.getValue(sourceKey(creator, source)).scoreMicros
                }.thenBy { source ->
                    ranks.getValue(sourceKey(creator, source)).lastAttemptMillis
                }.thenBy(::stableSeedKey),
            )
        }
        return roundRobinSources(orderedCreators, orderedPerCreator)
    }

    /**
     * Historia tylko korzysta z modelu. Nie uczy go na starszych stronach,
     * bo wielokrotne przewijanie sztucznie zwiększałoby aktywność twórcy.
     */
    fun <T> prioritizeSourceGroups(
        groups: List<T>,
        intervalMinutes: Int,
        creator: (T) -> Creator,
        source: (T) -> CreatorSource,
    ): List<T> {
        if (groups.size < 2) return groups
        val candidates = groups.map { candidate(creator(it), source(it)) }
        val ranks = loadRanks(candidates, intervalMinutes)
        val ranked = groups.mapIndexed { index, item ->
            val current = candidate(creator(item), source(item))
            RankedSourceGroup(
                item = item,
                creatorId = current.creatorId,
                rank = ranks.getValue(current.sourceKey),
                originalIndex = index,
            )
        }
        val buckets = ranked.groupBy(RankedSourceGroup<T>::creatorId)
            .mapValues { (_, values) ->
                values.sortedWith(
                    compareByDescending<RankedSourceGroup<T>> { it.rank.tier }
                        .thenByDescending { it.rank.scoreMicros }
                        .thenBy { it.rank.lastAttemptMillis }
                        .thenBy(RankedSourceGroup<T>::originalIndex),
                )
            }
        val orderedCreatorIds = buckets.keys.sortedWith(
            compareByDescending<String> { creatorId ->
                buckets.getValue(creatorId).maxOf { it.rank.tier }
            }.thenByDescending { creatorId ->
                buckets.getValue(creatorId).sumOf { it.rank.scoreMicros }
            }.thenBy { creatorId ->
                buckets.getValue(creatorId).minOf { it.rank.lastAttemptMillis }
            }.thenBy { it },
        )
        val maxSources = buckets.values.maxOfOrNull(List<*>::size) ?: 0
        return buildList {
            repeat(maxSources) { sourceIndex ->
                orderedCreatorIds.forEach { creatorId ->
                    buckets.getValue(creatorId).getOrNull(sourceIndex)?.let {
                        add(it.item)
                    }
                }
            }
        }
    }

    /**
     * Aktualizacja jest wykonywana po zwykłej synchronizacji, bez osobnego
     * alarmu i bez dodatkowego ruchu sieciowego. Powtarzające się videoId są
     * przypisywane tylko pierwszemu źródłu w stabilnej kolejności.
     */
    fun recordOutcomes(observations: List<SourcePriorityObservation>) {
        if (observations.isEmpty()) return
        val now = clock()
        val ordered = observations.sortedBy { it.candidate.sourceKey }
        val current = loadStats(ordered.map(SourcePriorityObservation::candidate))
        val claimedVideoIds = hashSetOf<String>()
        val updates = ordered.mapNotNull { observation ->
            val stats = current[observation.candidate.sourceKey] ?: return@mapNotNull null
            val uniqueEvents = observation.detectedVideoIds.count(claimedVideoIds::add)
            if (
                !shouldPersistSourcePriority(
                    stats = stats,
                    successful = observation.successful,
                    learnFromResult = observation.learnFromResult,
                    detectedItems = uniqueEvents,
                    nowMillis = now,
                )
            ) {
                return@mapNotNull null
            }
            val learningSinceMillis = listOf(
                stats.lastModelUpdateMillis,
                observation.previousSuccessfulCheckMillis,
            ).filter { it > 0L }.minOrNull() ?: 0L
            updateSourcePriorityModel(
                stats = stats,
                successful = observation.successful,
                learnFromResult = observation.learnFromResult,
                previousSuccessfulCheckMillis = learningSinceMillis,
                detectedItems = uniqueEvents,
                nowMillis = now,
            )
        }
        database.updateSourcePriorities(updates)
    }

    private fun loadRanks(
        candidates: List<SourcePriorityCandidate>,
        intervalMinutes: Int,
    ): Map<String, SourcePriorityRank> {
        val stats = loadStats(candidates)
        val now = clock()
        return candidates.associate { current ->
            current.sourceKey to sourcePriorityRank(
                stats = stats.getValue(current.sourceKey),
                nowMillis = now,
                intervalMinutes = intervalMinutes,
            )
        }
    }

    private fun loadStats(
        candidates: List<SourcePriorityCandidate>,
    ): Map<String, SourcePriorityStats> {
        val uniqueCandidates = candidates.distinctBy(SourcePriorityCandidate::sourceKey)
        val sourceKeys = uniqueCandidates.map(SourcePriorityCandidate::sourceKey)
        var stats = database.sourcePriorityStats(sourceKeys)
        val missing = uniqueCandidates.filterNot { it.sourceKey in stats }
        if (missing.isNotEmpty()) {
            database.ensureSourcePrioritySeeds(
                missing.map { current ->
                    val prior = seedCatalog.sources[current.stableSeedKey]
                    SourcePrioritySeed(
                        sourceKey = current.sourceKey,
                        priorRatePerDay = prior?.priorRatePerDay
                            ?: seedCatalog.globalRatePerDay,
                        priorExposureDays = prior?.priorExposureDays
                            ?: DEFAULT_PRIOR_EXPOSURE_DAYS,
                    )
                },
            )
            stats = database.sourcePriorityStats(sourceKeys)
        }
        return stats
    }

    private fun sourceKey(creator: Creator, source: CreatorSource): String =
        "${creator.id}|${source.type.name}|${source.url}"
}

internal fun shouldPersistSourcePriority(
    stats: SourcePriorityStats,
    successful: Boolean,
    learnFromResult: Boolean,
    detectedItems: Int,
    nowMillis: Long,
): Boolean =
    !successful ||
        stats.consecutiveFailures > 0 ||
        detectedItems > 0 ||
        (
            learnFromResult &&
                (
                    stats.lastModelUpdateMillis <= 0L ||
                        nowMillis - stats.lastModelUpdateMillis >=
                        MIN_MODEL_WRITE_INTERVAL_MILLIS
                    )
            )

private data class RankedSourceGroup<T>(
    val item: T,
    val creatorId: String,
    val rank: SourcePriorityRank,
    val originalIndex: Int,
)

internal fun sourcePriorityRank(
    stats: SourcePriorityStats,
    nowMillis: Long,
    intervalMinutes: Int,
): SourcePriorityRank {
    val safeNow = nowMillis.coerceAtLeast(0L)
    val rate = effectiveSourceRate(stats, safeNow)
    val intervalMillis = TimeUnit.MINUTES.toMillis(
        intervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES).toLong(),
    )
    val ageMillis = when {
        stats.lastSuccessfulCheckMillis <= 0L &&
            stats.consecutiveFailures > 0 &&
            stats.lastAttemptMillis > 0L -> when {
                safeNow <= stats.lastAttemptMillis -> intervalMillis
                else -> (safeNow - stats.lastAttemptMillis)
                    .coerceAtMost(MAX_SUCCESS_AGE_MILLIS)
            }
        stats.lastSuccessfulCheckMillis <= 0L -> MAX_SUCCESS_AGE_MILLIS
        safeNow <= stats.lastSuccessfulCheckMillis -> intervalMillis
        else -> (safeNow - stats.lastSuccessfulCheckMillis)
            .coerceAtMost(MAX_SUCCESS_AGE_MILLIS)
    }
    val ageDays = ageMillis.toDouble() / DAY_MILLIS
    val probabilityDue = 1.0 - exp(-rate * ageDays)
    val rateScore = (
        ln1p(rate) / ln1p(RATE_NORMALIZATION_PER_DAY)
        ).coerceIn(0.0, 1.0)
    val waitMillis = when {
        stats.lastAttemptMillis <= 0L -> MAX_ATTEMPT_AGE_MILLIS
        safeNow <= stats.lastAttemptMillis -> 0L
        else -> (safeNow - stats.lastAttemptMillis)
            .coerceAtMost(MAX_ATTEMPT_AGE_MILLIS)
    }
    val fairWindowMillis = maxOf(
        MIN_FAIR_WINDOW_MILLIS,
        intervalMillis * FAIR_INTERVAL_MULTIPLIER,
    )
    val fairness = (waitMillis.toDouble() / fairWindowMillis)
        .coerceIn(0.0, 1.0)
    val failureFactor = FAILURE_MULTIPLIER.pow(
        stats.consecutiveFailures.coerceIn(0, MAX_FAILURE_STREAK),
    ).coerceAtLeast(MIN_FAILURE_FACTOR)
    val rawScore = failureFactor * (
        PROBABILITY_WEIGHT * probabilityDue +
            RATE_WEIGHT * rateScore
        ) + FAIRNESS_WEIGHT * fairness
    val overdueAfter = maxOf(
        MIN_OVERDUE_MILLIS,
        intervalMillis * OVERDUE_INTERVAL_MULTIPLIER,
    )
    val tier = when {
        !stats.initialized && stats.consecutiveFailures == 0 -> 2
        waitMillis >= overdueAfter -> 1
        else -> 0
    }
    return SourcePriorityRank(
        tier = tier,
        scoreMicros = (rawScore * SCORE_SCALE).roundToLong(),
        lastAttemptMillis = stats.lastAttemptMillis,
    )
}

internal fun effectiveSourceRate(
    stats: SourcePriorityStats,
    nowMillis: Long,
): Double {
    val elapsedDays = elapsedDays(stats.lastModelUpdateMillis, nowMillis)
    val decay = 0.5.pow(elapsedDays / MODEL_HALF_LIFE_DAYS)
    val eventMass = finiteOr(stats.eventMass, 0.0).coerceAtLeast(0.0) * decay
    val exposure = finiteOr(stats.exposureDays, 0.0).coerceAtLeast(0.0) * decay
    val priorRate = finiteOr(stats.priorRatePerDay, DEFAULT_RATE_PER_DAY)
        .coerceIn(MIN_RATE_PER_DAY, MAX_RATE_PER_DAY)
    val priorExposure = finiteOr(
        stats.priorExposureDays,
        DEFAULT_PRIOR_EXPOSURE_DAYS,
    ).coerceIn(MIN_PRIOR_EXPOSURE_DAYS, MAX_PRIOR_EXPOSURE_DAYS)
    return ((eventMass + priorRate * priorExposure) / (exposure + priorExposure))
        .coerceIn(MIN_RATE_PER_DAY, MAX_RATE_PER_DAY)
}

internal fun updateSourcePriorityModel(
    stats: SourcePriorityStats,
    successful: Boolean,
    learnFromResult: Boolean,
    previousSuccessfulCheckMillis: Long,
    detectedItems: Int,
    nowMillis: Long,
): SourcePriorityUpdate {
    val safeNow = nowMillis.coerceAtLeast(0L)
    val modelElapsedDays = elapsedDays(stats.lastModelUpdateMillis, safeNow)
    val decay = 0.5.pow(modelElapsedDays / MODEL_HALF_LIFE_DAYS)
    var eventMass = finiteOr(stats.eventMass, 0.0).coerceAtLeast(0.0) * decay
    var exposureDays = finiteOr(stats.exposureDays, 0.0).coerceAtLeast(0.0) * decay
    var lastHitMillis = stats.lastHitMillis

    if (successful && learnFromResult && previousSuccessfulCheckMillis > 0L) {
        val rawObservationDays = (
            (safeNow - previousSuccessfulCheckMillis).coerceAtLeast(
                MIN_OBSERVATION_MILLIS,
            ).toDouble() / DAY_MILLIS
            ).coerceAtMost(MAX_OBSERVATION_GAP_DAYS)
        val creditedDays = rawObservationDays.coerceAtMost(MAX_CREDITED_DAYS)
        val scaledEvents = detectedItems
            .coerceIn(0, MAX_EVENTS_PER_OBSERVATION)
            .toDouble() * (creditedDays / rawObservationDays)
        eventMass += scaledEvents
        exposureDays += creditedDays
        if (detectedItems > 0) lastHitMillis = safeNow
    }

    return SourcePriorityUpdate(
        sourceKey = stats.sourceKey,
        eventMass = eventMass.coerceIn(0.0, MAX_EVENT_MASS),
        exposureDays = exposureDays.coerceIn(0.0, MAX_EXPOSURE_DAYS),
        lastModelUpdateMillis = safeNow,
        lastAttemptMillis = safeNow,
        lastHitMillis = lastHitMillis,
        consecutiveFailures = if (successful) {
            0
        } else {
            (stats.consecutiveFailures + 1).coerceAtMost(MAX_FAILURE_STREAK)
        },
    )
}

internal fun parseSourcePrioritySeedJson(json: String): SourcePrioritySeedCatalog {
    val root = JSONObject(json)
    val model = root.optJSONObject("model")
    val globalRate = finiteOr(
        model?.optDouble("globalRatePerDay", DEFAULT_RATE_PER_DAY)
            ?: root.optDouble("globalRatePerDay", DEFAULT_RATE_PER_DAY),
        DEFAULT_RATE_PER_DAY,
    ).coerceIn(MIN_RATE_PER_DAY, MAX_RATE_PER_DAY)
    val parsed = linkedMapOf<String, SourcePrioritySeedValue>()
    val sourceObject = root.optJSONObject("sources")
    if (sourceObject != null) {
        sourceObject.keys().asSequence().sorted().forEach { stableKey ->
            val item = sourceObject.optJSONObject(stableKey) ?: return@forEach
            parseSeedValue(item)?.let { value ->
                if (stableKey.isNotBlank()) parsed[stableKey.trim()] = value
            }
        }
    } else {
        // Tolerujemy prototypowy format tablicowy, aby wadliwy asset nie
        // wyłączał całego harmonogramu po aktualizacji aplikacji.
        val sourceArray = root.optJSONArray("sources")
        if (sourceArray != null) {
            for (index in 0 until sourceArray.length()) {
                val item = sourceArray.optJSONObject(index) ?: continue
                val stableKey = item.optString("key").trim()
                if (stableKey.isBlank()) continue
                parseSeedValue(item)?.let { parsed[stableKey] = it }
            }
        }
    }
    return SourcePrioritySeedCatalog(globalRate, parsed)
}

private fun parseSeedValue(item: JSONObject): SourcePrioritySeedValue? {
    val rate = item.optDouble("priorRatePerDay", Double.NaN)
    val exposure = item.optDouble("priorExposureDays", Double.NaN)
    if (!rate.isFinite() || !exposure.isFinite()) return null
    return SourcePrioritySeedValue(
        priorRatePerDay = rate.coerceIn(MIN_RATE_PER_DAY, MAX_RATE_PER_DAY),
        priorExposureDays = exposure.coerceIn(
            MIN_PRIOR_EXPOSURE_DAYS,
            MAX_PRIOR_EXPOSURE_DAYS,
        ),
    )
}

internal fun stableSeedKey(source: CreatorSource): String {
    val stableId = source.externalId?.trim()?.takeIf { it.isNotEmpty() }
        ?: source.url.trim()
    return "${source.type.name}:$stableId"
}

internal fun <T> roundRobinSources(
    creators: List<Creator>,
    sourcesByCreator: Map<Creator, List<T>>,
): List<Pair<Creator, T>> {
    val maxSources = creators.maxOfOrNull { sourcesByCreator[it].orEmpty().size } ?: 0
    return buildList {
        repeat(maxSources) { sourceIndex ->
            creators.forEach { creator ->
                sourcesByCreator[creator]?.getOrNull(sourceIndex)?.let { source ->
                    add(creator to source)
                }
            }
        }
    }
}

private fun elapsedDays(fromMillis: Long, toMillis: Long): Double {
    if (fromMillis <= 0L || toMillis <= fromMillis) return 0.0
    return ((toMillis - fromMillis).toDouble() / DAY_MILLIS)
        .coerceAtMost(MAX_DECAY_DAYS)
}

private fun finiteOr(value: Double, fallback: Double): Double =
    if (value.isFinite()) value else fallback

private fun loadSeedCatalog(context: Context): SourcePrioritySeedCatalog =
    runCatching {
        context.assets.open(SEED_ASSET_NAME)
            .bufferedReader(Charsets.UTF_8)
            .use { parseSourcePrioritySeedJson(it.readText()) }
    }.onFailure { error ->
        AppLog.warning(
            "SourcePriority",
            "Nie udało się odczytać początkowego modelu priorytetów",
            error,
        )
    }.getOrElse {
        SourcePrioritySeedCatalog(DEFAULT_RATE_PER_DAY, emptyMap())
    }

private const val SEED_ASSET_NAME = "source_priority_seed.json"
private const val DEFAULT_RATE_PER_DAY = 1.0 / 7.0
private const val DEFAULT_PRIOR_EXPOSURE_DAYS = 7.0
private const val MIN_PRIOR_EXPOSURE_DAYS = 1.0
private const val MAX_PRIOR_EXPOSURE_DAYS = 14.0
private const val MIN_RATE_PER_DAY = 1.0 / 365.0
private const val MAX_RATE_PER_DAY = 24.0
private const val MODEL_HALF_LIFE_DAYS = 28.0
private const val MAX_DECAY_DAYS = 90.0
private const val MAX_OBSERVATION_GAP_DAYS = 30.0
private const val MAX_CREDITED_DAYS = 7.0
private const val MAX_EVENTS_PER_OBSERVATION = 100
private const val MAX_EVENT_MASS = 1_000.0
private const val MAX_EXPOSURE_DAYS = 365.0
private const val RATE_NORMALIZATION_PER_DAY = 4.0
private const val PROBABILITY_WEIGHT = 0.70
private const val RATE_WEIGHT = 0.25
private const val FAIRNESS_WEIGHT = 0.05
private const val FAILURE_MULTIPLIER = 0.82
private const val MIN_FAILURE_FACTOR = 0.40
private const val MAX_FAILURE_STREAK = 8
private const val FAIR_INTERVAL_MULTIPLIER = 4L
private const val OVERDUE_INTERVAL_MULTIPLIER = 3L
private const val MIN_INTERVAL_MINUTES = 15
private const val SCORE_SCALE = 1_000_000.0
private val DAY_MILLIS = TimeUnit.DAYS.toMillis(1).toDouble()
private val MIN_OBSERVATION_MILLIS = TimeUnit.MINUTES.toMillis(5)
private val MIN_MODEL_WRITE_INTERVAL_MILLIS = TimeUnit.HOURS.toMillis(6)
private val MIN_FAIR_WINDOW_MILLIS = TimeUnit.HOURS.toMillis(6)
private val MIN_OVERDUE_MILLIS = TimeUnit.HOURS.toMillis(24)
private val MAX_SUCCESS_AGE_MILLIS = TimeUnit.DAYS.toMillis(7)
private val MAX_ATTEMPT_AGE_MILLIS = TimeUnit.DAYS.toMillis(30)
