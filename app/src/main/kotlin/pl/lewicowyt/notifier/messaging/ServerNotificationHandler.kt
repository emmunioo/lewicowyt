package pl.lewicowyt.notifier.messaging

import java.time.LocalTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import pl.lewicowyt.notifier.AppGraph
import pl.lewicowyt.notifier.data.isNotificationEnabledFor
import pl.lewicowyt.notifier.diagnostics.DiagnosticCategory
import pl.lewicowyt.notifier.diagnostics.DiagnosticLevel
import pl.lewicowyt.notifier.diagnostics.DiagnosticLogStore
import pl.lewicowyt.notifier.model.PublishedAtEvidence
import pl.lewicowyt.notifier.model.SourceType
import pl.lewicowyt.notifier.model.VideoEntry
import pl.lewicowyt.notifier.model.VideoKind
import pl.lewicowyt.notifier.model.VideoKindDecision
import pl.lewicowyt.notifier.model.VideoKindEvidence
import pl.lewicowyt.notifier.network.ResolvedSource
import pl.lewicowyt.notifier.network.rssVideoKindDecision
import pl.lewicowyt.notifier.notifications.ActiveHours
import pl.lewicowyt.notifier.notifications.NotificationCandidate
import pl.lewicowyt.notifier.worker.ActiveHoursReleaseWorker

/**
 * obsługa powiadomienia o filmie od serwera głównego (budzik UnifiedPush z polem `video`). serwer śle tylko
 * { channelId, videoId }, resztę robi apka: dopasowuje twórcę, sprawdza ustawienia,
 * (dla playlistowych) przynależność do playlisty, ciągnie metadane z RSS,
 * klasyfikuje rodzaj, buduje powiadomienie i dopisuje materiał do Historii.
 *
 * [handle] to jedna próba. dla twórcy playlistowego robi jedno sprawdzenie
 * playlisty; jeśli filmu jeszcze w niej nie ma, zwraca [HandleResult.RETRY_PLAYLIST],
 * a ponawianie (1×/min do 15 razy) ogarnia [NewVideoWorker] przez WorkManager –
 * więc przeżywa ubicie procesu.
 */
object ServerNotificationHandler {

    enum class HandleResult { DONE, RETRY_PLAYLIST }

    /**
     * Metadane DOSTARCZONE przez serwer główny społeczności w pushu (gdy zastępuje serwer domyślny).
     * Serwer może dosłać komplet (tytuł/czas/rodzaj/miniatura) — wtedy apka NIE dzwoni po YouTube — albo
     * tylko id (`title` == null), a wtedy apka dociąga metadane z RSS, dokładnie jak dla serwera domyślnego.
     * `thumbnail` to gotowa bitmapa (już zdekodowana z JXL/AVIF/…); używamy jej bez pobrania z sieci.
     */
    data class ProvidedVideo(
        val title: String?,
        val publishedAtMillis: Long?,
        val kind: VideoKind?,
        val thumbnail: android.graphics.Bitmap?,
    )

    /** Maksymalna liczba prób sprawdzenia playlisty (1×/min → do 15 minut). */
    const val PLAYLIST_MAX_ATTEMPTS = 15

    private data class PlaylistPush(val creatorId: String, val playlistId: String)

    // kanały „właścicieli playlist": push leci z kanału, ale materiał należy do
    // playlisty twórcy. lustro PLAYLIST_PUSH_CHANNELS z server/seed-channels.js
    // (szczegóły w PLAN_1_9_POWIADOMIENIA_GCP.md §10a)
    private val PLAYLIST_PUSH: Map<String, PlaylistPush> = mapOf(
        "UCgL0f77U3iEPSSU-Mv5yV5g" to PlaylistPush(
            creatorId = "program-polityczny",
            playlistId = "PLtvjdkxi9_FFKd_k5GZwzNLREdxwPC4LN",
        ),
    )

    /** tytuł zapisywany, gdy budzik wyprzedził RSS (naprawia go [TitleRepairWorker]). */
    internal const val PLACEHOLDER_TITLE = "Nowy materiał"

