package pl.lewicowyt.notifier.i18n

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import org.json.JSONObject

/**
 * Runtime-owe tłumaczenia wgrywane przez użytkownika — bez przebudowy aplikacji.
 *
 * Kluczem tłumaczenia jest ORYGINALNY polski tekst. [tr] zwraca tłumaczenie dla aktywnego
 * pakietu, a gdy go brak — polski oryginał (więc częściowe tłumaczenie też działa). Dzięki temu
 * ktoś (np. Francuz) może wgrać własny plik i podmienić teksty, a my nie musimy nic kompilować.
 *
 * „Polski (domyślny)" nie jest osobno zapisany w kodzie jako plik — powstaje przez EKSPORT
 * szablonu: zrzucamy wszystkie polskie napisy, które przeszły przez [tr] (rejestrowane w [seen]),
 * jako mapę „polski → polski" gotową do przetłumaczenia. To najprostsza baza dla tłumaczy.
 *
 * Format pliku (JSON, edytowalny ręcznie):
 * {
 *   "language": "Français", "code": "fr", "author": "…",
 *   "strings": { "Ustawienia": "Paramètres", "Społeczność": "Communauté", … }
 * }
 */
object Translations {
    private const val DIR = "translations"
    private const val ACTIVE_FILE = "active.json"
    private const val SEEN_FILE = "seen.json"
    private const val MAX_SEEN_STRINGS = 5_000
    private const val MAX_ENTRIES = 20_000
    private const val MAX_VALUE_CHARS = 20_000
    private const val MAX_FILE_BYTES = 8L * 1024 * 1024
    private const val MAX_GENDER_CACHE = 2_000

    // wybrane zaimki użytkownika — wpływają na formy rodzajowe w tekstach (token {g:on|ona|ono}).
    // Compose: zmiana odświeża UI. Domyślnie „on" (zgodnie z dotychczasowymi formami męskimi).
    var pronoun by mutableStateOf(Pronoun.ON)

    // token form rodzajowych: {g:wariant_on|wariant_ona|wariant_ono}. Tłumacze zachowują token i wpisują
    // 3 warianty w swoim języku; gdy wariantów mniej niż wybrany zaimek — bierzemy ostatni (fallback).
    private val GENDER = Regex("""\{g:(.*?)\}""")

    /** podstawia formy rodzajowe wg wybranego zaimka; teksty bez tokenu zostają bez zmian. */
    fun applyGender(text: String, p: Pronoun): String {
        if (!text.contains("{g:")) return text
        return GENDER.replace(text) { m ->
            val parts = m.groupValues[1].split('|')
            parts.getOrElse(p.ordinal) { parts.last() }
        }
    }

    // stan czytany przez [tr] w Compose → zmiana nadpisań odświeża UI
    private var overrides by mutableStateOf<Map<String, String>>(emptyMap())
    var languageLabel by mutableStateOf(DEFAULT_LABEL)
        private set
    var active by mutableStateOf(false)
        private set

    // wszystkie polskie napisy, które przewinęły się przez tr() – baza do eksportu szablonu
    // T-10: zbiór bez blokady — tr() woła się przy KAŻDYM przerysowaniu każdego tekstu, a synchronizowany zbiór
    // brał blokadę za każdym razem (eksport i tak sortuje, więc kolejność wstawiania nie ma znaczenia)
    private val seen: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    // T-10: wynik podstawienia form rodzajowych per zaimek — wyrażenie regularne liczymy raz na tekst, nie przy
    // każdym przerysowaniu. Klucz = tekst PO tłumaczeniu, więc zmiana tłumaczenia nie zwróci starej formy.
    private val genderCache = Array(Pronoun.entries.size) { java.util.concurrent.ConcurrentHashMap<String, String>() }

    fun init(context: Context) {
        loadSeen(context)
        val file = activeFile(context)
        if (file.isFile) {
            runCatching { parse(file.readText(Charsets.UTF_8)) }.getOrNull()?.let { apply(it) }
        }
    }

    /** tłumaczy (lub zwraca polski oryginał), po czym podstawia formy rodzajowe; rejestruje źródło do szablonu. */
    fun tr(source: String): String {
        // A-45: rejestr źródeł do szablonu tłumaczeń jest ograniczony — tr() dostaje też napisy dynamiczne
        // (z liczbami, nazwami), więc bez limitu zbiór rósł w pamięci bez końca.
        if (seen.size < MAX_SEEN_STRINGS) seen.add(source)
        // odczyt obu stanów zostaje tu (Compose śledzi je i przerysuje tekst po zmianie zaimka/tłumaczenia)
        val p = pronoun
        val text = overrides[source] ?: source
        if (!text.contains("{g:")) return text
        val cache = genderCache[p.ordinal]
        cache[text]?.let { return it }
        if (cache.size >= MAX_GENDER_CACHE) cache.clear()
        return applyGender(text, p).also { cache[text] = it }
    }

