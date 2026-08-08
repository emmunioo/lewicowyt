package pl.lewicowyt.notifier.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import pl.lewicowyt.notifier.model.HistoryFilter

private val Context.settingsDataStore by preferencesDataStore(name = "lewicowyt_settings")

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

enum class YouTubeLinkTarget {
    SYSTEM_DEFAULT,
    ALWAYS_ASK,
    YOUTUBE,
    NEWPIPE,
    BROWSER,
}

internal fun parseYouTubeLinkTarget(value: String?): YouTubeLinkTarget =
    value?.let { runCatching { YouTubeLinkTarget.valueOf(it) }.getOrNull() }
        ?: YouTubeLinkTarget.SYSTEM_DEFAULT

data class AppSettings(
    val selectedCreatorIds: Set<String> = emptySet(),
    val deselectedCreatorAtMillis: Map<String, Long> = emptyMap(),
    val intervalMinutes: Int = 60,
    val dailyHour: Int = 9,
    val dailyMinute: Int = 0,
    val historyWindowDays: Int = 14,
    val historyFilters: Set<HistoryFilter> = HistoryFilter.entries.toSet(),
    val globalHistoryTypes: Set<HistoryFilter> = HistoryFilter.entries.toSet(),
    val globalNotificationTypes: Set<HistoryFilter> = HistoryFilter.entries.toSet(),
    val creatorHistoryDisabledTypes: Map<String, Set<HistoryFilter>> = emptyMap(),
    val creatorNotificationDisabledTypes: Map<String, Set<HistoryFilter>> = emptyMap(),
    val allowMobileData: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accentColorArgb: Long = DEFAULT_ACCENT_COLOR_ARGB,
    val highContrastEnabled: Boolean = false,
    val youtubeLinkTarget: YouTubeLinkTarget = YouTubeLinkTarget.SYSTEM_DEFAULT,
    val youtubeApiEnabled: Boolean = false,
    val youtubeApiNeedsValidation: Boolean = false,
    val automaticUpdatesEnabled: Boolean = true,
    val lastBackgroundUpdateCheckAtMillis: Long = 0L,
    val lastSyncAtMillis: Long = 0L,
    val lastCompletedSyncAtMillis: Long = 0L,
    val deferredDndSyncAtMillis: Long = 0L,
    val lastSyncSummary: String = "Jeszcze nie synchronizowano",
)

class PreferencesRepository(private val context: Context) {
    private val secureApiKeyStore = SecureApiKeyStore(context)
    private val appearanceBackup = context.getSharedPreferences(
        APPEARANCE_BACKUP_NAME,
        Context.MODE_PRIVATE,
    )

    private object Keys {
        val selectedCreators = stringSetPreferencesKey("selected_creators")
        val deselectedCreators = stringSetPreferencesKey("deselected_creators")
        val intervalMinutes = intPreferencesKey("interval_minutes")
        val dailyHour = intPreferencesKey("daily_hour")
        val dailyMinute = intPreferencesKey("daily_minute")
        val historyWindowDays = intPreferencesKey("history_window_days")
        val historyFilters = stringSetPreferencesKey("history_filters")
        val globalHistoryTypes = stringSetPreferencesKey("global_history_types")
        val globalNotificationTypes = stringSetPreferencesKey("global_notification_types")
        val creatorHistoryDisabledTypes =
            stringSetPreferencesKey("creator_history_disabled_types")
        val creatorNotificationDisabledTypes =
            stringSetPreferencesKey("creator_notification_disabled_types")
        val allowMobileData = booleanPreferencesKey("allow_mobile_data")
        val themeMode = stringPreferencesKey("theme_mode")
        val accentColor = longPreferencesKey("accent_color_argb")
        val highContrastEnabled = booleanPreferencesKey("high_contrast_enabled")
        val youtubeLinkTarget = stringPreferencesKey("youtube_link_target")
        val youtubeApiEnabled = booleanPreferencesKey("youtube_api_enabled")
        val youtubeApiValidated = booleanPreferencesKey("youtube_api_validated")
        val automaticUpdatesEnabled = booleanPreferencesKey("automatic_updates_enabled")
        val lastBackgroundUpdateCheckAt =
            longPreferencesKey("last_background_update_check_at")
        // Klucz używany wyłącznie do jednorazowej migracji ze starszych wersji.
        val youtubeApiKey = stringPreferencesKey("youtube_api_key")
        val lastSyncAt = longPreferencesKey("last_sync_at")
        val lastCompletedSyncAt = longPreferencesKey("last_completed_sync_at")
        val deferredDndSyncAt = longPreferencesKey("deferred_dnd_sync_at")
        val lastSyncSummary = stringPreferencesKey("last_sync_summary")
    }

