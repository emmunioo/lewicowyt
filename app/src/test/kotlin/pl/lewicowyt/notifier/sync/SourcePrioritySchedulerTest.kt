package pl.lewicowyt.notifier.sync

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lewicowyt.notifier.data.SourcePriorityStats
import pl.lewicowyt.notifier.model.Creator

class SourcePrioritySchedulerTest {
    @Test
    fun prolificSourceHasHigherEffectiveRateAndRank() {
        val now = TimeUnit.DAYS.toMillis(100)
        val prolific = stats(
            sourceKey = "prolific",
            eventMass = 21.0,
            exposureDays = 7.0,
            lastModelUpdateMillis = now,
            lastAttemptMillis = now - TimeUnit.HOURS.toMillis(1),
            lastSuccessfulCheckMillis = now - TimeUnit.HOURS.toMillis(1),
        )
        val quiet = stats(
            sourceKey = "quiet",
            eventMass = 1.0,
            exposureDays = 7.0,
            lastModelUpdateMillis = now,
            lastAttemptMillis = now - TimeUnit.HOURS.toMillis(1),
            lastSuccessfulCheckMillis = now - TimeUnit.HOURS.toMillis(1),
        )

        assertTrue(
            effectiveSourceRate(prolific, now) >
                effectiveSourceRate(quiet, now),
        )
        val prolificRank = sourcePriorityRank(prolific, now, intervalMinutes = 60)
        val quietRank = sourcePriorityRank(quiet, now, intervalMinutes = 60)
        assertEquals(quietRank.tier, prolificRank.tier)
        assertTrue(prolificRank.scoreMicros > quietRank.scoreMicros)
    }

    @Test
    fun coldStartIsScheduledBeforeAnOrdinaryInitializedSource() {
        val now = TimeUnit.DAYS.toMillis(100)
        val coldStart = sourcePriorityRank(
            stats = stats(
                sourceKey = "cold",
                initialized = false,
                lastAttemptMillis = 0L,
                lastSuccessfulCheckMillis = 0L,
            ),
            nowMillis = now,
            intervalMinutes = 60,
        )
        val ordinary = sourcePriorityRank(
            stats = stats(
                sourceKey = "ordinary",
                initialized = true,
                lastAttemptMillis = now - TimeUnit.HOURS.toMillis(1),
                lastSuccessfulCheckMillis = now - TimeUnit.HOURS.toMillis(1),
            ),
            nowMillis = now,
            intervalMinutes = 60,
        )

        assertEquals(2, coldStart.tier)
        assertEquals(0, ordinary.tier)
        assertTrue(coldStart.tier > ordinary.tier)
    }

    @Test
    fun permanentlyBrokenColdSourceLeavesTheAbsoluteColdStartTier() {
        val now = TimeUnit.DAYS.toMillis(100)
        val broken = sourcePriorityRank(
            stats = stats(
                sourceKey = "broken",
                initialized = false,
                lastAttemptMillis = now - TimeUnit.HOURS.toMillis(1),
                consecutiveFailures = 1,
            ),
            nowMillis = now,
            intervalMinutes = 60,
        )
        val healthy = sourcePriorityRank(
            stats = stats(
                sourceKey = "healthy",
                initialized = true,
                eventMass = 10.0,
                exposureDays = 2.0,
                lastAttemptMillis = now - TimeUnit.HOURS.toMillis(1),
                lastSuccessfulCheckMillis = now - TimeUnit.HOURS.toMillis(1),
            ),
            nowMillis = now,
            intervalMinutes = 60,
        )

        assertEquals(0, broken.tier)
        assertEquals(0, healthy.tier)
        assertTrue(healthy.scoreMicros > broken.scoreMicros)
    }

    @Test
    fun overdueSourceGetsFairnessTierAheadOfRecentlyAttemptedSource() {
        val now = TimeUnit.DAYS.toMillis(100)
        val overdue = sourcePriorityRank(
            stats = stats(
                sourceKey = "overdue",
                lastAttemptMillis = now - TimeUnit.DAYS.toMillis(2),
                lastSuccessfulCheckMillis = now - TimeUnit.DAYS.toMillis(2),
            ),
            nowMillis = now,
            intervalMinutes = 60,
        )
        val recent = sourcePriorityRank(
            stats = stats(
                sourceKey = "recent",
                lastAttemptMillis = now - TimeUnit.MINUTES.toMillis(30),
                lastSuccessfulCheckMillis = now - TimeUnit.MINUTES.toMillis(30),
            ),
            nowMillis = now,
            intervalMinutes = 60,
        )

        assertEquals(1, overdue.tier)
        assertEquals(0, recent.tier)
        assertTrue(overdue.tier > recent.tier)
        assertTrue(overdue.scoreMicros > recent.scoreMicros)
    }

