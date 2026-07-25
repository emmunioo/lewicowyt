package pl.lewicowyt.notifier.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import pl.lewicowyt.notifier.model.Creator
import pl.lewicowyt.notifier.model.HistoryItem
import pl.lewicowyt.notifier.model.VideoEntry
import pl.lewicowyt.notifier.model.VideoKind
import pl.lewicowyt.notifier.model.VideoOrigin
import pl.lewicowyt.notifier.network.YouTubeHistoryItem

data class NotificationCursor(
    val videoId: String,
    val publishedAtMillis: Long,
)

class LocalDatabase(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {
    override fun onCreate(db: SQLiteDatabase) {
        createSourceStateTable(db)
        createVideoHistoryTable(db)
        createCreatorMetadataTable(db)
        createNotificationInboxTable(db)
        createNotificationIdsTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
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
    }

    private fun createSourceStateTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS source_state (
                source_key TEXT PRIMARY KEY,
                resolved_id TEXT,
                initialized INTEGER NOT NULL DEFAULT 0,
                last_checked_ms INTEGER NOT NULL DEFAULT 0,
                last_error TEXT,
                last_notification_video_id TEXT,
                last_notification_published_ms INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
    }

    private fun createVideoHistoryTable(db: SQLiteDatabase) {
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
                classification_last_attempt_ms INTEGER NOT NULL DEFAULT 0
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
    }

    private fun createCreatorMetadataTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS creator_metadata (
                creator_id TEXT PRIMARY KEY,
                avatar_url TEXT,
                updated_ms INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
    }

    private fun createNotificationInboxTable(db: SQLiteDatabase) {
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

    private fun createNotificationIdsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS notification_ids (
                notification_id INTEGER PRIMARY KEY AUTOINCREMENT,
                video_id TEXT NOT NULL UNIQUE
            )
            """.trimIndent(),
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
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        writableDatabase.update(
            "source_state",
            ContentValues().apply { put("resolved_id", resolvedId) },
            "source_key = ?",
            arrayOf(sourceKey),
        )
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
    fun getCreatorAvatars(): Map<String, String> = readableDatabase.rawQuery(
        "SELECT creator_id, avatar_url FROM creator_metadata WHERE avatar_url IS NOT NULL",
        null,
    ).use { cursor ->
        buildMap {
            while (cursor.moveToNext()) {
                val avatar = cursor.getString(1)
                if (!avatar.isNullOrBlank()) put(cursor.getString(0), avatar)
            }
        }
    }

    @Synchronized
    fun saveCreatorAvatar(creatorId: String, avatarUrl: String) {
        val values = ContentValues().apply {
            put("creator_id", creatorId)
            put("avatar_url", avatarUrl)
            put("updated_ms", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            "creator_metadata",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
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
    fun seedSource(sourceKey: String, creator: Creator, entries: List<VideoEntry>) {
        writableDatabase.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            // Tylko dane YouTube mogą ustanowić punkt odniesienia. Publiczna
            // instancja Piped nie może przesunąć kursora i ukryć późniejszych
            // prawidłowych publikacji.
            val newestEntry = entries
                .filter { it.origin == VideoOrigin.YOUTUBE }
                .maxWithOrNull(
                    compareBy<VideoEntry> { it.publishedAtMillis }.thenBy { it.id },
                )
            entries.forEach { entry ->
                val rowId = insertVideoInternal(
                    db = writableDatabase,
                    creator = creator,
                    entry = entry,
                    kind = VideoKind.VIDEO,
                    notified = true,
                    detectedAt = now,
                    classificationVersion = 0,
                    notificationChecked = true,
                )
                if (rowId == -1L && entry.origin == VideoOrigin.YOUTUBE) {
                    val promoted = promotePipedVideoFromYouTube(
                        creator = creator,
                        entry = entry,
                        kind = VideoKind.VIDEO,
                        notified = true,
                        classificationVersion = 0,
                    )
                    if (!promoted) {
                        reconcileHistoricalYouTubeEntry(entry, shouldNotify = false)
                    }
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
                SQLiteDatabase.CONFLICT_IGNORE,
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
        if (entry.origin != VideoOrigin.YOUTUBE) return
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
            SQLiteDatabase.CONFLICT_IGNORE,
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
            SQLiteDatabase.CONFLICT_IGNORE,
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

    @Synchronized
    fun containsVideo(videoId: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM video_history WHERE video_id = ? LIMIT 1",
        arrayOf(videoId),
    ).use { cursor -> cursor.moveToFirst() }

    @Synchronized
    fun videoOrigin(videoId: String): VideoOrigin? = readableDatabase.query(
        "video_history",
        arrayOf("origin"),
        "video_id = ?",
        arrayOf(videoId),
        null,
        null,
        null,
    ).use { cursor ->
        if (!cursor.moveToFirst()) {
            null
        } else {
            runCatching { VideoOrigin.valueOf(cursor.getString(0)) }.getOrNull()
        }
    }

    @Synchronized
    fun updateFromVerifiedYouTubeEntry(entry: VideoEntry) {
        writableDatabase.update(
            "video_history",
            ContentValues().apply {
                put("title", entry.title)
                put("url", entry.url)
                put("published_ms", entry.publishedAtMillis)
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
        shouldNotify: Boolean,
    ): Boolean {
        val values = ContentValues().apply {
            put("title", entry.title)
            put("url", entry.url)
            put("published_ms", entry.publishedAtMillis)
            put("origin", VideoOrigin.YOUTUBE.name)
            put("notification_checked", 1)
            if (shouldNotify) put("notified", 0)
        }
        val updated = writableDatabase.update(
            "video_history",
            values,
            "video_id = ? AND origin = ? AND notification_checked = 0",
            arrayOf(entry.id, VideoOrigin.YOUTUBE.name),
        )
        if (updated == 0) updateFromVerifiedYouTubeEntry(entry)
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
        kind: VideoKind,
        shouldNotify: Boolean,
        classificationVersion: Int,
    ): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existing = db.query(
                "video_history",
                arrayOf("origin", "notification_checked"),
                "video_id = ?",
                arrayOf(entry.id),
                null,
                null,
                null,
            ).use { cursor ->
                if (!cursor.moveToFirst()) {
                    null
                } else {
                    runCatching { VideoOrigin.valueOf(cursor.getString(0)) }
                        .getOrDefault(VideoOrigin.PIPED) to (cursor.getInt(1) == 1)
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
                            classificationVersion = classificationVersion,
                            notificationChecked = true,
                        ) != -1L
                    }
                }

                existing.first == VideoOrigin.PIPED -> {
                    db.update(
                        "video_history",
                        verifiedSyncValues(
                            creator = creator,
                            entry = entry,
                            kind = kind,
                            notified = !shouldNotify,
                            classificationVersion = classificationVersion,
                            includeCreator = true,
                        ),
                        "video_id = ? AND origin = ?",
                        arrayOf(entry.id, VideoOrigin.PIPED.name),
                    )
                    shouldNotify
                }

                !existing.second -> {
                    db.update(
                        "video_history",
                        verifiedSyncValues(
                            creator = creator,
                            entry = entry,
                            kind = kind,
                            notified = if (shouldNotify) false else null,
                            classificationVersion = classificationVersion,
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
                        verifiedSyncValues(
                            creator = creator,
                            entry = entry,
                            kind = kind,
                            notified = null,
                            classificationVersion = classificationVersion,
                            includeCreator = false,
                        ),
                        "video_id = ? AND origin = ?",
                        arrayOf(entry.id, VideoOrigin.YOUTUBE.name),
                    )
                    false
                }
            }
            db.setTransactionSuccessful()
            return becamePending
        } finally {
            db.endTransaction()
        }
    }

    private fun verifiedSyncValues(
        creator: Creator,
        entry: VideoEntry,
        kind: VideoKind,
        notified: Boolean?,
        classificationVersion: Int,
        includeCreator: Boolean,
    ): ContentValues = ContentValues().apply {
        if (includeCreator) {
            put("creator_id", creator.id)
            put("creator_name", creator.name)
        }
        put("title", entry.title)
        put("url", entry.url)
        put("published_ms", entry.publishedAtMillis)
        put("detected_ms", System.currentTimeMillis())
        if (kind != VideoKind.UNKNOWN) {
            put("kind", kind.name)
            put("classification_version", classificationVersion)
        }
        notified?.let { put("notified", if (it) 1 else 0) }
        put("origin", VideoOrigin.YOUTUBE.name)
        put("notification_checked", 1)
    }

    /**
     * Zastępuje niezaufany rekord Piped danymi potwierdzonymi w feedzie/API
     * konkretnego kanału. Warunek origin=PIPED zapobiega nadpisywaniu rekordu
     * YouTube, który mógł legalnie pojawić się także w innej playliście.
     */
    @Synchronized
    fun promotePipedVideoFromYouTube(
        creator: Creator,
        entry: VideoEntry,
        kind: VideoKind,
        notified: Boolean,
        classificationVersion: Int = 1,
    ): Boolean {
        val updated = writableDatabase.update(
            "video_history",
            ContentValues().apply {
                put("creator_id", creator.id)
                put("creator_name", creator.name)
                put("title", entry.title)
                put("url", entry.url)
                put("published_ms", entry.publishedAtMillis)
                put("detected_ms", System.currentTimeMillis())
                put("kind", kind.name)
                put("notified", if (notified) 1 else 0)
                put("classification_version", classificationVersion)
                put("origin", VideoOrigin.YOUTUBE.name)
                put("notification_checked", 1)
            },
            "video_id = ? AND origin = ?",
            arrayOf(entry.id, VideoOrigin.PIPED.name),
        )
        return updated == 1
    }

    @Synchronized
    fun insertNewVideo(
        creator: Creator,
        entry: VideoEntry,
        kind: VideoKind,
        notified: Boolean,
        classificationVersion: Int = 1,
    ): Boolean = insertVideoInternal(
        db = writableDatabase,
        creator = creator,
        entry = entry,
        kind = kind,
        notified = notified,
        detectedAt = System.currentTimeMillis(),
        classificationVersion = classificationVersion,
        notificationChecked = true,
    ) != -1L

    @Synchronized
    fun insertHistoricalVideos(
        creator: Creator,
        items: List<YouTubeHistoryItem>,
    ): Int = insertHistoricalVideosInternal(
        creator = creator,
        items = items,
        updateExisting = true,
    )

    @Synchronized
    fun insertPipedHistoricalVideos(
        creator: Creator,
        items: List<YouTubeHistoryItem>,
    ): Int = insertHistoricalVideosInternal(
        creator = creator,
        items = items,
        updateExisting = false,
    )

    private fun insertHistoricalVideosInternal(
        creator: Creator,
        items: List<YouTubeHistoryItem>,
        updateExisting: Boolean,
    ): Int {
        if (items.isEmpty()) return 0
        var inserted = 0
        val now = System.currentTimeMillis()
        writableDatabase.beginTransaction()
        try {
            items.forEach { item ->
                val rowId = insertVideoInternal(
                    db = writableDatabase,
                    creator = creator,
                    entry = item.entry,
                    kind = item.kind,
                    notified = true,
                    detectedAt = now,
                    classificationVersion = if (item.kind == VideoKind.UNKNOWN) 0 else 1,
                    notificationChecked = false,
                )
                if (rowId != -1L) {
                    inserted += 1
                } else if (updateExisting) {
                    updateHistoricalItem(creator, item)
                }
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
        return inserted
    }

    private fun updateHistoricalItem(creator: Creator, item: YouTubeHistoryItem) {
        val existingOrigin = videoOrigin(item.entry.id)
        writableDatabase.update(
            "video_history",
            ContentValues().apply {
                put("creator_id", creator.id)
                put("creator_name", creator.name)
                put("title", item.entry.title)
                put("url", item.entry.url)
                put("published_ms", item.entry.publishedAtMillis)
                put("kind", item.kind.name)
                put(
                    "classification_version",
                    if (item.kind == VideoKind.UNKNOWN) 0 else 1,
                )
                // Backfill historii nie decyduje o powiadomieniu. Rekord Piped
                // pozostaje oznaczony jako niezaufany do czasu, aż synchronizator
                // powiadomień oceni go względem kursora właściwego kanału.
                if (existingOrigin != VideoOrigin.PIPED) {
                    put("origin", item.entry.origin.name)
                }
            },
            "video_id = ?",
            arrayOf(item.entry.id),
        )
    }

    private fun insertVideoInternal(
        db: SQLiteDatabase,
        creator: Creator,
        entry: VideoEntry,
        kind: VideoKind,
        notified: Boolean,
        detectedAt: Long,
        classificationVersion: Int,
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
            put("detected_ms", detectedAt)
            put("kind", kind.name)
            put("notified", if (notified) 1 else 0)
            put("classification_version", classificationVersion)
            put("origin", entry.origin.name)
            put("notification_checked", if (notificationChecked) 1 else 0)
        },
        SQLiteDatabase.CONFLICT_IGNORE,
    )

    @Synchronized
    fun pendingUpcoming(selectedCreatorIds: Set<String>): List<HistoryItem> {
        if (selectedCreatorIds.isEmpty()) return emptyList()
        val placeholders = selectedCreatorIds.joinToString(",") { "?" }
        return readableDatabase.rawQuery(
            """
            SELECT video_id, creator_id, creator_name, title, url,
                   published_ms, detected_ms, kind, notified, origin
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
        ).use(::readHistoryItems)
    }

    @Synchronized
    fun pendingNotifications(
        selectedCreatorIds: Set<String>,
        limit: Int = 2_000,
    ): List<HistoryItem> {
        if (selectedCreatorIds.isEmpty()) return emptyList()
        val placeholders = selectedCreatorIds.joinToString(",") { "?" }
        return readableDatabase.rawQuery(
            """
            SELECT video_id, creator_id, creator_name, title, url,
                   published_ms, detected_ms, kind, notified, origin
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
        ).use(::readHistoryItems)
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
    fun markVideoState(videoId: String, kind: VideoKind, notified: Boolean) {
        writableDatabase.update(
            "video_history",
            ContentValues().apply {
                put("kind", kind.name)
                put("notified", if (notified) 1 else 0)
                put("classification_version", 1)
            },
            "video_id = ? AND origin = ?",
            arrayOf(videoId, VideoOrigin.YOUTUBE.name),
        )
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
        return readableDatabase.rawQuery(
            """
            SELECT video_id, creator_id, creator_name, title, url,
                   published_ms, detected_ms, kind, notified, origin
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
        ).use(::readHistoryItems)
    }

    @Synchronized
    fun markVideoClassification(
        videoId: String,
        kind: VideoKind,
    ) {
        writableDatabase.update(
            "video_history",
            ContentValues().apply {
                put("kind", kind.name)
                put("classification_version", 1)
                put("classification_last_attempt_ms", System.currentTimeMillis())
            },
            "video_id = ? AND origin = ?",
            arrayOf(videoId, VideoOrigin.YOUTUBE.name),
        )
    }

    @Synchronized
    fun recordFailedVideoClassification(videoId: String) {
        writableDatabase.execSQL(
            """
            UPDATE video_history
            SET classification_attempts = classification_attempts + 1,
                classification_last_attempt_ms = ?,
                kind = CASE
                    WHEN classification_attempts + 1 >= ?
                    THEN ?
                    ELSE kind
                END,
                classification_version = CASE
                    WHEN classification_attempts + 1 >= ?
                    THEN 1
                    ELSE classification_version
                END
            WHERE video_id = ? AND origin = ?
            """.trimIndent(),
            arrayOf<Any>(
                System.currentTimeMillis(),
                MAX_CLASSIFICATION_ATTEMPTS,
                VideoKind.VIDEO.name,
                MAX_CLASSIFICATION_ATTEMPTS,
                videoId,
                VideoOrigin.YOUTUBE.name,
            ),
        )
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
                "published_ms < ?",
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
        return readableDatabase.rawQuery(
            """
            SELECT video_id, creator_id, creator_name, title, url,
                   published_ms, detected_ms, kind, notified, origin
            FROM video_history
            WHERE published_ms >= ?
            ORDER BY published_ms DESC, detected_ms DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(cutoff.toString(), limit.coerceIn(1, 10_000).toString()),
        ).use(::readHistoryItems)
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
                    SQLiteDatabase.CONFLICT_IGNORE,
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
        return readableDatabase.rawQuery(
            """
            SELECT h.video_id, h.creator_id, h.creator_name, h.title, h.url,
                   h.published_ms, h.detected_ms, h.kind, h.notified, h.origin
            FROM notification_inbox n
            JOIN video_history h ON h.video_id = n.video_id
            WHERE n.created_ms >= ?
            ORDER BY h.published_ms DESC, n.created_ms DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(cutoff.toString(), limit.coerceIn(1, 2_000).toString()),
        ).use(::readHistoryItems)
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
                "creator_id IN ($placeholders)",
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
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    private fun readHistoryItems(cursor: Cursor): List<HistoryItem> = buildList {
        while (cursor.moveToNext()) {
            add(
                HistoryItem(
                    videoId = cursor.getString(0),
                    creatorId = cursor.getString(1),
                    creatorName = cursor.getString(2),
                    title = cursor.getString(3),
                    url = cursor.getString(4),
                    publishedAtMillis = cursor.getLong(5),
                    detectedAtMillis = cursor.getLong(6),
                    kind = runCatching { VideoKind.valueOf(cursor.getString(7)) }
                        .getOrDefault(VideoKind.UNKNOWN),
                    notified = cursor.getInt(8) == 1,
                    origin = runCatching { VideoOrigin.valueOf(cursor.getString(9)) }
                        .getOrDefault(VideoOrigin.YOUTUBE),
                ),
            )
        }
    }

    private fun compactDatabaseIfWorthwhile(db: SQLiteDatabase) {
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

    private companion object {
        const val DATABASE_NAME = "lewicowyt_notifier.db"
        const val DATABASE_VERSION = 9
        const val MAX_CLASSIFICATION_ATTEMPTS = 3
        const val DAY_MILLIS = 24L * 60L * 60L * 1000L
        const val MIN_FREE_PAGES_FOR_VACUUM = 128L
    }
}
