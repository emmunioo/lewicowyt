package pl.lewicowyt.notifier.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lewicowyt.notifier.model.HistoryFilter
import pl.lewicowyt.notifier.model.PublishedAtEvidence
import pl.lewicowyt.notifier.model.VideoEntry
import pl.lewicowyt.notifier.model.VideoKind
import pl.lewicowyt.notifier.model.VideoKindEvidence
import pl.lewicowyt.notifier.model.VideoOrigin
import pl.lewicowyt.notifier.model.SourceType
import pl.lewicowyt.notifier.network.YouTubeHistoryTab

class HistoryBackfillLoaderTest {
    @Test
    fun continuesOnlyWhilePageIsInsideSelectedTimeRange() {
        val cutoff = 1_000L

        assertTrue(
            shouldContinueHistoryPaging(
                publishedTimes = listOf(1_500L, 1_200L, 1_000L),
                cutoff = cutoff,
                hasNextPage = true,
                loadedPageCount = 1,
            ),
        )
        assertFalse(
            shouldContinueHistoryPaging(
                publishedTimes = listOf(1_500L, 999L),
                cutoff = cutoff,
                hasNextPage = true,
                loadedPageCount = 1,
            ),
        )
    }

    @Test
    fun emptyPageWithCursorContinuesButLastOrSafetyLimitedPageStops() {
        val cutoff = 1_000L

        assertTrue(
            shouldContinueHistoryPaging(
                publishedTimes = emptyList(),
                cutoff = cutoff,
                hasNextPage = true,
                loadedPageCount = 1,
            ),
        )
        assertFalse(
            shouldContinueHistoryPaging(
                publishedTimes = listOf(1_500L),
                cutoff = cutoff,
                hasNextPage = false,
                loadedPageCount = 1,
            ),
        )
        assertFalse(
            shouldContinueHistoryPaging(
                publishedTimes = listOf(1_500L),
                cutoff = cutoff,
                hasNextPage = true,
                loadedPageCount = 30,
            ),
        )
    }

    @Test
    fun completenessRequiresEndOfFeedOrCrossingTheCutoff() {
        val cutoff = 1_000L

        assertTrue(
            isHistoryRangeComplete(
                publishedTimes = emptyList(),
                cutoff = cutoff,
                hasNextPage = false,
            ),
        )
        assertTrue(
            isHistoryRangeComplete(
                publishedTimes = listOf(1_500L, 999L),
                cutoff = cutoff,
                hasNextPage = true,
            ),
        )
        assertFalse(
            isHistoryRangeComplete(
                publishedTimes = listOf(1_500L, 1_000L),
                cutoff = cutoff,
                hasNextPage = true,
            ),
        )
    }

    @Test
    fun customPlaylistMustReachItsEndBecauseItsOrderMayBeManual() {
        assertFalse(
            isHistoryTargetComplete(
                publishedTimes = listOf(500L),
                cutoff = 1_000L,
                hasNextPage = true,
                chronological = false,
            ),
        )
        assertTrue(
            isHistoryTargetComplete(
                publishedTimes = listOf(500L, 2_000L),
                cutoff = 1_000L,
                hasNextPage = false,
                chronological = false,
            ),
        )
    }

    @Test
    fun rssHistoryStartsWithRecentUniqueYouTubeEntries() {
        val items = rssHistoryItems(
            entries = listOf(
                rssEntry("AAAAAAAAAAA", publishedAt = 1_500L),
                rssEntry("BBBBBBBBBBB", publishedAt = 999L),
                rssEntry("AAAAAAAAAAA", publishedAt = 1_600L),
            ),
            cutoff = 1_000L,
        )

        assertEquals(1, items.size)
        assertEquals("AAAAAAAAAAA", items.single().entry.id)
        assertEquals(VideoOrigin.YOUTUBE, items.single().entry.origin)
        assertEquals(VideoKind.VIDEO, items.single().kind)
        assertEquals(VideoKindEvidence.DEFAULT_VIDEO_FALLBACK, items.single().evidence)
        assertEquals(PublishedAtEvidence.RSS, items.single().publishedAtEvidence)
    }

