package pl.lewicowyt.notifier.diagnostics

import android.app.AlarmManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import java.io.BufferedInputStream
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream
import java.util.zip.Inflater
import pl.lewicowyt.notifier.BuildConfig

internal enum class DiagnosticCategory(val id: Int, val label: String) {
    GENERAL(0, "OGÓLNE"),
    APP(1, "APLIKACJA"),
    SYNC(2, "SYNCHRONIZACJA"),
    HISTORY(3, "HISTORIA"),
    NETWORK(4, "SIEĆ"),
    SCHEDULER(5, "HARMONOGRAM"),
    UPDATE(6, "AKTUALIZACJE"),
    IMAGE(7, "OBRAZY"),
    DATABASE(8, "BAZA"),
    ;

    companion object {
        fun fromId(id: Int): DiagnosticCategory = entries.firstOrNull { it.id == id } ?: GENERAL

        fun fromTag(tag: String): DiagnosticCategory {
            val normalized = tag.lowercase()
            return when {
                "history" in normalized -> HISTORY
                "sync" in normalized || "sourcepriority" in normalized -> SYNC
                "network" in normalized || "youtube" in normalized || "dns" in normalized -> NETWORK
                "schedule" in normalized || "alarm" in normalized -> SCHEDULER
                "update" in normalized -> UPDATE
                "image" in normalized || "avatar" in normalized || "jxl" in normalized -> IMAGE
                "database" in normalized || "sqlite" in normalized -> DATABASE
                else -> GENERAL
            }
        }
    }
}

internal enum class DiagnosticLevel(val id: Int, val label: String) {
    INFO(0, "INFO"),
    WARNING(1, "OSTRZEŻENIE"),
    ERROR(2, "BŁĄD"),
    ;

    companion object {
        fun fromId(id: Int): DiagnosticLevel = entries.firstOrNull { it.id == id } ?: INFO
    }
}

internal data class DiagnosticLogState(
    val unlocked: Boolean,
    val enabled: Boolean,
    val storedBytes: Long,
    val eventCount: Int,
)

internal data class DiagnosticEvent(
    val timestampSeconds: Long,
    val level: DiagnosticLevel,
    val category: DiagnosticCategory,
    val message: String,
)

/**
 * Prywatny, domyślnie wyłączony dziennik. Na telefonie rekordy mają krótki
 * format binarny i osobno kompresowany ładunek DEFLATE (poziom 9). Dzięki temu
 * zapis jest odporny na ubicie procesu i nie wymaga trzymania otwartego pliku.
 * Eksport jest standardowym, czytelnym plikiem tekstowym GZIP.
 */
internal object DiagnosticLogStore {
    private val lock = Any()
    private var appContext: Context? = null
    private var unlocked = false
    private var enabled = false
    private var lastFingerprint = ""
    private var lastFingerprintAtMillis = 0L

    fun initialize(context: Context): Unit = synchronized(lock) {
        if (appContext != null) return
        appContext = context.applicationContext
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        unlocked = preferences.getBoolean(KEY_UNLOCKED, false)
        enabled = unlocked && preferences.getBoolean(KEY_ENABLED, false)
        if (enabled) {
            appendLocked(
                DiagnosticLevel.INFO,
                DiagnosticCategory.APP,
                "Uruchomiono proces aplikacji; wersja=${BuildConfig.VERSION_NAME}; sdk=${Build.VERSION.SDK_INT}",
            )
        }
    }

    fun state(): DiagnosticLogState = synchronized(lock) {
        val context = appContext
        DiagnosticLogState(
            unlocked = unlocked,
            enabled = enabled,
            storedBytes = context?.let(::logFiles)?.sumOf(File::length) ?: 0L,
            eventCount = context?.let(::readAllLocked)?.size ?: 0,
        )
    }

