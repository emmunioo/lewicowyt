package pl.lewicowyt.notifier.community.push

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import org.json.JSONObject

/**
 * Trwała skrzynka budzików odebranych przez WBUDOWANY dystrybutor (LEP). Łączy szybkość obsługi w procesie
 * z trwałością kolejki — budzik nie ginie, gdy proces zostanie zabity w dowolnym momencie:
 *
 *  1. odbiór: [put] zapisuje budzik ATOMOWO (plik tymczasowy → rename) jako `<id>.wake`;
 *  2. dopiero potem [EmbeddedPush] przesuwa kursor LEP (serwer może zapomnieć budzik);
 *  3. obsługa: [claim] przejmuje wpisy atomowym `rename` `.wake → .claim` (dwie równoległe obsługi nigdy nie
 *     wezmą tego samego wpisu), [CommunityWakeProcessor] obsługuje je, [complete] kasuje plik.
 *
 * Wpis `.claim`, którego nie obsługuje żadna korutyna TEGO procesu, został porzucony przez proces zabity w trakcie
 * obsługi — [claim] zwraca go z `recovered = true` i jest obsługiwany ponownie (z pominięciem ochrony przed
 * powtórką, która mogła go już oznaczyć). Ceną jest co najwyżej powtórzone powiadomienie po śmierci procesu
 * w ułamku sekundy między powiadomieniem a [complete] — nigdy zgubiony budzik.
 *
 * `id` = skrót (serwer|budzik): identyczny budzik (powtórka relaya) nie tworzy drugiego wpisu, dopóki pierwszy czeka.
 * Czysta logika na plikach (bez Androida poza [of]) — testowana jednostkowo (PushInboxTest).
 */
internal class PushInbox(private val dir: File, private val clock: () -> Long = System::currentTimeMillis) {

    /** Budzik do obsługi. [recovered] = porzucony przez zabity proces (patrz opis klasy). */
    data class Entry(val id: String, val senderHint: String, val blob: String, val recovered: Boolean)

    private val lock = Any()
    private val inProgress = HashSet<String>()

    /**
     * Zapisuje budzik trwale (fsync + atomowy rename). Zwraca, czy kursor LEP wolno przesunąć dalej:
     *  - true — budzik zapisany ALBO świadomie odrzucony (pusty, za duży, zalew skrzynki): ponowne pobranie
     *    niczego by nie zmieniło, a stojący kursor zablokowałby kolejne budziki;
     *  - false — błąd zapisu (np. brak miejsca): kursor stoi, serwer odda budzik przy następnym odpytaniu.
     */
    fun put(senderHint: String, blob: String): Boolean {
        if (senderHint.isBlank() || blob.isBlank() || blob.length > MAX_BLOB_CHARS) return true
        return runCatching {
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return false
            if (pendingCount() >= MAX_ENTRIES) return true // zalew (zepsuty/złośliwy relay) — nie zapychamy dysku
            val id = idOf(senderHint, blob)
            // ten sam budzik już czeka (id = skrót treści, więc plik jest identyczny) — nic do zapisania
            if (File(dir, "$id$WAKE").exists()) return true
            val tmp = File(dir, "$id$TMP")
            FileOutputStream(tmp).use { out ->
                out.write(JSONObject().put("h", senderHint).put("b", blob).put("t", clock()).toString().toByteArray())
                out.fd.sync()
            }
            tmp.renameTo(File(dir, "$id$WAKE")) || run { tmp.delete(); false }
        }.getOrDefault(false)
    }

    /**
     * Przejmuje do obsługi wszystkie czekające budziki (`.wake`) oraz porzucone przejęcia (`.claim` spoza
     * [inProgress]). Wpisy starsze niż [MAX_AGE_MS] kasuje bez obsługi (budzik sprzed doby nic już nie znaczy).
     */
    fun claim(): List<Entry> = synchronized(lock) {
        val files = dir.listFiles().orEmpty()
        val out = ArrayList<Entry>()
        val now = clock()
        for (f in files.sortedBy { it.lastModified() }) {
            val name = f.name
            when {
                name.endsWith(TMP) -> if (now - f.lastModified() > STALE_TMP_MS) f.delete()
                name.endsWith(WAKE) -> {
                    val id = name.removeSuffix(WAKE)
                    if (id in inProgress) continue // ten sam budzik jest właśnie obsługiwany — weźmiemy go następnym razem
                    val claimed = File(dir, "$id$CLAIM")
                    if (claimed.exists()) continue // porzucone przejęcie tego samego budzika — obsłużymy je niżej
                    if (!f.renameTo(claimed)) continue // przejęte równolegle
                    read(claimed, id, recovered = false, now)?.let { out += it; inProgress += id }
                }
                name.endsWith(CLAIM) -> {
                    val id = name.removeSuffix(CLAIM)
                    if (id in inProgress) continue
                    read(f, id, recovered = true, now)?.let { out += it; inProgress += id }
                }
            }
        }
        out
    }

    /** Budzik obsłużony — usuwa wpis. */
    fun complete(entry: Entry) = synchronized(lock) {
        File(dir, "${entry.id}$CLAIM").delete()
        inProgress -= entry.id
    }

    /** Obsługa przerwana (np. zatrzymanie usługi) — wpis zostaje i zostanie podjęty przy następnym [claim]. */
    fun release(entry: Entry) = synchronized(lock) {
        inProgress -= entry.id
    }

    private fun pendingCount(): Int = dir.list()?.count { it.endsWith(WAKE) || it.endsWith(CLAIM) } ?: 0

    private fun read(file: File, id: String, recovered: Boolean, now: Long): Entry? {
        val json = runCatching { JSONObject(file.readText()) }.getOrNull()
        val hint = json?.optString("h").orEmpty()
        val blob = json?.optString("b").orEmpty()
        val at = json?.optLong("t", 0L) ?: 0L
        if (hint.isBlank() || blob.isBlank() || now - at > MAX_AGE_MS) {
            file.delete() // uszkodzony albo przeterminowany — nie ma czego obsłużyć
            return null
        }
        return Entry(id, hint, blob, recovered)
    }

    companion object {
        private const val WAKE = ".wake"
        private const val CLAIM = ".claim"
        private const val TMP = ".tmp"
        // ten sam limit co plik budzika w CommunityPushWorker (A-30): budzik E2E z miniaturą mieści się z zapasem
        const val MAX_BLOB_CHARS = CommunityPushWorker.MAX_FILE_BLOB_CHARS
        const val MAX_ENTRIES = 500
        const val MAX_AGE_MS = 24L * 60 * 60 * 1000
        private const val STALE_TMP_MS = 60_000L

        @Volatile private var instance: PushInbox? = null

        /** Jedna skrzynka na proces — [inProgress] musi być wspólny dla usługi i workera oszczędnego. */
        fun of(context: Context): PushInbox = instance ?: synchronized(this) {
            instance ?: PushInbox(File(context.applicationContext.noBackupFilesDir, "push_inbox")).also { instance = it }
        }

        internal fun idOf(senderHint: String, blob: String): String =
            MessageDigest.getInstance("SHA-256").digest("$senderHint\n$blob".toByteArray())
                .joinToString("") { "%02x".format(it) }.take(40)
    }
}
