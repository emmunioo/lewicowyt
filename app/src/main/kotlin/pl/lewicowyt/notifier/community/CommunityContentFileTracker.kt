package pl.lewicowyt.notifier.community

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * Pamięta, które PLIKI TREŚCI (`content` w manifeście) apka już pobrała i scaliła dla danego serwera.
 * Dzięki temu przy kolejnym odświeżeniu pobieramy TYLKO nowe nazwy — model append-only skaluje się do
 * tysięcy plików (serwer dopisuje nazwę nowego pliku, apka dociąga jeden plik). Klucz to nazwa albo
 * nazwa+wersja ([CommunityContentFile.key]) — nowa wersja tej samej nazwy wypiera starą, więc plik z
 * podmienioną treścią zostanie pobrany ponownie. Dla każdego pliku pamiętamy też, KTÓRE pozycje z niego
 * przyszły — żeby nowa wersja pliku (albo plik zdjęty z listy) mogła sprzątnąć swoje stare pozycje.
 * Tylko nazwy plików i identyfikatory pozycji — żadnej treści ani danych o użytkowniku.
 */
interface CommunityContentFileTracker {
    fun ingested(serverId: String): Set<String>
    fun remember(serverId: String, names: Collection<String>)
    fun forget(serverId: String)

    /** A-08: odnotowuje kolejną porażkę pobrania pliku [name]; zwraca łączną liczbę porażek. */
    fun recordFailure(serverId: String, name: String): Int

    /** pozycje (itemId), które ostatnio przyszły z pliku [fileName] (sama nazwa, bez wersji). */
    fun itemsOf(serverId: String, fileName: String): Set<String>
    fun setItems(serverId: String, fileName: String, itemIds: Collection<String>)

    /** nazwy plików, dla których pamiętamy pozycje (do wykrycia plików zdjętych z `content`). */
    fun trackedFiles(serverId: String): Set<String>

    /** zapomina pliki (pozycje + wszystkie wersje w rejestrze pobranych). */
    fun forgetFiles(serverId: String, fileNames: Collection<String>)
}

/** nazwa pliku z klucza rejestru (klucz z wersją to `nazwa\twersja`). */
internal fun contentFileNameOfKey(key: String): String = key.substringBefore('\t')

/** domyślnie w pamięci (do testów bez Androida) — stan per instancja. */
class InMemoryContentFileTracker : CommunityContentFileTracker {
    private val map = ConcurrentHashMap<String, MutableSet<String>>()
    private val items = ConcurrentHashMap<String, Set<String>>()
    override fun ingested(serverId: String): Set<String> = map[serverId]?.toSet().orEmpty()
    override fun remember(serverId: String, names: Collection<String>) {
        if (names.isEmpty()) return
        val set = map.getOrPut(serverId) { ConcurrentHashMap.newKeySet() }
        val replaced = names.map(::contentFileNameOfKey).toSet()
        set.removeAll { contentFileNameOfKey(it) in replaced }
        set.addAll(names)
        // B-05: jak w wersji trwałej — pobrany plik zeruje swój licznik porażek
        names.forEach { failures.remove(serverId + "\n" + it) }
    }
    override fun forget(serverId: String) {
        map.remove(serverId)
        failures.keys.removeAll { it.startsWith(serverId + "\n") }
        items.keys.removeAll { it.startsWith(serverId + "\n") }
    }
    private val failures = ConcurrentHashMap<String, Int>()
    override fun recordFailure(serverId: String, name: String): Int =
        failures.merge(serverId + "\n" + name, 1, Int::plus) ?: 1
    override fun itemsOf(serverId: String, fileName: String): Set<String> = items[serverId + "\n" + fileName].orEmpty()
    override fun setItems(serverId: String, fileName: String, itemIds: Collection<String>) {
        items[serverId + "\n" + fileName] = itemIds.toSet()
    }
    override fun trackedFiles(serverId: String): Set<String> =
        items.keys.filter { it.startsWith(serverId + "\n") }.map { it.substringAfter('\n') }.toSet()
    override fun forgetFiles(serverId: String, fileNames: Collection<String>) {
        val names = fileNames.toSet()
        fileNames.forEach { items.remove(serverId + "\n" + it) }
        map[serverId]?.removeAll { contentFileNameOfKey(it) in names }
        // C-21: plik, który zniknął i wrócił, zaczyna z czystym licznikiem porażek
        failures.keys.removeAll { it.startsWith(serverId + "\n") && contentFileNameOfKey(it.substringAfter('\n')) in names }
    }
}

