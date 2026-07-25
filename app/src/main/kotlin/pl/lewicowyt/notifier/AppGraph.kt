package pl.lewicowyt.notifier

import android.annotation.SuppressLint
import android.content.Context
import pl.lewicowyt.notifier.data.CreatorCatalog
import pl.lewicowyt.notifier.data.LocalDatabase
import pl.lewicowyt.notifier.data.PreferencesRepository
import pl.lewicowyt.notifier.network.HttpTextClient
import pl.lewicowyt.notifier.network.PipedClient
import pl.lewicowyt.notifier.network.PrivacyHttpClient
import pl.lewicowyt.notifier.network.YouTubeFeedClient
import pl.lewicowyt.notifier.network.YouTubeHistoryClient
import pl.lewicowyt.notifier.network.YouTubeDataApiHistoryClient
import pl.lewicowyt.notifier.network.YouTubePageClassifier
import pl.lewicowyt.notifier.network.YouTubeSourceResolver
import pl.lewicowyt.notifier.network.androidApiRequestHeaders
import pl.lewicowyt.notifier.notifications.NotificationHelper
import pl.lewicowyt.notifier.sync.SyncEngine
import pl.lewicowyt.notifier.sync.HistoryBackfillLoader
import pl.lewicowyt.notifier.updates.GitHubUpdateChecker
import pl.lewicowyt.notifier.worker.SyncScheduler

@SuppressLint("StaticFieldLeak")
object AppGraph {
    @Volatile
    private var initialized = false

    lateinit var catalog: CreatorCatalog
        private set
    lateinit var preferences: PreferencesRepository
        private set
    lateinit var database: LocalDatabase
        private set
    lateinit var notifications: NotificationHelper
        private set
    lateinit var resolver: YouTubeSourceResolver
        private set
    lateinit var dataApiClient: YouTubeDataApiHistoryClient
        private set
    lateinit var syncEngine: SyncEngine
        private set
    lateinit var historyBackfill: HistoryBackfillLoader
        private set
    lateinit var scheduler: SyncScheduler
        private set
    lateinit var updateChecker: GitHubUpdateChecker
        private set

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val appContext = context.applicationContext
            catalog = CreatorCatalog(appContext)
            preferences = PreferencesRepository(appContext)
            database = LocalDatabase(appContext)
            notifications = NotificationHelper(appContext, database)
            val http = HttpTextClient(PrivacyHttpClient.get(appContext))
            val pipedClient = PipedClient(http)
            dataApiClient = YouTubeDataApiHistoryClient(
                http = http,
                apiRequestHeaders = androidApiRequestHeaders(appContext),
            )
            val historyClient = YouTubeHistoryClient(http)
            resolver = YouTubeSourceResolver(http, database)
            historyBackfill = HistoryBackfillLoader(
                catalog = catalog,
                preferences = preferences,
                database = database,
                resolver = resolver,
                client = historyClient,
                dataApiClient = dataApiClient,
                pipedClient = pipedClient,
            )
            syncEngine = SyncEngine(
                catalog = catalog,
                preferences = preferences,
                database = database,
                resolver = resolver,
                feedClient = YouTubeFeedClient(http),
                classifier = YouTubePageClassifier(http),
                notifications = notifications,
                dataApiClient = dataApiClient,
                historyClient = historyClient,
            )
            scheduler = SyncScheduler(appContext, preferences)
            updateChecker = GitHubUpdateChecker(http, BuildConfig.UPDATE_REPOSITORY)
            initialized = true
        }
    }
}