    private const val RSS_LOOKUP_ATTEMPTS = 3
    private val RSS_LOOKUP_DELAY_MILLIS = TimeUnit.SECONDS.toMillis(10)

    suspend fun handle(
        channelId: String,
        videoId: String,
        playlistAttempt: Int,
        provided: ProvidedVideo? = null,
    ): HandleResult = withContext(Dispatchers.IO) {
        // ten sam kontrakt walidacji co brama w FeedMessagingService – defense in
        // depth, bo wejście Workera mogło przetrwać restart. zepsute ID = cichy zrzut
        if (FeedPayload.parse(
                mapOf(FeedPayload.KEY_CHANNEL to channelId, FeedPayload.KEY_VIDEO to videoId),
            ) == null
        ) {
            return@withContext HandleResult.DONE
        }
        val graph = AppGraph
        val settings = graph.preferences.current()

        // Serwer społeczności ZASTĘPUJE domyślny: gdy user wybrał serwer główny, ignorujemy zdarzenia
        // z serwera domyślnego (provided == null), żeby nie dublować powiadomień. Głównym źródłem staje
        // się push serwera społeczności (provided != null). Okresowa weryfikacja RSS działa niezależnie.
        // H-02 (obrona w głąb): ignorujemy zdarzenia domyślne TYLKO gdy wskazany primary jest realnie aktywny
        // (istnieje, włączony, zgoda na aktualny regulamin). Nieistniejący/wyłączony/niezaakceptowany primary
        // traktujemy jak brak — inaczej po usunięciu/wyłączeniu serwera głównego WSZYSTKIE oficjalne
        // powiadomienia o filmach przepadałyby po cichu.
        val primaryId = graph.preferences.currentPrimaryServerId()
        // B-11/B-90: gdy głównym jest sam serwer domyślny, zdarzenia FCM `feed` pochodzą OD NIEGO (awaryjny
        // tor) — nie wolno ich odrzucać jako „zastąpionych” przez serwer główny.
        val primaryActive = primaryId.isNotEmpty() &&
            !pl.lewicowyt.notifier.community.CommunityDefaults.isDefault(primaryId) &&
            graph.communityRepository.contactableServers().any { it.serverId == primaryId }
        if (provided == null && primaryActive) {
            return@withContext HandleResult.DONE
        }

        // 1. dopasuj twórcę/twórców do kanału (OKO.press → program-polityczny).
        //    logika playlistowa dotyczy tylko serwera domyślnego (push z kanału-właściciela playlisty)
        val playlistPush = if (provided == null) PLAYLIST_PUSH[channelId] else null
        val matched = if (playlistPush != null) {
            listOfNotNull(graph.catalog.findById(playlistPush.creatorId))
        } else {
            graph.catalog.creators.filter { creator ->
                creator.sources.any { it.type == SourceType.CHANNEL && it.externalId == channelId }
            }
        }
        val selected = matched.filter { it.id in settings.selectedCreatorIds }
        if (selected.isEmpty()) return@withContext HandleResult.DONE

        // 2. twórca playlistowy: sprawdź przynależność. jak filmu jeszcze nie ma
        //    w playliście – poproś o ponowienie (do 15 razy)
        // E-06: wpis z RSS playlisty (pobieranego i tak przy sprawdzaniu przynależności) daje od razu
        // prawdziwy tytuł — twórca playlistowy nie ma znanego kanału, więc z tytułem zastępczym nie dostałby opisu
        val playlistEntry = playlistPush?.let { playlistEntry(it.playlistId, videoId) }
        if (playlistPush != null && playlistEntry == null) {
            return@withContext if (playlistAttempt < PLAYLIST_MAX_ATTEMPTS - 1) {
                HandleResult.RETRY_PLAYLIST
            } else {
                HandleResult.DONE
            }
        }

        // 3. metadane. Gdy serwer główny dosłał komplet (title != null) — używamy ich wprost, bez YouTube.
        //    Inaczej (serwer domyślny albo główny bez metadanych) — tytuł/data z RSS, rodzaj z klasyfikatora.
        val entry: VideoEntry
        val decision: VideoKindDecision
        val publishedEvidence: PublishedAtEvidence
        var titlePlaceholder = false
        if (provided?.title != null) {
            entry = VideoEntry(
                id = videoId,
                title = provided.title,
                url = "https://www.youtube.com/watch?v=$videoId",
                publishedAtMillis = provided.publishedAtMillis ?: System.currentTimeMillis(),
                author = selected.first().name,
            )
            // D-11: rodzaj bez deklaracji serwera to tylko ZGADNIĘCIE (filtr typów traktuje je jako niepewne —
            // Short bez `k` nie przejdzie, gdy Shorty są wyłączone); data od serwera ma rangę niższą niż RSS,
            // więc dokładna data z RSS ją później poprawi
            decision = provided.kind?.let { VideoKindDecision(it, VideoKindEvidence.PLAYER_METADATA) }
                ?: VideoKindDecision(VideoKind.VIDEO, VideoKindEvidence.DEFAULT_VIDEO_FALLBACK)
            publishedEvidence = if (provided.publishedAtMillis != null) PublishedAtEvidence.WEB_TIMESTAMP else PublishedAtEvidence.UNKNOWN
        } else {
            val fromRss = playlistEntry ?: fetchChannelEntry(channelId, videoId)
            // budzik wyprzedził RSS (zdarza się o minuty) — tytuł zastępczy, uzupełni go TitleRepairWorker
            titlePlaceholder = fromRss == null
            entry = fromRss ?: fallbackEntry(videoId, selected.first().name)
            if (titlePlaceholder) {
                DiagnosticLogStore.event(
                    DiagnosticCategory.HISTORY, DiagnosticLevel.WARNING, "PUSH_RSS_MISS",
                    fields = mapOf("video" to videoId, "channel" to channelId, "attempts" to RSS_LOOKUP_ATTEMPTS),
                    text = "Film z budzika nie pojawił się jeszcze w RSS — zapisano tytuł zastępczy",
                )
            }
            val classified = runCatching { graph.classifier.classify(videoId) }
                .getOrDefault(VideoKind.UNKNOWN)
            decision = if (provided?.kind != null) {
                VideoKindDecision(provided.kind, VideoKindEvidence.PLAYER_METADATA)
            } else if (classified != VideoKind.UNKNOWN) {
                VideoKindDecision(classified, VideoKindEvidence.PLAYER_METADATA)
            } else {
                rssVideoKindDecision(entry)
            }
            // tytuł zastępczy ma datę „teraz", nie z RSS — nie może blokować poprawnej daty z RSS później
            publishedEvidence = if (titlePlaceholder) PublishedAtEvidence.UNKNOWN else PublishedAtEvidence.RSS
        }

        // 4. weź twórcę, który ma włączone powiadomienia tego rodzaju
        val notifyCreator = selected.firstOrNull {
            settings.isNotificationEnabledFor(it.id, decision.kind)
        } ?: return@withContext HandleResult.DONE

        // 5. dopisz do Historii + skrzynki i wyślij powiadomienie (miniaturę ciągnie
        //    NotificationHelper) – ten sam potok co SyncEngine
        val pending = graph.database.upsertVerifiedVideoFromSync(
            creator = notifyCreator,
            entry = entry,
            publishedAtEvidence = publishedEvidence,
            kind = decision.kind,
            evidence = decision.evidence,
            shouldNotify = true,
        )
        // C-07: film już znany i rozpatrzony (np. sync był szybszy niż spóźniony budzik) — bez drugiego powiadomienia
        if (!pending) return@withContext HandleResult.DONE
        graph.database.addNotificationInbox(listOf(entry.id))
        if (titlePlaceholder) TitleRepairWorker.schedule(graph.appContext, channelId, videoId, notifyCreator.id)

        // 6. „Godziny powiadomień": w oknie wysyłamy od razu; poza oknem wpis czeka
        //    (notified=0), a ActiveHoursReleaseWorker dowiezie go po otwarciu okna
        //    (jednym zbiorczym powiadomieniem, R-20)
        val nowMinutes = LocalTime.now().let { it.hour * 60 + it.minute }
        if (ActiveHours.isWithin(settings, nowMinutes)) {
            val delivery = graph.notifications.notifyBatch(
                listOf(NotificationCandidate(notifyCreator, entry, decision.kind, provided?.thumbnail)),
            )
            graph.database.markVideosNotified(delivery.deliveredVideoIds)
        } else {
            ActiveHoursReleaseWorker.schedule(graph.appContext, settings)
        }
        HandleResult.DONE
    }

