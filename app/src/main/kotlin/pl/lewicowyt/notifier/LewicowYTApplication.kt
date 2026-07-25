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
            AppGraph.database.pruneExpiredData()
            JxlImageCache.pruneExpired(this@LewicowYTApplication)
            JxlImageCache.migrateExisting(this@LewicowYTApplication)
            AppGraph.scheduler.ensureScheduled()
        }
    }
}
