package pl.lewicowyt.notifier.community

/**
 * Weryfikacja „serwera głównego": gdy serwer społeczności ZASTĘPUJE serwer domyślny, apka co jakiś czas
 * porównuje to, co serwer deklaruje ([CommunityPrimaryFeed]) z RSS YouTube (ground truth — dokładnie to,
 * czym posługuje się serwer domyślny) oraz z listą wybranych twórców. Wynik to kategoryzowana lista
 * różnic; po kilku kolejnych rozbieżnościach apka ostrzega użytkownika, CO dokładnie się różni.
 */
enum class PrimaryHealthKind {
    SERVER_UNREACHABLE,   // serwer nie odpowiedział
    MISSING_CREATORS,     // twórcy, których chcesz, a serwer ich nie obsługuje
    EXTRA_CREATORS,       // twórcy dorzuceni przez serwer, których nie wybrałeś (możliwe „podszywanie")
    DUPLICATE_CREATORS,   // ten sam kanał zadeklarowany wielokrotnie
    MISSING_VIDEOS,       // filmy widoczne w RSS, których serwer nie zgłosił (opóźnienie/brak)
    EXTRA_VIDEOS,         // filmy zgłoszone przez serwer, których nie ma w RSS kanału (złe/wstrzyknięte)
    DUPLICATE_VIDEOS,     // ten sam film zgłoszony wielokrotnie
    HIGH_LATENCY,         // film zgłoszony, ale znacznie później niż opublikowany w RSS
}

data class PrimaryHealthDifference(val kind: PrimaryHealthKind, val detail: String)

/** stan zdrowia serwera głównego (persystowany, żeby baner przetrwał restart). */
data class CommunityPrimaryHealth(
    val serverId: String,
    val consecutiveFailures: Int,
    val differences: List<PrimaryHealthDifference>,
    val checkedAtMillis: Long,
) {
    /** ostrzegamy dopiero po progu kolejnych rozbieżnych sprawdzeń. */
    fun shouldWarn(threshold: Int): Boolean = differences.isNotEmpty() && consecutiveFailures >= threshold
}

/** film z RSS (ground truth). */
data class RssVideo(val videoId: String, val publishedAtMillis: Long?)

object CommunityPrimaryHealthEvaluator {
    // film w RSS starszy niż to, a niezgłoszony przez serwer = realna rozbieżność (nie chwilowe opóźnienie)
    const val MISSING_AFTER_MS = 6L * 60 * 60 * 1000        // 6 h
    const val RSS_FEED_SIZE = 15                             // A-16: tyle filmów podaje RSS kanału YouTube
    const val SILENT_CHANNEL_WINDOW_MS = 7L * 24 * 60 * 60 * 1000 // A-59
    const val SILENT_CHANNEL_MIN = 2                          // A-59
    // film zgłoszony znacznie później niż opublikowany = wysokie opóźnienie serwera
    const val HIGH_LATENCY_MS = 3L * 60 * 60 * 1000         // 3 h
    private const val MAX_NAMES = 5