    /**
     * Uzupełnia tytuł zastępczy („Nowy materiał") z RSS kanału. true = film znaleziony w RSS i zapisany
     * (albo nie ma już czego naprawiać), false = jeszcze go nie ma — spróbujemy później.
     */
    suspend fun repairTitle(channelId: String, videoId: String, creatorId: String, attempt: Int): Boolean =
        withContext(Dispatchers.IO) {
            val creator = AppGraph.catalog.findById(creatorId) ?: return@withContext true
            val entry = fetchChannelEntry(channelId, videoId, attempts = 1)
            if (entry == null) {
                DiagnosticLogStore.event(
                    DiagnosticCategory.HISTORY, DiagnosticLevel.INFO, "TITLE_REPAIR_PENDING",
                    fields = mapOf("video" to videoId, "attempt" to attempt),
                )
                return@withContext false
            }
            // shouldNotify = false: tylko aktualizacja metadanych istniejącego wpisu (tytuł, adres, data z RSS);
            // rodzaj z najsłabszym dowodem nie nadpisze lepszej klasyfikacji
            AppGraph.database.upsertVerifiedVideoFromSync(
                creator = creator,
                entry = entry,
                publishedAtEvidence = PublishedAtEvidence.RSS,
                kind = VideoKind.VIDEO,
                evidence = VideoKindEvidence.DEFAULT_VIDEO_FALLBACK,
                shouldNotify = false,
            )
            // E-06: porażki opisu przy tytule zastępczym nie mogą go zablokować na stałe — po naprawie od nowa
            AppGraph.database.resetDescriptionFailures(videoId)
            DiagnosticLogStore.event(
                DiagnosticCategory.HISTORY, DiagnosticLevel.INFO, "TITLE_REPAIRED",
                fields = mapOf("video" to videoId, "attempt" to attempt),
            )
            true
        }

