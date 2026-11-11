package pl.lewicowyt.notifier

import android.app.Application
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import pl.lewicowyt.notifier.community.push.CommunityPushManager
import pl.lewicowyt.notifier.community.push.PushSettings
import pl.lewicowyt.notifier.diagnostics.DiagnosticLogStore
import pl.lewicowyt.notifier.i18n.Translations
import pl.lewicowyt.notifier.images.CommunityImageLoader
import pl.lewicowyt.notifier.images.JxlImageCache

class LewicowYTApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        DiagnosticLogStore.initialize(this)
        Translations.init(this)
        AppGraph.initialize(this)
        AppGraph.notifications.createChannel()
        // F-05: auto-init Firebase jest WYŁĄCZONY w manifeście, więc sam token FCM nie powstaje przy starcie.
        // Serwer domyślny działa WYŁĄCZNIE przez UnifiedPush — nie subskrybujemy już tematu "feed". Kto miał
        // włączony awaryjny FCM (wcześniejsze wersje subskrybowały temat), dostaje jednorazowe wypisanie;
        // przy FCM wyłączonym (domyślnie) nie kontaktujemy Google wcale.
        if (PushSettings.fcmFallbackEnabled(this)) {
            CommunityPushManager.retireDefaultFeedTopic(this)
        }
        applicationScope.launch {
            // A-37: każde zadanie startowe osobno w startupTask — wyjątek jednego (pełny dysk, SQLiteException,
            // IOException) nie może wywalić procesu przy KAŻDYM starcie (crash-loop) ani zablokować kolejnych.
            // Q3: zastosuj wybór „miniaturki WebP vs JXL" do cache obrazów (domyślnie WebP = bez konwersji)
            startupTask("thumbFormat") { JxlImageCache.convertDownloadsToJxl = AppGraph.preferences.current().youtubeThumbnailsAsJxl }
            startupTask("sourceRepair") { cancelNotificationsAfterSourceCatalogRepair() }
            startupTask("staleUpdate") { AppGraph.updateManager.removeStalePreparedUpdate() }
            startupTask("pruneDb") { AppGraph.database.pruneExpiredData() }
            startupTask("pruneThumbs") {
                JxlImageCache.pruneExpired(
                    context = this@LewicowYTApplication,
                    protectedUrls = AppGraph.database.favoriteThumbnailUrls(),
                )
            }
            startupTask("migrateThumbs") { JxlImageCache.migrateExisting(this@LewicowYTApplication) }
            startupTask("pruneCommunityImages") { CommunityImageLoader.pruneExpired(this@LewicowYTApplication) }
            // N-03: FAKTYCZNIE uruchamiamy automatyczne sprawdzanie aktualizacji — dotąd koordynator nie był
            // nigdzie wołany, więc przełącznik „Automatyczne aktualizacje" i awaryjny rollback były martwe.
            // Wewnętrzna rezerwacja 2 h self-throttluje częste starty; respektuje ustawienie i tryb obowiązkowy.
            startupTask("updateCheck") {
                AppGraph.backgroundUpdateCoordinator.checkAfterYouTubeSync(AppGraph.preferences.current())
            }
        }
    }

    private suspend fun startupTask(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            runCatching { DiagnosticLogStore.warning("Startup", "Zadanie startowe $name nie powiodło się", error) }
        }
    }

    private fun cancelNotificationsAfterSourceCatalogRepair() {
        val preferences = getSharedPreferences(SOURCE_REPAIR_PREFERENCES, MODE_PRIVATE)
        if (preferences.getInt(SOURCE_REPAIR_REVISION_KEY, 0) >= SOURCE_REPAIR_REVISION) {
            return
        }

        // otwarcie bazy odpala migrację kasującą niezweryfikowane kanały –
        // sprzątamy więc też wiszące po nich powiadomienia
        AppGraph.database.writableDatabase
        AppGraph.notifications.cancelAll()
        preferences.edit()
            .putInt(SOURCE_REPAIR_REVISION_KEY, SOURCE_REPAIR_REVISION)
            .commit()
    }

    private companion object {
        const val SOURCE_REPAIR_PREFERENCES = "source_catalog_repair"
        const val SOURCE_REPAIR_REVISION_KEY = "revision"
        const val SOURCE_REPAIR_REVISION = 1
    }
}