    /**
     * Porównuje deklarację serwera z RSS i wybranymi twórcami. `nameOf` mapuje channelId → czytelna nazwa
     * (do komunikatów). Pusty wynik = brak rozbieżności.
     */
    fun evaluate(
        reachable: Boolean,
        feed: CommunityPrimaryFeed?,
        expectedCreators: Set<String>,
        rss: Map<String, List<RssVideo>>,
        nowMs: Long,
        nameOf: (String) -> String = { it },
    ): List<PrimaryHealthDifference> {
        if (!reachable) {
            return listOf(PrimaryHealthDifference(PrimaryHealthKind.SERVER_UNREACHABLE, "Serwer główny nie odpowiedział."))
        }
        feed ?: return emptyList() // serwer nie deklaruje primaryFeed — nie ma czego porównywać
        val out = ArrayList<PrimaryHealthDifference>()

        // — twórcy —
        val declared = feed.creators.toSet()
        val dupCreators = feed.creators.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
        if (dupCreators.isNotEmpty()) {
            out += PrimaryHealthDifference(PrimaryHealthKind.DUPLICATE_CREATORS,
                "Zduplikowani twórcy: ${names(dupCreators, nameOf)}.")
        }
        // E-03: protokół dopuszcza primaryFeed z samymi `videos` — bez listy kanałów nie ma czego porównywać
        // z wybranymi twórcami (wcześniej każdy wybrany wyglądał na „nieobsługiwanego")
        if (expectedCreators.isNotEmpty() && declared.isNotEmpty()) {
            val missing = expectedCreators - declared
            if (missing.isNotEmpty()) out += PrimaryHealthDifference(PrimaryHealthKind.MISSING_CREATORS,
                "Serwer nie obsługuje ${missing.size} wybranych twórców: ${names(missing, nameOf)}.")
            val extra = declared - expectedCreators
            if (extra.isNotEmpty()) out += PrimaryHealthDifference(PrimaryHealthKind.EXTRA_CREATORS,
                "Serwer dorzuca ${extra.size} twórców, których nie wybrałeś: ${names(extra, nameOf)}.")
        }

        // — filmy —
        val dupVideos = feed.videos.groupingBy { it.videoId }.eachCount().filter { it.value > 1 }.keys
        if (dupVideos.isNotEmpty()) {
            out += PrimaryHealthDifference(PrimaryHealthKind.DUPLICATE_VIDEOS,
                "Zduplikowane filmy: ${dupVideos.size} (np. ${dupVideos.take(MAX_NAMES).joinToString(", ")}).")
        }
        val byChannel = feed.videos.groupBy { it.channelId }
        val missingVideos = ArrayList<String>()
        val extraVideos = ArrayList<String>()
        val lateVideos = ArrayList<String>()
        // E-03: bez `creators` porównujemy z RSS kanały, dla których serwer zgłosił filmy
        val compared = declared.ifEmpty { byChannel.keys }
        for (ch in compared) {
            val rssVids = rss[ch] ?: continue // brak RSS dla kanału (np. usunięty) — pomijamy
            val rssById = rssVids.associateBy { it.videoId }
            val serverVids = byChannel[ch].orEmpty()
            val serverIds = serverVids.map { it.videoId }.toSet()
            // R-13: serwer deklaruje OGRANICZONĄ listę najnowszych filmów, nie całe RSS. „Brakujący" to tylko
            // film z OKNA, które serwer realnie pokrywa — nie starszy niż NAJSTARSZY film zgłoszony dla tego
            // kanału (horyzont serwera). Inaczej każdy starszy film z RSS (przy 60 twórcach do ~900) fałszywie
            // alarmowałby. Gdy serwer nie zgłosił dla kanału żadnego datowanego filmu — nie mamy horyzontu i
            // nie wnioskujemy o brakach (brak zgłoszeń może być zgodny z prawdą).
            val serverHorizon = serverVids.mapNotNull { it.publishedAtMillis }.minOrNull()
            // A-59: serwer deklaruje kanał, ale nie zgłasza dla niego ŻADNEGO filmu, choć RSS ma co najmniej
            // [SILENT_CHANNEL_MIN] filmy z ostatniego tygodnia (starsze niż próg braku) — to już nie „ograniczona
            // lista" (R-13), tylko wyciszenie kanału. Pojedynczy film nadal nie alarmuje (test R-13).
            if (serverVids.isEmpty()) {
                val recentMissing = rssVids.filter { rv ->
                    val p = rv.publishedAtMillis ?: return@filter false
                    nowMs - p > MISSING_AFTER_MS && nowMs - p <= SILENT_CHANNEL_WINDOW_MS
                }
                if (recentMissing.size >= SILENT_CHANNEL_MIN) missingVideos += recentMissing.map { it.videoId }
            }
            if (serverHorizon != null) {
                rssVids.forEach { rv ->
                    if (rv.videoId !in serverIds && rv.publishedAtMillis != null &&
                        rv.publishedAtMillis >= serverHorizon &&
                        nowMs - rv.publishedAtMillis > MISSING_AFTER_MS
                    ) missingVideos += rv.videoId
                }
            }
            // nadmiarowe: serwer zgłosił film, którego nie ma w RSS tego kanału.
            // A-16: RSS YouTube ma tylko [RSS_FEED_SIZE] najnowszych filmów — gdy jest pełny, film starszy niż
            // najstarszy z RSS (albo bez daty) jest po prostu SPOZA okna RSS, a nie „spoza kanału".
            val rssHorizon = if (rssVids.size >= RSS_FEED_SIZE) rssVids.mapNotNull { it.publishedAtMillis }.minOrNull() else null
            serverVids.forEach { sv ->
                if (sv.videoId !in rssById) {
                    val sp = sv.publishedAtMillis
                    val outsideRssWindow = rssHorizon != null && (sp == null || sp < rssHorizon)
                    if (!outsideRssWindow) extraVideos += sv.videoId
                } else {
                    val rp = rssById[sv.videoId]?.publishedAtMillis
                    if (rp != null && sv.publishedAtMillis != null && sv.publishedAtMillis - rp > HIGH_LATENCY_MS) {
                        lateVideos += sv.videoId
                    }
                }
            }
        }
        // D-19j: filmy zgłoszone dla kanałów, których serwer NIE zadeklarował w `creators`, wcześniej w ogóle
        // nie były oceniane (pętla szła tylko po zadeklarowanych) — to też filmy „spoza listy" (możliwe wstrzyknięcie)
        // E-03: tylko gdy serwer w ogóle deklaruje kanały — feed z samymi `videos` jest zgodny z protokołem
        val undeclaredVideos = if (declared.isEmpty()) emptyList()
        else feed.videos.filter { it.channelId !in declared }.map { it.videoId }
        if (missingVideos.isNotEmpty()) out += PrimaryHealthDifference(PrimaryHealthKind.MISSING_VIDEOS,
            "Brak ${missingVideos.size} filmów widocznych w RSS (np. ${missingVideos.take(MAX_NAMES).joinToString(", ")}).")
        if (extraVideos.isNotEmpty()) out += PrimaryHealthDifference(PrimaryHealthKind.EXTRA_VIDEOS,
            "Serwer zgłosił ${extraVideos.size} filmów spoza RSS kanału (np. ${extraVideos.take(MAX_NAMES).joinToString(", ")}).")
        if (undeclaredVideos.isNotEmpty()) out += PrimaryHealthDifference(PrimaryHealthKind.EXTRA_VIDEOS,
            "Serwer zgłosił ${undeclaredVideos.size} filmów spoza zadeklarowanych kanałów (np. ${undeclaredVideos.take(MAX_NAMES).joinToString(", ")}).")
        if (lateVideos.isNotEmpty()) out += PrimaryHealthDifference(PrimaryHealthKind.HIGH_LATENCY,
            "${lateVideos.size} filmów zgłoszonych z dużym opóźnieniem.")
        return out
    }

    private fun names(ids: Collection<String>, nameOf: (String) -> String): String {
        val shown = ids.take(MAX_NAMES).joinToString(", ") { nameOf(it) }
        return if (ids.size > MAX_NAMES) "$shown, +${ids.size - MAX_NAMES}" else shown
    }
}