    fun unlock(): Unit = synchronized(lock) {
        val context = appContext ?: return
        if (unlocked) return
        unlocked = true
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_UNLOCKED, true)
            .apply()
    }

    fun hide(): Unit = synchronized(lock) {
        val context = appContext ?: return
        enabled = false
        unlocked = false
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, false)
            .putBoolean(KEY_UNLOCKED, false)
            .apply()
    }

    fun setEnabled(value: Boolean): Unit = synchronized(lock) {
        val context = appContext ?: return
        val newValue = value && unlocked
        if (enabled == newValue) return
        enabled = newValue
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
        if (enabled) {
            val alarmManager = context.getSystemService(AlarmManager::class.java)
            val exactAlarm = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                alarmManager?.canScheduleExactAlarms() == true
            val powerManager = context.getSystemService(PowerManager::class.java)
            val unrestrictedBattery = powerManager?.isIgnoringBatteryOptimizations(
                context.packageName,
            ) == true
            appendLocked(
                DiagnosticLevel.INFO,
                DiagnosticCategory.APP,
                "Włączono diagnostykę; wersja=${BuildConfig.VERSION_NAME}; " +
                    "sdk=${Build.VERSION.SDK_INT}; dokładny_alarm=$exactAlarm; " +
                    "bateria_bez_ograniczeń=$unrestrictedBattery",
            )
        }
    }

    fun info(category: DiagnosticCategory, message: String) = synchronized(lock) {
        if (enabled) appendLocked(DiagnosticLevel.INFO, category, message)
    }

    fun warning(tag: String, message: String, error: Throwable?) = synchronized(lock) {
        if (enabled) appendLocked(
            DiagnosticLevel.WARNING,
            DiagnosticCategory.fromTag(tag),
            withError(message, error),
        )
    }

    fun error(tag: String, message: String, error: Throwable?) = synchronized(lock) {
        if (enabled) appendLocked(
            DiagnosticLevel.ERROR,
            DiagnosticCategory.fromTag(tag),
            withError(message, error),
        )
    }

    fun clear(): Unit = synchronized(lock) {
        val context = appContext ?: return
        logFiles(context).forEach { file ->
            if (file.parentFile == diagnosticsDirectory(context)) file.delete()
        }
        lastFingerprint = ""
        lastFingerprintAtMillis = 0L
    }

    fun exportTo(destination: Uri): Boolean = synchronized(lock) {
        val context = appContext ?: return false
        val events = readAllLocked(context)
        runCatching {
            context.contentResolver.openOutputStream(destination, "w")?.use { output ->
                BestSizeGzipOutputStream(output).use { gzip ->
                    BufferedWriter(OutputStreamWriter(gzip, Charsets.UTF_8)).use { writer ->
                        writer.appendLine("lewicowYT — dziennik diagnostyczny")
                        writer.appendLine("wersja=${BuildConfig.VERSION_NAME}; sdk=${Build.VERSION.SDK_INT}")
                        writer.appendLine("Klucze API, nagłówki autoryzacji i parametry URL są usuwane.")
                        writer.appendLine()
                        events.forEach { event ->
                            writer.append(EXPORT_TIME_FORMAT.format(
                                Instant.ofEpochSecond(event.timestampSeconds),
                            ))
                            writer.append(" | ")
                            writer.append(event.level.label)
                            writer.append(" | ")
                            writer.append(event.category.label)
                            writer.append(" | ")
                            writer.appendLine(event.message)
                        }
                    }
                }
            } ?: error("Nie można otworzyć pliku docelowego")
        }.isSuccess
    }

    private fun appendLocked(
        level: DiagnosticLevel,
        category: DiagnosticCategory,
        rawMessage: String,
    ) {
        val context = appContext ?: return
        val message = DiagnosticLogCodec.sanitize(rawMessage)
        if (message.isBlank()) return
        val nowMillis = System.currentTimeMillis()
        val fingerprint = "${level.id}|${category.id}|$message"
        if (fingerprint == lastFingerprint && nowMillis - lastFingerprintAtMillis < DEDUP_MILLIS) {
            return
        }
        lastFingerprint = fingerprint
        lastFingerprintAtMillis = nowMillis
        val event = DiagnosticEvent(
            timestampSeconds = nowMillis / 1_000L,
            level = level,
            category = category,
            message = message,
        )
        val encoded = DiagnosticLogCodec.encode(event)
        val current = currentFile(context)
        if (current.length() + encoded.size > MAX_FILE_BYTES) rotateLocked(context)
        val target = currentFile(context)
        FileOutputStream(target, true).use { output ->
            if (target.length() == 0L) DataOutputStream(output).writeInt(FILE_MAGIC)
            output.write(encoded)
            output.flush()
        }
    }

    private fun rotateLocked(context: Context) {
        val current = currentFile(context)
        val previous = previousFile(context)
        previous.delete()
        if (current.isFile) current.renameTo(previous)
    }

    private fun readAllLocked(context: Context): List<DiagnosticEvent> = buildList {
        logFiles(context).forEach { file ->
            if (!file.isFile || file.length() !in 4..MAX_READ_BYTES) return@forEach
            runCatching {
                DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
                    if (input.readInt() != FILE_MAGIC) return@use
                    while (true) {
                        val event = try {
                            DiagnosticLogCodec.decode(input)
                        } catch (_: EOFException) {
                            null
                        }
                        if (event == null) break
                        add(event)
                    }
                }
            }
        }
    }.sortedBy(DiagnosticEvent::timestampSeconds)

    private fun withError(message: String, error: Throwable?): String = buildString {
        append(message)
        if (error != null) {
            append("; ")
            append(error.javaClass.simpleName)
            error.message?.takeIf(String::isNotBlank)?.let {
                append(": ")
                append(it)
            }
        }
    }

    private fun diagnosticsDirectory(context: Context): File =
        File(context.filesDir, DIRECTORY_NAME).apply { mkdirs() }

    private fun currentFile(context: Context) = File(diagnosticsDirectory(context), CURRENT_FILE)
    private fun previousFile(context: Context) = File(diagnosticsDirectory(context), PREVIOUS_FILE)
    private fun logFiles(context: Context) = listOf(previousFile(context), currentFile(context))

    private class BestSizeGzipOutputStream(output: OutputStream) : GZIPOutputStream(output) {
        init {
            def.setLevel(Deflater.BEST_COMPRESSION)
        }
    }

    private const val PREFERENCES_NAME = "diagnostic_log_preferences"
    private const val KEY_UNLOCKED = "unlocked"
    private const val KEY_ENABLED = "enabled"
    private const val DIRECTORY_NAME = "diagnostics"
    private const val CURRENT_FILE = "current.dlog"
    private const val PREVIOUS_FILE = "previous.dlog"
    private const val FILE_MAGIC = 0x4C595431
    private const val MAX_FILE_BYTES = 1_024L * 1_024L
    private const val MAX_READ_BYTES = MAX_FILE_BYTES + 64L * 1024L
    private const val DEDUP_MILLIS = 2L * 60L * 1_000L
    private val EXPORT_TIME_FORMAT = DateTimeFormatter
        .ofPattern("uuuu-MM-dd HH:mm:ss 'UTC'")
        .withZone(ZoneOffset.UTC)
}

