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
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream
import java.util.zip.Inflater
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
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
    COMMUNITY(9, "SPOŁECZNOŚĆ"),
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
    // dziennik ruchu sieciowego (każde zapytanie HTTP) — osobny przełącznik i osobne pliki
    val trafficEnabled: Boolean = false,
    val storedBytes: Long,
    // E-05: -1 = jeszcze nie policzone (liczenie dekoduje cały dziennik — tylko w tle, eventCount())
    val eventCount: Int = -1,
)

internal data class DiagnosticEvent(
    val timestampSeconds: Long,
    val level: DiagnosticLevel,
    val category: DiagnosticCategory,
    val message: String,
)

/**
 * prywatny dziennik, domyślnie off. na telefonie rekordy w krótkim formacie
 * binarnym + osobno kompresowany ładunek DEFLATE (poziom 9) – zapis przeżywa
 * ubicie procesu i nie trzyma otwartego pliku. eksport to zwykły czytelny GZIP.
 */
internal object DiagnosticLogStore {
    private val lock = Any()
    private var appContext: Context? = null
    private var unlocked = false
    private var enabled = false
    private var trafficEnabled = false
    private var lastFingerprint = ""
    private var lastFingerprintAtMillis = 0L
    private var exportSummary: List<String> = emptyList()

