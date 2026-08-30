package pl.lewicowyt.notifier

import android.annotation.SuppressLint
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import pl.lewicowyt.notifier.data.CreatorCatalog
import pl.lewicowyt.notifier.data.LocalDatabase
import pl.lewicowyt.notifier.data.PreferencesRepository
import pl.lewicowyt.notifier.diagnostics.DiagnosticSnapshotProvider
import pl.lewicowyt.notifier.images.BundledAvatarStore
import pl.lewicowyt.notifier.images.CreatorAvatarUpdater
import pl.lewicowyt.notifier.links.YouTubeLinkLauncher
import pl.lewicowyt.notifier.network.HttpTextClient
import pl.lewicowyt.notifier.network.PrivacyHttpClient
import pl.lewicowyt.notifier.network.YouTubeFeedClient
import pl.lewicowyt.notifier.network.YouTubeHistoryClient
import pl.lewicowyt.notifier.network.YouTubeDataApiHistoryClient
import pl.lewicowyt.notifier.network.YouTubePageClassifier
import pl.lewicowyt.notifier.network.YouTubeSourceResolver
import pl.lewicowyt.notifier.network.androidApiRequestHeaders
import pl.lewicowyt.notifier.notifications.NotificationHelper
import pl.lewicowyt.notifier.search.OlderMaterialSearchService
import pl.lewicowyt.notifier.sync.DescriptionBackfillLoader
import pl.lewicowyt.notifier.sync.SyncEngine
import pl.lewicowyt.notifier.sync.HistoryBackfillLoader
import pl.lewicowyt.notifier.sync.SourcePriorityScheduler
import pl.lewicowyt.notifier.updates.AppUpdateManager
import pl.lewicowyt.notifier.updates.BackgroundUpdateCoordinator
import pl.lewicowyt.notifier.updates.GitHubUpdateChecker
import pl.lewicowyt.notifier.worker.SyncScheduler

@SuppressLint("StaticFieldLeak")
object AppGraph {
    @Volatile
    private var initialized = false

    // Osobny zakres tylko na seedowanie awatarów w tle - seedDatabase() otwiera
    // zapisywalną bazę (uruchamia migracje) i robi ~50 upsertów, co blokowało
    // wątek główny przy KAŻDYM zimnym starcie (initialize() jest wołane
    // synchronicznie z Application.onCreate()). Wynik jest idempotentny i
    // niekrytyczny dla pierwszej klatki - awatary i tak dopływają leniwie.
    private val avatarSeedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var catalog: CreatorCatalog
        private set
    lateinit var preferences: PreferencesRepository
        private set
    lateinit var database: LocalDatabase
        private set
    lateinit var notifications: NotificationHelper
        private set
    lateinit var youtubeLinks: YouTubeLinkLauncher
        private set
    lateinit var resolver: YouTubeSourceResolver
        private set
    lateinit var dataApiClient: YouTubeDataApiHistoryClient
        private set
    lateinit var syncEngine: SyncEngine
        private set
    lateinit var historyBackfill: HistoryBackfillLoader
        private set
    lateinit var descriptionBackfill: DescriptionBackfillLoader
        private set
    lateinit var olderMaterialSearch: OlderMaterialSearchService
        private set
    lateinit var scheduler: SyncScheduler
        private set
    internal lateinit var diagnostics: DiagnosticSnapshotProvider
        private set
    lateinit var updateChecker: GitHubUpdateChecker
        private set
    lateinit var updateManager: AppUpdateManager
        private set
    lateinit var backgroundUpdateCoordinator: BackgroundUpdateCoordinator
        private set

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val appContext = context.applicationContext
            catalog = CreatorCatalog(appContext)
            preferences = PreferencesRepository(appContext)
            database = LocalDatabase(appContext)
            youtubeLinks = YouTubeLinkLauncher(appContext)
            avatarSeedScope.launch { BundledAvatarStore.seedDatabase(appContext, database) }
            notifications = NotificationHelper(appContext, database, youtubeLinks)
            val okHttp = PrivacyHttpClient.get(appContext)
            val http = HttpTextClient(okHttp)
            val feedClient = YouTubeFeedClient(http)
            dataApiClient = YouTubeDataApiHistoryClient(
                http = http,
                apiRequestHeaders = androidApiRequestHeaders(appContext),
            )
            val historyClient = YouTubeHistoryClient(http, database)
            val pageClassifier = YouTubePageClassifier(http)
            resolver = YouTubeSourceResolver(http, database)
            val avatarUpdater = CreatorAvatarUpdater(appContext, database, resolver)
            val sourcePriorityScheduler = SourcePriorityScheduler(
                context = appContext,
                database = database,
            )
            historyBackfill = HistoryBackfillLoader(
                catalog = catalog,
                preferences = preferences,
                database = database,
                resolver = resolver,
                feedClient = feedClient,
                client = historyClient,
                dataApiClient = dataApiClient,
                classifier = pageClassifier,
                sourcePriorityScheduler = sourcePriorityScheduler,
            )
            descriptionBackfill = DescriptionBackfillLoader(
                database = database,
                classifier = pageClassifier,
                preferences = preferences,
                dataApiClient = dataApiClient,
                catalog = catalog,
            )
            olderMaterialSearch = OlderMaterialSearchService(
                http = http,
                resolver = resolver,
                classifier = pageClassifier,
                database = database,
            )
            syncEngine = SyncEngine(
                catalog = catalog,
                preferences = preferences,
                database = database,
                resolver = resolver,
                feedClient = feedClient,
                classifier = pageClassifier,
                notifications = notifications,
                dataApiClient = dataApiClient,
                historyClient = historyClient,
                sourcePriorityScheduler = sourcePriorityScheduler,
                avatarUpdater = avatarUpdater,
                descriptionBackfill = descriptionBackfill,
            )
            scheduler = SyncScheduler(appContext, preferences)
            diagnostics = DiagnosticSnapshotProvider(
                context = appContext,
                catalog = catalog,
                preferences = preferences,
                database = database,
                scheduler = scheduler,
            )
            updateChecker = GitHubUpdateChecker(http, BuildConfig.UPDATE_REPOSITORY)
            updateManager = AppUpdateManager(appContext, okHttp)
            backgroundUpdateCoordinator = BackgroundUpdateCoordinator(
                preferences = preferences,
                checker = updateChecker,
                manager = updateManager,
            )
            initialized = true
        }
    }
}