/** trwały zbiór pobranych nazw plików w SharedPreferences (produkcyjnie), rozdzielony po serwerze. */
class CommunityContentFileStore(context: Context) : CommunityContentFileTracker {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // R-11: trzymamy nazwy jako UPORZĄDKOWANĄ listę (jedna nazwa na linię), a nie StringSet. StringSet w
    // SharedPreferences nie ma kolejności, więc dawny `takeLast(MAX_TRACKED)` wyrzucał PRZYPADKOWE nazwy —
    // „zapomniany" plik był pobierany od nowa i mógł przywrócić wycofaną treść. Lista = deterministyczna
    // ewikcja najstarszych. (Nazwy nie zawierają '\n' — parser je wyklucza jako znak sterujący.)
    private fun orderedNames(serverId: String): List<String> =
        prefs.getString(serverId, null)?.split('\n')?.filter { it.isNotEmpty() }.orEmpty()

    override fun ingested(serverId: String): Set<String> = orderedNames(serverId).toSet()

    // A-08b: odczyt-modyfikacja-zapis pod blokadą (ViewModel i worker potrafią odświeżać równolegle) —
    // inaczej gubiły się nazwy plików (pobieranie od nowa, możliwe wskrzeszenie usuniętej treści).
    @Synchronized
    override fun remember(serverId: String, names: Collection<String>) {
        if (names.isEmpty()) return
        // nowa wersja pliku wypiera starą: inaczej powrót do wcześniejszej wersji nie zostałby pobrany
        val replaced = names.map(::contentFileNameOfKey).toSet()
        val merged = LinkedHashSet(orderedNames(serverId).filterNot { contentFileNameOfKey(it) in replaced })
        merged.addAll(names)
        // twardy limit: usuwamy NAJSTARSZE (początek listy), deterministycznie
        val capped = if (merged.size > MAX_TRACKED) merged.toList().takeLast(MAX_TRACKED) else merged.toList()
        prefs.edit()
            .putString(serverId, capped.joinToString("\n"))
            .apply { names.forEach { remove(failureKey(serverId, it)) } }
            .apply()
    }

    @Synchronized
    override fun forget(serverId: String) {
        val edit = prefs.edit().remove(serverId)
        prefs.all.keys
            .filter { it.startsWith("fail\n$serverId\n") || it.startsWith("items\n$serverId\n") }
            .forEach { edit.remove(it) }
        edit.apply()
    }

    @Synchronized
    override fun recordFailure(serverId: String, name: String): Int {
        val key = failureKey(serverId, name)
        val count = prefs.getInt(key, 0) + 1
        prefs.edit().putInt(key, count).apply()
        return count
    }

    override fun itemsOf(serverId: String, fileName: String): Set<String> =
        prefs.getString(itemsKey(serverId, fileName), null)?.split('\n')?.filter { it.isNotEmpty() }?.toSet().orEmpty()

    @Synchronized
    override fun setItems(serverId: String, fileName: String, itemIds: Collection<String>) {
        prefs.edit().putString(itemsKey(serverId, fileName), itemIds.joinToString("\n")).apply()
    }

    override fun trackedFiles(serverId: String): Set<String> {
        val prefix = "items\n$serverId\n"
        return prefs.all.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.toSet()
    }

    @Synchronized
    override fun forgetFiles(serverId: String, fileNames: Collection<String>) {
        if (fileNames.isEmpty()) return
        val names = fileNames.toSet()
        val edit = prefs.edit()
        names.forEach { edit.remove(itemsKey(serverId, it)) }
        // C-21: liczniki porażek usuniętych plików też (klucz porażki = nazwa\twersja)
        val failPrefix = "fail\n$serverId\n"
        prefs.all.keys.filter { it.startsWith(failPrefix) && contentFileNameOfKey(it.removePrefix(failPrefix)) in names }
            .forEach { edit.remove(it) }
        edit.putString(serverId, orderedNames(serverId).filterNot { contentFileNameOfKey(it) in names }.joinToString("\n"))
        edit.apply()
    }

    private fun failureKey(serverId: String, name: String) = "fail\n$serverId\n$name"
    private fun itemsKey(serverId: String, fileName: String) = "items\n$serverId\n$fileName"

    private companion object {
        const val PREFS = "community_content_files"
        const val MAX_TRACKED = 20_000
    }
}
