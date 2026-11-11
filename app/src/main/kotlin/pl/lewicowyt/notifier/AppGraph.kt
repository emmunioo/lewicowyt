package pl.lewicowyt.notifier

import android.annotation.SuppressLint
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import pl.lewicowyt.notifier.community.CommunityHttpClient
import pl.lewicowyt.notifier.community.CommunityContentFileStore
import pl.lewicowyt.notifier.community.CommunityEndpointStore
import pl.lewicowyt.notifier.community.AppTerms
import pl.lewicowyt.notifier.community.CommunityRepository
import pl.lewicowyt.notifier.community.HttpsCommunityFetcher
import pl.lewicowyt.notifier.community.LocalDatabaseCommunityStore
import pl.lewicowyt.notifier.community.toCommunityUrlOrNull
import pl.lewicowyt.notifier.data.CreatorCatalog
import pl.lewicowyt.notifier.data.LocalDatabase
import pl.lewicowyt.notifier.data.PreferencesRepository
import pl.lewicowyt.notifier.data.StreamPrivacyChecker
import pl.lewicowyt.notifier.diagnostics.DiagnosticSnapshotProvider
import pl.lewicowyt.notifier.images.BundledAvatarStore
import pl.lewicowyt.notifier.images.CommunityImageLoader
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

@SuppressLint("StaticFieldLeak")
object AppGraph {
    @Volatile
    private var initialized = false

    // seedowanie awatarów w osobnym scope: seedDatabase() otwiera bazę do zapisu
    // (migracje + ~50 upsertów) i blokowało main thread na każdym zimnym starcie.
    // idempotentne, awatary i tak dochodzą leniwie
    private val avatarSeedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var appContext: Context
        private set
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
    lateinit var feedClient: YouTubeFeedClient
        private set
    lateinit var classifier: YouTubePageClassifier

    lateinit var streamPrivacyChecker: StreamPrivacyChecker
        private set
    lateinit var syncEngine: SyncEngine
        private set
    lateinit var historyBackfill: HistoryBackfillLoader
        private set
    lateinit var descriptionBackfill: DescriptionBackfillLoader
        private set
    lateinit var olderMaterialSearch: OlderMaterialSearchService
        private set
    internal lateinit var diagnostics: DiagnosticSnapshotProvider
        private set
    lateinit var updateChecker: GitHubUpdateChecker
        private set
    lateinit var updateManager: AppUpdateManager
        private set
    lateinit var backgroundUpdateCoordinator: BackgroundUpdateCoordinator
        private set
    lateinit var communityRepository: CommunityRepository
        private set

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val appContext = context.applicationContext
            this.appContext = appContext
            catalog = CreatorCatalog(appContext)
            preferences = PreferencesRepository(appContext)
            database = LocalDatabase(appContext)
            youtubeLinks = YouTubeLinkLauncher(appContext)
            // T-04: baza otwiera się leniwie — rozgrzewamy ją w tle, a nie na wątku głównym w Application.onCreate
            val warmDb = database
            avatarSeedScope.launch { runCatching { warmDb.warmUp() } }
            avatarSeedScope.launch { BundledAvatarStore.seedDatabase(appContext, database) }
            // T-05: rejestr „czy obraz społeczności jest animacją" w bazie aplikacji (sprawdzany raz na obraz)
            val db = database
            CommunityImageLoader.animationRegistry = object : CommunityImageLoader.AnimationRegistry {
                override fun isAnimated(contentKey: String) = db.communityImageAnimated(contentKey)
                override fun remember(contentKey: String, animated: Boolean) = db.setCommunityImageAnimated(contentKey, animated)
                override fun clear() = db.clearCommunityImageKinds()
            }
            // loader obrazów społeczności ma proste, procesowe pole – trzymamy je zgodne z ustawieniem
            avatarSeedScope.launch {
                preferences.settingsFlow.collect {
                    CommunityImageLoader.recompressLargeImages = it.communityRecompressImages
                    CommunityImageLoader.downloadImages = it.communityDownloadImages
                    CommunityImageLoader.downloadWebpAnimations = it.communityDownloadWebpAnimations
                    CommunityImageLoader.maxJxlBytes =
                        if (it.communityMaxJxlKb > 0) it.communityMaxJxlKb.toLong() * 1024L else 0L
                }
            }
            notifications = NotificationHelper(appContext, database, youtubeLinks) {
                runCatching { preferences.current().notifyOnWatch }.getOrDefault(true)
            }
            val okHttp = PrivacyHttpClient.get(appContext)
            val http = HttpTextClient(okHttp)
            // serwery społeczności: transport pull dobierany po endpointcie (HTTPS lub .onion
            // przez Tor). send-only klient dzieli prywatny OkHttp z resztą.
            val communityHttp = CommunityHttpClient(appContext, okHttp)
            communityRepository = CommunityRepository(
                store = LocalDatabaseCommunityStore(database),
                fetcherProvider = { endpoint ->
                    if (endpoint.toCommunityUrlOrNull() != null) HttpsCommunityFetcher(communityHttp) else null
                },
                // obejście hairpin-NAT: pamięta działający adres + alternatywy (LAN) z manifestu
                endpointHints = CommunityEndpointStore(appContext),
                // tryb plików treści: pamięta, które pliki `content` już pobrano (pobieramy tylko nowe)
                contentFileTracker = CommunityContentFileStore(appContext),
                defaultServerAllowed = { AppTerms.accepted(appContext) },
            )
            feedClient = YouTubeFeedClient(http)
            dataApiClient = YouTubeDataApiHistoryClient(
                http = http,
                apiRequestHeaders = androidApiRequestHeaders(appContext),
            )
            val historyClient = YouTubeHistoryClient(http, database)
            classifier = YouTubePageClassifier(http)
            streamPrivacyChecker = StreamPrivacyChecker(database, classifier)
            val pageClassifier = classifier
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
            diagnostics = DiagnosticSnapshotProvider(
                context = appContext,
                catalog = catalog,
                preferences = preferences,
                database = database,
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
