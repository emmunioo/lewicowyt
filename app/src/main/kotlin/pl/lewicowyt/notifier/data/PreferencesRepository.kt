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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import pl.lewicowyt.notifier.community.CommunityDefaults
import pl.lewicowyt.notifier.i18n.Pronoun
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
    BROWSER,
    OTHER_APP,
}

internal fun parseYouTubeLinkTarget(value: String?): YouTubeLinkTarget =
    value?.let { runCatching { YouTubeLinkTarget.valueOf(it) }.getOrNull() }
        ?: YouTubeLinkTarget.SYSTEM_DEFAULT

/** preferencja „otwieraj w" dla jednej platformy linków społeczności (per platforma, jak dla YouTube). */
data class CommunityLinkPref(
    val target: YouTubeLinkTarget = YouTubeLinkTarget.SYSTEM_DEFAULT,
    val packageName: String? = null,
)

internal fun parseCommunityLinkPrefs(raw: String?): Map<String, CommunityLinkPref> {
    raw ?: return emptyMap()
    return runCatching {
        val obj = org.json.JSONObject(raw)
        buildMap {
            obj.keys().forEach { id ->
                val o = obj.optJSONObject(id) ?: return@forEach
                put(id, CommunityLinkPref(
                    target = parseYouTubeLinkTarget(o.optString("t")),
                    packageName = o.optString("p").ifBlank { null }?.takeIf(::isSafeAndroidPackageName),
                ))
            }
        }
    }.getOrDefault(emptyMap())
}

internal fun serializeCommunityLinkPrefs(prefs: Map<String, CommunityLinkPref>): String {
    val obj = org.json.JSONObject()
    prefs.forEach { (id, pref) ->
        obj.put(id, org.json.JSONObject().put("t", pref.target.name).apply {
            pref.packageName?.let { put("p", it) }
        })
    }
    return obj.toString()
}

internal fun isSafeAndroidPackageName(value: String): Boolean =
    value.length in 3..255 && ANDROID_PACKAGE_NAME.matches(value)

private val ANDROID_PACKAGE_NAME = Regex(
    "[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+",
)

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
    val activeHoursEnabled: Boolean = false,
    val activeHoursStartMinutes: Int = 8 * 60,
    val activeHoursEndMinutes: Int = 22 * 60,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val pronoun: Pronoun = Pronoun.ON,
    val accentColorArgb: Long = DEFAULT_ACCENT_COLOR_ARGB,
    val highContrastEnabled: Boolean = false,
    val youtubeLinkTarget: YouTubeLinkTarget = YouTubeLinkTarget.SYSTEM_DEFAULT,
    val otherYouTubeAppPackage: String? = null,
    val youtubeApiEnabled: Boolean = false,
    val youtubeApiNeedsValidation: Boolean = false,
    val automaticUpdatesEnabled: Boolean = true,
    // gdy obraz z serwera społeczności jest za ciężki, apka sama go przekompresuje do JXL
    val communityRecompressImages: Boolean = true,
    // maksymalny rozmiar pliku JXL w KB (0 = bez limitu). powyżej – stopniowa kompresja w tle
    val communityMaxJxlKb: Int = 0,
    // pobieranie obrazów z serwerów społeczności (osobno feed i powiadomienia)
    val communityDownloadImages: Boolean = true,
    val communityDownloadNotificationImages: Boolean = true,
    // powiadomienia o nowych materiałach także na sparowanym smartwatchu (mostek systemowy Androida)
    val notifyOnWatch: Boolean = true,
    // pobieranie animacji WebP (wyłącz, by oszczędzić transfer i pamięć)
    val communityDownloadWebpAnimations: Boolean = true,
    // jak daleko wstecz trzymać w bazie treść serwerów: osobno tekst i obrazy (w dniach)
    val communityTextRetentionDays: Int = 60,
    val communityImageRetentionDays: Int = 14,
    // miniaturki YouTube: false = trzymaj pobrany WebP (większy plik, zero dodatkowego CPU);
    // true = przekoduj do JXL (mniejszy plik na dysku, ale koszt kompresji i dekodowania na telefonie)
    val youtubeThumbnailsAsJxl: Boolean = false,
    // zapis mema (długie przytrzymanie): format ("ORIGINAL"/"JXL"/"WEBP"/"PNG"/"JPG"),
    // jakość 1..100 i „moc kompresji" 0..100 (działa dla JXL/MozJPEG)
    // R-16: domyślnie zapisujemy mem SKOMPRESOWANY (JXL) — mniejszy plik. Użytkownik może w ustawieniach
    // wybrać „Oryginał” (bajt-w-bajt z serwera) albo inny format.
    val communityMemeSaveFormat: String = "JXL",
    val communityMemeSaveQuality: Int = 90,
    val communityMemeSaveEffort: Int = 60,
    // per platforma linków (id -> jak otwierać). Puste = domyślnie przeglądarka/system.
    val communityLinkPrefs: Map<String, CommunityLinkPref> = emptyMap(),
    val lastBackgroundUpdateCheckAtMillis: Long = 0L,
    val lastSyncAtMillis: Long = 0L,
    val lastCompletedSyncAtMillis: Long = 0L,
    val lastSyncSummary: String = "Jeszcze nie synchronizowano",
)

