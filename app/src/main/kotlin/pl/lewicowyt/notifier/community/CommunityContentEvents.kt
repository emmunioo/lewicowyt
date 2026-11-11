package pl.lewicowyt.notifier.community

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * sygnał „treść serwera w bazie się zmieniła". Budzik push odświeża serwer w tle (CommunityPushWorker),
 * a otwarta zakładka trzyma listę w pamięci — bez tego nowy post pokazywał się dopiero po restarcie, bo
 * ręczne odświeżenie dostawało 304 (worker zdążył zapisać nowy ETag) i zostawiało starą listę.
 */
object CommunityContentEvents {
    private val _changed = MutableStateFlow<Pair<Long, String?>>(0L to null)

    /** (licznik, serverId ostatniej zmiany) — licznik rośnie przy każdym zapisie treści. */
    val changed: StateFlow<Pair<Long, String?>> = _changed.asStateFlow()

    // D-15: serwery ze zmianą, jeszcze nieprzeładowane — StateFlow łączy szybkie zapisy dwóch serwerów w jedną
    // wartość, więc kolektor zbiera zbiór zamiast polegać na „ostatnim serverId"
    private val pending = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** zabiera (i czyści) zbiór serwerów, których treść zmieniła się od poprzedniego wywołania. */
    fun drainChanged(): Set<String> {
        val out = HashSet<String>()
        val it = pending.iterator()
        while (it.hasNext()) { out += it.next(); it.remove() }
        return out
    }

    fun contentStored(serverId: String) {
        pending += serverId
        _changed.update { (it.first + 1) to serverId } // C-22f: atomowo (równoległe zapisy nie gubią zmian)
    }
}
