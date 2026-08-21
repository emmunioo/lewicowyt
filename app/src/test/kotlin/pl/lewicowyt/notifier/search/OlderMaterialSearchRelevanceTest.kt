package pl.lewicowyt.notifier.search

import org.junit.Assert.assertEquals
import org.junit.Test
import pl.lewicowyt.notifier.model.OlderMaterialCandidate

class OlderMaterialSearchRelevanceTest {
    @Test
    fun exactTitleRejectsLooseYouTubeResultMatchingOnlyStopWord() {
        val exact = candidate("hua7SlaA85o", "Razem nie jest idealne...")
        val loose = candidate(
            "kXWqL5TwLYs",
            "Pogadajmy o PAŃSTWOWYM KAPITALIZMIE i o tym, czego PolskiInkwizytor nie rozumie!",
        )

        assertEquals(
            listOf(exact),
            rankOlderMaterialCandidates(
                query = "Razem nie jest idealne",
                candidates = listOf(loose, exact),
                limit = 20,
            ),
        )
    }

    @Test
    fun meaningfulKeywordsStillFindARelevantNonExactTitle() {
        val relevant = candidate(
            "kXWqL5TwLYs",
            "Pogadajmy o PAŃSTWOWYM KAPITALIZMIE i o tym, czego ktoś nie rozumie!",
        )

        assertEquals(
            listOf(relevant),
            rankOlderMaterialCandidates(
                query = "państwowy kapitalizm",
                candidates = listOf(relevant),
                limit = 20,
            ),
        )
    }

    @Test
    fun queryCanMatchDescriptionSnippetReturnedWithSearchResult() {
        val result = candidate("61dyKD2DFhs", "Sytuacja ze Zgrzytem jest... dziwna")

        assertEquals(
            listOf(result),
            rankOlderMaterialCandidates(
                query = "Sama już nie wiem, co myśleć",
                candidates = listOf(result),
                evidenceByVideoId = mapOf(
                    result.videoId to
                        "Sama już nie wiem, co myśleć. Dla pełnego kontekstu: mój film...",
                ),
                limit = 20,
            ),
        )
    }

    @Test
    fun polishInflectionCanMatchByStableWordPrefix() {
        val relevant = candidate("z001tyLR20s", "Nie ma idealnej listy wyborczej...")

        assertEquals(
            listOf(relevant),
            rankOlderMaterialCandidates(
                query = "idealne wybory",
                candidates = listOf(relevant),
                limit = 20,
            ),
        )
    }

    private fun candidate(videoId: String, title: String) = OlderMaterialCandidate(
        videoId = videoId,
        title = title,
        creatorId = "ralindel",
        creatorName = "Ralindel",
    )
}