    @Test
    fun rssCanonicalShortUrlIsAConfirmedShort() {
        val item = rssHistoryItems(
            entries = listOf(
                rssEntry("Oj0-9Ks6d7k", publishedAt = 1_500L).copy(
                    url = "https://www.youtube.com/shorts/Oj0-9Ks6d7k",
                ),
            ),
            cutoff = 1_000L,
        ).single()

        assertEquals(VideoKind.SHORT, item.kind)
        assertEquals(VideoKindEvidence.RSS_SHORT_URL, item.evidence)
    }

    @Test
    fun ambiguousApiPremiereUsesWebFallbackAndBecomesFilm() = runBlocking {
        var calls = 0
        val items = resolveAmbiguousDataApiKinds(
            items = listOf(historyItem("lU4H50GMJyI", VideoKind.UNKNOWN)),
        ) {
            calls += 1
            VideoKind.VIDEO
        }

        assertEquals(1, calls)
        assertEquals(VideoKind.VIDEO, items.single().kind)
        assertEquals(VideoKindEvidence.PLAYER_METADATA, items.single().evidence)
    }

    @Test
    fun definitiveApiKindDoesNotInvokeFallback() = runBlocking {
        var calls = 0
        val items = resolveAmbiguousDataApiKinds(
            items = listOf(historyItem("AAAAAAAAAAA", VideoKind.LIVE)),
        ) {
            calls += 1
            VideoKind.VIDEO
        }

        assertEquals(0, calls)
        assertEquals(VideoKind.LIVE, items.single().kind)
    }

    @Test
    fun unresolvedApiKindRemainsUnverified() = runBlocking {
        val items = resolveAmbiguousDataApiKinds(
            items = listOf(historyItem("BBBBBBBBBBB", VideoKind.UNKNOWN)),
        ) {
            VideoKind.UNKNOWN
        }

        assertEquals(VideoKind.UNKNOWN, items.single().kind)
        assertEquals(VideoKindEvidence.NONE, items.single().evidence)
    }

    @Test
    fun unresolvedApiKindGetsReversibleFilmFallbackBeforeStorage() {
        val item = historyItem("BBBBBBBBBBB", VideoKind.UNKNOWN)
            .withDefaultVideoFallback()

        assertEquals(VideoKind.VIDEO, item.kind)
        assertEquals(VideoKindEvidence.DEFAULT_VIDEO_FALLBACK, item.evidence)
    }

    @Test
    fun historyLoadsFiveChannelsAtOnce() {
        assertEquals(5, HISTORY_CHANNEL_CONCURRENCY)
    }

    @Test
    fun switchingVisibleHistoryKindKeepsTheSameNetworkTargetSet() {
        val videos = historyFilterTargetSignature(setOf(HistoryFilter.VIDEOS))
        val shorts = historyFilterTargetSignature(setOf(HistoryFilter.SHORTS))
        val streams = historyFilterTargetSignature(setOf(HistoryFilter.STREAMS))

        assertEquals(videos, shorts)
        assertEquals(videos, streams)
        assertFalse(videos == historyFilterTargetSignature(emptySet()))
    }

    @Test
    fun apiVerifiesOnlyKindsItCannotProveByMetadata() {
        assertEquals(
            listOf(YouTubeHistoryTab.SHORTS, YouTubeHistoryTab.STREAMS),
            apiKindVerificationTabs(SourceType.CHANNEL),
        )
        assertEquals(
            listOf(YouTubeHistoryTab.PLAYLIST),
            apiKindVerificationTabs(SourceType.PLAYLIST),
        )
    }

    @Test
    fun webHistoryLoadsFilmsThenShortsThenStreams() {
        assertEquals(
            listOf(
                YouTubeHistoryTab.VIDEOS,
                YouTubeHistoryTab.SHORTS,
                YouTubeHistoryTab.STREAMS,
            ),
            webHistoryTabsForSource(SourceType.CHANNEL),
        )
    }

