package pl.lewicowyt.notifier

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import pl.lewicowyt.notifier.images.JxlImageCache

class LewicowYTApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        AppGraph.initialize(this)
        AppGraph.notifications.createChannel()
        applicationScope.launch {
            cancelNotificationsAfterSourceCatalogRepair()
            AppGraph.updateManager.removeStalePreparedUpdate()
            AppGraph.database.pruneExpiredData()
            JxlImageCache.pruneExpired(this@LewicowYTApplication)
            JxlImageCache.migrateExisting(this@LewicowYTApplication)
            AppGraph.scheduler.ensureScheduled()
        }
    }

    private fun cancelNotificationsAfterSourceCatalogRepair() {
        val preferences = getSharedPreferences(SOURCE_REPAIR_PREFERENCES, MODE_PRIVATE)
        if (preferences.getInt(SOURCE_REPAIR_REVISION_KEY, 0) >= SOURCE_REPAIR_REVISION) {
            return
        }

        // Otwarcie bazy uruchamia migrację usuwającą rekordy, których kanału
        // nie można było zweryfikować. Czyścimy też odpowiadające im alerty Androida.
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