    val settingsFlow: Flow<AppSettings> = context.settingsDataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            val hasStoredApiKey =
                preferences[Keys.youtubeApiKey].orEmpty().isNotBlank() ||
                    secureApiKeyStore.read().isNotBlank()
            val globalHistoryTypes = readContentTypes(
                values = preferences[Keys.globalHistoryTypes],
                keyExists = preferences.contains(Keys.globalHistoryTypes),
            )
            val globalNotificationTypes = readContentTypes(
                values = preferences[Keys.globalNotificationTypes],
                keyExists = preferences.contains(Keys.globalNotificationTypes),
            ).intersect(globalHistoryTypes)
            val storedAccent = preferences[Keys.accentColor]
                ?.takeIf(::isValidAccentColor)
            val backupAccent = appearanceBackup
                .takeIf { it.contains(APPEARANCE_BACKUP_ACCENT_KEY) }
                ?.getLong(APPEARANCE_BACKUP_ACCENT_KEY, DEFAULT_ACCENT_COLOR_ARGB)
                ?.takeIf(::isValidAccentColor)
            val resolvedAccent = resolveAccentColor(storedAccent, backupAccent)
            if (
                storedAccent != null &&
                appearanceBackup.getLong(
                    APPEARANCE_BACKUP_ACCENT_KEY,
                    Long.MIN_VALUE,
                ) != storedAccent
            ) {
                // Druga, niezależna kopia chroni wygląd przy migracji lub
                // uszkodzeniu pojedynczego klucza Preferences DataStore.
                appearanceBackup.edit()
                    .putLong(APPEARANCE_BACKUP_ACCENT_KEY, storedAccent)
                    .apply()
            }
            AppSettings(
                selectedCreatorIds = preferences[Keys.selectedCreators].orEmpty(),
                deselectedCreatorAtMillis = decodeDeselectedCreators(
                    preferences[Keys.deselectedCreators].orEmpty(),
                ),
                intervalMinutes = preferences[Keys.intervalMinutes] ?: 60,
                dailyHour = (preferences[Keys.dailyHour] ?: 9).coerceIn(0, 23),
                dailyMinute = (preferences[Keys.dailyMinute] ?: 0).coerceIn(0, 59),
                historyWindowDays = normalizeHistoryDays(
                    preferences[Keys.historyWindowDays] ?: 14,
                ),
                historyFilters = if (preferences.contains(Keys.historyFilters)) {
                    preferences[Keys.historyFilters].orEmpty()
                        .mapNotNull { value ->
                            runCatching { HistoryFilter.valueOf(value) }.getOrNull()
                        }
                        .toSet()
                } else {
                    HistoryFilter.entries.toSet()
                },
                globalHistoryTypes = globalHistoryTypes,
                globalNotificationTypes = globalNotificationTypes,
                creatorHistoryDisabledTypes = decodeCreatorContentTypes(
                    preferences[Keys.creatorHistoryDisabledTypes].orEmpty(),
                ),
                creatorNotificationDisabledTypes = decodeCreatorContentTypes(
                    preferences[Keys.creatorNotificationDisabledTypes].orEmpty(),
                ),
                allowMobileData = preferences[Keys.allowMobileData] ?: true,
                themeMode = preferences[Keys.themeMode]
                    ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                    ?: ThemeMode.SYSTEM,
                accentColorArgb = resolvedAccent,
                highContrastEnabled = preferences[Keys.highContrastEnabled] ?: false,
                youtubeLinkTarget = parseYouTubeLinkTarget(preferences[Keys.youtubeLinkTarget]),
                youtubeApiEnabled = hasStoredApiKey &&
                    preferences[Keys.youtubeApiEnabled] == true &&
                    preferences[Keys.youtubeApiValidated] == true,
                youtubeApiNeedsValidation = hasStoredApiKey &&
                    preferences[Keys.youtubeApiEnabled] == true &&
                    preferences[Keys.youtubeApiValidated] != true,
                automaticUpdatesEnabled =
                    preferences[Keys.automaticUpdatesEnabled] ?: true,
                lastBackgroundUpdateCheckAtMillis =
                    preferences[Keys.lastBackgroundUpdateCheckAt] ?: 0L,
                lastSyncAtMillis = preferences[Keys.lastSyncAt] ?: 0L,
                // Starsze instalacje nie miały osobnego znacznika ukończenia.
                lastCompletedSyncAtMillis = preferences[Keys.lastCompletedSyncAt]
                    ?: preferences[Keys.lastSyncAt]
                    ?: 0L,
                deferredDndSyncAtMillis = preferences[Keys.deferredDndSyncAt] ?: 0L,
                lastSyncSummary = preferences[Keys.lastSyncSummary]
                    ?: "Jeszcze nie synchronizowano",
            )
        }

    suspend fun current(): AppSettings {
        val settings = settingsFlow.first()
        if (settings.youtubeApiEnabled || settings.youtubeApiNeedsValidation) youtubeApiKey()
        return settingsFlow.first()
    }

    suspend fun selectedCreatorIds(): Set<String> =
        settingsFlow.first().selectedCreatorIds

    suspend fun setCreatorSelected(creatorId: String, selected: Boolean) {
        context.settingsDataStore.edit { preferences ->
            val updated = preferences[Keys.selectedCreators].orEmpty().toMutableSet()
            val deselected = decodeDeselectedCreators(
                preferences[Keys.deselectedCreators].orEmpty(),
            ).toMutableMap()
            if (selected) {
                updated += creatorId
                deselected -= creatorId
            } else {
                if (creatorId in updated) deselected[creatorId] = System.currentTimeMillis()
                updated -= creatorId
            }
            preferences[Keys.selectedCreators] = updated
            preferences[Keys.deselectedCreators] = encodeDeselectedCreators(deselected)
        }
    }

    suspend fun setCreatorsSelected(creatorIds: Collection<String>, selected: Boolean) {
        context.settingsDataStore.edit { preferences ->
            val updated = preferences[Keys.selectedCreators].orEmpty().toMutableSet()
            val deselected = decodeDeselectedCreators(
                preferences[Keys.deselectedCreators].orEmpty(),
            ).toMutableMap()
            if (selected) {
                updated += creatorIds
                creatorIds.forEach { deselected -= it }
            } else {
                val now = System.currentTimeMillis()
                creatorIds.filter { it in updated }
                    .forEach { deselected[it] = now }
                updated -= creatorIds.toSet()
            }
            preferences[Keys.selectedCreators] = updated
            preferences[Keys.deselectedCreators] = encodeDeselectedCreators(deselected)
        }
    }

    suspend fun setIntervalMinutes(value: Int) {
        context.settingsDataStore.edit { it[Keys.intervalMinutes] = value.coerceAtLeast(15) }
    }

    suspend fun setDailyTime(hour: Int, minute: Int) {
        context.settingsDataStore.edit {
            it[Keys.dailyHour] = hour.coerceIn(0, 23)
            it[Keys.dailyMinute] = minute.coerceIn(0, 59)
        }
    }

    suspend fun setHistoryWindowDays(value: Int) {
        context.settingsDataStore.edit {
            it[Keys.historyWindowDays] = normalizeHistoryDays(value)
        }
    }

    suspend fun setHistoryFilter(filter: HistoryFilter, enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            val updated = if (preferences.contains(Keys.historyFilters)) {
                preferences[Keys.historyFilters].orEmpty().toMutableSet()
            } else {
                HistoryFilter.entries.mapTo(mutableSetOf()) { it.name }
            }
            if (enabled) updated += filter.name else updated -= filter.name
            preferences[Keys.historyFilters] = updated
        }
    }

    suspend fun setGlobalHistoryType(type: HistoryFilter, enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            val history = storedContentTypes(preferences[Keys.globalHistoryTypes]).toMutableSet()
            val notifications = storedContentTypes(
                preferences[Keys.globalNotificationTypes],
            ).toMutableSet()
            if (enabled) {
                history += type
            } else {
                history -= type
                notifications -= type
            }
            preferences[Keys.globalHistoryTypes] = history.mapTo(mutableSetOf()) { it.name }
            preferences[Keys.globalNotificationTypes] =
                notifications.mapTo(mutableSetOf()) { it.name }
        }
    }

    suspend fun setGlobalNotificationType(type: HistoryFilter, enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            val history = storedContentTypes(preferences[Keys.globalHistoryTypes])
            val notifications = storedContentTypes(
                preferences[Keys.globalNotificationTypes],
            ).toMutableSet()
            if (enabled && type in history) notifications += type else notifications -= type
            preferences[Keys.globalNotificationTypes] =
                notifications.mapTo(mutableSetOf()) { it.name }
        }
    }

    suspend fun setCreatorHistoryType(
        creatorId: String,
        type: HistoryFilter,
        enabled: Boolean,
    ) {
        context.settingsDataStore.edit { preferences ->
            val historyDisabled = decodeCreatorContentTypes(
                preferences[Keys.creatorHistoryDisabledTypes].orEmpty(),
            ).mapValues { (_, types) -> types.toMutableSet() }.toMutableMap()
            val notificationsDisabled = decodeCreatorContentTypes(
                preferences[Keys.creatorNotificationDisabledTypes].orEmpty(),
            ).mapValues { (_, types) -> types.toMutableSet() }.toMutableMap()
            updateCreatorDisabledType(historyDisabled, creatorId, type, disabled = !enabled)
            if (!enabled) {
                updateCreatorDisabledType(
                    notificationsDisabled,
                    creatorId,
                    type,
                    disabled = true,
                )
            }
            preferences[Keys.creatorHistoryDisabledTypes] =
                encodeCreatorContentTypes(historyDisabled)
            preferences[Keys.creatorNotificationDisabledTypes] =
                encodeCreatorContentTypes(notificationsDisabled)
        }
    }

    suspend fun setCreatorNotificationType(
        creatorId: String,
        type: HistoryFilter,
        enabled: Boolean,
    ) {
        context.settingsDataStore.edit { preferences ->
            val globalHistory = storedContentTypes(preferences[Keys.globalHistoryTypes])
            val globalNotifications = storedContentTypes(
                preferences[Keys.globalNotificationTypes],
            )
            val historyDisabled = decodeCreatorContentTypes(
                preferences[Keys.creatorHistoryDisabledTypes].orEmpty(),
            )
            val notificationsDisabled = decodeCreatorContentTypes(
                preferences[Keys.creatorNotificationDisabledTypes].orEmpty(),
            ).mapValues { (_, types) -> types.toMutableSet() }.toMutableMap()
            val historyEnabled = type in globalHistory &&
                type !in historyDisabled[creatorId].orEmpty()
            val notificationCanBeEnabled = historyEnabled && type in globalNotifications
            updateCreatorDisabledType(
                notificationsDisabled,
                creatorId,
                type,
                disabled = !enabled || !notificationCanBeEnabled,
            )
            preferences[Keys.creatorNotificationDisabledTypes] =
                encodeCreatorContentTypes(notificationsDisabled)
        }
    }

    suspend fun setAllowMobileData(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.allowMobileData] = value }
    }

    suspend fun setAutomaticUpdatesEnabled(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.automaticUpdatesEnabled] = value }
    }

    suspend fun reserveBackgroundUpdateCheck(
        minimumIntervalMillis: Long,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        require(minimumIntervalMillis > 0L)
        var reserved = false
        context.settingsDataStore.edit { preferences ->
            val previous = preferences[Keys.lastBackgroundUpdateCheckAt] ?: 0L
            val clockMovedBackwards = nowMillis < previous
            if (clockMovedBackwards || nowMillis - previous >= minimumIntervalMillis) {
                preferences[Keys.lastBackgroundUpdateCheckAt] = nowMillis
                reserved = true
            }
        }
        return reserved
    }

    suspend fun setThemeMode(value: ThemeMode) {
        context.settingsDataStore.edit { it[Keys.themeMode] = value.name }
    }

    suspend fun setHighContrastEnabled(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.highContrastEnabled] = value }
    }

    suspend fun setYouTubeLinkTarget(value: YouTubeLinkTarget) {
        context.settingsDataStore.edit { it[Keys.youtubeLinkTarget] = value.name }
    }

    suspend fun setAccentColor(argb: Long) {
        val normalized = normalizeAccentColor(argb)
        // Backup jest zapisywany synchronicznie przed DataStore. Dzięki temu
        // nawet przerwanie procesu bezpośrednio po przesunięciu suwaka nie
        // przywróci domyślnej czerwieni przy następnym uruchomieniu/aktualizacji.
        appearanceBackup.edit()
            .putLong(APPEARANCE_BACKUP_ACCENT_KEY, normalized)
            .commit()
        context.settingsDataStore.edit {
            it[Keys.accentColor] = normalized
        }
    }

    suspend fun setValidatedYoutubeApiKey(value: String) {
        val normalized = value.trim()
        require(normalized.isNotBlank()) { "Zweryfikowany klucz API nie może być pusty" }
        secureApiKeyStore.write(normalized)
        context.settingsDataStore.edit {
            it[Keys.youtubeApiEnabled] = true
            it[Keys.youtubeApiValidated] = true
            it.remove(Keys.youtubeApiKey)
        }
    }

    suspend fun clearYoutubeApiKey() {
        secureApiKeyStore.write("")
        context.settingsDataStore.edit {
            it[Keys.youtubeApiEnabled] = false
            it[Keys.youtubeApiValidated] = false
            it.remove(Keys.youtubeApiKey)
        }
    }

    suspend fun youtubeApiKey(): String {
        secureApiKeyStore.read().takeIf { it.isNotBlank() }?.let { return it }
        val legacyKey = context.settingsDataStore.data.first()[Keys.youtubeApiKey]
            .orEmpty()
            .trim()
        if (legacyKey.isBlank()) return ""

        secureApiKeyStore.write(legacyKey)
        context.settingsDataStore.edit {
            it[Keys.youtubeApiEnabled] = true
            // Klucz zapisany przez starszą wersję musi przejść nowe żądanie
            // kontrolne, zanim będzie ponownie używany.
            it[Keys.youtubeApiValidated] = false
            it.remove(Keys.youtubeApiKey)
        }
        return legacyKey
    }

    suspend fun removeDeselectionRecords(creatorIds: Set<String>) {
        if (creatorIds.isEmpty()) return
        context.settingsDataStore.edit { preferences ->
            val updated = decodeDeselectedCreators(
                preferences[Keys.deselectedCreators].orEmpty(),
            ).toMutableMap()
            creatorIds.forEach { updated -= it }
            preferences[Keys.deselectedCreators] = encodeDeselectedCreators(updated)
        }
    }

    suspend fun updateLastSync(
        timestamp: Long,
        summary: String,
        completed: Boolean = true,
    ) {
        context.settingsDataStore.edit { preferences ->
            if (completed) {
                preferences[Keys.lastCompletedSyncAt] = timestamp
            } else if (!preferences.contains(Keys.lastCompletedSyncAt)) {
                // Przy pierwszym uruchomieniu po aktualizacji zachowujemy dawny
                // znacznik jako ostatnie ukończone sprawdzenie. Sama awaria nie
                // może wyglądać dla harmonogramu jak świeża synchronizacja.
                preferences[Keys.lastCompletedSyncAt] =
                    preferences[Keys.lastSyncAt] ?: 0L
            }
            preferences[Keys.lastSyncAt] = timestamp
            preferences[Keys.lastSyncSummary] = summary.take(MAX_SYNC_SUMMARY_CHARS)
        }
    }

    suspend fun recordDeferredDndSync(timestamp: Long = System.currentTimeMillis()) {
        context.settingsDataStore.edit { preferences ->
            val previous = preferences[Keys.deferredDndSyncAt] ?: 0L
            if (previous <= 0L || timestamp < previous) {
                preferences[Keys.deferredDndSyncAt] = timestamp
            }
        }
    }

    suspend fun clearDeferredDndSync() {
        context.settingsDataStore.edit { it.remove(Keys.deferredDndSyncAt) }
    }

    private companion object {
        val HISTORY_WINDOWS = setOf(7, 14, 21, 30, 60)
        const val MAX_SYNC_SUMMARY_CHARS = 1_000
        const val APPEARANCE_BACKUP_NAME = "lewicowyt_appearance_backup"
        const val APPEARANCE_BACKUP_ACCENT_KEY = "accent_color_argb"

        fun normalizeHistoryDays(value: Int): Int =
        if (value in HISTORY_WINDOWS) value else 14

        fun storedContentTypes(values: Set<String>?): Set<HistoryFilter> =
            if (values == null) {
                ALL_CONTENT_TYPES
            } else {
                values.mapNotNullTo(mutableSetOf()) { value ->
                    runCatching { HistoryFilter.valueOf(value) }.getOrNull()
                }
            }

        fun readContentTypes(values: Set<String>?, keyExists: Boolean): Set<HistoryFilter> =
            if (keyExists) storedContentTypes(values.orEmpty()) else ALL_CONTENT_TYPES

        fun updateCreatorDisabledType(
            values: MutableMap<String, MutableSet<HistoryFilter>>,
            creatorId: String,
            type: HistoryFilter,
            disabled: Boolean,
        ) {
            if (creatorId.isBlank()) return
            val types = values.getOrPut(creatorId) { mutableSetOf() }
            if (disabled) types += type else types -= type
            if (types.isEmpty()) values -= creatorId
        }

        fun decodeDeselectedCreators(values: Set<String>): Map<String, Long> =
            values.mapNotNull { encoded ->
                val separator = encoded.lastIndexOf('|')
                if (separator <= 0) return@mapNotNull null
                val timestamp = encoded.substring(separator + 1).toLongOrNull()
                    ?: return@mapNotNull null
                encoded.substring(0, separator) to timestamp
            }.toMap()

        fun encodeDeselectedCreators(values: Map<String, Long>): Set<String> =
            values.mapTo(mutableSetOf()) { (creatorId, timestamp) ->
                "$creatorId|$timestamp"
            }

    }
}

internal fun isValidAccentColor(value: Long): Boolean = value in MIN_ARGB..MAX_ARGB

internal fun normalizeAccentColor(value: Long): Long =
    value.coerceIn(MIN_ARGB, MAX_ARGB)

internal fun resolveAccentColor(stored: Long?, backup: Long?): Long =
    stored?.takeIf(::isValidAccentColor)
        ?: backup?.takeIf(::isValidAccentColor)
        ?: DEFAULT_ACCENT_COLOR_ARGB

const val DEFAULT_ACCENT_COLOR_ARGB = 0xFFFF0000L
private const val MIN_ARGB = 0xFF000000L
private const val MAX_ARGB = 0xFFFFFFFFL