    @Test
    fun failedCheckDoesNotAddExposureOrTreatFailureAsPublicationMiss() {
        val now = TimeUnit.DAYS.toMillis(100)
        val current = stats(
            sourceKey = "failed",
            eventMass = 3.0,
            exposureDays = 5.0,
            lastModelUpdateMillis = now,
            consecutiveFailures = 2,
        )

        val update = updateSourcePriorityModel(
            stats = current,
            successful = false,
            learnFromResult = true,
            previousSuccessfulCheckMillis = now - TimeUnit.DAYS.toMillis(1),
            detectedItems = 0,
            nowMillis = now,
        )

        assertEquals(current.eventMass, update.eventMass, DOUBLE_TOLERANCE)
        assertEquals(current.exposureDays, update.exposureDays, DOUBLE_TOLERANCE)
        assertTrue(update.exposureDays >= 0.0)
        assertEquals(3, update.consecutiveFailures)
    }

    @Test
    fun firstInitializationWithLearningDisabledDoesNotTrainOnImportedHistory() {
        val now = TimeUnit.DAYS.toMillis(100)
        val current = stats(
            sourceKey = "first-run",
            eventMass = 2.0,
            exposureDays = 4.0,
            lastModelUpdateMillis = now,
            consecutiveFailures = 3,
        )

        val update = updateSourcePriorityModel(
            stats = current,
            successful = true,
            learnFromResult = false,
            previousSuccessfulCheckMillis = now - TimeUnit.DAYS.toMillis(1),
            detectedItems = 15,
            nowMillis = now,
        )

        assertEquals(current.eventMass, update.eventMass, DOUBLE_TOLERANCE)
        assertEquals(current.exposureDays, update.exposureDays, DOUBLE_TOLERANCE)
        assertEquals(current.lastHitMillis, update.lastHitMillis)
        assertEquals(0, update.consecutiveFailures)
    }

    @Test
    fun quietModelWritesAreThrottledButHitsAndFailuresAreImmediate() {
        val now = TimeUnit.DAYS.toMillis(100)
        val recent = stats(
            sourceKey = "throttled",
            lastModelUpdateMillis = now - TimeUnit.HOURS.toMillis(1),
        )

        assertTrue(
            !shouldPersistSourcePriority(
                stats = recent,
                successful = true,
                learnFromResult = true,
                detectedItems = 0,
                nowMillis = now,
            ),
        )
        assertTrue(
            shouldPersistSourcePriority(
                stats = recent,
                successful = true,
                learnFromResult = true,
                detectedItems = 1,
                nowMillis = now,
            ),
        )
        assertTrue(
            shouldPersistSourcePriority(
                stats = recent,
                successful = false,
                learnFromResult = false,
                detectedItems = 0,
                nowMillis = now,
            ),
        )
        assertTrue(
            shouldPersistSourcePriority(
                stats = recent.copy(
                    lastModelUpdateMillis = now - TimeUnit.HOURS.toMillis(6),
                ),
                successful = true,
                learnFromResult = true,
                detectedItems = 0,
                nowMillis = now,
            ),
        )
    }

    @Test
    fun learnedMassUsesTwentyEightDayHalfLife() {
        val now = TimeUnit.DAYS.toMillis(100)
        val current = stats(
            sourceKey = "decay",
            priorRatePerDay = 0.1,
            priorExposureDays = 7.0,
            eventMass = 28.0,
            exposureDays = 7.0,
            lastModelUpdateMillis = now - TimeUnit.DAYS.toMillis(28),
        )

        val update = updateSourcePriorityModel(
            stats = current,
            successful = false,
            learnFromResult = false,
            previousSuccessfulCheckMillis = 0L,
            detectedItems = 0,
            nowMillis = now,
        )

        assertEquals(14.0, update.eventMass, DOUBLE_TOLERANCE)
        assertEquals(3.5, update.exposureDays, DOUBLE_TOLERANCE)
        assertEquals(1.4, effectiveSourceRate(current, now), DOUBLE_TOLERANCE)
    }

    @Test
    fun clockMovingBackwardsCannotCreateNegativeOrNonFiniteModelValues() {
        val current = stats(
            sourceKey = "clock",
            eventMass = 4.0,
            exposureDays = 8.0,
            lastModelUpdateMillis = TimeUnit.DAYS.toMillis(10),
            lastAttemptMillis = TimeUnit.DAYS.toMillis(10),
            lastSuccessfulCheckMillis = TimeUnit.DAYS.toMillis(10),
        )

        val update = updateSourcePriorityModel(
            stats = current,
            successful = false,
            learnFromResult = true,
            previousSuccessfulCheckMillis = TimeUnit.DAYS.toMillis(11),
            detectedItems = 20,
            nowMillis = -1_000L,
        )
        val rank = sourcePriorityRank(
            stats = current,
            nowMillis = -1_000L,
            intervalMinutes = 60,
        )

        assertEquals(current.eventMass, update.eventMass, DOUBLE_TOLERANCE)
        assertEquals(current.exposureDays, update.exposureDays, DOUBLE_TOLERANCE)
        assertEquals(0L, update.lastAttemptMillis)
        assertTrue(update.eventMass.isFinite())
        assertTrue(update.exposureDays.isFinite())
        assertTrue(update.eventMass >= 0.0)
        assertTrue(update.exposureDays >= 0.0)
        assertTrue(rank.scoreMicros >= 0L)
    }

