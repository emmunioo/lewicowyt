package pl.lewicowyt.notifier.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import pl.lewicowyt.notifier.model.VideoKind

@RunWith(AndroidJUnit4::class)
class BundledDatabaseMigrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun prepareLegacyDatabase() {
        deleteTestDatabases()
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(DATABASE_NAME), null).use { db ->
            createCompleteSchema22(db)
            insertCompleteSample(db)
            db.version = 22
        }
    }

    @After
    fun cleanUp() = deleteTestDatabases()

    @Test
    fun completeSchema22MigrationPreservesDataAndEnablesFtsAndCompressedDescriptions() {
        val database = LocalDatabase(context, DATABASE_NAME)
        try {
            assertEquals(25, scalarInt(database, "PRAGMA user_version"))
            assertEquals("ok", scalarText(database, "PRAGMA integrity_check"))
            LEGACY_TABLES.forEach { table ->
                assertEquals("Brak danych po migracji w $table", 1, tableRows(database, table))
            }
            assertTrue(database.containsVideo(VIDEO_ID))
            assertFalse(hasColumn(database, "video_history", "description"))
            assertTrue(hasColumn(database, "video_history", "description_data"))
            assertTrue(hasObject(database, "table", "video_history_fts"))
            assertTrue(hasObject(database, "trigger", "video_history_fts_insert"))
            val creatorSearch = database.searchHistory(
                query = "ral",
                selectedCreatorIds = setOf("creator"),
                kinds = setOf(VideoKind.VIDEO),
                cutoffMillis = 0L,
                favoritesOnly = false,
            )
            assertEquals(HistorySearchEngine.FTS5, creatorSearch.engine)
            assertEquals(VIDEO_ID, creatorSearch.items.single().videoId)
            val description = "Szczegółowy opis o sprawiedliwości społecznej. ".repeat(80)
            assertTrue(database.saveDescription(VIDEO_ID, description))
            val search = database.searchHistory(
                query = "sprawiedliwość",
                selectedCreatorIds = setOf("creator"),
                kinds = setOf(VideoKind.VIDEO),
                cutoffMillis = 0L,
                favoritesOnly = false,
            )
            assertEquals(HistorySearchEngine.FTS5, search.engine)
            assertEquals(VIDEO_ID, search.items.single().videoId)
            assertTrue(
                search.items.single().descriptionSnippet.orEmpty()
                    .contains("sprawiedliwości"),
            )

            database.writableDatabase.execSQL(
                "DELETE FROM video_history_fts WHERE video_id = '$VIDEO_ID'",
            )
            val fallbackSearch = database.searchHistory(
                query = "ral",
                selectedCreatorIds = setOf("creator"),
                kinds = setOf(VideoKind.VIDEO),
                cutoffMillis = 0L,
                favoritesOnly = false,
            )
            assertEquals(HistorySearchEngine.SQL_FALLBACK, fallbackSearch.engine)
            assertEquals(VIDEO_ID, fallbackSearch.items.single().videoId)

            assertTrue(database.setFavorite(VIDEO_ID, true))
            database.pruneExpiredData(nowMillis = 100L * DAY_MILLIS)
            assertTrue(database.containsVideo(VIDEO_ID))

            assertTrue(database.setFavorite(VIDEO_ID, false))
            database.pruneExpiredData(nowMillis = 100L * DAY_MILLIS)
            assertFalse(database.containsVideo(VIDEO_ID))
        } finally {
            database.close()
        }
    }

    @Test
    fun prematureSchema23IsCleanedWithoutLosingFavorite() {
        context.deleteDatabase(SCHEMA_23_DATABASE_NAME)
        SQLiteDatabase.openOrCreateDatabase(
            context.getDatabasePath(SCHEMA_23_DATABASE_NAME),
            null,
        ).use { db ->
            createCompleteSchema22(db)
            insertCompleteSample(db)
            db.version = 22
        }
        BundledDatabase(context.getDatabasePath(SCHEMA_23_DATABASE_NAME)).use { db ->
            db.execSQL("ALTER TABLE video_history ADD COLUMN description TEXT")
            db.execSQL(
                "ALTER TABLE video_history ADD COLUMN " +
                    "is_favorite INTEGER NOT NULL DEFAULT 0",
            )
            db.execSQL("ALTER TABLE video_history ADD COLUMN favorited_ms INTEGER")
            db.execSQL(
                "UPDATE video_history SET description = 'stary opis', " +
                    "is_favorite = 1, favorited_ms = 123",
            )
            db.execSQL(
                """
                CREATE VIRTUAL TABLE video_history_fts USING fts5(
                    title, creator_name, description,
                    content='video_history', content_rowid='rowid'
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TRIGGER video_history_fts_insert AFTER INSERT ON video_history BEGIN
                    INSERT INTO video_history_fts(rowid, title, creator_name, description)
                    VALUES (new.rowid, new.title, new.creator_name, new.description);
                END
                """.trimIndent(),
            )
            db.execSQL("PRAGMA user_version = 23")
        }

        val database = LocalDatabase(context, SCHEMA_23_DATABASE_NAME)
        try {
            assertEquals(25, scalarInt(database, "PRAGMA user_version"))
            assertTrue(database.containsVideo(VIDEO_ID))
            assertTrue(database.recentHistory(limit = 1).single().isFavorite)
            assertFalse(hasColumn(database, "video_history", "description"))
            assertTrue(hasColumn(database, "video_history", "description_data"))
            assertTrue(hasObject(database, "table", "video_history_fts"))
            assertTrue(hasObject(database, "trigger", "video_history_fts_insert"))
            assertEquals("ok", scalarText(database, "PRAGMA integrity_check"))
        } finally {
            database.close()
        }
    }

    @Test
    fun schema22WithUncheckpointedWalMigratesAndPreservesWalRows() {
        val source = context.getDatabasePath(WAL_SOURCE_DATABASE_NAME)
        val target = context.getDatabasePath(WAL_TARGET_DATABASE_NAME)
        SQLiteDatabase.openOrCreateDatabase(source, null).use { db ->
            assertTrue(db.enableWriteAheadLogging())
            createCompleteSchema22(db)
            db.version = 22
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            db.rawQuery("PRAGMA wal_autocheckpoint=0", null).use { it.moveToFirst() }
            insertCompleteSample(db)

            source.copyTo(target, overwrite = true)
            val sourceWal = File("${source.absolutePath}-wal")
            assertTrue("Nie utworzono testowego WAL", sourceWal.length() > 0L)
            sourceWal.copyTo(File("${target.absolutePath}-wal"), overwrite = true)
        }

        val database = LocalDatabase(context, WAL_TARGET_DATABASE_NAME)
        try {
            assertEquals(25, scalarInt(database, "PRAGMA user_version"))
            assertTrue(database.containsVideo(VIDEO_ID))
            LEGACY_TABLES.forEach { table ->
                assertEquals("WAL nie zachował $table", 1, tableRows(database, table))
            }
            assertEquals("ok", scalarText(database, "PRAGMA integrity_check"))
        } finally {
            database.close()
        }
    }

    @Test
    fun contentValuesInsertAndUpdateWorkOnAndroid() {
        val database = BundledDatabase(context.getDatabasePath(CONTENT_VALUES_DATABASE_NAME))
        try {
            database.execSQL(
                "CREATE TABLE sample (id TEXT PRIMARY KEY, value TEXT, enabled INTEGER)",
            )
            assertTrue(
                database.insertOrThrow(
                    "sample",
                    null,
                    ContentValues().apply {
                        put("id", "one")
                        put("value", "before")
                        put("enabled", 0)
                    },
                ) > 0L,
            )
            assertEquals(
                1,
                database.update(
                    "sample",
                    ContentValues().apply {
                        put("value", "after")
                        put("enabled", 1)
                    },
                    "id = ?",
                    arrayOf("one"),
                ),
            )
            database.rawQuery(
                "SELECT value, enabled FROM sample WHERE id = ?",
                arrayOf("one"),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("after", cursor.getString(0))
                assertEquals(1, cursor.getInt(1))
            }
        } finally {
            database.close()
        }
    }

    private fun createCompleteSchema22(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE source_state (
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
        db.execSQL(
            """
            CREATE TABLE video_history (
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
                published_evidence INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE creator_metadata (
                creator_id TEXT PRIMARY KEY,
                avatar_url TEXT,
                avatar_sha256 TEXT,
                avatar_checked_ms INTEGER NOT NULL DEFAULT 0,
                avatar_attempt_ms INTEGER NOT NULL DEFAULT 0,
                updated_ms INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE TABLE notification_inbox " +
                "(video_id TEXT PRIMARY KEY, created_ms INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE notification_ids " +
                "(notification_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "video_id TEXT NOT NULL UNIQUE)",
        )
        db.execSQL(
            """
            CREATE TABLE source_priority (
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
        db.execSQL(
            """
            CREATE TABLE youtube_channel_tabs (
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
            "CREATE INDEX idx_video_history_detected ON video_history(detected_ms DESC)",
        )
        db.execSQL(
            "CREATE INDEX idx_video_history_published ON video_history(published_ms DESC)",
        )
        db.execSQL(
            "CREATE INDEX idx_video_history_pending ON video_history(kind, notified)",
        )
        db.execSQL(
            "CREATE INDEX idx_video_history_classification " +
                "ON video_history(classification_version, published_ms DESC)",
        )
        db.execSQL(
            "CREATE INDEX idx_notification_inbox_created " +
                "ON notification_inbox(created_ms DESC)",
        )
        db.execSQL(
            "CREATE INDEX idx_youtube_channel_tabs_checked " +
                "ON youtube_channel_tabs(state, checked_ms)",
        )
    }

    private fun insertCompleteSample(db: SQLiteDatabase) {
        db.execSQL(
            """
            INSERT INTO source_state VALUES (
                'source', 'channel', 1, 123, NULL, '$VIDEO_ID', 1, '$VIDEO_ID'
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO video_history VALUES (
                '$VIDEO_ID', 'creator', 'Ralindel', 'Testowy materiał',
                'https://www.youtube.com/watch?v=$VIDEO_ID', 1, 1, 'VIDEO',
                0, 1, 'YOUTUBE', 1, 0, 0, 50, 40
            )
            """.trimIndent(),
        )
        db.execSQL(
            "INSERT INTO creator_metadata VALUES " +
                "('creator', 'https://yt3.ggpht.com/avatar=s176', " +
                "'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', " +
                "12, 11, 10)",
        )
        db.execSQL("INSERT INTO notification_inbox VALUES ('$VIDEO_ID', 1)")
        db.execSQL("INSERT INTO notification_ids(video_id) VALUES ('$VIDEO_ID')")
        db.execSQL(
            "INSERT INTO source_priority VALUES ('source', 1.0, 2.0, 3.0, 4.0, 5, 6, 7, 0)",
        )
        db.execSQL(
            "INSERT INTO youtube_channel_tabs VALUES " +
                "('source', 'channel', 'VIDEOS', 'PRESENT', 'params', 8, 9)",
        )
    }

    private fun scalarInt(database: LocalDatabase, sql: String): Int =
        database.readableDatabase.rawQuery(sql, null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun scalarText(database: LocalDatabase, sql: String): String =
        database.readableDatabase.rawQuery(sql, null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun tableRows(database: LocalDatabase, table: String): Int =
        scalarInt(database, "SELECT count(*) FROM $table")

    private fun hasColumn(database: LocalDatabase, table: String, column: String): Boolean =
        database.readableDatabase.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            var found = false
            while (!found && cursor.moveToNext()) found = cursor.getString(1) == column
            found
        }

    private fun hasObject(database: LocalDatabase, type: String, name: String): Boolean =
        database.readableDatabase.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = ? AND name = ?",
            arrayOf(type, name),
        ).use { it.moveToFirst() }

    private fun deleteTestDatabases() {
        listOf(
            DATABASE_NAME,
            SCHEMA_23_DATABASE_NAME,
            WAL_SOURCE_DATABASE_NAME,
            WAL_TARGET_DATABASE_NAME,
            CONTENT_VALUES_DATABASE_NAME,
        ).forEach(context::deleteDatabase)
    }

    private companion object {
        const val DATABASE_NAME = "bundled-migration-test.db"
        const val SCHEMA_23_DATABASE_NAME = "bundled-schema-23-test.db"
        const val WAL_SOURCE_DATABASE_NAME = "bundled-wal-source.db"
        const val WAL_TARGET_DATABASE_NAME = "bundled-wal-target.db"
        const val CONTENT_VALUES_DATABASE_NAME = "bundled-content-values-test.db"
        const val VIDEO_ID = "abcdefghijk"
        const val DAY_MILLIS = 24L * 60L * 60L * 1000L
        val LEGACY_TABLES = listOf(
            "source_state",
            "video_history",
            "creator_metadata",
            "notification_inbox",
            "notification_ids",
            "source_priority",
            "youtube_channel_tabs",
        )
    }
}