internal object DiagnosticLogCodec {
    private const val FLAG_DEFLATED = 1
    private const val MAX_MESSAGE_BYTES = 1_024
    private const val MAX_FRAME_BYTES = 4_096

    fun encode(event: DiagnosticEvent): ByteArray {
        val raw = sanitize(event.message).toByteArray(Charsets.UTF_8)
            .let { if (it.size <= MAX_MESSAGE_BYTES) it else it.copyOf(MAX_MESSAGE_BYTES) }
        val compressed = deflate(raw)
        val useCompressed = compressed.size < raw.size
        val payload = if (useCompressed) compressed else raw
        return ByteArrayOutputStream(payload.size + 9).use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(event.timestampSeconds.toInt())
                output.writeByte(event.level.id)
                output.writeByte(event.category.id)
                output.writeByte(if (useCompressed) FLAG_DEFLATED else 0)
                output.writeShort(payload.size)
                output.write(payload)
            }
            buffer.toByteArray()
        }
    }

    fun decode(input: DataInputStream): DiagnosticEvent? {
        val timestamp = input.readInt().toLong() and 0xFFFF_FFFFL
        val level = DiagnosticLevel.fromId(input.readUnsignedByte())
        val category = DiagnosticCategory.fromId(input.readUnsignedByte())
        val flags = input.readUnsignedByte()
        val length = input.readUnsignedShort()
        if (length !in 0..MAX_FRAME_BYTES) return null
        val payload = ByteArray(length)
        input.readFully(payload)
        val decoded = if (flags and FLAG_DEFLATED != 0) inflate(payload) else payload
        if (decoded.size > MAX_MESSAGE_BYTES) return null
        return DiagnosticEvent(
            timestampSeconds = timestamp,
            level = level,
            category = category,
            message = decoded.toString(Charsets.UTF_8),
        )
    }

    fun sanitize(value: String): String = value
        .replace(API_KEY, "[USUNIĘTY_KLUCZ_API]")
        .replace(AUTHORIZATION, "[USUNIĘTA_AUTORYZACJA]")
        .replace(URL_QUERY) { match -> match.groupValues[1] }
        .replace(ANDROID_PRIVATE_PATH, "[PRYWATNA_ŚCIEŻKA]")
        .replace(Regex("[\\r\\n\\t]+"), " ")
        .filter { it >= ' ' }
        .trim()
        .take(MAX_MESSAGE_BYTES)

    private fun deflate(input: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        return try {
            deflater.setInput(input)
            deflater.finish()
            val output = ByteArrayOutputStream(input.size)
            val buffer = ByteArray(512)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                if (count <= 0) break
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun inflate(input: ByteArray): ByteArray {
        val inflater = Inflater(true)
        return try {
            inflater.setInput(input)
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(512)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count <= 0) break
                require(output.size() + count <= MAX_MESSAGE_BYTES)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } finally {
            inflater.end()
        }
    }

    private val API_KEY = Regex(
        "(?i)(AIza[0-9A-Za-z_-]{20,}|(?:api[_-]?key|key)=)[^&\\s]+",
    )
    private val AUTHORIZATION = Regex(
        "(?i)(?:authorization\\s*[:=]\\s*(?:bearer\\s+)?|bearer\\s+)[^,;\\s]+",
    )
    private val URL_QUERY = Regex("(https://[^?\\s]+)\\?[^\\s]+")
    private val ANDROID_PRIVATE_PATH = Regex(
        "(?:/data/(?:user/\\d+|data)/|/storage/emulated/\\d+/)[^\\s,;]+",
    )
}
