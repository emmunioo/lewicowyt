package pl.lewicowyt.notifier.data

import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.database.sqlite.SQLiteQueryBuilder
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_CREATE
import androidx.sqlite.driver.bundled.SQLITE_OPEN_FULLMUTEX
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Mała warstwa zgodności dla dotychczasowego kodu bazy. Cały plik bazy jest
 * otwierany przez dołączony SQLite, dzięki czemu FTS5 jest dostępne również na
 * Androidzie 8, bez równoczesnego otwierania tego samego pliku przez systemowy
 * silnik SQLite.
 */
internal class BundledDatabase(
    databaseFile: File,
) : AutoCloseable {
    private val lock = ReentrantLock(true)
    private val connection: SQLiteConnection
    private var transactionOwner: Thread? = null
    private var transactionSuccessful = false

    init {
        databaseFile.parentFile?.mkdirs()
        connection = BundledSQLiteDriver().open(
            databaseFile.absolutePath,
            SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE or SQLITE_OPEN_FULLMUTEX,
        )
    }

    fun execSQL(sql: String, bindArgs: Array<out Any?> = emptyArray()) = locked {
        execute(sql, bindArgs.asIterable())
    }

    fun rawQuery(sql: String, selectionArgs: Array<out Any?>?): Cursor = locked {
        connection.prepare(sql).use { statement ->
            bind(statement, selectionArgs.orEmpty().asIterable())
            val columnNames = Array(statement.getColumnCount()) { statement.getColumnName(it) }
            val cursor = MatrixCursor(columnNames)
            while (statement.step()) {
                cursor.addRow(
                    Array<Any?>(columnNames.size) { index ->
                        when {
                            statement.isNull(index) -> null
                            statement.getColumnType(index) == SQLITE_INTEGER ->
                                statement.getLong(index)
                            statement.getColumnType(index) == SQLITE_FLOAT ->
                                statement.getDouble(index)
                            statement.getColumnType(index) == SQLITE_BLOB ->
                                statement.getBlob(index)
                            else -> statement.getText(index)
                        }
                    },
                )
            }
            cursor
        }
    }

    /**
     * Mapuje wynik bezpośrednio z natywnego statementu. W przeciwieństwie do
     * rawQuery() nie tworzy pośredniego MatrixCursor i dlatego nadaje się do
     * ograniczonych, ale większych list Historii oraz przyszłej paginacji FTS.
     * Mapper nie może zachować referencji do statementu poza wywołaniem.
     */
    fun <T> queryRows(
        sql: String,
        selectionArgs: Array<out Any?>?,
        mapper: (SQLiteStatement) -> T,
    ): List<T> = locked {
        connection.prepare(sql).use { statement ->
            bind(statement, selectionArgs.orEmpty().asIterable())
            buildList {
                while (statement.step()) add(mapper(statement))
            }
        }
    }

    @Suppress("LongParameterList")
    fun query(
        table: String,
        columns: Array<String>?,
        selection: String?,
        selectionArgs: Array<out Any?>?,
        groupBy: String?,
        having: String?,
        orderBy: String?,
    ): Cursor {
        require(IDENTIFIER.matches(table)) { "Nieprawidłowa nazwa tabeli" }
        columns.orEmpty().forEach { column ->
            require(SAFE_COLUMN.matches(column)) { "Nieprawidłowa nazwa kolumny" }
        }
        val sql = SQLiteQueryBuilder.buildQueryString(
            false,
            table,
            columns,
            selection,
            groupBy,
            having,
            orderBy,
            null,
        )
        return rawQuery(sql, selectionArgs)
    }

    fun insertWithOnConflict(
        table: String,
        nullColumnHack: String?,
        values: ContentValues,
        conflictAlgorithm: Int,
    ): Long = insert(table, nullColumnHack, values, conflictAlgorithm, throwOnFailure = false)

    fun insertOrThrow(
        table: String,
        nullColumnHack: String?,
        values: ContentValues,
    ): Long = insert(table, nullColumnHack, values, CONFLICT_NONE, throwOnFailure = true)

    fun update(
        table: String,
        values: ContentValues,
        whereClause: String?,
        whereArgs: Array<out Any?>?,
    ): Int = locked {
        require(IDENTIFIER.matches(table)) { "Nieprawidłowa nazwa tabeli" }
        if (values.size() == 0) return@locked 0
        val entries = contentEntries(values)
        entries.forEach { require(IDENTIFIER.matches(it.first)) }
        val sql = buildString {
            append("UPDATE ").append(table).append(" SET ")
            append(entries.joinToString(",") { "${it.first} = ?" })
            if (!whereClause.isNullOrBlank()) append(" WHERE ").append(whereClause)
        }
        execute(sql, entries.map { it.second } + whereArgs.orEmpty().toList())
        changedRows()
    }

    fun delete(
        table: String,
        whereClause: String?,
        whereArgs: Array<out Any?>?,
    ): Int = locked {
        require(IDENTIFIER.matches(table)) { "Nieprawidłowa nazwa tabeli" }
        val sql = buildString {
            append("DELETE FROM ").append(table)
            if (!whereClause.isNullOrBlank()) append(" WHERE ").append(whereClause)
        }
        execute(sql, whereArgs.orEmpty().asIterable())
        changedRows()
    }

    fun beginTransaction() {
        lock.lock()
        try {
            check(transactionOwner == null) { "Zagnieżdżona transakcja nie jest obsługiwana" }
            execute("BEGIN IMMEDIATE", emptyList())
            transactionOwner = Thread.currentThread()
            transactionSuccessful = false
        } catch (error: Throwable) {
            lock.unlock()
            throw error
        }
    }

    fun setTransactionSuccessful() {
        check(transactionOwner == Thread.currentThread()) { "Brak aktywnej transakcji" }
        transactionSuccessful = true
    }

    fun endTransaction() {
        check(transactionOwner == Thread.currentThread()) { "Brak aktywnej transakcji" }
        try {
            execute(if (transactionSuccessful) "COMMIT" else "ROLLBACK", emptyList())
        } finally {
            transactionOwner = null
            transactionSuccessful = false
            lock.unlock()
        }
    }

    override fun close() = locked {
        check(transactionOwner == null) { "Nie można zamknąć bazy w transakcji" }
        connection.close()
    }

    private fun insert(
        table: String,
        nullColumnHack: String?,
        values: ContentValues,
        conflictAlgorithm: Int,
        throwOnFailure: Boolean,
    ): Long = locked {
        require(IDENTIFIER.matches(table)) { "Nieprawidłowa nazwa tabeli" }
        val entries = contentEntries(values)
        val sql = if (entries.isEmpty()) {
            val column = requireNotNull(nullColumnHack).also {
                require(IDENTIFIER.matches(it))
            }
            "INSERT ${conflictClause(conflictAlgorithm)} INTO $table ($column) VALUES (NULL)"
        } else {
            entries.forEach { require(IDENTIFIER.matches(it.first)) }
            val columns = entries.joinToString(",") { it.first }
            val placeholders = List(entries.size) { "?" }.joinToString(",")
            "INSERT ${conflictClause(conflictAlgorithm)} INTO $table ($columns) " +
                "VALUES ($placeholders)"
        }
        execute(sql, entries.map { it.second })
        if (changedRows() == 0) {
            if (throwOnFailure) error("Nie udało się dodać rekordu do $table")
            -1L
        } else {
            scalarLong("SELECT last_insert_rowid()")
        }
    }

    private fun changedRows(): Int = scalarLong("SELECT changes()").toInt()

    private fun scalarLong(sql: String): Long = connection.prepare(sql).use { statement ->
        check(statement.step()) { "Zapytanie nie zwróciło wartości" }
        statement.getLong(0)
    }

    private fun execute(sql: String, bindArgs: Iterable<Any?>) {
        connection.prepare(sql).use { statement ->
            bind(statement, bindArgs)
            while (statement.step()) Unit
        }
    }

    /**
     * Androidowa implementacja Set zwracana przez ContentValues.valueSet()
     * celowo nie obsługuje toArray(). Kotlinowe toList()/map() próbują użyć
     * tej operacji i kończą się UnsupportedOperationException. Kopiujemy więc
     * wpisy przez iterator do zwykłych par, zanim zbudujemy polecenie SQL.
     */
    private fun contentEntries(values: ContentValues): List<Pair<String, Any?>> = buildList(
        values.size(),
    ) {
        for (entry in values.valueSet()) {
            add(entry.key to entry.value)
        }
    }

    private fun bind(statement: SQLiteStatement, values: Iterable<Any?>) {
        values.forEachIndexed { index, value ->
            val parameter = index + 1
            when (value) {
                null -> statement.bindNull(parameter)
                is ByteArray -> statement.bindBlob(parameter, value)
                is Float -> statement.bindDouble(parameter, value.toDouble())
                is Double -> statement.bindDouble(parameter, value)
                is Number -> statement.bindLong(parameter, value.toLong())
                is Boolean -> statement.bindLong(parameter, if (value) 1L else 0L)
                else -> statement.bindText(parameter, value.toString())
            }
        }
    }

    private inline fun <T> locked(block: () -> T): T = lock.withLock(block)

    private fun conflictClause(algorithm: Int): String = when (algorithm) {
        CONFLICT_NONE -> ""
        CONFLICT_ROLLBACK -> "OR ROLLBACK"
        CONFLICT_ABORT -> "OR ABORT"
        CONFLICT_FAIL -> "OR FAIL"
        CONFLICT_IGNORE -> "OR IGNORE"
        CONFLICT_REPLACE -> "OR REPLACE"
        else -> error("Nieobsługiwany algorytm konfliktu: $algorithm")
    }

    companion object {
        const val CONFLICT_NONE = 0
        const val CONFLICT_ROLLBACK = 1
        const val CONFLICT_ABORT = 2
        const val CONFLICT_FAIL = 3
        const val CONFLICT_IGNORE = 4
        const val CONFLICT_REPLACE = 5

        private const val SQLITE_INTEGER = 1
        private const val SQLITE_FLOAT = 2
        private const val SQLITE_BLOB = 4
        private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
        private val SAFE_COLUMN = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\s+AS\\s+[A-Za-z_][A-Za-z0-9_]*)?", RegexOption.IGNORE_CASE)
    }
}
