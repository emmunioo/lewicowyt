package pl.lewicowyt.notifier.data

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import androidx.sqlite.SQLiteStatement
import java.io.File
import pl.lewicowyt.notifier.AppLog
import pl.lewicowyt.notifier.community.CommunityAuthorRecord
import pl.lewicowyt.notifier.community.CommunityConsentRecord
import pl.lewicowyt.notifier.community.CommunitySection
import pl.lewicowyt.notifier.community.CommunityServerCodec
import pl.lewicowyt.notifier.community.CommunityServerRecord
import pl.lewicowyt.notifier.community.CommunityStoredContent
import pl.lewicowyt.notifier.community.CommunityStoredItem
import pl.lewicowyt.notifier.community.CommunityTransportKind
import pl.lewicowyt.notifier.diagnostics.DiagnosticCategory
import pl.lewicowyt.notifier.diagnostics.DiagnosticLevel
import pl.lewicowyt.notifier.diagnostics.DiagnosticLogStore
import pl.lewicowyt.notifier.diagnostics.DiagnosticReasonCode
import pl.lewicowyt.notifier.model.Creator
import pl.lewicowyt.notifier.model.ConfirmedOlderMaterial
import pl.lewicowyt.notifier.model.DescriptionAvailability
import pl.lewicowyt.notifier.model.HistoryItem
import pl.lewicowyt.notifier.model.MEMBERS_ONLY_DESCRIPTION_MARKER
import pl.lewicowyt.notifier.model.PublishedAtEvidence
import pl.lewicowyt.notifier.model.SCHEDULED_STREAM_DESCRIPTION_MARKER
import pl.lewicowyt.notifier.model.VideoEntry
import pl.lewicowyt.notifier.model.VideoKind
import pl.lewicowyt.notifier.model.VideoKindDecision
import pl.lewicowyt.notifier.model.VideoKindEvidence
import pl.lewicowyt.notifier.model.VideoOrigin
import pl.lewicowyt.notifier.model.chooseVideoKindDecision
import pl.lewicowyt.notifier.network.YouTubeHistoryItem

data class NotificationCursor(
    val videoId: String,
    val publishedAtMillis: Long,
)

data class RssNotificationSnapshot(
    val knownVideoIds: List<String>,
)

data class CreatorAvatarMetadata(
    val url: String?,
    val sha256: String?,
    val checkedAtMillis: Long,
    val lastAttemptAtMillis: Long,
)

data class PendingDescription(
    val videoId: String,
    val creatorId: String,
    val title: String,
    val kind: VideoKind,
)

internal data class DescriptionStorageResult(
    val saved: Boolean,
    val originalBytes: Int,
    val storedBytes: Int,
    val codec: StoredDescriptionCodec,
    val compressionMethod: String,
    val dictionaryId: String?,
    val dictionaryVersion: Int?,
)

internal data class DatabaseDiagnosticState(
    val engine: String = "BUNDLED_SQLITE",
    val sqliteVersion: String,
    val userVersion: Int,
    val appSchemaVersion: Int,
    val journalMode: String,
    val fts5Available: Boolean,
)

private data class StoredVideoEvidence(
    val publishedEvidenceRank: Int,
    val kindDecision: VideoKindDecision,
)

internal fun shouldReplacePublishedAt(
    existingEvidenceRank: Int?,
    incomingEvidence: PublishedAtEvidence,
): Boolean = existingEvidenceRank == null ||
    incomingEvidence.rank > existingEvidenceRank ||
    (
        incomingEvidence.rank == existingEvidenceRank &&
            incomingEvidence.canRefreshAtSameRank
        )

