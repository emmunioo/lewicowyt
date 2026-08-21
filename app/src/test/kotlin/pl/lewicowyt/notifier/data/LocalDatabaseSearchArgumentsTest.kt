package pl.lewicowyt.notifier.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lewicowyt.notifier.model.HistoryItem
import pl.lewicowyt.notifier.model.VideoKind

class LocalDatabaseSearchArgumentsTest {
    @Test
    fun polishNikNicAlternationKeepsResultVisibleWhileTyping() {
        assertEquals(
            "(\"pelnomocnic\"* OR \"pelnomocnik\"*)",
            buildFtsQuery("pełnomocnic"),
        )
        assertEquals(
            "(\"pelnomocnik\"* OR \"pelnomocnic\"*)",
            buildFtsQuery("pełnomocnik"),
        )
    }

    @Test
    fun ordinaryInflectionGetsOnlyOneAdditionalStem() {
        assertEquals("(\"pelnomocni\"* OR \"pelnomocn\"*)", buildFtsQuery("pełnomocni"))
        assertEquals(listOf("historia", "histor"), polishSearchPrefixVariants("historia"))
    }

    @Test
    fun polishInflectionsProduceConservativePrefixStem() {
        assertEquals(listOf("prawica", "prawic"), polishSearchPrefixVariants("prawica"))
        assertEquals(listOf("prawicy", "prawic"), polishSearchPrefixVariants("prawicy"))
        assertEquals(listOf("historiami", "histori"), polishSearchPrefixVariants("historiami"))
    }

    @Test
    fun normalizationRemovesAllPolishDiacritics() {
        assertEquals("Zazolc gesla jazn", normalizePolishSearchText("Zażółć gęślą jaźń"))
    }

    @Test
    fun commonWordsBecomeOptionalWhenQueryHasMeaningfulTerms() {
        val plan = buildHistorySearchPlan("jak prawica")!!

        assertEquals(listOf("prawica"), plan.tokens)
        assertEquals("(\"prawica\"* OR \"prawic\"*)", plan.strictQuery)
    }

    @Test
    fun multiWordQueryHasStrictAndRelaxedStrategies() {
        val plan = buildHistorySearchPlan("Razem nie jest idealne")!!

        assertEquals(listOf("razem", "nie", "idealne"), plan.tokens)
        assertTrue(plan.strictQuery.contains(" AND "))
        assertTrue(plan.relaxedQuery!!.contains(" OR "))
        assertTrue(plan.typoCandidateQuery!!.contains("\"raze\"*"))
    }

    @Test
    fun damerauLevenshteinHandlesTyposAndTranspositions() {
        assertEquals(1, damerauLevenshteinDistance("ralidnel", "ralindel", 2))
        assertEquals(1, damerauLevenshteinDistance("prawcia", "prawica", 2))
        assertTrue(damerauLevenshteinDistance("prawica", "historia", 2) > 2)
    }

    @Test
    fun exactTitleRanksAboveDescriptionOnlyMatch() {
        val descriptionOnly = historyItem(
            videoId = "abcdefghijk",
            title = "Cotygodniowy przegląd",
            creator = "Inny kanał",
            description = "Razem nie jest idealne",
        )
        val exactTitle = historyItem(
            videoId = "lmnopqrstuv",
            title = "Razem nie jest idealne",
            creator = "Ralindel",
        )

        assertEquals(
            exactTitle.videoId,
            rankHistorySearchItems(listOf(descriptionOnly, exactTitle), "Razem nie jest idealne").first().videoId,
        )
    }

    @Test
    fun typoStrategyKeepsCloseCreatorAndRejectsUnrelatedCandidate() {
        val unrelated = historyItem("abcdefghijk", "Inny materiał", "Historia")
        val expected = historyItem("lmnopqrstuv", "Blachosmrodziarze cierpią", "Ralindel")

        val results = rankHistorySearchItems(
            items = listOf(unrelated, expected),
            query = "ralidnel",
            requireTypoMatch = true,
        )

        assertEquals(listOf(expected.videoId), results.map(HistoryItem::videoId))
    }

    @Test
    fun nonFavoriteSearchBindsNumericZeroInsteadOfTextZero() {
        val arguments = historySearchNumericArguments(
            cutoffMillis = 0L,
            favoritesOnly = false,
            limit = 41,
            offset = 0,
        )

        assertEquals(0L, arguments[0])
        assertEquals(0, arguments[1])
        assertEquals(41, arguments[2])
        assertEquals(0, arguments[3])
        assertTrue(arguments[0] is Long)
        assertTrue(arguments[1] is Int)
        assertTrue(arguments[2] is Int)
        assertTrue(arguments[3] is Int)
    }

    @Test
    fun favoritesSearchBindsNumericOne() {
        val arguments = historySearchNumericArguments(
            cutoffMillis = 123L,
            favoritesOnly = true,
            limit = 40,
            offset = 5,
        )

        assertEquals(123L, arguments[0])
        assertEquals(1, arguments[1])
        assertEquals(40, arguments[2])
        assertEquals(5, arguments[3])
    }

    @Test
    fun pendingDescriptionsBindDatesAttemptsAndLimitAsNumbers() {
        val arguments = descriptionPendingNumericArguments(
            cutoffMillis = 1_000L,
            scheduledBeforeMillis = 1_500L,
            maxAttempts = 3,
            retryBeforeMillis = 2_000L,
            limit = 8,
        )

        assertEquals(1_000L, arguments[0])
        assertEquals(1_500L, arguments[1])
        assertEquals(3, arguments[2])
        assertEquals(2_000L, arguments[3])
        assertEquals(8, arguments[4])
        assertTrue(arguments[0] is Long)
        assertTrue(arguments[1] is Long)
        assertTrue(arguments[2] is Int)
        assertTrue(arguments[3] is Long)
        assertTrue(arguments[4] is Int)
    }

    @Test
    fun scheduledMarkerUsesRenewableDescriptionState() {
        assertEquals(3, descriptionStorageState("Zaplanowana transmisja"))
        assertEquals(1, descriptionStorageState("Prawdziwy opis transmisji"))
    }

    private fun historyItem(
        videoId: String,
        title: String,
        creator: String,
        description: String? = null,
    ) = HistoryItem(
        videoId = videoId,
        creatorId = "creator-$videoId",
        creatorName = creator,
        title = title,
        url = "https://www.youtube.com/watch?v=$videoId",
        publishedAtMillis = 1L,
        detectedAtMillis = 1L,
        kind = VideoKind.VIDEO,
        notified = false,
        descriptionSnippet = description,
    )
}
