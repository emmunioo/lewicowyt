package pl.lewicowyt.notifier.data

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import androidx.sqlite.SQLiteStatement
import pl.lewicowyt.notifier.AppLog
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
    internal val writableDatabase = BundledDatabase(context.getDatabasePath(databaseName))
    internal val readableDatabase: BundledDatabase
        get() = writableDatabase

    init {
        val oldVersion = readableDatabase.rawQuery("PRAGMA user_version", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
        require(oldVersion <= DATABASE_VERSION) {
            "Baza pochodzi z nowszej wersji aplikacji ($oldVersion > $DATABASE_VERSION)"
        }
        if (oldVersion == 0) {
            writableDatabase.beginTransaction()
            try {
                onCreate(writableDatabase)
                writableDatabase.execSQL("PRAGMA user_version = $DATABASE_VERSION")
                writableDatabase.setTransactionSuccessful()
            } finally {
                writableDatabase.endTransaction()
            }
        } else if (oldVersion < DATABASE_VERSION) {
            writableDatabase.beginTransaction()
            try {
                onUpgrade(writableDatabase, oldVersion, DATABASE_VERSION)
                writableDatabase.execSQL("PRAGMA user_version = $DATABASE_VERSION")
                writableDatabase.setTransactionSuccessful()
            } finally {
                writableDatabase.endTransaction()
            }
        }
        runCatching { ensureVideoHistoryFtsCoverage(writableDatabase) }
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
        createVideoHistoryFts(db)
        createCreatorMetadataTable(db)
        createNotificationInboxTable(db)
        createNotificationIdsTable(db)
        createSourcePriorityTable(db)
        createYouTubeChannelTabsTable(db)
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
            // Wersja RSS-first korzysta wyłącznie ze źródeł YouTube. Usuwamy
            // niepotwierdzone rekordy ze starszej integracji; zostaną ponownie
            // pobrane i sklasyfikowane przez RSS oraz API/Web.
            db.delete(
                "video_history",
                "origin <> ?",
                arrayOf(VideoOrigin.YOUTUBE.name),
            )
        }
        if (oldVersion < 11) {
            // Starszy resolver HTML mógł odczytać identyfikator polecanego kanału
            // zamiast kanału otwartego na stronie. Schemat historii nie przechowuje
            // ID kanału przy filmie, więc nie da się bezpiecznie odróżnić zatrutych
            // rekordów. Jednorazowo odbudowujemy historię ze zweryfikowanego katalogu.
            db.delete("notification_inbox", null, null)
            db.delete("notification_ids", null, null)
            db.delete("video_history", null, null)
            db.delete("source_state", null, null)
        }
        if (oldVersion < 12) {
            // Starszy klasyfikator nie rozpoznawał aktualnego znacznika Shortów.
            // Ponawiamy klasyfikacje, które po kilku błędach uznano awaryjnie
            // za zwykły film.
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
        // Migracje 13 i 14 w starszym wydaniu zerowały `kind`. Nie wolno tego
        // powtarzać przy bezpośredniej aktualizacji starszej bazy do bieżącej
        // wersji: niepewna ponowna klasyfikacja nie może usuwać streamów z UI.
        if (oldVersion < 15) {
            // Urządzenia, które zdążyły uruchomić wadliwą migrację 14, wymagają
            // ponownej klasyfikacji. Zachowujemy jednak aktualny typ do chwili,
            // gdy YouTube dostarczy jednoznaczną odpowiedź.
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
            // Starsza ścieżka Data API uznawała każdą zakończoną Premierę za
            // archiwalny stream, a każdy materiał do 180 sekund za Short.
            // Zachowujemy widoczny typ do chwili pewnej odpowiedzi, lecz
            // pozwalamy nowemu klasyfikatorowi poprawić te rekordy w miejscu.
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
            // Starsze klasyfikatory mogły pomylić typ w obie strony. Nie
            // usuwamy widocznej historii, ale kolejkujemy każdy rekord do
            // ponownego potwierdzenia nowym mechanizmem dowodów.
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
            // Nierozpoznany wpis ma być od razu widoczny jako zwykły film.
            // Bardziej wiarygodna karta kanału nadal może później zmienić typ.
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
            // Przed publicznym wydaniem 1.6 wycofano przedwczesny indeks FTS5
            // i nieskompresowaną kolumnę opisu. Bazy testowe schematu 23 mogły
            // już je utworzyć, dlatego sprzątamy je jawnie. Właściwy magazyn
            // Zstd BLOB i contentless FTS5 powstaną dopiero w 1.7.
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
            // 1.8-beta (#13): description_availability przechowuje 4-stanowy
            // wskaźnik dostępności opisu (NONE/DOWNLOADED/MEMBERS_ONLY/
            // SCHEDULED_STREAM) wyliczany RAZ przy zapisie, zamiast dekompresować
            // Zstd pełny opis przy każdym odczycie listy Historii. Backfill
            // istniejących wierszy jest w czystym SQL: markery są krótkie i
            // przechowywane jako UTF-8, więc rozpoznajemy je przez
            // CAST(description_data AS TEXT) bez dekompresji. Wartości 0..3 są
            // stabilnym odwzorowaniem DescriptionAvailability (patrz
            // descriptionAvailabilityDatabaseValue), nie zależnym od ordinal.
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
            END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS video_history_fts_delete
            AFTER DELETE ON video_history BEGIN
                DELETE FROM video_history_fts WHERE video_id = old.video_id;
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
                WHERE video_id = new.video_id;
            END
            """.trimIndent(),
        )
    }

    /** Naprawia bazy schema 25 utworzone przez wcześniejsze kompilacje testowe. */
    private fun ensureVideoHistoryFtsCoverage(db: BundledDatabase) {
        createVideoHistoryFts(db)
        val missingBefore = db.rawQuery(
            """
            SELECT COUNT(*)
            FROM video_history h
            WHERE NOT EXISTS (
                SELECT 1 FROM video_history_fts f WHERE f.video_id = h.video_id
            )
            """.trimIndent(),
            null,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
        if (missingBefore <= 0L) return
        db.beginTransaction()
        try {
            db.execSQL(
                """
                INSERT INTO video_history_fts(video_id, title, creator_name, description)
                SELECT h.video_id,
                       replace(replace(h.title, 'Ł', 'L'), 'ł', 'l'),
                       replace(replace(h.creator_name, 'Ł', 'L'), 'ł', 'l'),
                       ''
                FROM video_history h
                WHERE NOT EXISTS (
                    SELECT 1 FROM video_history_fts f WHERE f.video_id = h.video_id
                )
                """.trimIndent(),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
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
     * Odpowiedź pierwszej strony kanału zawiera kompletną listę dostępnych kart.
     * Zapisujemy trzy stany atomowo; błąd sieci nie wywołuje tej metody, więc
     * nigdy nie zmienia poprzedniej wartości na fałszywe ABSENT.
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
     * Zapisuje wyłącznie pozytywnie rozpoznane karty z odpowiedzi częściowej.
     * Brak karty w takim JSON-ie nie jest dowodem ABSENT.
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
        // Resolver HTML nie może nadpisać zasobu lub URL-u, którego treść
        // została już zweryfikowana i zapisana wraz z SHA-256.
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
        // Nowszy pakiet APK może zawierać świeższy avatar niż lokalny cache.
        // Lokalnej aktualizacji wykonanej już po zbudowaniu APK nie cofamy.
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
     * Atomowo scala wpis potwierdzony przez synchronizowane źródło YouTube.
     * Dzięki temu równoległy backfill historii nie może wstawić rekordu między
     * wcześniejszym sprawdzeniem a zapisem i bezpowrotnie ukryć powiadomienia.
     *
     * @return true, gdy wywołanie utworzyło nową pozycję oczekującą na
     * dostarczenie powiadomienia.
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
            // Jeden odczyt strony zastępuje po dwa SELECT-y wykonywane wcześniej
            // dla każdego istniejącego filmu (data oraz rodzaj).
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
                    WHERE video_id = ?
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
                ?.let { "https://i.ytimg.com/vi/$it/sddefault.jpg" }
        }
        .toSet()

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
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (deletedRows > 0) compactDatabaseIfWorthwhile(db)
    }

    @Synchronized
    fun recentHistory(days: Int = 60, limit: Int = 500): List<HistoryItem> {
        val cutoff = System.currentTimeMillis() - days.coerceIn(1, 365).toLong() * DAY_MILLIS
        // `limit` ogranicza WYŁĄCZNIE okno najnowszych materiałów (pula rośnie w
        // miarę przewijania — patrz refreshHistoryNow). Ulubione są zawsze
        // zwracane w całości, niezależnie od `limit`, aby filtr Ulubionych nigdy
        // nie gubił pozycji starszych niż okno (#5).
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
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    @Synchronized
    fun clearHistory() {
        writableDatabase.beginTransaction()
        try {
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

    // Stabilne wartości bazodanowe kolumny description_availability. Celowo NIE
    // opieramy się na .ordinal, aby przyszła zmiana kolejności enuma nie
    // uszkodziła zapisanych rekordów. Wartości muszą być zgodne z backfillem
    // migracji 26 (CASE 0..3).
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

    // Tania ścieżka listy Historii (recentHistory/recentNotificationInbox):
    // czyta gotowy wskaźnik dostępności z kolumny zamiast dekompresować Zstd
    // pełny opis dla każdego z maks. 10 000 wierszy przy każdym odświeżeniu.
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

    private companion object {
        const val DATABASE_NAME = "lewicowyt_notifier.db"
        const val DATABASE_VERSION = 26
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

// Kompilowane raz. Wcześniej te wzorce powstawały na nowo przy każdym
// wywołaniu — a normalizePolishSearchText/searchTokens są w gorącej ścieżce
// rankingu wyszukiwarki (per kandydat i per token) (#10).
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
 * Parametry liczbowe muszą pozostać liczbami podczas bindowania SQLite.
 * Tekstowe "0" nie jest równe liczbowemu 0 w wyrażeniu `? = 0`, przez co
 * zwykłe (nieulubione) rekordy były odrzucane przez obie ścieżki wyszukiwania.
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
 * Selekcja opisów porównuje kolumny INTEGER i używa liczbowego LIMIT.
 * Bindowanie tych wartości jako tekst powoduje w SQLite odrzucenie poprawnych
 * rekordów jeszcze przed pobraniem opisu i zasileniem indeksu FTS5.
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