class LocalDatabase(
    context: Context,
    databaseName: String = DATABASE_NAME,
) {
    private val databaseFile = context.getDatabasePath(databaseName)

    // T-04: konstruktor nie dotyka dysku. Plik bazy jest otwierany, migrowany i sprawdzany przy PIERWSZYM użyciu
    // (pod blokadą lazy), a [warmUp] robi to w tle zaraz po starcie — dzięki temu migracja po aktualizacji nie
    // blokuje wątku głównego w `Application.onCreate` (ANR) ani odbiorników budzonych w tle.
    internal val writableDatabase: BundledDatabase by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        openRecovering(databaseFile).also { createOrMigrate(it) }
    }
    internal val readableDatabase: BundledDatabase
        get() = writableDatabase

    /** Otwiera bazę (i wykonuje migrację) — wołać z wątku roboczego zaraz po starcie procesu. */
    fun warmUp() {
        writableDatabase
    }

    private fun createOrMigrate(db: BundledDatabase) {
        val oldVersion = db.rawQuery("PRAGMA user_version", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
        // M-12: po [openRecovering] nie powinno być już bazy nowszej niż kod — gdyby jednak była (nieoczekiwane),
        // nie wywracamy startu aplikacji, tylko traktujemy jak brak znanego schematu (bez migracji).
        if (oldVersion == 0) {
            db.beginTransaction()
            try {
                onCreate(db)
                db.execSQL("PRAGMA user_version = $DATABASE_VERSION")
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } else if (oldVersion < DATABASE_VERSION) {
            db.beginTransaction()
            try {
                onUpgrade(db, oldVersion, DATABASE_VERSION)
                db.execSQL("PRAGMA user_version = $DATABASE_VERSION")
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        runCatching { ensureVideoHistoryFtsCoverage(db) }
            .onFailure { error ->
                AppLog.warning(
                    "DatabaseFTS",
                    "Nie udało się sprawdzić pokrycia indeksu FTS; dostępny jest fallback SQL",
                    error,
                )
            }
    }

    private fun onCreate(db: BundledDatabase) {
        createSourceStateTable(db)
        createVideoHistoryTable(db)
        createStreamPrivacyTable(db)
        createVideoHistoryFts(db)
        createCreatorMetadataTable(db)
        createNotificationInboxTable(db)
        createNotificationIdsTable(db)
        createSourcePriorityTable(db)
        createYouTubeChannelTabsTable(db)
        createCommunityServersTable(db)
        createCommunityConsentsTable(db)
        createCommunityAuthorsTable(db)
        createCommunityItemsTable(db)
        createCommunityImageKindTable(db)
    }

    private fun onUpgrade(db: BundledDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createCreatorMetadataTable(db)
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_video_history_published " +
                    "ON video_history(published_ms DESC)",
            )
        }
        if (oldVersion < 3) {
            db.execSQL(
                "ALTER TABLE video_history ADD COLUMN " +
                    "classification_version INTEGER NOT NULL DEFAULT 0",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_video_history_classification " +
                    "ON video_history(classification_version, published_ms DESC)",
            )
        }
        if (oldVersion < 4) {
            createNotificationInboxTable(db)
        }
        if (oldVersion < 5) {
            db.execSQL(
                "ALTER TABLE video_history ADD COLUMN " +
                    "origin TEXT NOT NULL DEFAULT '${VideoOrigin.YOUTUBE.name}'",
            )
        }
        if (oldVersion < 6) {
            db.execSQL(
                "ALTER TABLE source_state ADD COLUMN last_notification_video_id TEXT",
            )
            db.execSQL(
                "ALTER TABLE source_state ADD COLUMN " +
                "last_notification_published_ms INTEGER NOT NULL DEFAULT 0",
            )
        }
        if (oldVersion < 7) {
            createNotificationIdsTable(db)
        }
        if (oldVersion < 8) {
            db.execSQL(
                "ALTER TABLE video_history ADD COLUMN " +
                    "notification_checked INTEGER NOT NULL DEFAULT 1",
            )
        }
        if (oldVersion < 9) {
            db.execSQL(
                "ALTER TABLE video_history ADD COLUMN " +
                    "classification_attempts INTEGER NOT NULL DEFAULT 0",
            )
            db.execSQL(
                "ALTER TABLE video_history ADD COLUMN " +
                "classification_last_attempt_ms INTEGER NOT NULL DEFAULT 0",
            )
        }
        if (oldVersion < 10) {
            // RSS-first = tylko YouTube. wywalamy resztki starej integracji,
            // RSS/API i tak pobierze je ponownie
            db.delete(
                "video_history",
                "origin <> ?",
                arrayOf(VideoOrigin.YOUTUBE.name),
            )
        }
        if (oldVersion < 11) {
            // stary resolver HTML łapał czasem ID kanału z polecanych zamiast z
            // otwartej strony. historia nie trzyma channel ID przy filmie, więc nie
            // odróżnimy zatrutych wierszy – jednorazowy reset z katalogu
            db.delete("notification_inbox", null, null)
            db.delete("notification_ids", null, null)
            db.delete("video_history", null, null)
            db.delete("source_state", null, null)
        }
        if (oldVersion < 12) {
            // stary klasyfikator nie znał aktualnego znacznika Shortów – ponawiamy te,
            // które po kilku błędach spadły awaryjnie na zwykły film
            db.execSQL(
                """
                UPDATE video_history
                SET kind = ?,
                    classification_version = 0,
                    classification_attempts = 0,
                    classification_last_attempt_ms = 0
                WHERE classification_attempts >= ?
                """.trimIndent(),
                arrayOf<Any>(VideoKind.UNKNOWN.name, MAX_CLASSIFICATION_ATTEMPTS),
            )
        }
        // UWAGA: migracje 13/14 w starych wydaniach zerowały `kind`. przy skoku ze
        // starej bazy prosto na bieżącą NIE wolno tego powtarzać – niepewna
        // reklasyfikacja nie może wywalać streamów z UI.
        if (oldVersion < 15) {
            // kto odpalił felerną migrację 14 – reklasyfikacja, ale trzymamy obecny
            // typ dopóki YouTube nie da jednoznacznej odpowiedzi
            db.execSQL(
                """
                UPDATE video_history
                SET classification_version = 0,
                    classification_attempts = 0,
                    classification_last_attempt_ms = 0
                """.trimIndent(),
            )
        }
        if (oldVersion < 16) {
            // stara ścieżka Data API brała każdą skończoną Premierę za archiwalny
            // stream, a wszystko do 180 s za Short. typ zostaje widoczny, ale nowy
            // klasyfikator może poprawić te wiersze w miejscu
            db.execSQL(
                """
                UPDATE video_history
                SET classification_version = 0,
                    classification_attempts = 0,
                    classification_last_attempt_ms = 0
                WHERE kind IN (?, ?)
                """.trimIndent(),
                arrayOf<Any>(
                    VideoKind.STREAM_ARCHIVE.name,
                    VideoKind.SHORT.name,
                ),
            )
        }
        if (oldVersion < 17) {
            db.execSQL(
                "ALTER TABLE video_history ADD COLUMN " +
                    "kind_evidence INTEGER NOT NULL DEFAULT 0",
            )
            // stare klasyfikatory myliły typ w obie strony. historii nie ruszamy,
            // ale kolejkujemy wszystko do potwierdzenia nowym mechanizmem dowodów
            db.execSQL(
                """
                UPDATE video_history
                SET classification_version = 0,
                    kind_evidence = 0,
                    classification_attempts = 0,
                    classification_last_attempt_ms = 0
                """.trimIndent(),
            )
        }
        if (oldVersion < 18) {
            createSourcePriorityTable(db)
        }
        if (oldVersion < 19) {
            db.execSQL(
                "ALTER TABLE video_history ADD COLUMN " +
                    "published_evidence INTEGER NOT NULL DEFAULT 0",
            )
        }
        if (oldVersion < 20) {
            createYouTubeChannelTabsTable(db)
            // nierozpoznane pokazujemy od razu jako zwykły film; pewniejsza karta
            // kanału może to później zmienić
            db.execSQL(
                """
                UPDATE video_history
                SET kind = ?,
                    kind_evidence = ?,
                    classification_version = ?
                WHERE kind = ?
                """.trimIndent(),
                arrayOf<Any>(
                    VideoKind.VIDEO.name,
                    VideoKindEvidence.DEFAULT_VIDEO_FALLBACK.rank,
                    0,
                    VideoKind.UNKNOWN.name,
                ),
            )
        }
        if (oldVersion < 21) {
            db.execSQL("ALTER TABLE creator_metadata ADD COLUMN avatar_sha256 TEXT")
            db.execSQL(
                "ALTER TABLE creator_metadata ADD COLUMN " +
                    "avatar_checked_ms INTEGER NOT NULL DEFAULT 0",
            )
            db.execSQL(
                "ALTER TABLE creator_metadata ADD COLUMN " +
                    "avatar_attempt_ms INTEGER NOT NULL DEFAULT 0",
            )
        }
        if (oldVersion < 22) {
            db.execSQL("ALTER TABLE source_state ADD COLUMN rss_known_video_ids TEXT")
        }
        if (oldVersion < 23) {
            db.execSQL(
                "ALTER TABLE video_history ADD COLUMN " +
                    "is_favorite INTEGER NOT NULL DEFAULT 0",
            )
            db.execSQL("ALTER TABLE video_history ADD COLUMN favorited_ms INTEGER")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_video_history_favorite " +
                    "ON video_history(is_favorite, published_ms DESC)",
            )
        }
        if (oldVersion < 24) {
            // przed 1.6 wycofaliśmy przedwczesny FTS5 i nieskompresowaną kolumnę opisu.
            // testowe bazy schematu 23 mogły je już mieć – sprzątamy jawnie.
            // docelowy Zstd BLOB + contentless FTS5 dopiero w 1.7
            db.execSQL("DROP TRIGGER IF EXISTS video_history_fts_insert")
            db.execSQL("DROP TRIGGER IF EXISTS video_history_fts_delete")
            db.execSQL("DROP TRIGGER IF EXISTS video_history_fts_update")
            db.execSQL("DROP TABLE IF EXISTS video_history_fts")
            if (hasColumn(db, "video_history", "description")) {
                db.execSQL("ALTER TABLE video_history DROP COLUMN description")
            }
        }
        if (oldVersion < 25) {
            db.execSQL("ALTER TABLE video_history ADD COLUMN description_data BLOB")
            db.execSQL(
                "ALTER TABLE video_history ADD COLUMN description_codec INTEGER NOT NULL DEFAULT 0",
            )
            db.execSQL("ALTER TABLE video_history ADD COLUMN description_dictionary_id TEXT")
            db.execSQL("ALTER TABLE video_history ADD COLUMN description_dictionary_version INTEGER")
            db.execSQL(
                "ALTER TABLE video_history ADD COLUMN description_original_size INTEGER NOT NULL DEFAULT 0",
            )
            db.execSQL(
                "ALTER TABLE video_history ADD COLUMN description_state INTEGER NOT NULL DEFAULT 0",
            )
            db.execSQL(
                "ALTER TABLE video_history ADD COLUMN description_attempts INTEGER NOT NULL DEFAULT 0",
            )
            db.execSQL(
                "ALTER TABLE video_history ADD COLUMN description_last_attempt_ms INTEGER NOT NULL DEFAULT 0",
            )
            createVideoHistoryFts(db)
            db.execSQL(
                """
                INSERT INTO video_history_fts(video_id, title, creator_name, description)
                SELECT video_id,
                       replace(replace(title, 'Ł', 'L'), 'ł', 'l'),
                       replace(replace(creator_name, 'Ł', 'L'), 'ł', 'l'),
                       ''
                FROM video_history
                """.trimIndent(),
            )
        }
        if (oldVersion < 26) {
            // 1.8-beta (#13): description_availability trzyma 4-stanowy marker
            // (NONE/DOWNLOADED/MEMBERS_ONLY/SCHEDULED_STREAM) liczony RAZ przy
            // zapisie, żeby nie dekompresować Zstd przy każdym odczycie Historii.
            // backfill w czystym SQL – markery są krótkie i w UTF-8, więc łapiemy je
            // przez CAST(description_data AS TEXT) bez dekompresji. 0..3 to stałe
            // mapowanie DescriptionAvailability (patrz descriptionAvailabilityDatabaseValue),
            // niezależne od .ordinal
            db.execSQL(
                "ALTER TABLE video_history ADD COLUMN " +
                    "description_availability INTEGER NOT NULL DEFAULT 0",
            )
            db.execSQL(
                """
                UPDATE video_history
                SET description_availability = CASE
                    WHEN description_data IS NULL THEN 0
                    WHEN description_codec = ? AND CAST(description_data AS TEXT) = ? THEN 2
                    WHEN description_codec = ? AND CAST(description_data AS TEXT) = ? THEN 3
                    ELSE 1
                END
                """.trimIndent(),
                arrayOf<Any>(
                    StoredDescriptionCodec.UTF8.databaseValue,
                    MEMBERS_ONLY_DESCRIPTION_MARKER,
                    StoredDescriptionCodec.UTF8.databaseValue,
                    SCHEDULED_STREAM_DESCRIPTION_MARKER,
                ),
            )
        }
        if (oldVersion < 27) {
            // 1.9: warstwa serwerów społeczności. czysto addytywne – żadnych danych
            // z istniejących tabel nie ruszamy.
            createCommunityServersTable(db)
            createCommunityConsentsTable(db)
        }
        if (oldVersion < 28) {
            // ustawienia powiadomień per serwer + flaga "może być główny". guard przez
            // hasColumn, bo baza tworzona świeżo w bloku <27 ma już te kolumny.
            if (!hasColumn(db, "community_servers", "notify_enabled")) {
                db.execSQL(
                    "ALTER TABLE community_servers ADD COLUMN " +
                        "notify_enabled INTEGER NOT NULL DEFAULT 1",
                )
            }
            if (!hasColumn(db, "community_servers", "muted_platforms")) {
                db.execSQL("ALTER TABLE community_servers ADD COLUMN muted_platforms TEXT")
            }
            if (!hasColumn(db, "community_servers", "can_be_primary")) {
                db.execSQL(
                    "ALTER TABLE community_servers ADD COLUMN " +
                        "can_be_primary INTEGER NOT NULL DEFAULT 0",
                )
            }
        }
        if (oldVersion < 29) {
            // widoczność reklam per serwer (domyślnie włączone)
            if (!hasColumn(db, "community_servers", "ads_enabled")) {
                db.execSQL(
                    "ALTER TABLE community_servers ADD COLUMN " +
                        "ads_enabled INTEGER NOT NULL DEFAULT 1",
                )
            }
        }
        if (oldVersion < 30) {
            // druga oś: wyciszenia WYŚWIETLANIA w zakładce (muted_platforms = powiadomienia)
            if (!hasColumn(db, "community_servers", "display_muted")) {
                db.execSQL("ALTER TABLE community_servers ADD COLUMN display_muted TEXT")
            }
        }
        if (oldVersion < 31) {
            // trwały, wspólny magazyn treści: słownik autorów + pozycje ze ściśniętym ZSTD tekstem.
            // czysto addytywne; przy pierwszym odświeżeniu serwery same zapełnią te tabele.
            createCommunityAuthorsTable(db)
            createCommunityItemsTable(db)
        }
        if (oldVersion < 32) {
            // obraz jako osobne kolumny (osobna retencja tekstu/obrazu) + flaga ulubionych
            if (!hasColumn(db, "community_items", "image_url")) {
                db.execSQL("ALTER TABLE community_items ADD COLUMN image_url TEXT")
            }
            if (!hasColumn(db, "community_items", "notify_image_url")) {
                db.execSQL("ALTER TABLE community_items ADD COLUMN notify_image_url TEXT")
            }
            if (!hasColumn(db, "community_items", "image_quality")) {
                db.execSQL("ALTER TABLE community_items ADD COLUMN image_quality INTEGER")
            }
            if (!hasColumn(db, "community_items", "favorite")) {
                db.execSQL("ALTER TABLE community_items ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0")
            }
        }
        if (oldVersion < 33) {
            // wykrywanie streamów, które stały się prywatne — osobna tabela, nie rusza odczytów historii
            createStreamPrivacyTable(db)
        }
        if (oldVersion < 34) {
            // #10: walidatory warunkowego GET-a (ETag / Last-Modified) zapamiętane per serwer, oddawane
            // przy kolejnym pobraniu manifestu (If-None-Match / If-Modified-Since). Serwer może ustawić
            // ETag na SHA-256 manifestu — wtedy 304 to „ten sam skrót, nie pobieraj". Addytywne, nullable.
            if (!hasColumn(db, "community_servers", "manifest_etag")) {
                db.execSQL("ALTER TABLE community_servers ADD COLUMN manifest_etag TEXT")
            }
            if (!hasColumn(db, "community_servers", "manifest_last_modified")) {
                db.execSQL("ALTER TABLE community_servers ADD COLUMN manifest_last_modified TEXT")
            }
        }
        if (oldVersion < 35) {
            // H-01: hash regulaminu z OSTATNIEGO manifestu. Pozwala egzekwować zgodę po treści regulaminu
            // (a nie tylko flagę accepted) poza warstwą UI — po zmianie regulaminu serwer wypada z
            // contactableServers()/restoredState() aż do ponownej zgody. Addytywne, nullable.
            if (!hasColumn(db, "community_servers", "manifest_terms_hash")) {
                db.execSQL("ALTER TABLE community_servers ADD COLUMN manifest_terms_hash TEXT")
            }
        }
        if (oldVersion < 36) {
            // R-01: ostatnio znany `push` i `primaryFeed` (JSON). Odtwarzane w restoredState, żeby po zimnym
            // starcie/304 serwer nie wypadł z rejestru push (inaczej budziki były cicho ignorowane). Addytywne.
            if (!hasColumn(db, "community_servers", "push_json")) {
                db.execSQL("ALTER TABLE community_servers ADD COLUMN push_json TEXT")
            }
            if (!hasColumn(db, "community_servers", "primary_feed_json")) {
                db.execSQL("ALTER TABLE community_servers ADD COLUMN primary_feed_json TEXT")
            }
        }
        if (oldVersion < 37) {
            // R-09: tekst+wersja OSTATNIEGO regulaminu (obok hasza). Po restarcie przy zmienionym regulaminie
            // pozwala pokazać przycisk „Przejrzyj regulamin” i wyświetlić nowy regulamin OFFLINE (bez manifestu).
            if (!hasColumn(db, "community_servers", "manifest_terms_text")) {
                db.execSQL("ALTER TABLE community_servers ADD COLUMN manifest_terms_text TEXT")
            }
            if (!hasColumn(db, "community_servers", "manifest_terms_version")) {
                db.execSQL("ALTER TABLE community_servers ADD COLUMN manifest_terms_version TEXT")
            }
        }
        if (oldVersion < 38) {
            // A-35: triggery FTS po mapie video_id → rowid (zamiast pełnych skanów kolumny UNINDEXED).
            db.execSQL("DROP TRIGGER IF EXISTS video_history_fts_insert")
            db.execSQL("DROP TRIGGER IF EXISTS video_history_fts_delete")
            db.execSQL("DROP TRIGGER IF EXISTS video_history_fts_update")
            createVideoHistoryFts(db)
            rebuildVideoHistoryFtsMap(db)
            // A-33: R-02 zmienił author_id (z serverId) bez migracji — awatary zapisane sprzed poprawki mogły
            // wskazywać na cudzy host (beacon dla zapisanych wierszy). Zerujemy awatary, których host ≠ host
            // serwera; przy następnym odświeżeniu poprawny awatar wróci z manifestu.
            migrateCommunityAuthorAvatars(db)
        }
        if (oldVersion < 39) {
            // T-05: rejestr „czy obraz społeczności jest animacją" (sprawdzany raz, nie przy każdym wyświetleniu)
            createCommunityImageKindTable(db)
        }
    }

    /**
     * T-05: czy obraz z cache obrazów społeczności (po kluczu TREŚCI — content-addressing, więc wpis nigdy nie jest
     * nieaktualny) jest animowanym WebP. Sprawdzany raz (przy zapisie albo z 21 bajtów nagłówka); dawniej przy
     * KAŻDYM wejściu obrazu na ekran aplikacja czytała z dysku cały plik tylko po to, by zajrzeć do nagłówka.
     */
    private fun createCommunityImageKindTable(db: BundledDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS community_image_kind (" +
                "content_key TEXT PRIMARY KEY NOT NULL, " +
                "animated INTEGER NOT NULL" +
                ")",
        )
    }

    /** T-05: null = obraz jeszcze nie sprawdzony. */
    fun communityImageAnimated(contentKey: String): Boolean? = readableDatabase.rawQuery(
        "SELECT animated FROM community_image_kind WHERE content_key = ?",
        arrayOf(contentKey),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) != 0 else null }

    fun setCommunityImageAnimated(contentKey: String, animated: Boolean) {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO community_image_kind(content_key, animated) VALUES(?, ?)",
            arrayOf<Any>(contentKey, if (animated) 1 else 0),
        )
    }

    /** T-05: przy czyszczeniu cache obrazów rejestr też znika (inaczej rósłby bez końca). */
    fun clearCommunityImageKinds() {
        writableDatabase.execSQL("DELETE FROM community_image_kind")
    }

    private fun createStreamPrivacyTable(db: BundledDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS stream_privacy (
                video_id TEXT PRIMARY KEY,
                stream_start_ms INTEGER,
                is_private INTEGER NOT NULL DEFAULT 0,
                checked_ms INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
    }

    private fun createSourceStateTable(db: BundledDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS source_state (
                source_key TEXT PRIMARY KEY,
                resolved_id TEXT,
                initialized INTEGER NOT NULL DEFAULT 0,
                last_checked_ms INTEGER NOT NULL DEFAULT 0,
                last_error TEXT,
                last_notification_video_id TEXT,
                last_notification_published_ms INTEGER NOT NULL DEFAULT 0,
                rss_known_video_ids TEXT
            )
            """.trimIndent(),
        )
    }

    private fun createVideoHistoryTable(db: BundledDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS video_history (
                video_id TEXT PRIMARY KEY,
                creator_id TEXT NOT NULL,
                creator_name TEXT NOT NULL,
                title TEXT NOT NULL,
                url TEXT NOT NULL,
                published_ms INTEGER NOT NULL,
                detected_ms INTEGER NOT NULL,
                kind TEXT NOT NULL,
                notified INTEGER NOT NULL DEFAULT 0,
                classification_version INTEGER NOT NULL DEFAULT 0,
                origin TEXT NOT NULL DEFAULT 'YOUTUBE',
                notification_checked INTEGER NOT NULL DEFAULT 1,
                classification_attempts INTEGER NOT NULL DEFAULT 0,
                classification_last_attempt_ms INTEGER NOT NULL DEFAULT 0,
                kind_evidence INTEGER NOT NULL DEFAULT 0,
                published_evidence INTEGER NOT NULL DEFAULT 0,
                is_favorite INTEGER NOT NULL DEFAULT 0,
                favorited_ms INTEGER,
                description_data BLOB,
                description_codec INTEGER NOT NULL DEFAULT 0,
                description_dictionary_id TEXT,
                description_dictionary_version INTEGER,
                description_original_size INTEGER NOT NULL DEFAULT 0,
                description_state INTEGER NOT NULL DEFAULT 0,
                description_attempts INTEGER NOT NULL DEFAULT 0,
                description_last_attempt_ms INTEGER NOT NULL DEFAULT 0,
                description_availability INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_video_history_detected " +
                "ON video_history(detected_ms DESC)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_video_history_published " +
                "ON video_history(published_ms DESC)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_video_history_pending " +
                "ON video_history(kind, notified)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_video_history_classification " +
                "ON video_history(classification_version, published_ms DESC)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_video_history_favorite " +
                "ON video_history(is_favorite, published_ms DESC)",
        )
    }

    private fun createVideoHistoryFts(db: BundledDatabase) {
        db.execSQL(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS video_history_fts USING fts5(
                video_id UNINDEXED,
                title,
                creator_name,
                description,
                tokenize = 'unicode61 remove_diacritics 2'
            )
            """.trimIndent(),
        )
        // A-35: `video_id` w FTS5 jest UNINDEXED, więc `WHERE video_id = ?` na tabeli FTS to pełny skan na
        // każdy wiersz (prune 60 dni / backfill opisów = O(n·m)). Mapa video_id → rowid FTS (PRIMARY KEY, a
        // rowid FTS5 jest stabilny także po VACUUM — w przeciwieństwie do rowid `video_history`) daje O(log n).
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS video_history_fts_map(" +
                "video_id TEXT PRIMARY KEY, fts_rowid INTEGER NOT NULL) WITHOUT ROWID",
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS video_history_fts_insert
            AFTER INSERT ON video_history BEGIN
                INSERT INTO video_history_fts(video_id, title, creator_name, description)
                VALUES (
                    new.video_id,
                    replace(replace(new.title, 'Ł', 'L'), 'ł', 'l'),
                    replace(replace(new.creator_name, 'Ł', 'L'), 'ł', 'l'),
                    ''
                );
                INSERT OR REPLACE INTO video_history_fts_map(video_id, fts_rowid)
                VALUES (new.video_id, last_insert_rowid());
            END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS video_history_fts_delete
            AFTER DELETE ON video_history BEGIN
                DELETE FROM video_history_fts WHERE rowid =
                    (SELECT fts_rowid FROM video_history_fts_map WHERE video_id = old.video_id);
                DELETE FROM video_history_fts_map WHERE video_id = old.video_id;
            END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS video_history_fts_update
            AFTER UPDATE OF title, creator_name ON video_history BEGIN
                UPDATE video_history_fts
                SET title = replace(replace(new.title, 'Ł', 'L'), 'ł', 'l'),
                    creator_name = replace(replace(new.creator_name, 'Ł', 'L'), 'ł', 'l')
                WHERE rowid = (SELECT fts_rowid FROM video_history_fts_map WHERE video_id = new.video_id);
            END
            """.trimIndent(),
        )
    }

    /**
     * A-33: awatar autora zostaje tylko, gdy jego host == host KAŻDEGO serwera, którego pozycje wskazują tego
     * autora (reguła anty-beacon z parsera). Autor bez pozycji albo z awatarem z cudzego hosta → `NULL`.
     */
    private fun migrateCommunityAuthorAvatars(db: BundledDatabase) {
        fun host(url: String?): String? =
            runCatching { java.net.URI(url!!.trim()).host?.lowercase()?.removeSuffix(".") }.getOrNull()
        val allowed = HashMap<String, Boolean>()
        db.rawQuery(
            "SELECT a.author_id, a.avatar_url, s.endpoint FROM community_authors a " +
                "LEFT JOIN community_items i ON i.author_id = a.author_id " +
                "LEFT JOIN community_servers s ON s.server_id = i.server_id " +
                "WHERE a.avatar_url IS NOT NULL",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val authorId = cursor.getString(0) ?: continue
                val avatarHost = host(cursor.getString(1))
                val serverHost = if (cursor.isNull(2)) null else host(cursor.getString(2))
                val ok = avatarHost != null && serverHost != null && avatarHost == serverHost
                allowed[authorId] = (allowed[authorId] ?: true) && ok
            }
        }
        allowed.filterValues { !it }.keys.forEach { authorId ->
            db.execSQL("UPDATE community_authors SET avatar_url = NULL WHERE author_id = ?", arrayOf<Any>(authorId))
        }
    }

    /** A-35: przebudowa mapy video_id → rowid FTS i usunięcie zdublowanych/osieroconych wierszy FTS. */
    private fun rebuildVideoHistoryFtsMap(db: BundledDatabase) {
        db.execSQL("DELETE FROM video_history_fts_map")
        // przy duplikatach zostaje najnowszy wiersz FTS (najwyższy rowid — np. z uzupełnionym opisem)
        db.execSQL(
            "INSERT INTO video_history_fts_map(video_id, fts_rowid) " +
                "SELECT video_id, MAX(rowid) FROM video_history_fts GROUP BY video_id",
        )
        db.execSQL(
            "DELETE FROM video_history_fts WHERE rowid NOT IN (SELECT fts_rowid FROM video_history_fts_map)",
        )
        db.execSQL(
            "DELETE FROM video_history_fts WHERE rowid IN (SELECT m.fts_rowid FROM video_history_fts_map m " +
                "WHERE NOT EXISTS (SELECT 1 FROM video_history h WHERE h.video_id = m.video_id))",
        )
        db.execSQL(
            "DELETE FROM video_history_fts_map WHERE NOT EXISTS " +
                "(SELECT 1 FROM video_history h WHERE h.video_id = video_history_fts_map.video_id)",
        )
    }

    /** Naprawia bazy schema 25 utworzone przez wcześniejsze kompilacje testowe. */
    private fun ensureVideoHistoryFtsCoverage(db: BundledDatabase) {
        createVideoHistoryFts(db)
        // A-39: `video_id` jest UNINDEXED w FTS5, więc dawne `NOT EXISTS (… f.video_id = h.video_id)` robiło pełny
        // skan FTS na KAŻDY wiersz historii (O(N²)) przy każdym zimnym starcie na wątku głównym → ANR. Szybka
        // ścieżka: porównanie liczności historii i mapy video_id → rowid FTS (A-35; O(N), milisekundy). Dopiero
        // przy rozjeździe naprawa po indeksowanej mapie (O(N log N)).
        fun count(sql: String): Long =
            db.rawQuery(sql, null).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
        val historyRows = count("SELECT COUNT(*) FROM video_history")
        val mapRows = count("SELECT COUNT(*) FROM video_history_fts_map")
        if (mapRows == historyRows) return
        var missingBefore = 0L
        db.beginTransaction()
        try {
            rebuildVideoHistoryFtsMap(db)
            missingBefore = count(
                "SELECT COUNT(*) FROM video_history h " +
                    "WHERE NOT EXISTS (SELECT 1 FROM video_history_fts_map m WHERE m.video_id = h.video_id)",
            )
            if (missingBefore > 0L) {
                db.execSQL(
                    """
                    INSERT INTO video_history_fts(video_id, title, creator_name, description)
                    SELECT h.video_id,
                           replace(replace(h.title, 'Ł', 'L'), 'ł', 'l'),
                           replace(replace(h.creator_name, 'Ł', 'L'), 'ł', 'l'),
                           ''
                    FROM video_history h
                    WHERE NOT EXISTS (
                        SELECT 1 FROM video_history_fts_map m WHERE m.video_id = h.video_id
                    )
                    """.trimIndent(),
                )
                rebuildVideoHistoryFtsMap(db)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (missingBefore <= 0L) return
        DiagnosticLogStore.event(
            category = DiagnosticCategory.DATABASE,
            level = DiagnosticLevel.WARNING,
            name = "FTS_INDEX_REPAIRED",
            fields = mapOf("restoredRows" to missingBefore),
        )
    }

    private fun hasColumn(db: BundledDatabase, table: String, column: String): Boolean =
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            var found = false
            while (!found && cursor.moveToNext()) {
                found = cursor.getString(1) == column
            }
            found
        }

    private fun createCreatorMetadataTable(db: BundledDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS creator_metadata (
                creator_id TEXT PRIMARY KEY,
                avatar_url TEXT,
                avatar_sha256 TEXT,
                avatar_checked_ms INTEGER NOT NULL DEFAULT 0,
                avatar_attempt_ms INTEGER NOT NULL DEFAULT 0,
                updated_ms INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
    }

    private fun createNotificationInboxTable(db: BundledDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS notification_inbox (
                video_id TEXT PRIMARY KEY,
                created_ms INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_notification_inbox_created " +
                "ON notification_inbox(created_ms DESC)",
        )
    }

    private fun createNotificationIdsTable(db: BundledDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS notification_ids (
                notification_id INTEGER PRIMARY KEY AUTOINCREMENT,
                video_id TEXT NOT NULL UNIQUE
            )
            """.trimIndent(),
        )
    }

    private fun createSourcePriorityTable(db: BundledDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS source_priority (
                source_key TEXT PRIMARY KEY,
                prior_rate_per_day REAL NOT NULL,
                prior_exposure_days REAL NOT NULL,
                event_mass REAL NOT NULL DEFAULT 0,
                exposure_days REAL NOT NULL DEFAULT 0,
                last_model_update_ms INTEGER NOT NULL DEFAULT 0,
                last_attempt_ms INTEGER NOT NULL DEFAULT 0,
                last_hit_ms INTEGER NOT NULL DEFAULT 0,
                consecutive_failures INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
    }

    private fun createYouTubeChannelTabsTable(db: BundledDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS youtube_channel_tabs (
                source_key TEXT NOT NULL,
                channel_id TEXT NOT NULL,
                tab_name TEXT NOT NULL,
                state TEXT NOT NULL,
                params TEXT,
                checked_ms INTEGER NOT NULL,
                last_attempt_ms INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (source_key, tab_name)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_youtube_channel_tabs_checked " +
                "ON youtube_channel_tabs(state, checked_ms)",
        )
    }

    // --- serwery społeczności ---
    // wszystko lokalne: do serwera nic nie odsyłamy. zgoda na regulamin (i odmowa)
    // żyje wyłącznie tutaj, bo apka nie ma jak jej wysłać z powrotem.

    private fun createCommunityServersTable(db: BundledDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS community_servers (
                server_id TEXT PRIMARY KEY,
                endpoint TEXT NOT NULL,
                added_ms INTEGER NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 1,
                display_name TEXT,
                transport TEXT,
                last_manifest_fetch_ms INTEGER NOT NULL DEFAULT 0,
                notify_enabled INTEGER NOT NULL DEFAULT 1,
                muted_platforms TEXT,
                can_be_primary INTEGER NOT NULL DEFAULT 0,
                ads_enabled INTEGER NOT NULL DEFAULT 1,
                display_muted TEXT,
                manifest_etag TEXT,
                manifest_last_modified TEXT,
                manifest_terms_hash TEXT,
                push_json TEXT,
                primary_feed_json TEXT,
                manifest_terms_text TEXT,
                manifest_terms_version TEXT
            )
            """.trimIndent(),
        )
    }

    private fun createCommunityConsentsTable(db: BundledDatabase) {
        // tożsamością regulaminu jest hash treści – jeden wiersz na (serwer, regulamin).
        // trzymamy pełny tekst + wersję + daty + decyzję (także odmowę): dowód świadomej zgody.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS community_consents (
                server_id TEXT NOT NULL,
                terms_hash TEXT NOT NULL,
                terms_version TEXT NOT NULL,
                terms_text TEXT NOT NULL,
                presented_ms INTEGER NOT NULL,
                decided_ms INTEGER NOT NULL,
                accepted INTEGER NOT NULL,
                PRIMARY KEY (server_id, terms_hash)
            )
            """.trimIndent(),
        )
    }

    private fun createCommunityAuthorsTable(db: BundledDatabase) {
        // słownik autorów WSPÓLNY dla wszystkich serwerów: nazwa + avatar twórcy/źródła raz,
        // w pozycjach referencjonowane przez author_id (oszczędność miejsca).
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS community_authors (
                author_id TEXT PRIMARY KEY,
                platform TEXT NOT NULL,
                name TEXT NOT NULL,
                avatar_url TEXT
            )
            """.trimIndent(),
        )
    }

    private fun createCommunityItemsTable(db: BundledDatabase) {
        // jedna pozycja treści; tekst (JSON pól) ściśnięty ZSTD w blob. server_id + author_id to
        // znormalizowane referencje. item_id = stabilny klucz per serwer (re-wysyłka nadpisuje).
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS community_items (
                server_id TEXT NOT NULL,
                item_id TEXT NOT NULL,
                section TEXT NOT NULL,
                author_id TEXT,
                published_ms INTEGER,
                blob BLOB NOT NULL,
                codec INTEGER NOT NULL,
                original_size INTEGER NOT NULL,
                image_url TEXT,
                notify_image_url TEXT,
                image_quality INTEGER,
                favorite INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (server_id, item_id)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_community_items_server ON community_items(server_id)",
        )
    }

    /**
     * Aktualizuje treść serwera: upsert autorów + upsert pozycji ze świeżego manifestu.
     * ULUBIONE są zachowywane (flaga przenoszona przy nadpisaniu), a pozycje NIE-ulubione,
     * których serwer już nie podaje, są usuwane. Ulubione zostają, dopóki user ich nie odznaczy
     * albo serwer ich jawnie nie usunie ([deleteCommunityItems]).
     */
    @Synchronized
    fun replaceCommunityContent(serverId: String, content: CommunityStoredContent) =
        writeCommunityContent(serverId, content, wipeNonFavorites = true)

    /** tryb plików treści: DOKŁADA/aktualizuje pozycje po itemId bez kasowania istniejących nie-ulubionych. */
    @Synchronized
    fun appendCommunityContent(serverId: String, content: CommunityStoredContent) =
        writeCommunityContent(serverId, content, wipeNonFavorites = false)

    private fun writeCommunityContent(
        serverId: String,
        content: CommunityStoredContent,
        wipeNonFavorites: Boolean,
    ) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            content.authors.forEach { author ->
                // C-09: dopisywanie pliku (append) bez awatara NIE kasuje awatara z wcześniejszego pliku
                if (!wipeNonFavorites) {
                    db.execSQL(
                        "INSERT INTO community_authors(author_id, platform, name, avatar_url) VALUES(?, ?, ?, ?) " +
                            "ON CONFLICT(author_id) DO UPDATE SET platform = excluded.platform, name = excluded.name, " +
                            "avatar_url = COALESCE(excluded.avatar_url, community_authors.avatar_url)",
                        arrayOf<Any?>(author.authorId, author.platform, author.name, author.avatarUrl),
                    )
                    return@forEach
                }
                db.insertWithOnConflict(
                    "community_authors",
                    null,
                    ContentValues().apply {
                        put("author_id", author.authorId)
                        put("platform", author.platform)
                        put("name", author.name)
                        author.avatarUrl?.let { put("avatar_url", it) } ?: putNull("avatar_url")
                    },
                    BundledDatabase.CONFLICT_REPLACE,
                )
            }
            val favorites = communityFavoriteItemIdsLocked(serverId)
            // replace: usuwamy nie-ulubione (ulubione nigdy nie znikają samą aktualizacją). append: nie kasujemy.
            if (wipeNonFavorites) {
                db.delete("community_items", "server_id = ? AND favorite = 0", arrayOf(serverId))
            }
            content.items.forEach { item ->
                db.insertWithOnConflict(
                    "community_items",
                    null,
                    ContentValues().apply {
                        put("server_id", item.serverId)
                        put("item_id", item.itemId)
                        put("section", item.section.name)
                        item.authorId?.let { put("author_id", it) } ?: putNull("author_id")
                        item.publishedMs?.let { put("published_ms", it) } ?: putNull("published_ms")
                        put("blob", item.blob)
                        put("codec", item.codec)
                        put("original_size", item.originalSize)
                        item.imageUrl?.let { put("image_url", it) } ?: putNull("image_url")
                        item.notifyImageUrl?.let { put("notify_image_url", it) } ?: putNull("notify_image_url")
                        item.imageQuality?.let { put("image_quality", it) } ?: putNull("image_quality")
                        put("favorite", if (item.itemId in favorites) 1 else 0)
                    },
                    BundledDatabase.CONFLICT_REPLACE,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        pruneOrphanCommunityAuthors()
    }

    private fun communityFavoriteItemIdsLocked(serverId: String): Set<String> =
        writableDatabase.query(
            "community_items",
            arrayOf("item_id"),
            "server_id = ? AND favorite = 1",
            arrayOf(serverId),
            null,
            null,
            null,
        ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    /** ustawia/zdejmuje ulubione dla pozycji – ulubiona nie zostanie usunięta retencją ani aktualizacją. */
    @Synchronized
    fun setCommunityItemFavorite(serverId: String, itemId: String, favorite: Boolean) {
        writableDatabase.update(
            "community_items",
            ContentValues().apply { put("favorite", if (favorite) 1 else 0) },
            "server_id = ? AND item_id = ?",
            arrayOf(serverId, itemId),
        )
    }

    /** ulubione itemId danego serwera – do zaznaczenia stanu w feedzie. */
    @Synchronized
    fun communityFavoriteItemIds(serverId: String): Set<String> =
        communityFavoriteItemIdsLocked(serverId)

    /** JAWNE usunięcie pozycji przez serwer – kasuje całkowicie, także ulubione. */
    @Synchronized
    fun deleteCommunityItems(serverId: String, itemIds: Collection<String>) {
        if (itemIds.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            itemIds.forEach { id ->
                db.delete("community_items", "server_id = ? AND item_id = ?", arrayOf(serverId, id))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        pruneOrphanCommunityAuthors()
    }

    /**
     * Retencja treści: osobno dla tekstu i obrazów. Pozycje starsze niż [textBeforeMillis] (i NIE
     * ulubione) znikają całkiem; dla pozycji starszych niż [imageBeforeMillis] usuwamy sam obraz,
     * ale tekst zostaje (baza może trzymać tekst bez zdjęcia). 0 = brak limitu danej osi.
     */
    @Synchronized
    fun pruneCommunityContent(
        textBeforeMillis: Long,
        imageBeforeMillis: Long,
        pruneAllText: Boolean,
        pruneAllImage: Boolean,
    ) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // M-09: pruneAll = „nie zapisuj" (0 dni) → usuń CAŁĄ treść poza ulubionymi, także bez daty.
            // Inaczej usuwamy tylko datowane starsze niż próg (pozycji bez daty nie da się zestarzeć).
            if (pruneAllText) {
                db.delete("community_items", "favorite = 0", null)
            } else if (textBeforeMillis > 0L) {
                db.delete(
                    "community_items",
                    "favorite = 0 AND published_ms IS NOT NULL AND published_ms < ?",
                    arrayOf(textBeforeMillis.toString()),
                )
            }
            if (pruneAllImage) {
                db.execSQL(
                    "UPDATE community_items SET image_url = NULL, notify_image_url = NULL, " +
                        "image_quality = NULL WHERE favorite = 0 " +
                        "AND (image_url IS NOT NULL OR notify_image_url IS NOT NULL)",
                )
                // R-07: obrazy MEMÓW leżą w blobie (nie w kolumnach image_url), więc zerowanie kolumn ich nie
                // usuwa. Mem to treść obrazkowa — przy retencji obrazów usuwamy cały nie-ulubiony mem (bez
                // obrazu nie ma czego pokazać). "0 = nie zapisuj obrazów" => memy nie są trzymane.
                db.delete("community_items", "favorite = 0 AND section = ?", arrayOf("MEME"))
            } else if (imageBeforeMillis > 0L) {
                db.execSQL(
                    "UPDATE community_items SET image_url = NULL, notify_image_url = NULL, " +
                        "image_quality = NULL WHERE favorite = 0 AND published_ms IS NOT NULL " +
                        "AND published_ms < ? AND (image_url IS NOT NULL OR notify_image_url IS NOT NULL)",
                    arrayOf(imageBeforeMillis),
                )
                // R-07: memy starsze niż okno obrazów usuwamy w całości (obraz = cała treść mema)
                db.delete(
                    "community_items",
                    "favorite = 0 AND section = ? AND published_ms IS NOT NULL AND published_ms < ?",
                    arrayOf("MEME", imageBeforeMillis.toString()),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        pruneOrphanCommunityAuthors()
    }

    /** pozycje serwera z dołączonymi danymi autora (LEFT JOIN ze słownikiem), najnowsze pierwsze. */
    @Synchronized
    fun communityStoredItems(serverId: String): List<CommunityStoredItem> = readableDatabase.rawQuery(
        """
        SELECT i.server_id, i.item_id, i.section, i.author_id, i.published_ms,
               i.blob, i.codec, i.original_size, i.image_url, i.notify_image_url,
               i.image_quality, i.favorite, a.platform, a.name, a.avatar_url
        FROM community_items i
        LEFT JOIN community_authors a ON a.author_id = i.author_id
        WHERE i.server_id = ?
        ORDER BY i.published_ms DESC
        """.trimIndent(),
        arrayOf(serverId),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val section = runCatching { CommunitySection.valueOf(cursor.getString(2)) }.getOrNull()
                    ?: continue
                add(
                    CommunityStoredItem(
                        serverId = cursor.getString(0),
                        itemId = cursor.getString(1),
                        section = section,
                        authorId = cursor.getString(3),
                        publishedMs = if (cursor.isNull(4)) null else cursor.getLong(4),
                        blob = cursor.getBlob(5),
                        codec = cursor.getInt(6),
                        originalSize = cursor.getInt(7),
                        imageUrl = cursor.getString(8),
                        notifyImageUrl = cursor.getString(9),
                        imageQuality = if (cursor.isNull(10)) null else cursor.getInt(10),
                        favorite = cursor.getInt(11) != 0,
                        authorPlatform = cursor.getString(12),
                        authorName = cursor.getString(13),
                        authorAvatarUrl = cursor.getString(14),
                    ),
                )
            }
        }
    }

    /** kasuje autorów, do których nie prowadzi już żadna pozycja (po zamianie treści serwera). */
    private fun pruneOrphanCommunityAuthors() {
        writableDatabase.execSQL(
            "DELETE FROM community_authors WHERE author_id NOT IN " +
                "(SELECT DISTINCT author_id FROM community_items WHERE author_id IS NOT NULL)",
        )
    }

    /** dodaje serwer, ale nie kasuje enabled/added, gdy user doda go ponownie. */
    @Synchronized
    fun addCommunityServer(serverId: String, endpoint: String, addedAtMillis: Long) {
        writableDatabase.insertWithOnConflict(
            "community_servers",
            null,
            ContentValues().apply {
                put("server_id", serverId)
                put("endpoint", endpoint)
                put("added_ms", addedAtMillis)
                put("enabled", 1)
            },
            BundledDatabase.CONFLICT_IGNORE,
        )
    }

    /** cache nazwy/transportu/flagi „może być główny" z ostatniego manifestu – do listy. */
    @Synchronized
    fun updateCommunityServerManifestCache(
        serverId: String,
        displayName: String?,
        transport: CommunityTransportKind?,
        canBePrimary: Boolean,
        fetchedAtMillis: Long,
        etag: String?,
        lastModified: String?,
        termsHash: String?,
        pushJson: String?,
        primaryFeedJson: String?,
        termsText: String?,
        termsVersion: String?,
    ) {
        writableDatabase.update(
            "community_servers",
            ContentValues().apply {
                displayName?.let { put("display_name", it) } ?: putNull("display_name")
                transport?.let { put("transport", it.name) } ?: putNull("transport")
                put("can_be_primary", if (canBePrimary) 1 else 0)
                put("last_manifest_fetch_ms", fetchedAtMillis)
                // #10: zapisujemy walidatory tylko gdy serwer je podał; brak nagłówka nie kasuje
                // poprzedniego (serwer mógł chwilowo ich nie wysłać — nie tracimy możliwości 304).
                etag?.takeIf { it.isNotBlank() }?.let { put("manifest_etag", it) }
                lastModified?.takeIf { it.isNotBlank() }?.let { put("manifest_last_modified", it) }
                // H-01: hash regulaminu z tego manifestu — zapisujemy ZAWSZE (także po zmianie), żeby bramka
                // zgody po haszu działała poza UI. Null tylko gdy manifest nie miał regulaminu.
                termsHash?.let { put("manifest_terms_hash", it) } ?: putNull("manifest_terms_hash")
                // R-01: push/primaryFeed ze ŚWIEŻEGO manifestu zapisujemy ZAWSZE (null = serwer je usunął).
                // Przy 304/zimnym starcie ta funkcja nie jest wołana, więc wartość z bazy przetrwa i zasili
                // restoredState — serwer nie wypadnie z rejestru push.
                put("push_json", pushJson)
                put("primary_feed_json", primaryFeedJson)
                // R-09: tekst+wersja regulaminu ze świeżego manifestu (do offline „Przejrzyj regulamin")
                put("manifest_terms_text", termsText)
                put("manifest_terms_version", termsVersion)
            },
            "server_id = ?",
            arrayOf(serverId),
        )
    }

    /** J-01: walidatory ETag/Last-Modified ustawiane dokładnie (null kasuje) — po niekompletnym pobraniu plików treści. */
    @Synchronized
    fun setCommunityServerManifestValidators(serverId: String, etag: String?, lastModified: String?) {
        writableDatabase.update(
            "community_servers",
            ContentValues().apply {
                put("manifest_etag", etag?.takeIf { it.isNotBlank() })
                put("manifest_last_modified", lastModified?.takeIf { it.isNotBlank() })
            },
            "server_id = ?",
            arrayOf(serverId),
        )
    }

    @Synchronized
    fun setCommunityServerNotifyEnabled(serverId: String, enabled: Boolean) {
        writableDatabase.update(
            "community_servers",
            ContentValues().apply { put("notify_enabled", if (enabled) 1 else 0) },
            "server_id = ?",
            arrayOf(serverId),
        )
    }

    @Synchronized
    fun setCommunityServerAdsEnabled(serverId: String, enabled: Boolean) {
        writableDatabase.update(
            "community_servers",
            ContentValues().apply { put("ads_enabled", if (enabled) 1 else 0) },
            "server_id = ?",
            arrayOf(serverId),
        )
    }

    /** wyciszenia POWIADOMIEŃ (klucze platforma / platforma/twórca). puste = powiadamiaj ze wszystkiego. */
    @Synchronized
    fun setCommunityServerNotifyMuted(serverId: String, muted: Set<String>) =
        updateCommunityMutedColumn(serverId, "muted_platforms", muted)

    /** wyciszenia WYŚWIETLANIA w zakładce (te same klucze). puste = pokazuj wszystko. */
    @Synchronized
    fun setCommunityServerDisplayMuted(serverId: String, muted: Set<String>) =
        updateCommunityMutedColumn(serverId, "display_muted", muted)

    private fun updateCommunityMutedColumn(serverId: String, column: String, muted: Set<String>) {
        // rozdzielamy nową linią, bo klucze twórców mogą zawierać przecinki
        val encoded = muted.map { it.trim() }.filter { it.isNotEmpty() }.toSortedSet().joinToString("\n")
        writableDatabase.update(
            "community_servers",
            ContentValues().apply {
                if (encoded.isEmpty()) putNull(column) else put(column, encoded)
            },
            "server_id = ?",
            arrayOf(serverId),
        )
    }

    @Synchronized
    fun setCommunityServerEnabled(serverId: String, enabled: Boolean) {
        writableDatabase.update(
            "community_servers",
            ContentValues().apply { put("enabled", if (enabled) 1 else 0) },
            "server_id = ?",
            arrayOf(serverId),
        )
    }

    /** usuwa serwer i całą jego historię zgód – user zrezygnował z niego w całości. */
    @Synchronized
    fun deleteCommunityServer(serverId: String) {
        writableDatabase.delete("community_items", "server_id = ?", arrayOf(serverId))
        writableDatabase.delete("community_consents", "server_id = ?", arrayOf(serverId))
        writableDatabase.delete("community_servers", "server_id = ?", arrayOf(serverId))
        pruneOrphanCommunityAuthors()
    }

    @Synchronized
    fun communityServers(): List<CommunityServerRecord> = readableDatabase.query(
        "community_servers",
        COMMUNITY_SERVER_COLUMNS,
        null,
        null,
        null,
        null,
        "added_ms ASC",
    ).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.toCommunityServerRecord()) }
    }

    @Synchronized
    fun communityServer(serverId: String): CommunityServerRecord? = readableDatabase.query(
        "community_servers",
        COMMUNITY_SERVER_COLUMNS,
        "server_id = ?",
        arrayOf(serverId),
        null,
        null,
        null,
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toCommunityServerRecord() else null }

    @Synchronized
    fun recordCommunityConsent(record: CommunityConsentRecord) {
        writableDatabase.insertWithOnConflict(
            "community_consents",
            null,
            ContentValues().apply {
                put("server_id", record.serverId)
                put("terms_hash", record.termsHash)
                put("terms_version", record.termsVersionLabel)
                put("terms_text", record.termsText)
                put("presented_ms", record.presentedAtMillis)
                put("decided_ms", record.decidedAtMillis)
                put("accepted", if (record.accepted) 1 else 0)
            },
            BundledDatabase.CONFLICT_REPLACE,
        )
    }

    /** decyzja usera dla DOKŁADNIE tego regulaminu (po hashu) – tego używa bramka zgody. */
    @Synchronized
    fun communityConsentForTerms(serverId: String, termsHash: String): CommunityConsentRecord? =
        readableDatabase.query(
            "community_consents",
            COMMUNITY_CONSENT_COLUMNS,
            "server_id = ? AND terms_hash = ?",
            arrayOf(serverId, termsHash),
            null,
            null,
            null,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toCommunityConsentRecord() else null }

    /** ostatnia decyzja dla serwera (do wyświetlenia/audytu), niezależnie od regulaminu. */
    @Synchronized
    fun latestCommunityConsent(serverId: String): CommunityConsentRecord? = readableDatabase.query(
        "community_consents",
        COMMUNITY_CONSENT_COLUMNS,
        "server_id = ?",
        arrayOf(serverId),
        null,
        null,
        "decided_ms DESC, rowid DESC", // D-01: remis co do ms → ostatnio zapisana zgoda
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toCommunityConsentRecord() else null }

    private fun Cursor.toCommunityServerRecord() = CommunityServerRecord(
        serverId = getString(0),
        endpoint = getString(1),
        addedAtMillis = getLong(2),
        enabled = getInt(3) != 0,
        displayName = getString(4),
        transport = CommunityTransportKind.fromRaw(getString(5)),
        lastManifestFetchMillis = getLong(6),
        notifyEnabled = getInt(7) != 0,
        notifyMuted = decodeCommunityMuted(getString(8)),
        canBePrimary = getInt(9) != 0,
        adsEnabled = getInt(10) != 0,
        displayMuted = decodeCommunityMuted(getString(11)),
        manifestEtag = getString(12),
        manifestLastModified = getString(13),
        manifestTermsHash = getString(14),
        push = CommunityServerCodec.pushFromJson(getString(15)),
        primaryFeed = CommunityServerCodec.primaryFeedFromJson(getString(16)),
        manifestTermsText = getString(17),
        manifestTermsVersion = getString(18),
    )

    private fun decodeCommunityMuted(raw: String?): Set<String> = raw
        ?.split('\n')
        ?.map { it.trim().lowercase() }
        ?.filter { it.isNotEmpty() }
        ?.toSet()
        .orEmpty()

    private fun Cursor.toCommunityConsentRecord() = CommunityConsentRecord(
        serverId = getString(0),
        termsHash = getString(1),
        termsVersionLabel = getString(2),
        termsText = getString(3),
        presentedAtMillis = getLong(4),
        decidedAtMillis = getLong(5),
        accepted = getInt(6) != 0,
    )

    @Synchronized
    fun getResolvedId(sourceKey: String): String? = readableDatabase.query(
        "source_state",
        arrayOf("resolved_id"),
        "source_key = ?",
        arrayOf(sourceKey),
        null,
        null,
        null,
    ).use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }

    @Synchronized
    fun saveResolvedId(sourceKey: String, resolvedId: String) {
        val values = ContentValues().apply {
            put("source_key", sourceKey)
            put("resolved_id", resolvedId)
        }
        writableDatabase.insertWithOnConflict(
            "source_state",
            null,
            values,
            BundledDatabase.CONFLICT_IGNORE,
        )
        writableDatabase.update(
            "source_state",
            ContentValues().apply { put("resolved_id", resolvedId) },
            "source_key = ?",
            arrayOf(sourceKey),
        )
    }

    @Synchronized
    fun getYouTubeChannelTabs(
        sourceKey: String,
        channelId: String,
    ): StoredYouTubeChannelTabs? {
        val rows = readableDatabase.query(
            "youtube_channel_tabs",
            arrayOf("tab_name", "state", "params", "checked_ms", "last_attempt_ms"),
            "source_key = ? AND channel_id = ?",
            arrayOf(sourceKey, channelId),
            null,
            null,
            null,
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val tabName = cursor.getString(0)
                    val state = runCatching {
                        YouTubeChannelTabState.valueOf(cursor.getString(1))
                    }.getOrDefault(YouTubeChannelTabState.UNKNOWN)
                    put(
                        tabName,
                        StoredYouTubeChannelTab(
                            tabName = tabName,
                            state = state,
                            params = cursor.getString(2)?.takeIf(String::isNotBlank),
                            checkedAtMillis = cursor.getLong(3),
                            lastAttemptAtMillis = cursor.getLong(4),
                        ),
                    )
                }
            }
        }
        return rows.takeIf { it.isNotEmpty() }?.let {
            StoredYouTubeChannelTabs(sourceKey, channelId, it)
        }
    }

    /**
     * pierwsza strona kanału ma komplet dostępnych kart. zapisujemy trzy stany
     * atomowo; błąd sieci nie woła tej metody, więc nigdy nie podmienia poprzedniej
     * wartości na fałszywe ABSENT.
     */
    @Synchronized
    fun saveYouTubeChannelTabs(
        sourceKey: String,
        channelId: String,
        presentParams: Map<String, String>,
        checkedAtMillis: Long = System.currentTimeMillis(),
    ) {
        if (presentParams.isEmpty() || checkedAtMillis <= 0L) return
        val safePresent = presentParams
            .filterKeys(REQUIRED_CHANNEL_TABS::contains)
            .mapValues { (_, params) -> params.take(MAX_CHANNEL_TAB_PARAMS_CHARS) }
        if (safePresent.isEmpty()) return

        val db = writableDatabase
        db.beginTransaction()
        try {
            REQUIRED_CHANNEL_TABS.forEach { tabName ->
                val params = safePresent[tabName]
                db.insertWithOnConflict(
                    "youtube_channel_tabs",
                    null,
                    ContentValues().apply {
                        put("source_key", sourceKey)
                        put("channel_id", channelId)
                        put("tab_name", tabName)
                        put(
                            "state",
                            if (params == null) {
                                YouTubeChannelTabState.ABSENT.name
                            } else {
                                YouTubeChannelTabState.PRESENT.name
                            },
                        )
                        if (params == null) putNull("params") else put("params", params)
                        put("checked_ms", checkedAtMillis)
                        put("last_attempt_ms", checkedAtMillis)
                    },
                    BundledDatabase.CONFLICT_REPLACE,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * zapisuje tylko pewnie rozpoznane karty z częściowej odpowiedzi.
     * brak karty w takim JSON-ie to nie dowód ABSENT.
     */
    @Synchronized
    fun markYouTubeChannelTabsPresent(
        sourceKey: String,
        channelId: String,
        presentParams: Map<String, String>,
        checkedAtMillis: Long = System.currentTimeMillis(),
    ) {
        if (checkedAtMillis <= 0L) return
        val safePresent = presentParams
            .filterKeys(REQUIRED_CHANNEL_TABS::contains)
            .mapValues { (_, params) -> params.take(MAX_CHANNEL_TAB_PARAMS_CHARS) }
            .filterValues(String::isNotBlank)
        if (safePresent.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            safePresent.forEach { (tabName, params) ->
                db.insertWithOnConflict(
                    "youtube_channel_tabs",
                    null,
                    ContentValues().apply {
                        put("source_key", sourceKey)
                        put("channel_id", channelId)
                        put("tab_name", tabName)
                        put("state", YouTubeChannelTabState.PRESENT.name)
                        put("params", params)
                        put("checked_ms", checkedAtMillis)
                        put("last_attempt_ms", checkedAtMillis)
                    },
                    BundledDatabase.CONFLICT_REPLACE,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Atomowo rezerwuje kontrolę brakujących/nieznanych kart. Próba ma osobny
     * znacznik od ostatniego udanego rozpoznania, więc awaria nie odświeża
     * ważności starego ABSENT.
     */
    @Synchronized
    fun claimYouTubeChannelTabsRefresh(
        sourceKey: String,
        channelId: String,
        nowMillis: Long = System.currentTimeMillis(),
        minAgeMillis: Long = MISSING_TAB_REFRESH_MILLIS,
    ): Boolean {
        if (nowMillis <= 0L || minAgeMillis <= 0L) return false
        val stored = getYouTubeChannelTabs(sourceKey, channelId)
        val targets = REQUIRED_CHANNEL_TABS.filter { tabName ->
            stored?.tabs?.get(tabName).needsRefreshAttempt(nowMillis, minAgeMillis)
        }
        if (targets.isEmpty()) return false

        val db = writableDatabase
        db.beginTransaction()
        try {
            targets.forEach { tabName ->
                val previous = stored?.tabs?.get(tabName)
                db.insertWithOnConflict(
                    "youtube_channel_tabs",
                    null,
                    ContentValues().apply {
                        put("source_key", sourceKey)
                        put("channel_id", channelId)
                        put("tab_name", tabName)
                        put("state", previous?.state?.name ?: YouTubeChannelTabState.UNKNOWN.name)
                        previous?.params?.let { put("params", it) } ?: putNull("params")
                        put("checked_ms", previous?.checkedAtMillis ?: 0L)
                        put("last_attempt_ms", nowMillis)
                    },
                    BundledDatabase.CONFLICT_REPLACE,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return true
    }

    /** Rezerwuje pierwszą próbę po lokalnej granicy dobowej (domyślnie 02:00). */
    @Synchronized
    fun claimYouTubeChannelTabsRefreshAfterBoundary(
        sourceKey: String,
        channelId: String,
        nowMillis: Long,
        attemptBoundaryMillis: Long,
    ): Boolean {
        if (nowMillis <= 0L || attemptBoundaryMillis <= 0L) return false
        val stored = getYouTubeChannelTabs(sourceKey, channelId)
        val targets = REQUIRED_CHANNEL_TABS.filter { tabName ->
            stored?.tabs?.get(tabName).needsRefreshAfterBoundary(
                nowMillis = nowMillis,
                attemptBoundaryMillis = attemptBoundaryMillis,
            )
        }
        if (targets.isEmpty()) return false

        val db = writableDatabase
        db.beginTransaction()
        try {
            targets.forEach { tabName ->
                val previous = stored?.tabs?.get(tabName)
                db.insertWithOnConflict(
                    "youtube_channel_tabs",
                    null,
                    ContentValues().apply {
                        put("source_key", sourceKey)
                        put("channel_id", channelId)
                        put("tab_name", tabName)
                        put("state", previous?.state?.name ?: YouTubeChannelTabState.UNKNOWN.name)
                        previous?.params?.let { put("params", it) } ?: putNull("params")
                        put("checked_ms", previous?.checkedAtMillis ?: 0L)
                        put("last_attempt_ms", nowMillis)
                    },
                    BundledDatabase.CONFLICT_REPLACE,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return true
    }

    @Synchronized
    fun getCreatorAvatar(creatorId: String): String? = readableDatabase.query(
        "creator_metadata",
        arrayOf("avatar_url"),
        "creator_id = ?",
        arrayOf(creatorId),
        null,
        null,
        null,
    ).use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }

    @Synchronized
    fun getCreatorAvatars(): Map<String, String> = readableDatabase.queryRows(
        "SELECT creator_id, avatar_url FROM creator_metadata WHERE avatar_url IS NOT NULL",
        null,
    ) { row -> row.getText(0) to row.getText(1) }
        .filter { (_, avatar) -> avatar.isNotBlank() }
        .toMap()

    @Synchronized
    fun saveCreatorAvatar(creatorId: String, avatarUrl: String) {
        val existing = getCreatorAvatarMetadata(creatorId)
        // resolver HTML nie nadpisuje tego, co już zweryfikowane i zapisane z SHA-256
        if (!existing?.sha256.isNullOrBlank()) return
        val values = ContentValues().apply {
            put("creator_id", creatorId)
            put("avatar_url", avatarUrl)
            existing?.sha256?.let { put("avatar_sha256", it) } ?: putNull("avatar_sha256")
            put("avatar_checked_ms", existing?.checkedAtMillis ?: 0L)
            put("avatar_attempt_ms", existing?.lastAttemptAtMillis ?: 0L)
            put("updated_ms", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            "creator_metadata",
            null,
            values,
            BundledDatabase.CONFLICT_REPLACE,
        )
    }

    @Synchronized
    fun getCreatorAvatarMetadata(creatorId: String): CreatorAvatarMetadata? =
        readableDatabase.query(
            "creator_metadata",
            arrayOf(
                "avatar_url",
                "avatar_sha256",
                "avatar_checked_ms",
                "avatar_attempt_ms",
            ),
            "creator_id = ?",
            arrayOf(creatorId),
            null,
            null,
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            CreatorAvatarMetadata(
                url = cursor.getString(0),
                sha256 = cursor.getString(1),
                checkedAtMillis = cursor.getLong(2),
                lastAttemptAtMillis = cursor.getLong(3),
            )
        }

    @Synchronized
    fun seedBundledCreatorAvatar(
        creatorId: String,
        assetUrl: String,
        sha256: String,
        checkedAtMillis: Long,
        force: Boolean = false,
    ) {
        val existing = getCreatorAvatarMetadata(creatorId)
        // nowszy APK może nieść świeższy avatar niż cache, ale nie cofamy
        // aktualizacji zrobionej lokalnie już po zbudowaniu APK
        if (!force && existing != null && existing.checkedAtMillis >= checkedAtMillis) return
        saveVerifiedCreatorAvatar(
            creatorId = creatorId,
            avatarUrl = assetUrl,
            sha256 = sha256,
            checkedAtMillis = checkedAtMillis,
        )
    }

    @Synchronized
    fun saveVerifiedCreatorAvatar(
        creatorId: String,
        avatarUrl: String,
        sha256: String,
        checkedAtMillis: Long,
    ) {
        require(SHA_256.matches(sha256.lowercase()))
        val values = ContentValues().apply {
            put("creator_id", creatorId)
            put("avatar_url", avatarUrl)
            put("avatar_sha256", sha256.lowercase())
            put("avatar_checked_ms", checkedAtMillis)
            put("avatar_attempt_ms", checkedAtMillis)
            put("updated_ms", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            "creator_metadata",
            null,
            values,
            BundledDatabase.CONFLICT_REPLACE,
        )
    }

    @Synchronized
    fun markCreatorAvatarChecked(creatorId: String, checkedAtMillis: Long) {
        val values = ContentValues().apply {
            put("avatar_checked_ms", checkedAtMillis)
            put("avatar_attempt_ms", checkedAtMillis)
        }
        writableDatabase.update(
            "creator_metadata",
            values,
            "creator_id = ?",
            arrayOf(creatorId),
        )
    }

    @Synchronized
    fun markCreatorAvatarAttempted(creatorId: String, attemptedAtMillis: Long) {
        val existing = getCreatorAvatarMetadata(creatorId)
        val values = ContentValues().apply {
            put("creator_id", creatorId)
            existing?.url?.let { put("avatar_url", it) } ?: putNull("avatar_url")
            existing?.sha256?.let { put("avatar_sha256", it) } ?: putNull("avatar_sha256")
            put("avatar_checked_ms", existing?.checkedAtMillis ?: 0L)
            put("avatar_attempt_ms", attemptedAtMillis)
            put("updated_ms", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            "creator_metadata",
            null,
            values,
            BundledDatabase.CONFLICT_REPLACE,
        )
    }

    @Synchronized
    fun creatorAvatarIdsDue(
        creatorIds: List<String>,
        checkedBeforeMillis: Long,
        attemptedBeforeMillis: Long,
    ): Set<String> {
        if (creatorIds.isEmpty()) return emptySet()
        val stored = readableDatabase.queryRows(
            "SELECT creator_id, avatar_checked_ms, avatar_attempt_ms FROM creator_metadata",
            null,
        ) { row -> row.getText(0) to (row.getLong(1) to row.getLong(2)) }
            .toMap()
        return creatorIds.filterTo(mutableSetOf()) { creatorId ->
            val state = stored[creatorId]
            state == null ||
                (state.first <= checkedBeforeMillis && state.second <= attemptedBeforeMillis)
        }
    }

    @Synchronized
    fun isSourceInitialized(sourceKey: String): Boolean = readableDatabase.query(
        "source_state",
        arrayOf("initialized"),
        "source_key = ?",
        arrayOf(sourceKey),
        null,
        null,
        null,
    ).use { cursor ->
        cursor.moveToFirst() && cursor.getInt(0) == 1
    }

    @Synchronized
    fun getSourceLastCheckedMillis(sourceKey: String): Long = readableDatabase.query(
        "source_state",
        arrayOf("last_checked_ms"),
        "source_key = ?",
        arrayOf(sourceKey),
        null,
        null,
        null,
    ).use { cursor ->
        if (cursor.moveToFirst()) cursor.getLong(0) else 0L
    }

    @Synchronized
    fun getNotificationCursor(sourceKey: String): NotificationCursor? = readableDatabase.query(
        "source_state",
        arrayOf("last_notification_video_id", "last_notification_published_ms"),
        "source_key = ?",
        arrayOf(sourceKey),
        null,
        null,
        null,
    ).use { cursor ->
        if (!cursor.moveToFirst() || cursor.isNull(0)) {
            null
        } else {
            cursor.getString(0)?.takeIf { it.isNotBlank() }?.let { videoId ->
                NotificationCursor(videoId, cursor.getLong(1))
            }
        }
    }

    @Synchronized
    fun getRssNotificationSnapshot(sourceKey: String): RssNotificationSnapshot? =
        readableDatabase.query(
            "source_state",
            arrayOf("rss_known_video_ids"),
            "source_key = ?",
            arrayOf(sourceKey),
            null,
            null,
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) {
                null
            } else {
                RssNotificationSnapshot(
                    knownVideoIds = cursor.getString(0)
                        .orEmpty()
                        .split(',')
                        .filter(YOUTUBE_VIDEO_ID::matches)
                        .distinct()
                        .take(MAX_RSS_KNOWN_VIDEO_IDS),
                )
            }
        }

    @Synchronized
    fun saveRssNotificationSnapshot(sourceKey: String, knownVideoIds: Collection<String>) {
        val encoded = knownVideoIds
            .filter(YOUTUBE_VIDEO_ID::matches)
            .distinct()
            .take(MAX_RSS_KNOWN_VIDEO_IDS)
            .joinToString(",")
        writableDatabase.insertWithOnConflict(
            "source_state",
            null,
            ContentValues().apply { put("source_key", sourceKey) },
            BundledDatabase.CONFLICT_IGNORE,
        )
        writableDatabase.update(
            "source_state",
            ContentValues().apply { put("rss_known_video_ids", encoded) },
            "source_key = ?",
            arrayOf(sourceKey),
        )
    }

    @Synchronized
    fun seedSource(
        sourceKey: String,
        creator: Creator,
        items: List<YouTubeHistoryItem>,
    ) {
        writableDatabase.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            val newestEntry = items
                .map(YouTubeHistoryItem::entry)
                .maxWithOrNull(
                    compareBy<VideoEntry> { it.publishedAtMillis }.thenBy { it.id },
                )
            items.forEach { item ->
                val entry = item.entry
                val rowId = insertVideoInternal(
                    db = writableDatabase,
                    creator = creator,
                    entry = entry,
                    kind = item.kind,
                    notified = true,
                    detectedAt = now,
                    classificationVersion =
                        if (item.evidence.isFinal) CURRENT_CLASSIFIER_VERSION else 0,
                    kindEvidence = item.evidence,
                    publishedAtEvidence = item.publishedAtEvidence,
                    notificationChecked = true,
                )
                if (rowId == -1L) {
                    reconcileHistoricalYouTubeEntry(
                        entry = entry,
                        publishedAtEvidence = item.publishedAtEvidence,
                        shouldNotify = false,
                    )
                    applyKindDecisionInternal(
                        db = writableDatabase,
                        videoId = entry.id,
                        incoming = VideoKindDecision(item.kind, item.evidence),
                    )
                }
            }
            val sourceValues = ContentValues().apply {
                put("source_key", sourceKey)
                put("initialized", 1)
                put("last_checked_ms", now)
                putNull("last_error")
                newestEntry?.let {
                    put("last_notification_video_id", it.id)
                    put("last_notification_published_ms", it.publishedAtMillis)
                }
            }
            writableDatabase.insertWithOnConflict(
                "source_state",
                null,
                sourceValues,
                BundledDatabase.CONFLICT_IGNORE,
            )
            writableDatabase.update(
                "source_state",
                ContentValues().apply {
                    put("initialized", 1)
                    put("last_checked_ms", now)
                    putNull("last_error")
                    newestEntry?.let {
                        put("last_notification_video_id", it.id)
                        put("last_notification_published_ms", it.publishedAtMillis)
                    }
                },
                "source_key = ?",
                arrayOf(sourceKey),
            )
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    @Synchronized
    fun saveNotificationCursor(sourceKey: String, entry: VideoEntry) {
        val current = getNotificationCursor(sourceKey)
        if (current != null) {
            val movesBackwards = entry.publishedAtMillis < current.publishedAtMillis ||
                (
                    entry.publishedAtMillis == current.publishedAtMillis &&
                        entry.id <= current.videoId
                    )
            if (movesBackwards) return
        }

        writableDatabase.insertWithOnConflict(
            "source_state",
            null,
            ContentValues().apply { put("source_key", sourceKey) },
            BundledDatabase.CONFLICT_IGNORE,
        )
        writableDatabase.update(
            "source_state",
            ContentValues().apply {
                put("last_notification_video_id", entry.id)
                put("last_notification_published_ms", entry.publishedAtMillis)
            },
            "source_key = ?",
            arrayOf(sourceKey),
        )
    }

    @Synchronized
    fun markSourceChecked(sourceKey: String, error: String?) {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("source_key", sourceKey)
            if (error == null) put("last_checked_ms", now)
            if (error == null) putNull("last_error") else put("last_error", error.take(500))
        }
        writableDatabase.insertWithOnConflict(
            "source_state",
            null,
            values,
            BundledDatabase.CONFLICT_IGNORE,
        )
        writableDatabase.update(
            "source_state",
            ContentValues().apply {
                if (error == null) put("last_checked_ms", now)
                if (error == null) putNull("last_error") else put("last_error", error.take(500))
            },
            "source_key = ?",
            arrayOf(sourceKey),
        )
    }

    /**
     * Seed może zmienić się między wydaniami aplikacji. Aktualizujemy wyłącznie
     * prior, pozostawiając lokalnie nauczone obserwacje użytkownika.
     */
    @Synchronized
    fun ensureSourcePrioritySeeds(seeds: List<SourcePrioritySeed>) {
        if (seeds.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            seeds.distinctBy(SourcePrioritySeed::sourceKey).forEach { seed ->
                db.insertWithOnConflict(
                    "source_priority",
                    null,
                    ContentValues().apply {
                        put("source_key", seed.sourceKey)
                        put("prior_rate_per_day", seed.priorRatePerDay)
                        put("prior_exposure_days", seed.priorExposureDays)
                    },
                    BundledDatabase.CONFLICT_IGNORE,
                )
                db.update(
                    "source_priority",
                    ContentValues().apply {
                        put("prior_rate_per_day", seed.priorRatePerDay)
                        put("prior_exposure_days", seed.priorExposureDays)
                    },
                    """
                    source_key = ? AND (
                        prior_rate_per_day <> ? OR prior_exposure_days <> ?
                    )
                    """.trimIndent(),
                    arrayOf(
                        seed.sourceKey,
                        seed.priorRatePerDay.toString(),
                        seed.priorExposureDays.toString(),
                    ),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun sourcePriorityStats(sourceKeys: List<String>): Map<String, SourcePriorityStats> {
        val uniqueKeys = sourceKeys.distinct()
        if (uniqueKeys.isEmpty()) return emptyMap()
        val placeholders = uniqueKeys.joinToString(",") { "?" }
        return readableDatabase.queryRows(
            """
            SELECT p.source_key,
                   COALESCE(s.initialized, 0),
                   p.prior_rate_per_day,
                   p.prior_exposure_days,
                   p.event_mass,
                   p.exposure_days,
                   p.last_model_update_ms,
                   MAX(p.last_attempt_ms, COALESCE(s.last_checked_ms, 0)),
                   COALESCE(s.last_checked_ms, 0),
                   p.last_hit_ms,
                   p.consecutive_failures
            FROM source_priority p
            LEFT JOIN source_state s ON s.source_key = p.source_key
            WHERE p.source_key IN ($placeholders)
            """.trimIndent(),
            uniqueKeys.toTypedArray(),
        ) { row ->
            SourcePriorityStats(
                sourceKey = row.getText(0),
                initialized = row.getLong(1) == 1L,
                priorRatePerDay = row.getDouble(2),
                priorExposureDays = row.getDouble(3),
                eventMass = row.getDouble(4),
                exposureDays = row.getDouble(5),
                lastModelUpdateMillis = row.getLong(6),
                lastAttemptMillis = row.getLong(7),
                lastSuccessfulCheckMillis = row.getLong(8),
                lastHitMillis = row.getLong(9),
                consecutiveFailures = row.getLong(10).toInt(),
            )
        }.associateBy(SourcePriorityStats::sourceKey)
    }

    /**
     * Cały przebieg synchronizacji zapisuje model jedną transakcją. Pozwala to
     * uniknąć dziesiątek niezależnych zapisów wykonywanych przez korutyny.
     */
    @Synchronized
    fun updateSourcePriorities(updates: List<SourcePriorityUpdate>) {
        if (updates.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            updates.distinctBy(SourcePriorityUpdate::sourceKey).forEach { update ->
                db.update(
                    "source_priority",
                    ContentValues().apply {
                        put("event_mass", update.eventMass)
                        put("exposure_days", update.exposureDays)
                        put("last_model_update_ms", update.lastModelUpdateMillis)
                        put("last_attempt_ms", update.lastAttemptMillis)
                        put("last_hit_ms", update.lastHitMillis)
                        put("consecutive_failures", update.consecutiveFailures)
                    },
                    "source_key = ?",
                    arrayOf(update.sourceKey),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun containsVideo(videoId: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM video_history WHERE video_id = ? LIMIT 1",
        arrayOf(videoId),
    ).use { cursor -> cursor.moveToFirst() }

    @Synchronized
    fun updateFromVerifiedYouTubeEntry(
        entry: VideoEntry,
        publishedAtEvidence: PublishedAtEvidence,
    ) {
        val db = writableDatabase
        db.update(
            "video_history",
            ContentValues().apply {
                put("title", entry.title)
                put("url", entry.url)
                putPublishedAtIfTrusted(
                    db = db,
                    videoId = entry.id,
                    publishedAtMillis = entry.publishedAtMillis,
                    evidence = publishedAtEvidence,
                )
                put("origin", VideoOrigin.YOUTUBE.name)
            },
            "video_id = ?",
            arrayOf(entry.id),
        )
    }

    /**
     * Rekord dodany przez ekran historii nie może sam rozstrzygać, czy należy
     * wysłać powiadomienie. Pierwsza właściwa synchronizacja źródła robi to
     * dokładnie raz względem jego trwałego kursora.
     *
     * @return true tylko wtedy, gdy rekord po raz pierwszy stał się oczekującym
     * powiadomieniem.
     */
    @Synchronized
    fun reconcileHistoricalYouTubeEntry(
        entry: VideoEntry,
        publishedAtEvidence: PublishedAtEvidence,
        shouldNotify: Boolean,
    ): Boolean {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("title", entry.title)
            put("url", entry.url)
            putPublishedAtIfTrusted(
                db = db,
                videoId = entry.id,
                publishedAtMillis = entry.publishedAtMillis,
                evidence = publishedAtEvidence,
            )
            put("origin", VideoOrigin.YOUTUBE.name)
            put("notification_checked", 1)
            if (shouldNotify) put("notified", 0)
        }
        val updated = db.update(
            "video_history",
            values,
            "video_id = ? AND origin = ? AND notification_checked = 0",
            arrayOf(entry.id, VideoOrigin.YOUTUBE.name),
        )
        if (updated == 0) {
            updateFromVerifiedYouTubeEntry(entry, publishedAtEvidence)
        }
        return updated == 1 && shouldNotify
    }

    /**
     * atomowo scala wpis potwierdzony przez synchronizowane źródło YT. dzięki temu
     * równoległy backfill nie wciśnie rekordu między sprawdzeniem a zapisem i nie
     * ukryje powiadomienia na zawsze.
     *
     * @return true, gdy powstała nowa pozycja czekająca na dostarczenie powiadomienia.
     */
    @Synchronized
    fun upsertVerifiedVideoFromSync(
        creator: Creator,
        entry: VideoEntry,
        publishedAtEvidence: PublishedAtEvidence,
        kind: VideoKind,
        evidence: VideoKindEvidence,
        shouldNotify: Boolean,
    ): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existing = db.query(
                "video_history",
                arrayOf("notification_checked"),
                "video_id = ?",
                arrayOf(entry.id),
                null,
                null,
                null,
            ).use { cursor ->
                if (!cursor.moveToFirst()) {
                    null
                } else {
                    cursor.getInt(0) == 1
                }
            }

            val becamePending = when {
                existing == null -> {
                    if (!shouldNotify) {
                        false
                    } else {
                        insertVideoInternal(
                            db = db,
                            creator = creator,
                            entry = entry.copy(origin = VideoOrigin.YOUTUBE),
                            kind = kind,
                            notified = false,
                            detectedAt = System.currentTimeMillis(),
                            classificationVersion =
                                if (evidence.isFinal) CURRENT_CLASSIFIER_VERSION else 0,
                            kindEvidence = evidence,
                            publishedAtEvidence = publishedAtEvidence,
                            notificationChecked = true,
                        ) != -1L
                    }
                }

                !existing -> {
                    db.update(
                        "video_history",
                        syncMetadataValues(
                            db = db,
                            creator = creator,
                            entry = entry,
                            publishedAtEvidence = publishedAtEvidence,
                            notified = if (shouldNotify) false else null,
                            includeCreator = true,
                        ),
                        "video_id = ? AND origin = ? AND notification_checked = 0",
                        arrayOf(entry.id, VideoOrigin.YOUTUBE.name),
                    )
                    shouldNotify
                }

                else -> {
                    db.update(
                        "video_history",
                        syncMetadataValues(
                            db = db,
                            creator = creator,
                            entry = entry,
                            publishedAtEvidence = publishedAtEvidence,
                            notified = null,
                            includeCreator = false,
                        ),
                        "video_id = ? AND origin = ?",
                        arrayOf(entry.id, VideoOrigin.YOUTUBE.name),
                    )
                    false
                }
            }
            if (existing != null) {
                applyKindDecisionInternal(
                    db = db,
                    videoId = entry.id,
                    incoming = VideoKindDecision(kind, evidence),
                )
            }
            db.setTransactionSuccessful()
            return becamePending
        } finally {
            db.endTransaction()
        }
    }

    private fun syncMetadataValues(
        db: BundledDatabase,
        creator: Creator,
        entry: VideoEntry,
        publishedAtEvidence: PublishedAtEvidence,
        notified: Boolean?,
        includeCreator: Boolean,
    ): ContentValues = ContentValues().apply {
        if (includeCreator) {
            put("creator_id", creator.id)
            put("creator_name", creator.name)
        }
        put("title", entry.title)
        put("url", entry.url)
        putPublishedAtIfTrusted(
            db = db,
            videoId = entry.id,
            publishedAtMillis = entry.publishedAtMillis,
            evidence = publishedAtEvidence,
        )
        notified?.let { put("notified", if (it) 1 else 0) }
        put("origin", VideoOrigin.YOUTUBE.name)
        put("notification_checked", 1)
    }

    @Synchronized
    fun insertNewVideo(
        creator: Creator,
        entry: VideoEntry,
        kind: VideoKind,
        notified: Boolean,
        evidence: VideoKindEvidence = VideoKindEvidence.API_METADATA,
        publishedAtEvidence: PublishedAtEvidence = PublishedAtEvidence.UNKNOWN,
    ): Boolean = insertVideoInternal(
        db = writableDatabase,
        creator = creator,
        entry = entry,
        kind = kind,
        notified = notified,
        detectedAt = System.currentTimeMillis(),
        classificationVersion =
            if (evidence.isFinal) CURRENT_CLASSIFIER_VERSION else 0,
        kindEvidence = evidence,
        publishedAtEvidence = publishedAtEvidence,
        notificationChecked = true,
    ) != -1L

    @Synchronized
    fun insertHistoricalVideos(
        creator: Creator,
        items: List<YouTubeHistoryItem>,
    ): Int = insertHistoricalVideosInternal(
        creator = creator,
        items = items,
    )

    private fun insertHistoricalVideosInternal(
        creator: Creator,
        items: List<YouTubeHistoryItem>,
    ): Int {
        if (items.isEmpty()) return 0
        val distinctItems = items.distinctBy { it.entry.id }
        var inserted = 0
        val now = System.currentTimeMillis()
        val db = writableDatabase
        db.beginTransaction()
        try {
            // jeden odczyt zamiast dwóch SELECT-ów na każdy istniejący film (data + rodzaj)
            val existingEvidence = storedVideoEvidence(
                db = db,
                videoIds = distinctItems.map { it.entry.id },
            )
            distinctItems.forEach { item ->
                val rowId = insertVideoInternal(
                    db = db,
                    creator = creator,
                    entry = item.entry,
                    kind = item.kind,
                    notified = true,
                    detectedAt = now,
                    classificationVersion = if (
                        item.kind == VideoKind.UNKNOWN || !item.evidence.isFinal
                    ) {
                        0
                    } else {
                        CURRENT_CLASSIFIER_VERSION
                    },
                    kindEvidence = item.evidence,
                    publishedAtEvidence = item.publishedAtEvidence,
                    notificationChecked = false,
                )
                if (rowId != -1L) {
                    inserted += 1
                } else {
                    updateHistoricalItem(
                        creator = creator,
                        item = item,
                        existing = existingEvidence[item.entry.id],
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return inserted
    }

    private fun updateHistoricalItem(
        creator: Creator,
        item: YouTubeHistoryItem,
        existing: StoredVideoEvidence?,
    ) {
        val db = writableDatabase
        db.update(
            "video_history",
            ContentValues().apply {
                put("creator_id", creator.id)
                put("creator_name", creator.name)
                put("title", item.entry.title)
                put("url", item.entry.url)
                putPublishedAtIfTrusted(
                    db = db,
                    videoId = item.entry.id,
                    publishedAtMillis = item.entry.publishedAtMillis,
                    evidence = item.publishedAtEvidence,
                    knownEvidenceRank = existing?.publishedEvidenceRank,
                )
                put("origin", VideoOrigin.YOUTUBE.name)
            },
            "video_id = ?",
            arrayOf(item.entry.id),
        )
        applyKindDecisionInternal(
            db = db,
            videoId = item.entry.id,
            incoming = VideoKindDecision(item.kind, item.evidence),
            knownCurrent = existing?.kindDecision,
            recordAttemptWhenUnchanged = false,
        )
    }

    private fun ContentValues.putPublishedAtIfTrusted(
        db: BundledDatabase,
        videoId: String,
        publishedAtMillis: Long,
        evidence: PublishedAtEvidence,
        knownEvidenceRank: Int? = null,
    ) {
        val existingEvidenceRank = knownEvidenceRank ?: db.query(
            "video_history",
            arrayOf("published_evidence"),
            "video_id = ?",
            arrayOf(videoId),
            null,
            null,
            null,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else null }
        if (shouldReplacePublishedAt(existingEvidenceRank, evidence)) {
            put("published_ms", publishedAtMillis)
            put("published_evidence", evidence.rank)
        }
    }

    private fun insertVideoInternal(
        db: BundledDatabase,
        creator: Creator,
        entry: VideoEntry,
        kind: VideoKind,
        notified: Boolean,
        detectedAt: Long,
        classificationVersion: Int,
        kindEvidence: VideoKindEvidence = VideoKindEvidence.NONE,
        publishedAtEvidence: PublishedAtEvidence = PublishedAtEvidence.UNKNOWN,
        notificationChecked: Boolean,
    ): Long = db.insertWithOnConflict(
        "video_history",
        null,
        ContentValues().apply {
            put("video_id", entry.id)
            put("creator_id", creator.id)
            put("creator_name", creator.name)
            put("title", entry.title)
            put("url", entry.url)
            put("published_ms", entry.publishedAtMillis)
            put("published_evidence", publishedAtEvidence.rank)
            put("detected_ms", detectedAt)
            put("kind", kind.name)
            put("notified", if (notified) 1 else 0)
            put("classification_version", classificationVersion)
            put("kind_evidence", kindEvidence.rank)
            put("origin", entry.origin.name)
            put("notification_checked", if (notificationChecked) 1 else 0)
        },
        BundledDatabase.CONFLICT_IGNORE,
    )

    @Synchronized
    fun pendingUpcoming(selectedCreatorIds: Set<String>): List<HistoryItem> {
        if (selectedCreatorIds.isEmpty()) return emptyList()
        val placeholders = selectedCreatorIds.joinToString(",") { "?" }
        return readableDatabase.queryRows(
            """
            SELECT video_id, creator_id, creator_name, title, url,
                   published_ms, detected_ms, kind, notified, origin, is_favorite,
                   description_data, description_codec, description_original_size
            FROM video_history
            WHERE kind = ?
              AND notified = 0
              AND origin = ?
              AND creator_id IN ($placeholders)
            ORDER BY published_ms DESC, detected_ms DESC
            LIMIT 50
            """.trimIndent(),
            arrayOf(
                VideoKind.UPCOMING.name,
                VideoOrigin.YOUTUBE.name,
                *selectedCreatorIds.toTypedArray(),
            ),
            mapper = ::readHistoryItem,
        )
    }

    @Synchronized
    fun pendingNotifications(
        selectedCreatorIds: Set<String>,
        limit: Int = 2_000,
    ): List<HistoryItem> {
        if (selectedCreatorIds.isEmpty()) return emptyList()
        val placeholders = selectedCreatorIds.joinToString(",") { "?" }
        return readableDatabase.queryRows(
            """
            SELECT video_id, creator_id, creator_name, title, url,
                   published_ms, detected_ms, kind, notified, origin, is_favorite,
                   description_data, description_codec, description_original_size
            FROM video_history
            WHERE notified = 0
              AND kind NOT IN (?, ?)
              AND origin = ?
              AND creator_id IN ($placeholders)
            ORDER BY published_ms DESC, detected_ms DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(
                VideoKind.UPCOMING.name,
                VideoKind.UNKNOWN.name,
                VideoOrigin.YOUTUBE.name,
                *selectedCreatorIds.toTypedArray(),
                limit.coerceIn(1, 10_000).toString(),
            ),
            mapper = ::readHistoryItem,
        )
    }

    @Synchronized
    fun markVideosNotified(videoIds: Collection<String>) {
        if (videoIds.isEmpty()) return
        writableDatabase.beginTransaction()
        try {
            val values = ContentValues().apply { put("notified", 1) }
            videoIds.distinct().forEach { videoId ->
                writableDatabase.update(
                    "video_history",
                    values,
                    "video_id = ?",
                    arrayOf(videoId),
                )
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    @Synchronized
    fun getOrCreateNotificationId(videoId: String): Int {
        require(videoId.isNotBlank())
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existing = db.query(
                "notification_ids",
                arrayOf("notification_id"),
                "video_id = ?",
                arrayOf(videoId),
                null,
                null,
                null,
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else null
            }
            val storedId = existing ?: db.insertOrThrow(
                "notification_ids",
                null,
                ContentValues().apply { put("video_id", videoId) },
            )
            check(storedId in 1..Int.MAX_VALUE.toLong()) {
                "Wyczerpano zakres identyfikatorów powiadomień"
            }
            db.setTransactionSuccessful()
            return storedId.toInt()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    @SuppressLint("UseKtx")
    fun markVideoState(
        videoId: String,
        kind: VideoKind,
        evidence: VideoKindEvidence,
        notified: Boolean,
    ) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            applyKindDecisionInternal(
                db = db,
                videoId = videoId,
                incoming = VideoKindDecision(kind, evidence),
            )
            db.update(
                "video_history",
                ContentValues().apply {
                    put("notified", if (notified) 1 else 0)
                },
                "video_id = ? AND origin = ?",
                arrayOf(videoId, VideoOrigin.YOUTUBE.name),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun unclassifiedHistory(
        selectedCreatorIds: Set<String>,
        days: Int = 60,
        limit: Int = 12,
    ): List<HistoryItem> {
        if (selectedCreatorIds.isEmpty()) return emptyList()
        val cutoff = System.currentTimeMillis() - days.coerceIn(1, 365).toLong() * DAY_MILLIS
        val placeholders = selectedCreatorIds.joinToString(",") { "?" }
        return readableDatabase.queryRows(
            """
            SELECT video_id, creator_id, creator_name, title, url,
                   published_ms, detected_ms, kind, notified, origin, is_favorite,
                   description_data, description_codec, description_original_size
            FROM video_history
            WHERE classification_version = 0
              AND published_ms >= ?
              AND origin = ?
              AND creator_id IN ($placeholders)
            ORDER BY classification_last_attempt_ms ASC, published_ms DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(
                cutoff.toString(),
                VideoOrigin.YOUTUBE.name,
                *selectedCreatorIds.toTypedArray(),
                limit.coerceIn(1, 100).toString(),
            ),
            mapper = ::readHistoryItem,
        )
    }

    @Synchronized
    fun unclassifiedVideoIds(videoIds: Collection<String>): List<String> {
        val distinctIds = videoIds
            .asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_CLASSIFICATION_QUERY_IDS)
            .toList()
        if (distinctIds.isEmpty()) return emptyList()
        val placeholders = distinctIds.joinToString(",") { "?" }
        return readableDatabase.queryRows(
            """
            SELECT video_id
            FROM video_history
            WHERE classification_version = 0
              AND origin = ?
              AND video_id IN ($placeholders)
            """.trimIndent(),
            arrayOf(VideoOrigin.YOUTUBE.name, *distinctIds.toTypedArray()),
        ) { row -> row.getText(0) }
    }

    @Synchronized
    fun markVideoClassification(
        videoId: String,
        kind: VideoKind,
        evidence: VideoKindEvidence,
    ) {
        applyKindDecisionInternal(
            db = writableDatabase,
            videoId = videoId,
            incoming = VideoKindDecision(kind, evidence),
        )
    }

    @Synchronized
    @SuppressLint("UseKtx")
    fun markVideoClassifications(decisions: Map<String, VideoKindDecision>) {
        val applicable = decisions.filterValues { decision ->
            decision.kind != VideoKind.UNKNOWN &&
                decision.evidence != VideoKindEvidence.NONE
        }
        if (applicable.isEmpty()) return

        val db = writableDatabase
        db.beginTransaction()
        try {
            applicable.forEach { (videoId, decision) ->
                applyKindDecisionInternal(db, videoId, decision)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun applyKindDecisionInternal(
        db: BundledDatabase,
        videoId: String,
        incoming: VideoKindDecision,
        knownCurrent: VideoKindDecision? = null,
        recordAttemptWhenUnchanged: Boolean = true,
    ): Boolean {
        if (
            incoming.kind == VideoKind.UNKNOWN ||
            incoming.evidence == VideoKindEvidence.NONE
        ) {
            return false
        }
        val current = knownCurrent ?: db.query(
            "video_history",
            arrayOf("kind", "kind_evidence"),
            "video_id = ? AND origin = ?",
            arrayOf(videoId, VideoOrigin.YOUTUBE.name),
            null,
            null,
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                return false
            }
            VideoKindDecision(
                kind = runCatching { VideoKind.valueOf(cursor.getString(0)) }
                    .getOrDefault(VideoKind.UNKNOWN),
                evidence = evidenceFromRank(cursor.getInt(1)),
            )
        }
        val selected = chooseVideoKindDecision(current, incoming)
        if (selected == current) {
            if (recordAttemptWhenUnchanged) {
                db.update(
                    "video_history",
                    ContentValues().apply {
                        put("classification_last_attempt_ms", System.currentTimeMillis())
                    },
                    "video_id = ? AND origin = ?",
                    arrayOf(videoId, VideoOrigin.YOUTUBE.name),
                )
            }
            return false
        }
        db.update(
            "video_history",
            ContentValues().apply {
                put("kind", selected.kind.name)
                put("kind_evidence", selected.evidence.rank)
                put(
                    "classification_version",
                    if (selected.evidence.isFinal) CURRENT_CLASSIFIER_VERSION else 0,
                )
                put("classification_attempts", 0)
                put("classification_last_attempt_ms", System.currentTimeMillis())
            },
            "video_id = ? AND origin = ?",
            arrayOf(videoId, VideoOrigin.YOUTUBE.name),
        )
        return true
    }

    private fun storedVideoEvidence(
        db: BundledDatabase,
        videoIds: List<String>,
    ): Map<String, StoredVideoEvidence> {
        val ids = videoIds.distinct().take(MAX_EVIDENCE_QUERY_IDS)
        if (ids.isEmpty()) return emptyMap()
        val placeholders = ids.joinToString(",") { "?" }
        return db.queryRows(
            """
            SELECT video_id, published_evidence, kind, kind_evidence
            FROM video_history
            WHERE origin = ? AND video_id IN ($placeholders)
            """.trimIndent(),
            arrayOf(VideoOrigin.YOUTUBE.name, *ids.toTypedArray()),
        ) { row ->
            row.getText(0) to StoredVideoEvidence(
                publishedEvidenceRank = row.getLong(1).toInt(),
                kindDecision = VideoKindDecision(
                    kind = runCatching { VideoKind.valueOf(row.getText(2)) }
                        .getOrDefault(VideoKind.UNKNOWN),
                    evidence = evidenceFromRank(row.getLong(3).toInt()),
                ),
            )
        }.toMap()
    }

    private fun evidenceFromRank(rank: Int): VideoKindEvidence =
        VideoKindEvidence.entries
            .filter { it.rank <= rank }
            .maxByOrNull(VideoKindEvidence::rank)
            ?: VideoKindEvidence.NONE

    @Synchronized
    fun recordFailedVideoClassification(videoId: String) {
        writableDatabase.execSQL(
            """
            UPDATE video_history
            SET classification_attempts = classification_attempts + 1,
                classification_last_attempt_ms = ?,
                classification_version = CASE
                    WHEN classification_attempts + 1 >= ? THEN ?
                    ELSE classification_version
                END
            WHERE video_id = ? AND origin = ?
            """.trimIndent(),
            arrayOf<Any>(
                System.currentTimeMillis(),
                MAX_CLASSIFICATION_ATTEMPTS,
                CURRENT_CLASSIFIER_VERSION,
                videoId,
                VideoOrigin.YOUTUBE.name,
            ),
        )
    }

    @Synchronized
    fun recordFailedVideoClassifications(videoIds: Collection<String>) {
        val distinctIds = videoIds
            .asSequence()
            .filter(String::isNotBlank)
            .distinct()
            .take(MAX_CLASSIFICATION_QUERY_IDS)
            .toList()
        if (distinctIds.isEmpty()) return

        val placeholders = distinctIds.joinToString(",") { "?" }
        writableDatabase.execSQL(
            """
            UPDATE video_history
            SET classification_attempts = classification_attempts + 1,
                classification_last_attempt_ms = ?,
                classification_version = CASE
                    WHEN classification_attempts + 1 >= ? THEN ?
                    ELSE classification_version
                END
            WHERE origin = ? AND video_id IN ($placeholders)
            """.trimIndent(),
            arrayOf<Any>(
                System.currentTimeMillis(),
                MAX_CLASSIFICATION_ATTEMPTS,
                CURRENT_CLASSIFIER_VERSION,
                VideoOrigin.YOUTUBE.name,
                *distinctIds.toTypedArray(),
            ),
        )
    }

    @Synchronized
    fun pendingDescriptions(
        selectedCreatorIds: Set<String>,
        cutoffMillis: Long,
        limit: Int,
        retryBeforeMillis: Long,
    ): List<PendingDescription> {
        if (selectedCreatorIds.isEmpty()) return emptyList()
        val nowMillis = System.currentTimeMillis()
        requeueExpiredScheduledDescriptionMarkers(nowMillis)
        val placeholders = selectedCreatorIds.joinToString(",") { "?" }
        return readableDatabase.queryRows(
            """
            SELECT video_id, creator_id, title, kind
            FROM video_history
            WHERE creator_id IN ($placeholders)
              AND published_ms >= ?
              AND description_state <> 1
              AND (description_state <> 3 OR published_ms <= ?)
              AND description_attempts < ?
              AND description_last_attempt_ms <= ?
              -- nie pobieramy transkrypcji/opisu dla streamów wykrytych jako prywatne
              AND video_id NOT IN (SELECT video_id FROM stream_privacy WHERE is_private = 1)
            -- Gotowe do ponowienia błędy są nieliczne i mają twardy limit
            -- prób. Obsługujemy je przed dużą kolejką nowych rekordów, aby
            -- pojedyncza chwilowa awaria nie blokowała opisu przez wiele dni.
            ORDER BY CASE WHEN description_state = 2 THEN 0 ELSE 1 END,
                     description_last_attempt_ms ASC,
                     published_ms DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf<Any>(
                *selectedCreatorIds.toTypedArray(),
                *descriptionPendingNumericArguments(
                    cutoffMillis = cutoffMillis,
                    scheduledBeforeMillis = nowMillis,
                    maxAttempts = MAX_DESCRIPTION_ATTEMPTS,
                    retryBeforeMillis = retryBeforeMillis,
                    limit = limit.coerceIn(1, MAX_DESCRIPTION_BATCH),
                ).toTypedArray(),
            ),
        ) { row ->
            PendingDescription(
                videoId = row.getText(0),
                creatorId = row.getText(1),
                title = row.getText(2),
                kind = runCatching { VideoKind.valueOf(row.getText(3)) }
                    .getOrDefault(VideoKind.VIDEO),
            )
        }
    }

    /**
     * Starsze wersje zapisywały tymczasowy marker planowanego streamu jako
     * końcowy opis (state=1). Marker jest krótki, więc kodek przechowuje go jako
     * UTF-8. Po nadejściu terminu zmieniamy wyłącznie dokładnie ten marker na
     * stan odnawialny; prawdziwych opisów planowanych transmisji nie ruszamy.
     */
    private fun requeueExpiredScheduledDescriptionMarkers(nowMillis: Long) {
        writableDatabase.execSQL(
            """
            UPDATE video_history
            SET description_state = 3,
                description_attempts = 0,
                description_last_attempt_ms = 0
            WHERE description_state = 1
              AND published_ms <= ?
              AND description_codec = ?
              AND CAST(description_data AS TEXT) = ?
            """.trimIndent(),
            arrayOf<Any>(
                nowMillis,
                StoredDescriptionCodec.UTF8.databaseValue,
                SCHEDULED_STREAM_DESCRIPTION_MARKER,
            ),
        )
    }

    @Synchronized
    fun saveDescription(videoId: String, description: String): Boolean =
        saveDescriptionWithStats(videoId, description)?.saved == true

    @Synchronized
    internal fun saveDescriptionWithStats(
        videoId: String,
        description: String,
    ): DescriptionStorageResult? {
        if (!YOUTUBE_VIDEO_ID.matches(videoId)) return null
        val searchable = DescriptionCodec.searchableText(description)
        val encoded = runCatching { DescriptionCodec.encode(description) }.getOrNull()
            ?: run {
                recordDescriptionFailure(videoId)
                return null
            }
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val updated = db.update(
                "video_history",
                ContentValues().apply {
                    put("description_data", encoded.data)
                    put("description_codec", encoded.codec.databaseValue)
                    put("description_original_size", encoded.originalSize)
                    put("description_state", descriptionStorageState(description))
                    put(
                        "description_availability",
                        descriptionAvailabilityDatabaseValue(
                            descriptionAvailability(description),
                        ),
                    )
                    put("description_attempts", 0)
                    put("description_last_attempt_ms", System.currentTimeMillis())
                    if (encoded.dictionaryId == null) putNull("description_dictionary_id")
                    else put("description_dictionary_id", encoded.dictionaryId)
                    if (encoded.dictionaryVersion == null) {
                        putNull("description_dictionary_version")
                    } else {
                        put("description_dictionary_version", encoded.dictionaryVersion)
                    }
                },
                "video_id = ?",
                arrayOf(videoId),
            ) == 1
            if (updated) {
                db.execSQL(
                    """
                    UPDATE video_history_fts
                    SET description = ?
                    WHERE rowid = (SELECT fts_rowid FROM video_history_fts_map WHERE video_id = ?)
                    """.trimIndent(),
                    arrayOf<Any>(normalizePolishSearchText(searchable), videoId),
                )
            }
            db.setTransactionSuccessful()
            DescriptionStorageResult(
                saved = updated,
                originalBytes = encoded.originalSize,
                storedBytes = encoded.data.size,
                codec = encoded.codec,
                compressionMethod = when (encoded.codec) {
                    StoredDescriptionCodec.ZSTD_5 -> if (encoded.dictionaryId != null) {
                        "ZSTD_LEVEL_5_WITH_DICTIONARY"
                    } else {
                        "ZSTD_LEVEL_5_WITHOUT_DICTIONARY"
                    }
                    StoredDescriptionCodec.UTF8 -> "UTF8_UNCOMPRESSED"
                    StoredDescriptionCodec.NONE -> "NONE"
                },
                dictionaryId = encoded.dictionaryId,
                dictionaryVersion = encoded.dictionaryVersion,
            )
        } finally {
            db.endTransaction()
        }
    }

    /** E-06: po naprawie tytułu zastępczego — porażki opisu (liczone przy złym tytule) zaczynają się od nowa. */
    @Synchronized
    fun resetDescriptionFailures(videoId: String) {
        if (!YOUTUBE_VIDEO_ID.matches(videoId)) return
        writableDatabase.execSQL(
            "UPDATE video_history SET description_attempts = 0, description_last_attempt_ms = 0, " +
                "description_state = CASE WHEN description_state = 2 THEN 0 ELSE description_state END " +
                "WHERE video_id = ? AND description_state <> 1",
            arrayOf<Any>(videoId),
        )
    }

    @Synchronized
    fun recordDescriptionFailure(videoId: String): Boolean {
        if (!YOUTUBE_VIDEO_ID.matches(videoId)) return false
        return writableDatabase.update(
            "video_history",
            ContentValues().apply {
                put("description_state", 2)
                put("description_last_attempt_ms", System.currentTimeMillis())
            },
            "video_id = ? AND description_attempts < ?",
            arrayOf<Any>(videoId, MAX_DESCRIPTION_ATTEMPTS),
        ).also { updated ->
            if (updated == 1) {
                writableDatabase.execSQL(
                    "UPDATE video_history SET description_attempts = description_attempts + 1 " +
                        "WHERE video_id = ?",
                    arrayOf<Any>(videoId),
                )
            }
        } == 1
    }

    @Synchronized
    fun searchHistory(
        query: String,
        selectedCreatorIds: Set<String>,
        kinds: Set<VideoKind>,
        cutoffMillis: Long,
        favoritesOnly: Boolean,
        limit: Int = 40,
        offset: Int = 0,
    ): HistorySearchResult {
        val searchPlan = buildHistorySearchPlan(query)
            ?: return HistorySearchResult(emptyList(), HistorySearchEngine.FTS5)
        if (selectedCreatorIds.isEmpty() || kinds.isEmpty()) {
            return HistorySearchResult(emptyList(), HistorySearchEngine.FTS5)
        }
        val creatorPlaceholders = selectedCreatorIds.joinToString(",") { "?" }
        val kindPlaceholders = kinds.joinToString(",") { "?" }
        val safeLimit = limit.coerceIn(1, MAX_FTS_PAGE_SIZE)
        val safeOffset = offset.coerceAtLeast(0)
        val candidateLimit = ((safeOffset + safeLimit) * SEARCH_CANDIDATE_MULTIPLIER)
            .coerceIn(safeLimit, MAX_FTS_PAGE_SIZE)

        fun executeFts(ftsQuery: String): List<HistoryItem> = readableDatabase.queryRows(
            """
            SELECT h.video_id, h.creator_id, h.creator_name, h.title, h.url,
                   h.published_ms, h.detected_ms, h.kind, h.notified, h.origin,
                   h.is_favorite, h.description_data, h.description_codec,
                   h.description_original_size
            FROM video_history_fts f
            JOIN video_history h ON h.video_id = f.video_id
            WHERE video_history_fts MATCH ?
              AND h.creator_id IN ($creatorPlaceholders)
              AND h.kind IN ($kindPlaceholders)
              AND (h.published_ms >= ? OR h.is_favorite = 1)
              AND (? = 0 OR h.is_favorite = 1)
            ORDER BY bm25(video_history_fts, 0.0, 12.0, 5.0, 1.0),
                     h.published_ms DESC
            LIMIT ? OFFSET ?
            """.trimIndent(),
            arrayOf<Any>(
                ftsQuery,
                *selectedCreatorIds.toTypedArray(),
                *kinds.map(VideoKind::name).toTypedArray(),
                *historySearchNumericArguments(
                    cutoffMillis = cutoffMillis,
                    favoritesOnly = favoritesOnly,
                    limit = candidateLimit,
                    offset = 0,
                ).toTypedArray(),
            ),
            mapper = ::readSearchHistoryItem,
        )

        val ftsAttempts = runCatching {
            val strict = executeFts(searchPlan.strictQuery)
            if (strict.isNotEmpty()) {
                Triple(strict, HistorySearchStrategy.STRICT, false)
            } else {
                val relaxed = searchPlan.relaxedQuery
                    ?.takeUnless { it == searchPlan.strictQuery }
                    ?.let(::executeFts)
                    .orEmpty()
                if (relaxed.isNotEmpty()) {
                    Triple(relaxed, HistorySearchStrategy.RELAXED, false)
                } else {
                    val typoCandidates = searchPlan.typoCandidateQuery
                        ?.takeUnless { it == searchPlan.strictQuery || it == searchPlan.relaxedQuery }
                        ?.let(::executeFts)
                        .orEmpty()
                    Triple(typoCandidates, HistorySearchStrategy.TYPO_TOLERANT, true)
                }
            }
        }.onFailure { error ->
            DiagnosticLogStore.event(
                category = DiagnosticCategory.DATABASE,
                level = DiagnosticLevel.WARNING,
                name = "FTS_SEARCH_FAILED",
                reason = DiagnosticReasonCode.FTS_QUERY_FAILED,
                fields = mapOf("errorType" to error.javaClass.simpleName),
            )
        }.getOrNull()
        if (ftsAttempts != null) {
            val (candidates, strategy, requireTypoMatch) = ftsAttempts
            val ranked = rankHistorySearchItems(
                items = candidates,
                query = query,
                requireTypoMatch = requireTypoMatch,
            ).drop(safeOffset).take(safeLimit)
            if (ranked.isNotEmpty()) {
                return HistorySearchResult(
                    items = ranked,
                    engine = HistorySearchEngine.FTS5,
                    strategy = strategy,
                )
            }
        }

        val fallback = searchHistoryByTitleOrCreator(
            query = query,
            selectedCreatorIds = selectedCreatorIds,
            kinds = kinds,
            cutoffMillis = cutoffMillis,
            favoritesOnly = favoritesOnly,
            limit = limit,
            offset = offset,
        )
        DiagnosticLogStore.event(
            category = DiagnosticCategory.DATABASE,
            level = DiagnosticLevel.INFO,
            name = "LOCAL_SEARCH_FALLBACK",
            fields = mapOf("resultCount" to fallback.size),
        )
        return HistorySearchResult(
            items = fallback,
            engine = HistorySearchEngine.SQL_FALLBACK,
            strategy = HistorySearchStrategy.SQL_FALLBACK,
        )
    }

    private fun searchHistoryByTitleOrCreator(
        query: String,
        selectedCreatorIds: Set<String>,
        kinds: Set<VideoKind>,
        cutoffMillis: Long,
        favoritesOnly: Boolean,
        limit: Int,
        offset: Int,
    ): List<HistoryItem> {
        val tokens = searchTokens(query)
        if (tokens.isEmpty()) return emptyList()
        val creatorPlaceholders = selectedCreatorIds.joinToString(",") { "?" }
        val kindPlaceholders = kinds.joinToString(",") { "?" }
        val normalizedTitle = "lower(replace(replace(h.title, 'Ł', 'L'), 'ł', 'l'))"
        val normalizedCreator =
            "lower(replace(replace(h.creator_name, 'Ł', 'L'), 'ł', 'l'))"
        val normalizedUtf8Description =
            "lower(replace(replace(CAST(h.description_data AS TEXT), 'Ł', 'L'), 'ł', 'l'))"
        val tokenVariants = tokens.map(::polishSearchPrefixVariants)
        val tokenWhere = tokenVariants.joinToString(" AND ") { variants ->
            variants.joinToString(prefix = "(", postfix = ")", separator = " OR ") {
                "($normalizedTitle LIKE ? OR $normalizedCreator LIKE ? OR " +
                    "(h.description_codec = ${StoredDescriptionCodec.UTF8.databaseValue} " +
                    "AND $normalizedUtf8Description LIKE ?))"
            }
        }
        val tokenArguments = tokenVariants.flatten().flatMap { token ->
            listOf("%$token%", "%$token%", "%$token%")
        }
        return readableDatabase.queryRows(
            """
            SELECT h.video_id, h.creator_id, h.creator_name, h.title, h.url,
                   h.published_ms, h.detected_ms, h.kind, h.notified, h.origin,
                   h.is_favorite, h.description_data, h.description_codec,
                   h.description_original_size
            FROM video_history h
            WHERE $tokenWhere
              AND h.creator_id IN ($creatorPlaceholders)
              AND h.kind IN ($kindPlaceholders)
              AND (h.published_ms >= ? OR h.is_favorite = 1)
              AND (? = 0 OR h.is_favorite = 1)
            ORDER BY h.published_ms DESC
            LIMIT ? OFFSET ?
            """.trimIndent(),
            arrayOf<Any>(
                *tokenArguments.toTypedArray(),
                *selectedCreatorIds.toTypedArray(),
                *kinds.map(VideoKind::name).toTypedArray(),
                *historySearchNumericArguments(
                    cutoffMillis = cutoffMillis,
                    favoritesOnly = favoritesOnly,
                    limit = limit.coerceIn(1, MAX_FTS_PAGE_SIZE),
                    offset = offset.coerceAtLeast(0),
                ).toTypedArray(),
            ),
            mapper = ::readSearchHistoryItem,
        )
    }

    private fun readSearchHistoryItem(row: SQLiteStatement): HistoryItem {
        val description = readStoredDescription(row)
        return readHistoryItem(row, descriptionAvailability(description)).copy(
            descriptionSnippet = description
                ?.takeUnless(::isInternalDescriptionMarker)
                ?.take(MAX_DESCRIPTION_SNIPPET_CHARS),
        )
    }

    @Synchronized
    fun insertConfirmedFavorite(material: ConfirmedOlderMaterial): Boolean {
        if (!YOUTUBE_VIDEO_ID.matches(material.videoId)) return false
        val now = System.currentTimeMillis()
        val inserted = writableDatabase.insertWithOnConflict(
            "video_history",
            null,
            ContentValues().apply {
                put("video_id", material.videoId)
                put("creator_id", material.creatorId)
                put("creator_name", material.creatorName)
                put("title", material.title.take(MAX_TITLE_CHARS))
                put("url", "https://www.youtube.com/watch?v=${material.videoId}")
                put("published_ms", material.publishedAtMillis)
                put("detected_ms", now)
                put("kind", material.kind.name)
                put("notified", 1)
                put("notification_checked", 1)
                put("classification_version", CURRENT_CLASSIFIER_VERSION)
                put("kind_evidence", VideoKindEvidence.PLAYER_METADATA.rank)
                put("published_evidence", PublishedAtEvidence.WEB_DATE.rank)
                put("origin", VideoOrigin.YOUTUBE.name)
                put("is_favorite", 1)
                put("favorited_ms", now)
            },
            BundledDatabase.CONFLICT_IGNORE,
        ) != -1L
        if (!inserted) {
            setFavorite(material.videoId, true)
        }
        material.description?.takeIf(String::isNotBlank)?.let {
            saveDescription(material.videoId, it)
        }
        return inserted || readableDatabase.query(
            "video_history",
            arrayOf("video_id"),
            "video_id = ? AND is_favorite = 1",
            arrayOf(material.videoId),
            null,
            null,
            null,
        ).use { it.moveToFirst() }
    }

    @Synchronized
    fun setFavorite(videoId: String, favorite: Boolean): Boolean {
        if (!YOUTUBE_VIDEO_ID.matches(videoId)) return false
        val values = ContentValues().apply {
            put("is_favorite", if (favorite) 1 else 0)
            if (favorite) put("favorited_ms", System.currentTimeMillis())
            else putNull("favorited_ms")
        }
        return writableDatabase.update(
            "video_history",
            values,
            "video_id = ?",
            arrayOf(videoId),
        ) == 1
    }

    @Synchronized
    fun favoriteThumbnailUrls(): Set<String> = readableDatabase.queryRows(
        "SELECT video_id FROM video_history WHERE is_favorite = 1",
        null,
    ) { row -> row.getText(0) }
        .mapNotNull { videoId ->
            videoId.takeIf(YOUTUBE_VIDEO_ID::matches)
                // musi odpowiadać URL-owi, którego faktycznie używa miniatura w Historii (VideoThumbnail),
                // inaczej ochrona przed czyszczeniem cache nie trafiałaby w ten wpis (klucz = urlKey(url)).
                ?.let { "https://i.ytimg.com/vi_webp/$it/hqdefault.webp" }
        }
        .toSet()

    /** wiersz do sprawdzenia prywatności streamu: dane z historii + zapisany stan. */
    data class StreamPrivacyRow(
        val videoId: String,
        val publishedMs: Long,
        val streamStartMs: Long?,
        val checkedMs: Long,
        val isPrivate: Boolean,
    )

    /** streamy (LIVE/STREAM_ARCHIVE) z historii + ich stan prywatności (LEFT JOIN), najnowsze. */
    @Synchronized
    fun streamsForPrivacyCheck(limit: Int = 300): List<StreamPrivacyRow> =
        readableDatabase.queryRows(
            """
            SELECT h.video_id, h.published_ms, p.stream_start_ms, p.checked_ms, p.is_private
            FROM video_history h
            LEFT JOIN stream_privacy p ON p.video_id = h.video_id
            WHERE h.kind IN ('LIVE', 'STREAM_ARCHIVE')
            ORDER BY h.published_ms DESC
            LIMIT $limit
            """.trimIndent(),
            null,
        ) { row ->
            StreamPrivacyRow(
                videoId = row.getText(0),
                publishedMs = row.getLong(1),
                streamStartMs = if (row.isNull(2)) null else row.getLong(2),
                checkedMs = if (row.isNull(3)) 0L else row.getLong(3),
                isPrivate = !row.isNull(4) && row.getLong(4) == 1L,
            )
        }

    /** zapisuje realny czas startu streamu (dobę później sprawdzamy prywatność). */
    @Synchronized
    fun setStreamStart(videoId: String, startMs: Long) {
        writableDatabase.execSQL(
            "INSERT INTO stream_privacy(video_id, stream_start_ms) VALUES(?, ?) " +
                "ON CONFLICT(video_id) DO UPDATE SET stream_start_ms = excluded.stream_start_ms",
            arrayOf<Any>(videoId, startMs),
        )
    }

    /** zapisuje wynik sprawdzenia prywatności + kiedy sprawdzono. */
    @Synchronized
    fun markStreamPrivacy(videoId: String, isPrivate: Boolean, checkedMs: Long) {
        writableDatabase.execSQL(
            "INSERT INTO stream_privacy(video_id, is_private, checked_ms) VALUES(?, ?, ?) " +
                "ON CONFLICT(video_id) DO UPDATE SET is_private = excluded.is_private, checked_ms = excluded.checked_ms",
            arrayOf<Any>(videoId, if (isPrivate) 1L else 0L, checkedMs),
        )
    }

    /** zbiór videoId streamów wykrytych jako prywatne — do badge, transkrypcji i wyszukiwarki. */
    @Synchronized
    fun privateVideoIds(): Set<String> =
        readableDatabase.queryRows(
            "SELECT video_id FROM stream_privacy WHERE is_private = 1",
            null,
        ) { row -> row.getText(0) }.toSet()

    /** Lekki stan infrastruktury; nie odczytuje ani nie eksportuje rekordów. */
    @Synchronized
    internal fun diagnosticState(): DatabaseDiagnosticState {
        val sqliteVersion = scalarText("SELECT sqlite_version()") ?: "UNKNOWN"
        val userVersion = scalarLong("PRAGMA user_version")?.toInt() ?: -1
        val journalMode = scalarText("PRAGMA journal_mode") ?: "UNKNOWN"
        val fts5Available = runCatching {
            writableDatabase.execSQL("DROP TABLE IF EXISTS temp.lewicowyt_fts5_probe")
            writableDatabase.execSQL(
                "CREATE VIRTUAL TABLE temp.lewicowyt_fts5_probe USING fts5(value)",
            )
            writableDatabase.execSQL("DROP TABLE temp.lewicowyt_fts5_probe")
            true
        }.getOrElse {
            runCatching {
                writableDatabase.execSQL("DROP TABLE IF EXISTS temp.lewicowyt_fts5_probe")
            }
            false
        }
        return DatabaseDiagnosticState(
            sqliteVersion = sqliteVersion,
            userVersion = userVersion,
            appSchemaVersion = DATABASE_VERSION,
            journalMode = journalMode,
            fts5Available = fts5Available,
        )
    }

    /** Ręczna szybka kontrola spójności; nie jest uruchamiana przy starcie. */
    @Synchronized
    fun quickCheck(): String = scalarText("PRAGMA quick_check(1)") ?: "UNKNOWN"

    private fun scalarText(sql: String): String? = readableDatabase.rawQuery(sql, null).use {
        if (it.moveToFirst()) it.getString(0) else null
    }

    private fun scalarLong(sql: String): Long? = readableDatabase.rawQuery(sql, null).use {
        if (it.moveToFirst()) it.getLong(0) else null
    }

    @Synchronized
    fun pruneExpiredData(nowMillis: Long = System.currentTimeMillis()) {
        val cutoffs = DataRetentionPolicy.cutoffs(nowMillis)
        val db = writableDatabase
        var deletedRows = 0
        db.beginTransaction()
        try {
            deletedRows += db.delete(
                "notification_inbox",
                """
                created_ms < ? OR video_id IN (
                    SELECT video_id FROM video_history WHERE published_ms < ?
                )
                """.trimIndent(),
                arrayOf(
                    cutoffs.notificationsBeforeMillis.toString(),
                    cutoffs.historyBeforeMillis.toString(),
                ),
            )
            deletedRows += db.delete(
                "video_history",
                "published_ms < ? AND is_favorite = 0",
                arrayOf(cutoffs.historyBeforeMillis.toString()),
            )
            deletedRows += db.delete(
                "notification_ids",
                "video_id NOT IN (SELECT video_id FROM video_history)",
                null,
            )
            // B-15: stan prywatności streamu żyje tylko tak długo, jak jego wpis w Historii
            deletedRows += db.delete(
                "stream_privacy",
                "video_id NOT IN (SELECT video_id FROM video_history)",
                null,
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (deletedRows > 0) compactDatabaseIfWorthwhile(db)
    }

    @Synchronized
    fun recentHistory(days: Int = 60, limit: Int = 500): List<HistoryItem> {
        val cutoff = System.currentTimeMillis() - days.coerceIn(1, 365).toLong() * DAY_MILLIS
        // `limit` tnie tylko okno najnowszych (pula rośnie przy przewijaniu, patrz
        // refreshHistoryNow). Ulubione lecą zawsze w całości, żeby filtr Ulubionych
        // nie gubił pozycji starszych niż okno (#5)
        return readableDatabase.queryRows(
            """
            SELECT video_id, creator_id, creator_name, title, url,
                   published_ms, detected_ms, kind, notified, origin, is_favorite,
                   description_availability
            FROM video_history
            WHERE is_favorite = 1
               OR video_id IN (
                   SELECT video_id FROM video_history
                   WHERE published_ms >= ?
                   ORDER BY published_ms DESC
                   LIMIT ?
               )
            ORDER BY published_ms DESC, detected_ms DESC
            """.trimIndent(),
            arrayOf(cutoff.toString(), limit.coerceIn(1, 10_000).toString()),
            mapper = ::readHistoryListItem,
        )
    }

    @Synchronized
    fun addNotificationInbox(videoIds: Collection<String>) {
        if (videoIds.isEmpty()) return
        val now = System.currentTimeMillis()
        writableDatabase.beginTransaction()
        try {
            videoIds.forEach { videoId ->
                writableDatabase.insertWithOnConflict(
                    "notification_inbox",
                    null,
                    ContentValues().apply {
                        put("video_id", videoId)
                        put("created_ms", now)
                    },
                    BundledDatabase.CONFLICT_IGNORE,
                )
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    @Synchronized
    fun recentNotificationInbox(
        limit: Int = 500,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<HistoryItem> {
        val cutoff = DataRetentionPolicy.cutoffs(nowMillis).notificationsBeforeMillis
        return readableDatabase.queryRows(
            """
            SELECT h.video_id, h.creator_id, h.creator_name, h.title, h.url,
                   h.published_ms, h.detected_ms, h.kind, h.notified, h.origin,
                   h.is_favorite, h.description_availability
            FROM notification_inbox n
            JOIN video_history h ON h.video_id = n.video_id
            WHERE n.created_ms >= ?
            ORDER BY h.published_ms DESC, n.created_ms DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(cutoff.toString(), limit.coerceIn(1, 2_000).toString()),
            mapper = ::readHistoryListItem,
        )
    }

    @Synchronized
    fun deleteHistoryForCreators(creatorIds: Set<String>) {
        if (creatorIds.isEmpty()) return
        val placeholders = creatorIds.joinToString(",") { "?" }
        val arguments = creatorIds.toTypedArray()
        writableDatabase.beginTransaction()
        try {
            writableDatabase.execSQL(
                """
                DELETE FROM notification_inbox
                WHERE video_id IN (
                    SELECT video_id FROM video_history
                    WHERE creator_id IN ($placeholders)
                )
                """.trimIndent(),
                arguments,
            )
            writableDatabase.delete(
                "video_history",
                "creator_id IN ($placeholders) AND is_favorite = 0",
                arguments,
            )
            writableDatabase.delete(
                "notification_ids",
                "video_id NOT IN (SELECT video_id FROM video_history)",
                null,
            )
            writableDatabase.delete(
                "stream_privacy",
                "video_id NOT IN (SELECT video_id FROM video_history)",
                null,
            ) // B-15
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    @Synchronized
    fun clearHistory() {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("stream_privacy", null, null) // B-15: razem z Historią
            writableDatabase.delete("notification_inbox", null, null)
            writableDatabase.delete("notification_ids", null, null)
            writableDatabase.delete("video_history", null, null)
            writableDatabase.delete("source_state", null, null)
            writableDatabase.delete("source_priority", null, null)
            writableDatabase.delete("youtube_channel_tabs", null, null)
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    private fun readHistoryItem(row: SQLiteStatement): HistoryItem {
        val description = readStoredDescription(row)
        return readHistoryItem(row, descriptionAvailability(description))
    }

    private fun readHistoryItem(
        row: SQLiteStatement,
        descriptionAvailability: DescriptionAvailability,
    ): HistoryItem = HistoryItem(
        videoId = row.getText(0),
        creatorId = row.getText(1),
        creatorName = row.getText(2),
        title = row.getText(3),
        url = row.getText(4),
        publishedAtMillis = row.getLong(5),
        detectedAtMillis = row.getLong(6),
        kind = runCatching { VideoKind.valueOf(row.getText(7)) }
            .getOrDefault(VideoKind.UNKNOWN),
        notified = row.getLong(8) == 1L,
        origin = runCatching { VideoOrigin.valueOf(row.getText(9)) }
            .getOrDefault(VideoOrigin.YOUTUBE),
        isFavorite = row.getLong(10) == 1L,
        descriptionAvailability = descriptionAvailability,
    )

    private fun readStoredDescription(row: SQLiteStatement): String? = DescriptionCodec.decode(
        data = if (row.isNull(11)) null else row.getBlob(11),
        codecValue = row.getLong(12).toInt(),
        originalSize = row.getLong(13).toInt(),
    )

    private fun descriptionAvailability(description: String?): DescriptionAvailability = when {
        description == null -> DescriptionAvailability.NONE
        description == MEMBERS_ONLY_DESCRIPTION_MARKER -> DescriptionAvailability.MEMBERS_ONLY
        description == SCHEDULED_STREAM_DESCRIPTION_MARKER ->
            DescriptionAvailability.SCHEDULED_STREAM
        else -> DescriptionAvailability.DOWNLOADED
    }

    // stałe wartości dla description_availability – celowo bez .ordinal, żeby
    // zmiana kolejności enuma nie popsuła zapisanych wierszy. musi się zgadzać
    // z backfillem migracji 26 (CASE 0..3)
    private fun descriptionAvailabilityDatabaseValue(
        availability: DescriptionAvailability,
    ): Int = when (availability) {
        DescriptionAvailability.NONE -> 0
        DescriptionAvailability.DOWNLOADED -> 1
        DescriptionAvailability.MEMBERS_ONLY -> 2
        DescriptionAvailability.SCHEDULED_STREAM -> 3
    }

    private fun descriptionAvailabilityFromDatabaseValue(
        value: Int,
    ): DescriptionAvailability = when (value) {
        1 -> DescriptionAvailability.DOWNLOADED
        2 -> DescriptionAvailability.MEMBERS_ONLY
        3 -> DescriptionAvailability.SCHEDULED_STREAM
        else -> DescriptionAvailability.NONE
    }

    // tania ścieżka listy Historii (recentHistory/recentNotificationInbox): bierze
    // gotowy wskaźnik z kolumny zamiast dekompresować Zstd dla ~10 000 wierszy co odświeżenie
    private fun readHistoryListItem(row: SQLiteStatement): HistoryItem =
        readHistoryItem(
            row,
            descriptionAvailabilityFromDatabaseValue(row.getLong(11).toInt()),
        )

    private fun compactDatabaseIfWorthwhile(db: BundledDatabase) {
        val pageCount = db.rawQuery("PRAGMA page_count", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
        val freePages = db.rawQuery("PRAGMA freelist_count", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
        if (freePages >= MIN_FREE_PAGES_FOR_VACUUM && freePages * 4 >= pageCount) {
            runCatching { db.execSQL("VACUUM") }
        }
    }

    fun close() {
        writableDatabase.close()
    }

    // internal (nie private): testy instrumentalne migracji czytają DATABASE_VERSION
    internal companion object {
        const val DATABASE_NAME = "lewicowyt_notifier.db"
        const val DATABASE_VERSION = 39

        /**
         * M-12: otwiera bazę, a gdy pochodzi z NOWSZEJ wersji aplikacji niż ten kod (np. po awaryjnym
         * rollbacku do starszego buildu), NIE wywraca startu (dawne `require` dawało crash-loop). Zamiast tego
         * robi kopię `<nazwa>.bak` i startuje od pustej bazy bieżącego schematu — dane zostają do odzysku w .bak.
         */
        private fun openRecovering(dbFile: File): BundledDatabase {
            val db = BundledDatabase(dbFile)
            val version = runCatching {
                db.rawQuery("PRAGMA user_version", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            }.getOrDefault(0)
            if (version <= DATABASE_VERSION) return db
            runCatching { db.execSQL("PRAGMA wal_checkpoint(TRUNCATE)") } // zrzuć WAL do głównego pliku przed kopią
            runCatching { db.close() }
            val parent = dbFile.parentFile
            val backup = File(parent, dbFile.name + ".bak")
            runCatching { if (backup.exists()) backup.delete() }
            runCatching { dbFile.copyTo(backup, overwrite = true) }
            // usuń główny plik + towarzyszące WAL/SHM, żeby powstała czysta baza bieżącego schematu
            runCatching { dbFile.delete() }
            runCatching { File(parent, dbFile.name + "-wal").delete() }
            runCatching { File(parent, dbFile.name + "-shm").delete() }
            AppLog.warning(
                "DatabaseRollback",
                "Baza z nowszej wersji — kopia do ${backup.name}, start od zera",
                IllegalStateException("user_version $version > $DATABASE_VERSION"),
            )
            return BundledDatabase(dbFile)
        }
        const val CURRENT_CLASSIFIER_VERSION = 1
        const val MAX_CHANNEL_TAB_PARAMS_CHARS = 2_048
        const val MAX_EVIDENCE_QUERY_IDS = 900
        const val MAX_CLASSIFICATION_ATTEMPTS = 3
        const val MAX_CLASSIFICATION_QUERY_IDS = 100
        const val MAX_RSS_KNOWN_VIDEO_IDS = 60
        const val MAX_DESCRIPTION_ATTEMPTS = 3
        const val MAX_DESCRIPTION_BATCH = 200
        const val MAX_FTS_PAGE_SIZE = 100
        const val MAX_DESCRIPTION_SNIPPET_CHARS = 320
        const val MAX_TITLE_CHARS = 300
        const val DAY_MILLIS = 24L * 60L * 60L * 1000L
        const val MIN_FREE_PAGES_FOR_VACUUM = 128L
        val SHA_256 = Regex("[0-9a-f]{64}")
        val YOUTUBE_VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

        // kolejność MUSI się zgadzać z toCommunityServerRecord/toCommunityConsentRecord
        private val COMMUNITY_SERVER_COLUMNS = arrayOf(
            "server_id",
            "endpoint",
            "added_ms",
            "enabled",
            "display_name",
            "transport",
            "last_manifest_fetch_ms",
            "notify_enabled",
            "muted_platforms",
            "can_be_primary",
            "ads_enabled",
            "display_muted",
            "manifest_etag",
            "manifest_last_modified",
            "manifest_terms_hash",
            "push_json",
            "primary_feed_json",
            "manifest_terms_text",
            "manifest_terms_version",
        )
        private val COMMUNITY_CONSENT_COLUMNS = arrayOf(
            "server_id",
            "terms_hash",
            "terms_version",
            "terms_text",
            "presented_ms",
            "decided_ms",
            "accepted",
        )
    }
}

enum class HistorySearchEngine {
    FTS5,
    SQL_FALLBACK,
}

enum class HistorySearchStrategy {
    STRICT,
    RELAXED,
    TYPO_TOLERANT,
    SQL_FALLBACK,
}

data class HistorySearchResult(
    val items: List<HistoryItem>,
    val engine: HistorySearchEngine,
    val strategy: HistorySearchStrategy = HistorySearchStrategy.STRICT,
)

internal data class HistorySearchPlan(
    val tokens: List<String>,
    val strictQuery: String,
    val relaxedQuery: String?,
    val typoCandidateQuery: String?,
)

internal fun buildHistorySearchPlan(value: String): HistorySearchPlan? {
    val allTokens = searchTokens(value)
    if (allTokens.isEmpty()) return null
    val meaningful = allTokens.filterNot(POLISH_SEARCH_STOP_WORDS::contains)
        .ifEmpty { allTokens }
    val groups = meaningful.map(::buildFtsTokenGroup)
    val strict = groups.joinToString(" AND ")
    val relaxed = groups.takeIf { it.size > 1 }?.joinToString(" OR ")
    val typoPrefixes = meaningful
        .filter { it.length >= MIN_TYPO_SEARCH_CHARS }
        .map { it.take(TYPO_CANDIDATE_PREFIX_CHARS.coerceAtMost(it.length - 1)) }
        .distinct()
    val typoQuery = typoPrefixes.takeIf { it.isNotEmpty() }
        ?.joinToString(" OR ") { prefix -> quoteFtsPrefix(prefix) }
    return HistorySearchPlan(
        tokens = meaningful,
        strictQuery = strict,
        relaxedQuery = relaxed,
        typoCandidateQuery = typoQuery,
    )
}

internal fun buildFtsQuery(value: String): String? = buildHistorySearchPlan(value)?.strictQuery

private fun buildFtsTokenGroup(token: String): String = polishSearchPrefixVariants(token)
    .joinToString(prefix = "(", postfix = ")", separator = " OR ", transform = ::quoteFtsPrefix)

private fun quoteFtsPrefix(value: String): String = "\"${value.replace("\"", "\"\"")}\"*"

/**
 * Podczas wpisywania polskich nazw wykonawców/ról rdzeń `-nik` często przechodzi
 * w `-nic-` (np. pełnomocnik/pełnomocniczka). Bez tej małej alternatywy wynik
 * znikał dokładnie po dopisaniu `c`, dopóki opis filmu nie został pobrany.
 * Ograniczenie do dłuższych tokenów zakończonych `-nik`/`-nic` nie zamienia
 * wyszukiwarki w kosztowne ani nadmiernie szerokie wyszukiwanie rozmyte.
 */
internal fun polishSearchPrefixVariants(token: String): List<String> {
    val normalized = normalizePolishSearchText(token).lowercase()
    if (normalized.length < MIN_POLISH_STEM_VARIANT_CHARS) return listOf(normalized)
    val variants = linkedSetOf(normalized)
    when {
        normalized.endsWith("nik") -> variants += normalized.dropLast(1) + "c"
        normalized.endsWith("nic") -> variants += normalized.dropLast(1) + "k"
    }
    POLISH_SEARCH_SUFFIXES.firstOrNull { suffix ->
        normalized.endsWith(suffix) && normalized.length - suffix.length >= MIN_POLISH_STEM_CHARS
    }?.let { suffix -> variants += normalized.dropLast(suffix.length) }
    return variants.take(MAX_POLISH_VARIANTS_PER_TOKEN)
}

// kompilowane raz – wcześniej regexy powstawały od nowa przy każdym wywołaniu,
// a normalizePolishSearchText/searchTokens siedzą w gorącej ścieżce rankingu (#10)
private val SEARCH_TOKEN_SEPARATOR = Regex("[^\\p{L}\\p{N}_-]+")
private val COMBINING_MARKS = Regex("\\p{M}+")

internal fun searchTokens(value: String): List<String> = normalizePolishSearchText(value)
    .lowercase()
    .split(SEARCH_TOKEN_SEPARATOR)
    .filter(String::isNotBlank)
    .take(12)

internal fun normalizePolishSearchText(value: String): String = java.text.Normalizer
    .normalize(value.replace('Ł', 'L').replace('ł', 'l'), java.text.Normalizer.Form.NFD)
    .replace(COMBINING_MARKS, "")

internal fun rankHistorySearchItems(
    items: List<HistoryItem>,
    query: String,
    requireTypoMatch: Boolean = false,
): List<HistoryItem> {
    if (items.size < 2 && !requireTypoMatch) return items
    val plan = buildHistorySearchPlan(query) ?: return emptyList()
    val normalizedQuery = normalizePolishSearchText(query).lowercase().trim()
    val scored = items.mapIndexedNotNull { index, item ->
        val title = SearchableField(item.title, weight = 5)
        val creator = SearchableField(item.creatorName, weight = 3)
        val description = SearchableField(item.descriptionSnippet.orEmpty(), weight = 1)
        val fields = listOf(title, creator, description)
        val tokenScores = plan.tokens.map { token ->
            fields.maxOf { field -> field.matchScore(token) }
        }
        val matchedTokens = tokenScores.count { it > 0 }
        val requiredMatches = ((plan.tokens.size * MIN_RELAXED_MATCH_PERCENT) + 99) / 100
        if (requireTypoMatch && matchedTokens < requiredMatches.coerceAtLeast(1)) return@mapIndexedNotNull null

        var score = tokenScores.sum()
        if (title.normalized == normalizedQuery) score += 1_200
        else if (normalizedQuery.length >= 3 && title.normalized.contains(normalizedQuery)) score += 600
        if (creator.normalized == normalizedQuery) score += 900
        else if (normalizedQuery.length >= 3 && creator.normalized.contains(normalizedQuery)) score += 400
        if (matchedTokens == plan.tokens.size) score += 300
        score += matchedTokens * 80
        RankedHistoryItem(item = item, score = score, originalIndex = index)
    }
    return scored.sortedWith(
        compareByDescending<RankedHistoryItem> { it.score }
            .thenBy { it.originalIndex },
    ).map(RankedHistoryItem::item)
}

private data class RankedHistoryItem(
    val item: HistoryItem,
    val score: Int,
    val originalIndex: Int,
)

private class SearchableField(value: String, private val weight: Int) {
    val normalized = normalizePolishSearchText(value).lowercase()
    private val words = normalized
        .split(SEARCH_TOKEN_SEPARATOR)
        .filter(String::isNotBlank)

    fun matchScore(token: String): Int {
        val variants = polishSearchPrefixVariants(token)
        if (words.any { it == token }) return 120 * weight
        if (words.any { word -> variants.any { variant -> word.startsWith(variant) } }) return 90 * weight
        if (token.length < MIN_TYPO_SEARCH_CHARS) return 0
        val maximumDistance = if (token.length >= 9) 2 else 1
        return if (words.any { word ->
                kotlin.math.abs(word.length - token.length) <= maximumDistance &&
                    damerauLevenshteinDistance(token, word, maximumDistance) <= maximumDistance
            }
        ) 55 * weight else 0
    }
}

/** Ograniczona wersja Damerau-Levenshteina; kończy wiersz, gdy nie może już zmieścić się w limicie. */
internal fun damerauLevenshteinDistance(left: String, right: String, limit: Int): Int {
    if (left == right) return 0
    if (kotlin.math.abs(left.length - right.length) > limit) return limit + 1
    var previousPrevious = IntArray(right.length + 1)
    var previous = IntArray(right.length + 1) { it }
    for (leftIndex in left.indices) {
        val current = IntArray(right.length + 1)
        current[0] = leftIndex + 1
        var rowMinimum = current[0]
        for (rightIndex in right.indices) {
            val substitution = previous[rightIndex] +
                if (left[leftIndex] == right[rightIndex]) 0 else 1
            current[rightIndex + 1] = minOf(
                current[rightIndex] + 1,
                previous[rightIndex + 1] + 1,
                substitution,
            )
            if (
                leftIndex > 0 && rightIndex > 0 &&
                left[leftIndex] == right[rightIndex - 1] &&
                left[leftIndex - 1] == right[rightIndex]
            ) {
                current[rightIndex + 1] = minOf(
                    current[rightIndex + 1],
                    previousPrevious[rightIndex - 1] + 1,
                )
            }
            rowMinimum = minOf(rowMinimum, current[rightIndex + 1])
        }
        if (rowMinimum > limit) return limit + 1
        previousPrevious = previous
        previous = current
    }
    return previous[right.length]
}

private const val MIN_POLISH_STEM_VARIANT_CHARS = 6
private const val MIN_POLISH_STEM_CHARS = 4
private const val MAX_POLISH_VARIANTS_PER_TOKEN = 3
private const val MIN_TYPO_SEARCH_CHARS = 5
private const val TYPO_CANDIDATE_PREFIX_CHARS = 4
private const val MIN_RELAXED_MATCH_PERCENT = 60
private const val SEARCH_CANDIDATE_MULTIPLIER = 3
private val POLISH_SEARCH_SUFFIXES = listOf(
    "owego", "owych", "owej", "owie", "ami", "ach", "ego", "emu",
    "owa", "owe", "owy", "ie", "ia", "iu", "om", "a", "y", "i", "e", "u",
)
private val POLISH_SEARCH_STOP_WORDS = setOf(
    "a", "i", "oraz", "w", "we", "z", "ze", "na", "do", "od", "o", "u",
    "po", "za", "dla", "jak", "co", "to", "ten", "ta", "te", "jest", "sa",
)

/**
 * przy bindowaniu do SQLite parametry liczbowe muszą zostać liczbami. tekstowe
 * "0" ≠ liczbowe 0 w `? = 0`, przez co zwykłe (nieulubione) rekordy odpadały
 * z obu ścieżek wyszukiwania.
 */
internal fun historySearchNumericArguments(
    cutoffMillis: Long,
    favoritesOnly: Boolean,
    limit: Int,
    offset: Int,
): List<Any> = listOf(
    cutoffMillis,
    if (favoritesOnly) 1 else 0,
    limit,
    offset,
)

/**
 * wybór opisów porównuje kolumny INTEGER i używa liczbowego LIMIT. bindowanie
 * ich jako tekst każe SQLite odrzucić poprawne rekordy jeszcze przed pobraniem
 * opisu i zasileniem FTS5.
 */
internal fun descriptionPendingNumericArguments(
    cutoffMillis: Long,
    scheduledBeforeMillis: Long,
    maxAttempts: Int,
    retryBeforeMillis: Long,
    limit: Int,
): List<Any> = listOf(
    cutoffMillis,
    scheduledBeforeMillis,
    maxAttempts,
    retryBeforeMillis,
    limit,
)

internal fun descriptionStorageState(description: String): Int =
    if (description == SCHEDULED_STREAM_DESCRIPTION_MARKER) 3 else 1

private fun isInternalDescriptionMarker(description: String): Boolean =
    description == SCHEDULED_STREAM_DESCRIPTION_MARKER ||
        description == MEMBERS_ONLY_DESCRIPTION_MARKER
