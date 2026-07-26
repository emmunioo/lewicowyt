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

enum class BackgroundMode {
    BALANCED,
    RELIABLE,
}

data class AppSettings(
    val selectedCreatorIds: Set<String> = emptySet(),
    val deselectedCreatorAtMillis: Map<String, Long> = emptyMap(),
    val intervalMinutes: Int = 60,
    val dailyHour: Int = 9,
    val dailyMinute: Int = 0,
    val historyWindowDays: Int = 14,
    val historyFilters: Set<HistoryFilter> = HistoryFilter.entries.toSet(),
    val allowMobileData: Boolean = true,
    val requireBatteryNotLow: Boolean = false,
    val backgroundMode: BackgroundMode = BackgroundMode.BALANCED,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accentColorArgb: Long = DEFAULT_ACCENT_COLOR_ARGB,
    val youtubeApiEnabled: Boolean = false,
    val youtubeApiNeedsValidation: Boolean = false,
    val lastSyncAtMillis: Long = 0L,
    val lastCompletedSyncAtMillis: Long = 0L,
    val lastSyncSummary: String = "Jeszcze nie synchronizowano",
)

class PreferencesRepository(private val context: Context) {
    private val secureApiKeyStore = SecureApiKeyStore(context)

    private object Keys {
        val selectedCreators = stringSetPreferencesKey("selected_creators")
        val deselectedCreators = stringSetPreferencesKey("deselected_creators")
        val intervalMinutes = intPreferencesKey("interval_minutes")
        val dailyHour = intPreferencesKey("daily_hour")
        val dailyMinute = intPreferencesKey("daily_minute")
        val historyWindowDays = intPreferencesKey("history_window_days")
        val historyFilters = stringSetPreferencesKey("history_filters")
        val allowMobileData = booleanPreferencesKey("allow_mobile_data")
        val batteryNotLow = booleanPreferencesKey("battery_not_low")
        val backgroundMode = stringPreferencesKey("background_mode")
        val themeMode = stringPreferencesKey("theme_mode")
        val accentColor = longPreferencesKey("accent_color_argb")
        val youtubeApiEnabled = booleanPreferencesKey("youtube_api_enabled")
        val youtubeApiValidated = booleanPreferencesKey("youtube_api_validated")
        // Klucz używany wyłącznie do jednorazowej migracji ze starszych wersji.
        val youtubeApiKey = stringPreferencesKey("youtube_api_key")
        val lastSyncAt = longPreferencesKey("last_sync_at")
        val lastCompletedSyncAt = longPreferencesKey("last_completed_sync_at")
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
                allowMobileData = preferences[Keys.allowMobileData] ?: true,
                requireBatteryNotLow = preferences[Keys.batteryNotLow] ?: false,
                backgroundMode = preferences[Keys.backgroundMode]
                    ?.let { runCatching { BackgroundMode.valueOf(it) }.getOrNull() }
                    ?: BackgroundMode.BALANCED,
                themeMode = preferences[Keys.themeMode]
                    ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                    ?: ThemeMode.SYSTEM,
                accentColorArgb = preferences[Keys.accentColor]
                    ?.takeIf { it in MIN_ARGB..MAX_ARGB }
                    ?: DEFAULT_ACCENT_COLOR_ARGB,
                youtubeApiEnabled = hasStoredApiKey &&
                    preferences[Keys.youtubeApiEnabled] == true &&
                    preferences[Keys.youtubeApiValidated] == true,
                youtubeApiNeedsValidation = hasStoredApiKey &&
                    preferences[Keys.youtubeApiEnabled] == true &&
                    preferences[Keys.youtubeApiValidated] != true,
                lastSyncAtMillis = preferences[Keys.lastSyncAt] ?: 0L,
                // Starsze instalacje nie miały osobnego znacznika ukończenia.
                lastCompletedSyncAtMillis = preferences[Keys.lastCompletedSyncAt]
                    ?: preferences[Keys.lastSyncAt]
                    ?: 0L,
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

    suspend fun setAllowMobileData(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.allowMobileData] = value }
    }

    suspend fun setRequireBatteryNotLow(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.batteryNotLow] = value }
    }

    suspend fun setBackgroundMode(value: BackgroundMode) {
        context.settingsDataStore.edit { it[Keys.backgroundMode] = value.name }
    }

    suspend fun setThemeMode(value: ThemeMode) {
        context.settingsDataStore.edit { it[Keys.themeMode] = value.name }
    }

    suspend fun setAccentColor(argb: Long) {
        context.settingsDataStore.edit {
            it[Keys.accentColor] = argb.coerceIn(MIN_ARGB, MAX_ARGB)
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

    private companion object {
        val HISTORY_WINDOWS = setOf(7, 14, 21, 30, 60)
        const val MIN_ARGB = 0xFF000000L
        const val MAX_ARGB = 0xFFFFFFFFL
        const val MAX_SYNC_SUMMARY_CHARS = 1_000

        fun normalizeHistoryDays(value: Int): Int =
            if (value in HISTORY_WINDOWS) value else 14

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

const val DEFAULT_ACCENT_COLOR_ARGB = 0xFFFF0000L