    fun initialize(context: Context): Unit = synchronized(lock) {
        if (appContext != null) return
        appContext = context.applicationContext
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        unlocked = preferences.getBoolean(KEY_UNLOCKED, false)
        enabled = unlocked && preferences.getBoolean(KEY_ENABLED, false)
        trafficEnabled = enabled && preferences.getBoolean(KEY_TRAFFIC, false)
        if (unlocked) loadHostSaltLocked(context)
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
            trafficEnabled = trafficEnabled,
            storedBytes = context?.let(::logFiles)?.sumOf(File::length) ?: 0L,
        )
    }

    /**
     * E-05: liczba zdarzeń wymaga zdekodowania wszystkich plików (z dziennikiem ruchu do ~8 MB) — wołać poza
     * wątkiem UI. Czyta bez blokady, żeby nie wstrzymywać zapisu ruchu z wątków OkHttp; pliki są tylko
     * dopisywane, a niedokończony rekord na końcu kończy odczyt — wynik jest tylko licznikiem do wyświetlenia.
     */
    fun eventCount(): Int {
        val context = synchronized(lock) { appContext } ?: return 0
        return readAll(context).size
    }

    fun isEnabled(): Boolean = synchronized(lock) { enabled }

    /** dziennik ruchu działa tylko przy włączonej diagnostyce (wyłączenie diagnostyki zatrzymuje też ruch). */
    fun isTrafficEnabled(): Boolean = synchronized(lock) { enabled && trafficEnabled }

    fun setTrafficEnabled(value: Boolean): Unit = synchronized(lock) {
        val context = appContext ?: return
        trafficEnabled = value && enabled
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_TRAFFIC, trafficEnabled)
            .apply()
        if (enabled) {
            appendLocked(
                DiagnosticLevel.INFO,
                DiagnosticCategory.NETWORK,
                if (trafficEnabled) "Włączono dziennik ruchu sieciowego" else "Wyłączono dziennik ruchu sieciowego",
            )
        }
    }

    /** jedno zapytanie HTTP do osobnego dziennika ruchu (nie wypiera zdarzeń synchronizacji z głównego). */
    fun traffic(level: DiagnosticLevel, message: String) = synchronized(lock) {
        if (enabled && trafficEnabled) appendLocked(level, DiagnosticCategory.NETWORK, message, traffic = true)
    }

    fun unlock(): Unit = synchronized(lock) {
        val context = appContext ?: return
        if (unlocked) return
        unlocked = true
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_UNLOCKED, true)
            .apply()
        loadHostSaltLocked(context)
    }

    /**
     * J-09: sól skrótów hostów — losowa na instalację, tworzona przy odblokowaniu diagnostyki. Bez niej skrót
     * był zwykłym SHA-256 nazwy, więc z eksportu dało się potwierdzić, z którego (publicznie znanego) serwera
     * ktoś korzysta. Ukrycie panelu ją kasuje (razem z dziennikiem) — kolejne odblokowanie losuje nową.
     */
    private fun loadHostSaltLocked(context: Context) {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val stored = preferences.getString(KEY_HOST_SALT, null)
            ?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
            ?.takeIf { it.size == HOST_SALT_BYTES }
        DiagnosticLogCodec.hostSalt = stored ?: ByteArray(HOST_SALT_BYTES).also { salt ->
            SecureRandom().nextBytes(salt)
            preferences.edit().putString(KEY_HOST_SALT, Base64.getEncoder().encodeToString(salt)).apply()
        }
    }

    fun hide(): Unit = synchronized(lock) {
        val context = appContext ?: return
        enabled = false
        unlocked = false
        trafficEnabled = false
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, false)
            .putBoolean(KEY_TRAFFIC, false)
            .putBoolean(KEY_UNLOCKED, false)
            .remove(KEY_HOST_SALT)
            .apply()
        DiagnosticLogCodec.hostSalt = null
        // B-18: po ukryciu panelu nie ma już przycisku „Wyczyść”, a dziennik zawiera linki do materiałów,
        // ID twórców i kanałów — ukrycie musi go usunąć, a nie zostawić na dysku.
        logFiles(context).forEach { file ->
            if (file.parentFile == diagnosticsDirectory(context)) file.delete()
        }
        lastFingerprint = ""
        lastFingerprintAtMillis = 0L
        exportSummary = emptyList()
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

    fun event(
        category: DiagnosticCategory,
        level: DiagnosticLevel,
        name: String,
        syncId: String? = null,
        reason: DiagnosticReasonCode? = null,
        fields: Map<String, Any?> = emptyMap(),
        text: String? = null,
    ) = synchronized(lock) {
        if (enabled) {
            appendLocked(
                level,
                category,
                formatDiagnosticEvent(name, syncId, reason, fields, text),
            )
        }
    }

    fun updateExportSummary(lines: List<String>) = synchronized(lock) {
        exportSummary = lines
            .map(DiagnosticLogCodec::sanitize)
            .filter(String::isNotBlank)
            .take(MAX_EXPORT_SUMMARY_LINES)
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

    /**
     * G-03: pod blokadą tylko migawka kontekstu i podsumowania; odczyt plików (tylko dopisywanych, jak w
     * [eventCount]) i zapis do dostawcy plików (chmura, USB — dowolnie wolny) idą bez blokady, żeby nie
     * wstrzymywać wpisów z wątków OkHttp ani state() na wątku UI.
     */
    fun exportTo(destination: Uri): Boolean {
        val (context, summary) = synchronized(lock) { appContext to exportSummary.toList() }
        if (context == null) return false
        val events = readAll(context)
        return runCatching {
            context.contentResolver.openOutputStream(destination, "w")?.use { output ->
                BestSizeGzipOutputStream(output).use { gzip ->
                    BufferedWriter(OutputStreamWriter(gzip, Charsets.UTF_8)).use { writer ->
                        writer.appendLine("=== lewicowYT diagnostics ===")
                        writer.appendLine("APP | version=${BuildConfig.VERSION_NAME} | sdk=${Build.VERSION.SDK_INT}")
                        summary.forEach(writer::appendLine)
                        writer.appendLine("LOG | secrets=redacted | urlQueries=redacted")
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
        traffic: Boolean = false,
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
        val current = if (traffic) trafficFile(context) else currentFile(context)
        val limit = if (traffic) MAX_TRAFFIC_FILE_BYTES else MAX_FILE_BYTES
        if (current.length() + encoded.size > limit) rotateLocked(context, traffic)
        val target = if (traffic) trafficFile(context) else currentFile(context)
        FileOutputStream(target, true).use { output ->
            if (target.length() == 0L) DataOutputStream(output).writeInt(FILE_MAGIC)
            output.write(encoded)
            output.flush()
        }
    }

    private fun rotateLocked(context: Context, traffic: Boolean = false) {
        val current = if (traffic) trafficFile(context) else currentFile(context)
        val previous = if (traffic) trafficPreviousFile(context) else previousFile(context)
        previous.delete()
        if (current.isFile) current.renameTo(previous)
    }

    private fun readAll(context: Context): List<DiagnosticEvent> = buildList {
        logFiles(context).forEach { file ->
            // E-09d: zwykły dziennik ma limit 1 MB, ruch 4 MB — każdy plik sprawdzany swoim limitem
            val maxBytes = if (file.name.startsWith("traffic")) MAX_TRAFFIC_READ_BYTES else MAX_READ_BYTES
            if (!file.isFile || file.length() !in 4..maxBytes) return@forEach
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
    private fun trafficFile(context: Context) = File(diagnosticsDirectory(context), TRAFFIC_FILE)
    private fun trafficPreviousFile(context: Context) = File(diagnosticsDirectory(context), TRAFFIC_PREVIOUS_FILE)
    private fun logFiles(context: Context) =
        listOf(previousFile(context), currentFile(context), trafficPreviousFile(context), trafficFile(context))

    private class BestSizeGzipOutputStream(output: OutputStream) : GZIPOutputStream(output) {
        init {
            def.setLevel(Deflater.BEST_COMPRESSION)
        }
    }

    private const val PREFERENCES_NAME = "diagnostic_log_preferences"
    private const val KEY_UNLOCKED = "unlocked"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TRAFFIC = "traffic"
    private const val KEY_HOST_SALT = "host_salt"
    private const val HOST_SALT_BYTES = 16
    private const val TRAFFIC_FILE = "traffic.dlog"
    private const val TRAFFIC_PREVIOUS_FILE = "traffic-previous.dlog"
    // ruch: 2 × 4 MB skompresowanych wpisów (~kilkadziesiąt tysięcy zapytań — kilka dni zwykłego użycia)
    private const val MAX_TRAFFIC_FILE_BYTES = 4L * 1_024L * 1_024L
    private const val MAX_TRAFFIC_READ_BYTES = MAX_TRAFFIC_FILE_BYTES + 64L * 1024L
    private const val DIRECTORY_NAME = "diagnostics"
    private const val CURRENT_FILE = "current.dlog"
    private const val PREVIOUS_FILE = "previous.dlog"
    private const val FILE_MAGIC = 0x4C595431
    private const val MAX_FILE_BYTES = 1_024L * 1_024L
    private const val MAX_READ_BYTES = MAX_FILE_BYTES + 64L * 1024L
    private const val DEDUP_MILLIS = 2L * 60L * 1_000L
    private const val MAX_EXPORT_SUMMARY_LINES = 12
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
        .replace(COOKIE, "[USUNIĘTE_COOKIE]")
        .replace(SENSITIVE_ASSIGNMENT, "$1=[USUNIĘTY_SEKRET]")
        .replace(URL_QUERY) { match -> match.groupValues[1] }
        // E-01: temat LEP to sekret rotowany po każdym budziku — w dzienniku tylko szablon trasy
        .replace(LEP_TOPIC_PATH, "/lep/$1/[TEMAT]")
        .replace(ANDROID_PRIVATE_PATH, "[PRYWATNA_ŚCIEŻKA]")
        // A-40: komunikaty błędów sieci zawierały adresy .onion i IP serwerów społeczności — redagujemy je
        .replace(ONION_HOST, "[ADRES_ONION]")
        .replace(IPV4, "[IP]")
        .replace(IPV6, "[IP]") // B-90: adresy IPv6 (np. serwera społeczności w LAN/sieci operatora)
        // C-20: nazwy hostów spoza stałej listy (serwery społeczności) → skrót; z dziennika nie da się
        // odczytać, z jakich serwerów ktoś korzysta, a ten sam host nadal da się rozpoznać w obrębie dziennika
        .replace(URL_HOST) { m ->
            val host = m.groupValues[2].lowercase()
            if (KNOWN_HOSTS.any { host == it || host.endsWith(".$it") }) m.value
            else m.groupValues[1] + "host#" + hostDigest(host)
        }
        // D-12: gołe hosty z komunikatów wyjątków („resolve host "x"", „connect to x/[IP]:2137", „x:443")
        .replace(BARE_HOST) { m ->
            val host = m.groupValues[2].lowercase()
            if (KNOWN_HOSTS.any { host == it || host.endsWith(".$it") }) m.value
            else m.groupValues[1] + "host#" + hostDigest(host)
        }
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
    private val COOKIE = Regex("(?i)(?:set-cookie|cookie)\\s*[:=]\\s*[^;\\r\\n]+")
    private val SENSITIVE_ASSIGNMENT = Regex(
        "(?i)\\b(token|access_token|refresh_token|signature|x-amz-signature|password|secret)\\s*[:=]\\s*[^&;,\\s]+",
    )
    private val LEP_TOPIC_PATH = Regex("/lep/(pub|sub)/[A-Za-z0-9_-]{16,128}")
    private val URL_QUERY = Regex("(https?://[^?\\s]+)\\?[^\\s]+")
    private val URL_HOST = Regex("(?i)(https?://)([a-z0-9-]+(?:\\.[a-z0-9-]+)+)")
    private val BARE_HOST = Regex(
        "(?i)(host \"|host |connect to |to |from )([a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.[a-z]{2,24})(?=[\"/:\\s,)]|$)",
    )
    private val KNOWN_HOSTS = listOf(
        "youtube.com", "youtu.be", "ytimg.com", "googlevideo.com", "googleapis.com", "google.com", "gstatic.com",
        "github.com", "githubusercontent.com", "firebaseio.com", "adguard-dns.com", "cloudflare-dns.com", "quad9.net",
    )

    /** J-09: sól na instalację (ustawia DiagnosticLogStore); null = losowa sól tego procesu (np. w testach). */
    @Volatile
    var hostSalt: ByteArray? = null
    private val processSalt = ByteArray(16).also { SecureRandom().nextBytes(it) }

    /**
     * J-09: HMAC-SHA256(sól, host), 8 znaków hex — ten sam host rozpoznawalny w obrębie dziennika, ale skrótu
     * nie da się sprawdzić z zewnątrz, licząc SHA-256 znanych nazw serwerów.
     */
    internal fun hostDigest(host: String, salt: ByteArray = hostSalt ?: processSalt): String =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(salt, "HmacSHA256"))
            doFinal(host.toByteArray())
        }.joinToString("") { "%02x".format(it) }.take(8)
    private val ONION_HOST = Regex("(?i)\\b[a-z2-7]{16,56}\\.onion\\b")
    private val IPV4 = Regex("\\b(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\b")
    // IPv6: wymagamy „::” albo co najmniej 5 grup — zwykłe godziny „12:30:45” nie są redagowane
    private val IPV6 = Regex(
        "(?i)(?<![0-9a-z])(?=[0-9a-f:]*::|(?:[0-9a-f]{1,4}:){4})[0-9a-f]{0,4}(?::[0-9a-f]{0,4}){2,7}(?![0-9a-z])",
    )
    private val ANDROID_PRIVATE_PATH = Regex(
        "(?:/data/(?:user/\\d+|data)/|/storage/emulated/\\d+/)[^\\s,;]+",
    )
}