    /** wgranie pliku tłumaczenia wybranego przez użytkownika (SAF Uri). */
    fun importFrom(context: Context, uri: Uri): Result<Int> = runCatching {
        // L-9: czytamy Z LIMITEM — nie wczytujemy całego pliku do RAM przed sprawdzeniem rozmiaru (OOM przy
        // ogromnym pliku). Przerywamy, gdy przekroczy MAX_FILE_BYTES.
        val bytes = context.contentResolver.openInputStream(uri)?.use { readBounded(it, MAX_FILE_BYTES) }
            ?: error("Nie można otworzyć pliku")
        val pkg = parse(bytes.toString(Charsets.UTF_8))
        require(pkg.strings.isNotEmpty()) { "Plik nie zawiera żadnych tłumaczeń (pole \"strings\")" }
        val dir = dir(context).apply { mkdirs() }
        File(dir, ACTIVE_FILE).writeText(serialize(pkg), Charsets.UTF_8)
        apply(pkg)
        pkg.strings.size
    }

    private fun readBounded(input: java.io.InputStream, max: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            require(total <= max) { "Plik za duży (max 8 MB)" }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** powrót do polskiego (usunięcie nadpisań). */
    fun clear(context: Context) {
        activeFile(context).delete()
        overrides = emptyMap()
        languageLabel = DEFAULT_LABEL
        active = false
    }

    /**
     * eksport szablonu do tłumaczenia: mapa „polski → polski" ze wszystkich napisów, które do tej
     * pory przeszły przez tr(). Im więcej ekranów user odwiedził, tym pełniejszy szablon.
     */
    fun exportTemplate(context: Context, uri: Uri): Result<Int> = runCatching {
        val strings = LinkedHashMap<String, String>()
        val sorted = synchronized(seen) { seen.toList() }.sorted()
        for (s in sorted) strings[s] = s
        val pkg = TranslationPackage(language = "Polski", code = "pl", author = "", strings = strings)
        context.contentResolver.openOutputStream(uri, "wt")?.use {
            it.write(serialize(pkg).toByteArray(Charsets.UTF_8))
        } ?: error("Nie można zapisać pliku")
        persistSeen(context)
        strings.size
    }

    fun knownStringCount(): Int = seen.size

    // --- wewnętrzne ---
    private fun apply(pkg: TranslationPackage) {
        overrides = pkg.strings
        active = pkg.strings.isNotEmpty()
        languageLabel = pkg.language.ifBlank { if (active) "Tłumaczenie użytkownika" else DEFAULT_LABEL }
    }

    private fun parse(json: String): TranslationPackage {
        val root = JSONObject(json)
        val stringsObj = root.optJSONObject("strings") ?: JSONObject()
        val map = LinkedHashMap<String, String>()
        val keys = stringsObj.keys()
        while (keys.hasNext() && map.size < MAX_ENTRIES) {
            val k = keys.next()
            val v = stringsObj.optString(k)
            if (k.isNotEmpty() && v.isNotEmpty()) map[k] = v.take(MAX_VALUE_CHARS)
        }
        return TranslationPackage(
            language = root.optString("language").trim().take(120),
            code = root.optString("code").trim().take(16),
            author = root.optString("author").trim().take(200),
            strings = map,
        )
    }

    private fun serialize(pkg: TranslationPackage): String {
        val root = JSONObject()
        root.put("language", pkg.language)
        root.put("code", pkg.code)
        root.put("author", pkg.author)
        val strings = JSONObject()
        for ((k, v) in pkg.strings) strings.put(k, v)
        root.put("strings", strings)
        return root.toString(2)
    }

    private fun loadSeen(context: Context) {
        val f = File(dir(context), SEEN_FILE)
        if (!f.isFile) return
        runCatching {
            val arr = JSONObject(f.readText(Charsets.UTF_8)).optJSONArray("seen") ?: return
            for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotEmpty() }?.let { seen.add(it) }
        }
    }

    private fun persistSeen(context: Context) {
        runCatching {
            val arr = org.json.JSONArray()
            synchronized(seen) { seen.forEach { arr.put(it) } }
            File(dir(context).apply { mkdirs() }, SEEN_FILE)
                .writeText(JSONObject().put("seen", arr).toString(), Charsets.UTF_8)
        }
    }

    private fun dir(context: Context) = File(context.applicationContext.filesDir, DIR)
    private fun activeFile(context: Context) = File(dir(context), ACTIVE_FILE)

    private const val DEFAULT_LABEL = "Polski (domyślny)"
}

/** zaimki użytkownika: on (męskie), ona (żeńskie), ono (nijakie). Kolejność = indeks w tokenie {g:…|…|…}. */
enum class Pronoun(val label: String) {
    ON("on"),
    ONA("ona"),
    ONO("ono");

    companion object {
        fun fromRaw(raw: String?): Pronoun =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: ON
    }
}

data class TranslationPackage(
    val language: String,
    val code: String,
    val author: String,
    val strings: Map<String, String>,
)

/** skrót do użycia w Compose: `Text(tr("Ustawienia"))`. Odświeża się po zmianie tłumaczenia. */
@Composable
fun tr(source: String): String = Translations.tr(source)