    private fun playlistEntry(playlistId: String, videoId: String): VideoEntry? {
        val resolved = ResolvedSource(
            sourceKey = "fcm|playlist|$playlistId",
            type = SourceType.PLAYLIST,
            externalId = playlistId,
            feedUrl = "https://www.youtube.com/feeds/videos.xml?playlist_id=$playlistId",
        )
        val entries = runCatching { AppGraph.feedClient.fetch(resolved) }
            .getOrDefault(emptyList())
        return entries.firstOrNull { it.id == videoId }
    }

    private suspend fun fetchChannelEntry(channelId: String, videoId: String, attempts: Int = RSS_LOOKUP_ATTEMPTS): VideoEntry? {
        val resolved = ResolvedSource(
            sourceKey = "fcm|channel|$channelId",
            type = SourceType.CHANNEL,
            externalId = channelId,
            feedUrl = "https://www.youtube.com/feeds/videos.xml?channel_id=$channelId",
        )
        // push serwera potrafi wyprzedzić RSS o kilkanaście sekund, więc dajemy
        // kilka szybkich prób (mieszczą się w oknie Workera)
        repeat(attempts) { attempt ->
            val entries = runCatching { AppGraph.feedClient.fetch(resolved) }
                .getOrDefault(emptyList())
            entries.firstOrNull { it.id == videoId }?.let { return it }
            if (attempt < attempts - 1) delay(RSS_LOOKUP_DELAY_MILLIS)
        }
        return null
    }

    private fun fallbackEntry(videoId: String, authorName: String): VideoEntry = VideoEntry(
        id = videoId,
        title = PLACEHOLDER_TITLE,
        url = "https://www.youtube.com/watch?v=$videoId",
        publishedAtMillis = System.currentTimeMillis(),
        author = authorName,
    )
}