    @Test
    fun webHistorySkipsGloballyOrPerCreatorDisabledTabs() {
        assertEquals(
            listOf(YouTubeHistoryTab.VIDEOS, YouTubeHistoryTab.STREAMS),
            webHistoryTabsForSource(
                SourceType.CHANNEL,
                setOf(HistoryFilter.VIDEOS, HistoryFilter.STREAMS),
            ),
        )
        assertTrue(webHistoryTabsForSource(SourceType.CHANNEL, emptySet()).isEmpty())
    }

    @Test
    fun ambiguousRssOrApiFilmWaitsForWebWhenKindsAreSelective() {
        val fallback = historyItem("DDDDDDDDDDD", VideoKind.UNKNOWN)
            .withDefaultVideoFallback()
        val apiFilm = historyItem("EEEEEEEEEEE", VideoKind.VIDEO)

        assertFalse(
            fallback.isSafeForEnabledContentTypes(setOf(HistoryFilter.VIDEOS)),
        )
        assertFalse(
            apiFilm.isSafeForEnabledContentTypes(setOf(HistoryFilter.VIDEOS)),
        )
        assertTrue(
            fallback.isSafeForEnabledContentTypes(HistoryFilter.entries.toSet()),
        )
    }

    @Test
    fun longerHistoryIsSplitIntoTwoWeekStagesWithoutOvershootingTheWindow() {
        assertEquals(listOf(14), historyStageDepths(14))
        assertEquals(listOf(14, 28, 30), historyStageDepths(30))
        assertEquals(listOf(14, 28, 42, 56, 60), historyStageDepths(60))
    }

    @Test
    fun pageCrossingStageBoundaryDefersOlderItemsWithoutLosingThem() {
        val items = listOf(
            historyItem("AAAAAAAAAAA", VideoKind.VIDEO).copy(
                entry = rssEntry("AAAAAAAAAAA", publishedAt = 1_500L),
            ),
            historyItem("BBBBBBBBBBB", VideoKind.SHORT).copy(
                entry = rssEntry("BBBBBBBBBBB", publishedAt = 1_200L),
            ),
            historyItem("CCCCCCCCCCC", VideoKind.STREAM_ARCHIVE).copy(
                entry = rssEntry("CCCCCCCCCCC", publishedAt = 900L),
            ),
        )

        val (ready, deferred) = splitHistoryStageItems(
            items = items,
            overallCutoff = 1_000L,
            stageCutoff = 1_300L,
        )

        assertEquals(listOf("AAAAAAAAAAA"), ready.map { it.entry.id })
        assertEquals(listOf("BBBBBBBBBBB"), deferred.map { it.entry.id })
    }

    @Test
    fun DomaFilmDoesNotMatchStreamsFilterAfterApiFallback() {
        assertTrue(VideoKind.VIDEO.matchesHistoryFilters(setOf(HistoryFilter.VIDEOS)))
        assertFalse(VideoKind.VIDEO.matchesHistoryFilters(setOf(HistoryFilter.STREAMS)))
    }

    @Test
    fun unknownMaterialIsNotGuessedAsFilm() {
        assertFalse(VideoKind.UNKNOWN.matchesHistoryFilters(setOf(HistoryFilter.VIDEOS)))
        assertFalse(VideoKind.UNKNOWN.matchesHistoryFilters(HistoryFilter.entries.toSet()))
    }

    private fun historyItem(id: String, kind: VideoKind) =
        pl.lewicowyt.notifier.network.YouTubeHistoryItem(
            entry = rssEntry(id, publishedAt = 1_500L),
            kind = kind,
            evidence = if (kind == VideoKind.UNKNOWN) {
                VideoKindEvidence.NONE
            } else {
                VideoKindEvidence.API_METADATA
            },
        )

    private fun rssEntry(id: String, publishedAt: Long) = VideoEntry(
        id = id,
        title = "Tytuł",
        url = "https://www.youtube.com/watch?v=$id",
        publishedAtMillis = publishedAt,
        author = "Kanał",
    )
}