class PreferencesRepository(private val context: Context) {
    private val secureApiKeyStore = SecureApiKeyStore(context)
    private val appearanceBackup = context.getSharedPreferences(
        APPEARANCE_BACKUP_NAME,
        Context.MODE_PRIVATE,
    )
    private val scheduleBackup = context.getSharedPreferences(
        SCHEDULE_BACKUP_NAME,
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
        val activeHoursEnabled = booleanPreferencesKey("active_hours_enabled")
        val activeHoursStart = intPreferencesKey("active_hours_start_minutes")
        val activeHoursEnd = intPreferencesKey("active_hours_end_minutes")
        // brak/"" = wbudowany serwer domyślny (B-11: czytane jako CommunityDefaults.SERVER_ID)
        val primaryServerId = stringPreferencesKey("primary_server_id")
        val themeMode = stringPreferencesKey("theme_mode")
        val pronoun = stringPreferencesKey("pronoun")
        val accentColor = longPreferencesKey("accent_color_argb")
        val highContrastEnabled = booleanPreferencesKey("high_contrast_enabled")
        val youtubeLinkTarget = stringPreferencesKey("youtube_link_target")
        val otherYouTubeAppPackage = stringPreferencesKey("other_youtube_app_package")
        val youtubeApiEnabled = booleanPreferencesKey("youtube_api_enabled")
        val youtubeApiValidated = booleanPreferencesKey("youtube_api_validated")
        val automaticUpdatesEnabled = booleanPreferencesKey("automatic_updates_enabled")
        val communityRecompressImages = booleanPreferencesKey("community_recompress_images")
        val communityMaxJxlKb = intPreferencesKey("community_max_jxl_kb")
        val communityDownloadImages = booleanPreferencesKey("community_download_images")
        val communityDownloadNotificationImages =
            booleanPreferencesKey("community_download_notification_images")
        val communityDownloadWebpAnimations =
            booleanPreferencesKey("community_download_webp_animations")
        val communityTextRetentionDays = intPreferencesKey("community_text_retention_days")
        val communityImageRetentionDays = intPreferencesKey("community_image_retention_days")
        val youtubeThumbnailsAsJxl = booleanPreferencesKey("youtube_thumbnails_as_jxl")
        val communityMemeSaveFormat = stringPreferencesKey("community_meme_save_format")
        val communityMemeSaveQuality = intPreferencesKey("community_meme_save_quality")
        val communityMemeSaveEffort = intPreferencesKey("community_meme_save_effort")
        val communityLinkPrefs = stringPreferencesKey("community_link_prefs")
        val lastBackgroundUpdateCheckAt =
            longPreferencesKey("last_background_update_check_at")
        // tylko do jednorazowej migracji ze starszych wersji
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
            val storedInterval = preferences[Keys.intervalMinutes]
                ?.takeIf(::isValidIntervalMinutes)
            val backupInterval = scheduleBackup
                .takeIf { it.contains(SCHEDULE_BACKUP_INTERVAL_KEY) }
                ?.getInt(SCHEDULE_BACKUP_INTERVAL_KEY, DEFAULT_INTERVAL_MINUTES)
            val resolvedInterval = resolveIntervalMinutes(storedInterval, backupInterval)
            val storedDailyHour = preferences[Keys.dailyHour]
                ?.takeIf(::isValidDailyHour)
            val storedDailyMinute = preferences[Keys.dailyMinute]
                ?.takeIf(::isValidDailyMinute)
            val backupDailyHour = scheduleBackup
                .takeIf { it.contains(SCHEDULE_BACKUP_DAILY_HOUR_KEY) }
                ?.getInt(SCHEDULE_BACKUP_DAILY_HOUR_KEY, DEFAULT_DAILY_HOUR)
            val backupDailyMinute = scheduleBackup
                .takeIf { it.contains(SCHEDULE_BACKUP_DAILY_MINUTE_KEY) }
                ?.getInt(SCHEDULE_BACKUP_DAILY_MINUTE_KEY, DEFAULT_DAILY_MINUTE)
            val resolvedDailyHour = resolveDailyHour(storedDailyHour, backupDailyHour)
            val resolvedDailyMinute = resolveDailyMinute(storedDailyMinute, backupDailyMinute)
            if (
                storedAccent != null &&
                appearanceBackup.getLong(
                    APPEARANCE_BACKUP_ACCENT_KEY,
                    Long.MIN_VALUE,
                ) != storedAccent
            ) {
                // druga, niezależna kopia ratuje wygląd, gdyby pojedynczy klucz
                // DataStore padł przy migracji
                appearanceBackup.edit()
                    .putLong(APPEARANCE_BACKUP_ACCENT_KEY, storedAccent)
                    .apply()
            }
            if (
                scheduleBackup.getInt(SCHEDULE_BACKUP_INTERVAL_KEY, -1) != resolvedInterval ||
                scheduleBackup.getInt(SCHEDULE_BACKUP_DAILY_HOUR_KEY, -1) != resolvedDailyHour ||
                scheduleBackup.getInt(SCHEDULE_BACKUP_DAILY_MINUTE_KEY, -1) != resolvedDailyMinute
            ) {
                // interwał zasila priorytety źródeł (SourcePriority) – osobna
                // kopia trzyma harmonogram, gdyby klucz DataStore chwilowo zniknął
                scheduleBackup.edit()
                    .putInt(SCHEDULE_BACKUP_INTERVAL_KEY, resolvedInterval)
                    .putInt(SCHEDULE_BACKUP_DAILY_HOUR_KEY, resolvedDailyHour)
                    .putInt(SCHEDULE_BACKUP_DAILY_MINUTE_KEY, resolvedDailyMinute)
                    .apply()
            }
            AppSettings(
                selectedCreatorIds = preferences[Keys.selectedCreators].orEmpty(),
                deselectedCreatorAtMillis = decodeDeselectedCreators(
                    preferences[Keys.deselectedCreators].orEmpty(),
                ),
                intervalMinutes = resolvedInterval,
                dailyHour = resolvedDailyHour,
                dailyMinute = resolvedDailyMinute,
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
                activeHoursEnabled = preferences[Keys.activeHoursEnabled] ?: false,
                activeHoursStartMinutes = normalizeMinuteOfDay(
                    preferences[Keys.activeHoursStart] ?: (8 * 60),
                ),
                activeHoursEndMinutes = normalizeMinuteOfDay(
                    preferences[Keys.activeHoursEnd] ?: (22 * 60),
                ),
                themeMode = preferences[Keys.themeMode]
                    ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                    ?: ThemeMode.SYSTEM,
                pronoun = Pronoun.fromRaw(preferences[Keys.pronoun]),
                accentColorArgb = resolvedAccent,
                highContrastEnabled = preferences[Keys.highContrastEnabled] ?: false,
                youtubeLinkTarget = parseYouTubeLinkTarget(preferences[Keys.youtubeLinkTarget]),
                otherYouTubeAppPackage = preferences[Keys.otherYouTubeAppPackage]
                    ?.takeIf(::isSafeAndroidPackageName),
                youtubeApiEnabled = hasStoredApiKey &&
                    preferences[Keys.youtubeApiEnabled] == true &&
                    preferences[Keys.youtubeApiValidated] == true,
                youtubeApiNeedsValidation = hasStoredApiKey &&
                    preferences[Keys.youtubeApiEnabled] == true &&
                    preferences[Keys.youtubeApiValidated] != true,
                automaticUpdatesEnabled =
                    preferences[Keys.automaticUpdatesEnabled] ?: true,
                communityRecompressImages =
                    preferences[Keys.communityRecompressImages] ?: true,
                communityMaxJxlKb =
                    (preferences[Keys.communityMaxJxlKb] ?: 0).coerceIn(0, MAX_COMMUNITY_JXL_KB),
                communityDownloadImages =
                    preferences[Keys.communityDownloadImages] ?: true,
                communityDownloadNotificationImages =
                    preferences[Keys.communityDownloadNotificationImages] ?: true,
                // zawsze włączone: wyboru, czy pokazać powiadomienie na zegarku, dokonuje aplikacja zegarka
                // (Mi Fitness, Zepp…); dawna wartość „wyłączone” z wersji testowych jest świadomie ignorowana
                notifyOnWatch = true,
                communityDownloadWebpAnimations =
                    preferences[Keys.communityDownloadWebpAnimations] ?: true,
                communityTextRetentionDays = normalizeCommunityRetentionDays(
                    preferences[Keys.communityTextRetentionDays] ?: 60,
                ),
                communityImageRetentionDays = normalizeCommunityRetentionDays(
                    preferences[Keys.communityImageRetentionDays] ?: 14,
                ),
                youtubeThumbnailsAsJxl = preferences[Keys.youtubeThumbnailsAsJxl] ?: false,
                // A-38: tylko znane formaty; śmieć/stara wartość → domyślny JXL (spójnie z konsumentami)
                communityMemeSaveFormat =
                    preferences[Keys.communityMemeSaveFormat]?.takeIf { it in MEME_SAVE_FORMATS } ?: "JXL",
                communityMemeSaveQuality =
                    (preferences[Keys.communityMemeSaveQuality] ?: 90).coerceIn(1, 100),
                communityMemeSaveEffort =
                    (preferences[Keys.communityMemeSaveEffort] ?: 60).coerceIn(0, 100),
                communityLinkPrefs = parseCommunityLinkPrefs(preferences[Keys.communityLinkPrefs]),
                lastBackgroundUpdateCheckAtMillis =
                    preferences[Keys.lastBackgroundUpdateCheckAt] ?: 0L,
                lastSyncAtMillis = preferences[Keys.lastSyncAt] ?: 0L,
                // starsze instalacje nie miały osobnego znacznika ukończenia
                lastCompletedSyncAtMillis = preferences[Keys.lastCompletedSyncAt]
                    ?: preferences[Keys.lastSyncAt]
                    ?: 0L,
                lastSyncSummary = preferences[Keys.lastSyncSummary]
                    ?: "Jeszcze nie synchronizowano",
            )
        }
        // DataStore emituje przy każdym zapisie, nawet gdy AppSettings wychodzi
        // identyczne (powtórny zapis tej samej wartości). bez tego każda emisja
        // odpalała na darmo cały potok stanu w AppViewModel (filtr+sort) (#4)
        .distinctUntilChanged()

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