    @Test
    fun validSeedJsonIsParsedAndMalformedEntriesAreSkipped() {
        val catalog = parseSourcePrioritySeedJson(
            """
            {
              "globalRatePerDay": 0.5,
              "sources": [
                null,
                {"key": "", "priorRatePerDay": 1.0, "priorExposureDays": 7.0},
                {"key": "CHANNEL:broken", "priorRatePerDay": "nope",
                 "priorExposureDays": 7.0},
                {"key": "CHANNEL:valid", "priorRatePerDay": 2.0,
                 "priorExposureDays": 3.0}
              ]
            }
            """.trimIndent(),
        )

        assertEquals(0.5, catalog.globalRatePerDay, DOUBLE_TOLERANCE)
        assertEquals(setOf("CHANNEL:valid"), catalog.sources.keys)
        assertEquals(
            2.0,
            catalog.sources.getValue("CHANNEL:valid").priorRatePerDay,
            DOUBLE_TOLERANCE,
        )
        assertEquals(
            3.0,
            catalog.sources.getValue("CHANNEL:valid").priorExposureDays,
            DOUBLE_TOLERANCE,
        )
    }

    @Test
    fun productionSeedMapAndNestedGlobalRateAreParsed() {
        val catalog = parseSourcePrioritySeedJson(
            """
            {
              "schemaVersion": 1,
              "model": {"globalRatePerDay": 0.25},
              "sources": {
                "CHANNEL:valid": {
                  "priorRatePerDay": 1.5,
                  "priorExposureDays": 9.0
                }
              }
            }
            """.trimIndent(),
        )

        assertEquals(0.25, catalog.globalRatePerDay, DOUBLE_TOLERANCE)
        assertEquals(setOf("CHANNEL:valid"), catalog.sources.keys)
        assertEquals(
            1.5,
            catalog.sources.getValue("CHANNEL:valid").priorRatePerDay,
            DOUBLE_TOLERANCE,
        )
        assertEquals(
            9.0,
            catalog.sources.getValue("CHANNEL:valid").priorExposureDays,
            DOUBLE_TOLERANCE,
        )
    }

    @Test
    fun malformedSeedShapeFallsBackToNeutralCatalog() {
        val catalog = parseSourcePrioritySeedJson(
            """
            {
              "globalRatePerDay": "not-a-number",
              "sources": {"instead": "of-an-array"}
            }
            """.trimIndent(),
        )

        assertEquals(1.0 / 7.0, catalog.globalRatePerDay, DOUBLE_TOLERANCE)
        assertTrue(catalog.sources.isEmpty())
    }

    @Test
    fun roundRobinIsStableAndFirstSlotsBelongToDifferentCreators() {
        val first = Creator(id = "first", name = "First", sources = emptyList())
        val second = Creator(id = "second", name = "Second", sources = emptyList())
        val third = Creator(id = "third", name = "Third", sources = emptyList())
        val creators = listOf(first, second, third)
        val sources = linkedMapOf(
            first to listOf("first-1", "first-2", "first-3"),
            second to listOf("second-1"),
            third to listOf("third-1", "third-2"),
        )
        val expected = listOf(
            first to "first-1",
            second to "second-1",
            third to "third-1",
            first to "first-2",
            third to "third-2",
            first to "first-3",
        )

        val firstRun = roundRobinSources(creators, sources)
        val secondRun = roundRobinSources(creators, sources)

        assertEquals(expected, firstRun)
        assertEquals(firstRun, secondRun)
        assertTrue(firstRun.size >= 2)
        assertTrue(firstRun[0].first.id != firstRun[1].first.id)
    }

    private fun stats(
        sourceKey: String,
        initialized: Boolean = true,
        priorRatePerDay: Double = 1.0 / 7.0,
        priorExposureDays: Double = 7.0,
        eventMass: Double = 0.0,
        exposureDays: Double = 0.0,
        lastModelUpdateMillis: Long = 0L,
        lastAttemptMillis: Long = 0L,
        lastSuccessfulCheckMillis: Long = 0L,
        lastHitMillis: Long = 0L,
        consecutiveFailures: Int = 0,
    ): SourcePriorityStats = SourcePriorityStats(
        sourceKey = sourceKey,
        initialized = initialized,
        priorRatePerDay = priorRatePerDay,
        priorExposureDays = priorExposureDays,
        eventMass = eventMass,
        exposureDays = exposureDays,
        lastModelUpdateMillis = lastModelUpdateMillis,
        lastAttemptMillis = lastAttemptMillis,
        lastSuccessfulCheckMillis = lastSuccessfulCheckMillis,
        lastHitMillis = lastHitMillis,
        consecutiveFailures = consecutiveFailures,
    )

    private companion object {
        const val DOUBLE_TOLERANCE = 1e-9
    }
}
