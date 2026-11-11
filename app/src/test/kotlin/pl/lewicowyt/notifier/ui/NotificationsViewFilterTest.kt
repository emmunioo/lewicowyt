package pl.lewicowyt.notifier.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import pl.lewicowyt.notifier.data.AppSettings
import pl.lewicowyt.notifier.model.HistoryFilter
import pl.lewicowyt.notifier.model.HistoryItem
import pl.lewicowyt.notifier.model.VideoKind

/** widok „🔔 Powiadomienia" połączonej zakładki Historia: te same filtry co Historia, bez zakresu czasu. */
class NotificationsViewFilterTest {
    private fun item(id: String, title: String, kind: VideoKind = VideoKind.VIDEO, favorite: Boolean = false) =
        HistoryItem(
            videoId = id,
            creatorId = "c",
            creatorName = "Ralindel",
            title = title,
            url = "https://www.youtube.com/watch?v=$id",
            publishedAtMillis = 1L, // bardzo stary — zakres czasu Historii nie może go chować
            detectedAtMillis = 1L,
            kind = kind,
            notified = true,
            isFavorite = favorite,
        )

    private val all = setOf(HistoryFilter.VIDEOS, HistoryFilter.STREAMS, HistoryFilter.SHORTS)

    private fun state(
        notifications: List<HistoryItem>,
        query: String = "",
        favoritesOnly: Boolean = false,
        filters: Set<HistoryFilter> = all,
        privateIds: Set<String> = emptySet(),
    ) = AppUiState(
        settings = AppSettings(historyFilters = filters, globalHistoryTypes = all),
        notifications = notifications,
        historySearchQuery = query,
        favoritesOnly = favoritesOnly,
        privateVideoIds = privateIds,
    )

    @Test
    fun `old notifications stay visible regardless of history time range`() {
        val list = listOf(item("aaaaaaaaaaa", "Film"))
        assertEquals(list, notificationsForView(state(list)))
    }

    @Test
    fun `type filters, favorites and search apply like in history`() {
        val film = item("aaaaaaaaaaa", "Prawica i lewica")
        val short = item("bbbbbbbbbbb", "Krótko", kind = VideoKind.SHORT, favorite = true)
        val list = listOf(film, short)
        assertEquals(listOf(film), notificationsForView(state(list, filters = setOf(HistoryFilter.VIDEOS))))
        assertEquals(listOf(short), notificationsForView(state(list, favoritesOnly = true)))
        assertEquals(listOf(film), notificationsForView(state(list, query = "LEWICA")))
    }

    @Test
    fun `private streams are hidden from search results only`() {
        val film = item("aaaaaaaaaaa", "Transmisja")
        assertEquals(listOf(film), notificationsForView(state(listOf(film), privateIds = setOf(film.videoId))))
        assertEquals(
            emptyList<HistoryItem>(),
            notificationsForView(state(listOf(film), query = "trans", privateIds = setOf(film.videoId))),
        )
    }
}