    // A-56: setIntervalMinutes/setDailyTime usunięte — harmonogram ręczny zniknął z UI (interwał stały 60 min);
    // odczyt (resolveIntervalMinutes/…) zostaje dla zgodności z zapisanymi wcześniej ustawieniami.

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

    suspend fun setActiveHoursEnabled(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.activeHoursEnabled] = value }
    }

    /**
     * Id serwera głównego. B-11: brak wyboru (albo zapisane "") = WBUDOWANY serwer domyślny
     * ([CommunityDefaults.SERVER_ID]) — od migracji to on dostarcza filmy budzikami LEP, więc "" nie może
     * znaczyć „żaden" (wcześniej budziki z filmami od serwera domyślnego były wtedy odrzucane).
     */
    val primaryServerIdFlow: Flow<String> = context.settingsDataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { it[Keys.primaryServerId].orEmpty().ifEmpty { CommunityDefaults.SERVER_ID } }
        .distinctUntilChanged()

    suspend fun currentPrimaryServerId(): String = primaryServerIdFlow.first()

    suspend fun setPrimaryServerId(serverId: String) {
        context.settingsDataStore.edit {
            if (serverId.isBlank()) it.remove(Keys.primaryServerId) else it[Keys.primaryServerId] = serverId
        }
    }

    suspend fun setActiveHours(startMinutes: Int, endMinutes: Int) {
        context.settingsDataStore.edit {
            it[Keys.activeHoursStart] = normalizeMinuteOfDay(startMinutes)
            it[Keys.activeHoursEnd] = normalizeMinuteOfDay(endMinutes)
        }
    }

    suspend fun setAutomaticUpdatesEnabled(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.automaticUpdatesEnabled] = value }
    }

    suspend fun setCommunityRecompressImages(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.communityRecompressImages] = value }
    }

    suspend fun setCommunityMaxJxlKb(value: Int) {
        context.settingsDataStore.edit {
            it[Keys.communityMaxJxlKb] = value.coerceIn(0, MAX_COMMUNITY_JXL_KB)
        }
    }

    suspend fun setCommunityDownloadImages(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.communityDownloadImages] = value }
    }

    suspend fun setCommunityDownloadNotificationImages(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.communityDownloadNotificationImages] = value }
    }

    suspend fun setCommunityDownloadWebpAnimations(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.communityDownloadWebpAnimations] = value }
    }

    suspend fun setYoutubeThumbnailsAsJxl(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.youtubeThumbnailsAsJxl] = value }
    }

    suspend fun setCommunityTextRetentionDays(value: Int) {
        context.settingsDataStore.edit {
            it[Keys.communityTextRetentionDays] = normalizeCommunityRetentionDays(value)
        }
    }

    suspend fun setCommunityImageRetentionDays(value: Int) {
        context.settingsDataStore.edit {
            it[Keys.communityImageRetentionDays] = normalizeCommunityRetentionDays(value)
        }
    }

    suspend fun setCommunityMemeSaveFormat(value: String) {
        if (value !in MEME_SAVE_FORMATS) return // A-38
        context.settingsDataStore.edit { it[Keys.communityMemeSaveFormat] = value }
    }

    suspend fun setCommunityMemeSaveQuality(value: Int) {
        context.settingsDataStore.edit { it[Keys.communityMemeSaveQuality] = value.coerceIn(1, 100) }
    }

    suspend fun setCommunityMemeSaveEffort(value: Int) {
        context.settingsDataStore.edit { it[Keys.communityMemeSaveEffort] = value.coerceIn(0, 100) }
    }

    /** ustawia „otwieraj w" dla jednej platformy linków (target + opcjonalny pakiet innej aplikacji). */
    suspend fun setCommunityLinkPref(platformId: String, target: YouTubeLinkTarget, packageName: String?) {
        context.settingsDataStore.edit { prefs ->
            val current = parseCommunityLinkPrefs(prefs[Keys.communityLinkPrefs]).toMutableMap()
            current[platformId] = CommunityLinkPref(target, packageName?.takeIf(::isSafeAndroidPackageName))
            prefs[Keys.communityLinkPrefs] = serializeCommunityLinkPrefs(current)
        }
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

    suspend fun setPronoun(value: Pronoun) {
        context.settingsDataStore.edit { it[Keys.pronoun] = value.name }
    }

    suspend fun setHighContrastEnabled(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.highContrastEnabled] = value }
    }

    suspend fun setYouTubeLinkTarget(value: YouTubeLinkTarget) {
        context.settingsDataStore.edit { it[Keys.youtubeLinkTarget] = value.name }
    }

    /** A-58: przywrócenie DOKŁADNEGO stanu linków YouTube (także braku pakietu) — dla testów instrumentalnych. */
    internal suspend fun restoreYouTubeLinkState(target: YouTubeLinkTarget, otherPackage: String?) {
        context.settingsDataStore.edit {
            it[Keys.youtubeLinkTarget] = target.name
            if (otherPackage != null) it[Keys.otherYouTubeAppPackage] = otherPackage else it.remove(Keys.otherYouTubeAppPackage)
        }
    }

    suspend fun setOtherYouTubeAppPackage(packageName: String) {
        require(isSafeAndroidPackageName(packageName)) { "Nieprawidłowa nazwa pakietu" }
        context.settingsDataStore.edit {
            it[Keys.otherYouTubeAppPackage] = packageName
            it[Keys.youtubeLinkTarget] = YouTubeLinkTarget.OTHER_APP.name
        }
    }

    suspend fun setAccentColor(argb: Long) {
        val normalized = normalizeAccentColor(argb)
        // backup leci synchronicznie przed DataStore – nawet ubicie procesu tuż
        // po ruszeniu suwakiem nie przywróci domyślnej czerwieni po restarcie
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
            // klucz ze starej wersji musi przejść nowe żądanie kontrolne,
            // zanim znów go użyjemy
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
                // przy pierwszym starcie po aktualizacji bierzemy dawny znacznik
                // jako ostatni ukończony sync – awaria nie może udawać świeżego sprawdzenia
                preferences[Keys.lastCompletedSyncAt] =
                    preferences[Keys.lastSyncAt] ?: 0L
            }
            preferences[Keys.lastSyncAt] = timestamp
            preferences[Keys.lastSyncSummary] = summary.take(MAX_SYNC_SUMMARY_CHARS)
        }
    }

    private companion object {
        val HISTORY_WINDOWS = setOf(7, 14, 21, 30, 60)
        const val MAX_SYNC_SUMMARY_CHARS = 1_000
        const val MAX_COMMUNITY_JXL_KB = 20_000
        const val APPEARANCE_BACKUP_NAME = "lewicowyt_appearance_backup"
        const val APPEARANCE_BACKUP_ACCENT_KEY = "accent_color_argb"
        const val SCHEDULE_BACKUP_NAME = "lewicowyt_schedule_backup"
        const val SCHEDULE_BACKUP_INTERVAL_KEY = "interval_minutes"
        const val SCHEDULE_BACKUP_DAILY_HOUR_KEY = "daily_hour"
        const val SCHEDULE_BACKUP_DAILY_MINUTE_KEY = "daily_minute"

        fun normalizeHistoryDays(value: Int): Int =
        if (value in HISTORY_WINDOWS) value else 14

        // retencja treści społeczności: 0 = nie trzymaj, inaczej 1..365 dni
        fun normalizeCommunityRetentionDays(value: Int): Int = value.coerceIn(0, 365)

        /** minuta doby przycięta do 0..1439 */
        fun normalizeMinuteOfDay(value: Int): Int = value.coerceIn(0, 24 * 60 - 1)

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

internal fun isValidIntervalMinutes(value: Int): Boolean =
    value in MIN_INTERVAL_MINUTES..MAX_INTERVAL_MINUTES

internal fun resolveIntervalMinutes(stored: Int?, backup: Int?): Int =
    stored?.takeIf(::isValidIntervalMinutes)
        ?: backup?.takeIf(::isValidIntervalMinutes)
        ?: DEFAULT_INTERVAL_MINUTES

internal fun isValidDailyHour(value: Int): Boolean = value in 0..23
internal fun isValidDailyMinute(value: Int): Boolean = value in 0..59

internal fun resolveDailyHour(stored: Int?, backup: Int?): Int =
    stored?.takeIf(::isValidDailyHour)
        ?: backup?.takeIf(::isValidDailyHour)
        ?: DEFAULT_DAILY_HOUR

internal fun resolveDailyMinute(stored: Int?, backup: Int?): Int =
    stored?.takeIf(::isValidDailyMinute)
        ?: backup?.takeIf(::isValidDailyMinute)
        ?: DEFAULT_DAILY_MINUTE

const val DEFAULT_ACCENT_COLOR_ARGB = 0xFFFF0000L
internal const val DEFAULT_INTERVAL_MINUTES = 60
internal const val DEFAULT_DAILY_HOUR = 9
internal const val DEFAULT_DAILY_MINUTE = 0
private const val MIN_INTERVAL_MINUTES = 15
private const val MAX_INTERVAL_MINUTES = 1440
private const val MIN_ARGB = 0xFF000000L
private const val MAX_ARGB = 0xFFFFFFFFL
// A-38: dozwolone formaty zapisu memów (= nazwy CommunityImageExporter.Format)
private val MEME_SAVE_FORMATS = setOf("ORIGINAL", "JXL", "WEBP", "PNG", "JPG")
