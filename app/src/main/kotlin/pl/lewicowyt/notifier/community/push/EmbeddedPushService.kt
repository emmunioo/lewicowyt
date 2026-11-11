package pl.lewicowyt.notifier.community.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import pl.lewicowyt.notifier.R

/**
 * Tryb INSTANT wbudowanego dystrybutora UnifiedPush: usługa pierwszoplanowa (typ specialUse) trzyma long-poll
 * (LEP) do każdego subskrybowanego serwera społeczności, więc budziki przychodzą w ~1 s — bez Google, bez FCM
 * i bez osobnej aplikacji. To serwery społeczności wysyłają budziki; usługa tylko je odbiera.
 *
 * Ścieżka budzika (najkrótsza możliwa): long-poll zwraca budzik → zapis do trwałej [PushInbox] → kursor →
 * [CommunityWakeProcessor.drainInbox] OD RAZU w tym procesie (pod krótką blokadą wybudzenia) → powiadomienie.
 * Bez kolejki WorkManagera: od Androida 16 zadania podlegają limitom nawet przy działającej usłudze, a na
 * Androidzie 8–11 praca nie jest przyspieszona i w Doze czekała na okno serwisowe.
 *
 * Zgodność z Google Play — uzasadnienie typu specialUse: AndroidManifest.xml (komentarz i
 * PROPERTY_SPECIAL_USE_FGS_SUBTYPE) oraz docs/GOOGLE_PLAY_REVIEW.md.
 */
class EmbeddedPushService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        runCatching {
            val cm = getSystemService(ConnectivityManager::class.java)
            val cb = object : ConnectivityManager.NetworkCallback() {
                private var current: Network? = null

                override fun onAvailable(network: Network) {
                    val previous = current
                    current = network
                    // A-24: pierwsze wywołanie = sieć bieżąca (tylko budzimy pętle czekające w backoffie). Zmiana
                    // sieci (Wi-Fi ↔ LTE): połączenia ze starej sieci są martwe, ale czekałyby na timeout odczytu
                    // (70 s) — przerywamy je, żeby pętle od razu połączyły się w nowej sieci.
                    if (previous != null && previous != network) EmbeddedPush.interruptAll() else EmbeddedPush.wakeAll()
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // B-08: startForeground może rzucić (ForegroundServiceStartNotAllowedException — start z tła bez wyjątku
        // od Androida 12, np. przy restarcie „sticky”). Zamiast crashu spadamy na worker oszczędny.
        val foreground = runCatching { startAsForeground() }.isSuccess
        if (!foreground) {
            running = false
            EmbeddedPushDrainWorker.schedule(applicationContext)
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        // nie dubluj pętli, gdy start zostanie wywołany ponownie na działającej usłudze — tylko każ jej od razu
        // przeliczyć zbiór serwerów (J-06: nowy serwer dostaje własną pętlę bez czekania)
        if (loopJob?.isActive != true) {
            loopJob = scope.launch {
                drainInbox() // budziki zapisane przed śmiercią poprzedniego procesu (PushInbox) — od razu
                loop()
            }
        } else {
            resync.trySend(Unit)
        }
        return START_STICKY
    }

    /**
     * J-06: KAŻDY serwer ma własną pętlę long-poll z własnym backoffem. Pętla nadzorcza tylko uruchamia/zatrzymuje
     * pętle serwerów przy zmianie zbioru subskrypcji.
     */
    private suspend fun loop() = coroutineScope {
        val perServer = HashMap<String, Job>()
        while (isActive) {
            val subs = EmbeddedPush.subscriptions(applicationContext)
            if (subs.isEmpty()) {
                running = false // zanim stopSelf: start() w tym czasie ma uruchomić usługę na nowo, nie tylko „resync"
                stopSelf()
                break
            }
            val ids = subs.mapTo(HashSet()) { it.serverId }
            perServer.keys.filterNot { it in ids }.forEach { perServer.remove(it)?.cancel() }
            for (id in ids) {
                if (perServer[id]?.isActive != true) perServer[id] = launch { loopFor(id) }
            }
            withTimeoutOrNull(RESYNC_MS) { resync.receive() }
        }
        perServer.values.forEach { it.cancel() }
    }

    /** Long-poll jednego serwera. Temat czytany co rundę (rotacja po każdym budziku). */
    private suspend fun loopFor(serverId: String) {
        var failures = 0
        while (currentCoroutineContext().isActive) {
            val sub = EmbeddedPush.subscriptions(applicationContext).firstOrNull { it.serverId == serverId } ?: break
            val wakeSeen = EmbeddedPush.wakeUps.value
            val started = SystemClock.elapsedRealtime()
            val result = runCatching { EmbeddedPush.poll(applicationContext, sub, hold = true) }
                .getOrDefault(EmbeddedPush.Poll.Failed)
            val elapsed = SystemClock.elapsedRealtime() - started
            if (result is EmbeddedPush.Poll.Ok && result.received > 0) drainInbox()

            // Temat zmienił się w trakcie long-polla (rotacja po budziku): jeszcze JEDNO natychmiastowe pobranie
            // ze starego tematu — budzik wysłany tam tuż przed tym, jak serwer przyjął nowy temat, nie zginie —
            // i od razu long-poll na nowym (bez czekania, aż stary skończy się po ~50 s).
            val current = EmbeddedPush.currentTopic(applicationContext, serverId)
            if (current != null && current != sub.topic) {
                val cursor = (result as? EmbeddedPush.Poll.Ok)?.cursor ?: sub.cursor
                val last = runCatching { EmbeddedPush.poll(applicationContext, sub.copy(cursor = cursor), hold = false) }
                    .getOrNull()
                if (last is EmbeddedPush.Poll.Ok && last.received > 0) drainInbox()
                failures = 0
                continue
            }

            val plan = LepPollPolicy.next(result, failures, elapsed)
            failures = plan.failures
            if (plan.waitMs > 0) {
                withTimeoutOrNull(plan.waitMs) { EmbeddedPush.wakeUps.first { it != wakeSeen } }
            }
        }
        // subskrypcja zniknęła — nadzorca uporządkuje zbiór przy najbliższym przeliczeniu
        resync.trySend(Unit)
    }

    /**
     * Obsługa budzików ze skrzynki we własnej korutynie (long-poll od razu wraca do nasłuchu), pod blokadą
     * wybudzenia: w Doze procesor zasypia zaraz po odebraniu pakietu, a obsługa (deszyfracja, losowe opóźnienie,
     * odświeżenie nadawcy) trwa do kilku sekund. Limit 60 s — blokada nigdy nie zostanie „zapomniana".
     */
    private fun drainInbox() {
        scope.launch {
            val wakeLock = runCatching {
                getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                    .apply { setReferenceCounted(false); acquire(WAKE_LOCK_TIMEOUT_MS) }
            }.getOrNull()
            try {
                CommunityWakeProcessor.drainInbox(applicationContext)
            } finally {
                runCatching { if (wakeLock?.isHeld == true) wakeLock.release() }
            }
        }
    }

    private fun startAsForeground() {
        val mgr = getSystemService(NotificationManager::class.java)
        // kanał v2 (ukryty na ekranie blokady, bez kropki na ikonie). Dawny kanał usuwamy — aplikacja nie może zmienić
        // widoczności istniejącego kanału. Usunięcie przed startForeground: kanału z aktywnym powiadomieniem
        // usługi system nie pozwala skasować.
        runCatching { if (mgr.getNotificationChannel(LEGACY_CHANNEL) != null) mgr.deleteNotificationChannel(LEGACY_CHANNEL) }
        if (mgr.getNotificationChannel(CHANNEL) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, "Odbiór powiadomień w tle", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Stałe, ciche powiadomienie wymagane przez Androida, gdy aplikacja utrzymuje " +
                        "połączenie z serwerami społeczności. Możesz wyłączyć ten kanał — odbiór działa dalej."
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                    enableVibration(false)
                    setSound(null, null)
                },
            )
        }
        val hide = PendingIntent.getActivity(
            this,
            0,
            hideNotificationIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setLocalOnly(true) // techniczne — nie na smartwatch
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Odbiór powiadomień społeczności")
            .setContentText("Połączenie z serwerami społeczności (UnifiedPush). Dotknij, aby ukryć — odbiór działa dalej.")
            .setContentIntent(hide)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .build()
        // Android 14+: typ specialUse (bez limitu 6 h/dobę i dozwolony po restarcie telefonu — Q-08). Android < 14
        // nie wymusza typów usług: startForeground bez typu przyjmuje typ z manifestu (specialUse), więc manifest
        // nie potrzebuje już dataSync (jeden typ = jedna deklaracja w Google Play).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(this, NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    // A-23: specialUse nie ma limitu czasu, ale gdyby system kiedyś wywołał onTimeout (nowe wersje Androida),
    // MUSIMY się zatrzymać — inaczej ForegroundServiceDidNotStopInTimeException. Odbiór przejmuje worker oszczędny,
    // a najbliższy sync (applyRunState) spróbuje wznowić tryb natychmiastowy.
    override fun onTimeout(startId: Int) {
        running = false
        EmbeddedPushDrainWorker.schedule(applicationContext)
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        onTimeout(startId)
    }

    override fun onDestroy() {
        netCallback?.let { cb ->
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) }
        }
        netCallback = null
        running = false
        scope.cancel()
        // blokujące long-polle nie reagują na anulowanie korutyn — przerywamy same połączenia
        EmbeddedPush.interruptAll()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "embedded_push_v2"
        private const val LEGACY_CHANNEL = "embedded_push"
        private const val NOTIF_ID = 4711
        private const val WAKE_LOCK_TAG = "lewicowyt:push-wake"
        private const val WAKE_LOCK_TIMEOUT_MS = 60_000L

        // J-06: awaryjne przeliczenie zbioru serwerów. Każdą zmianę i tak sygnalizują: start() → resync (nowy
        // serwer, zmiana trybu), koniec pętli serwera po zniknięciu subskrypcji → resync, rotacja → interrupt.
        // T-09: 15 min zamiast 60 s — nadzorca nie budzi się co minutę bez powodu.
        private const val RESYNC_MS = 15L * 60 * 1000
        private val resync = Channel<Unit>(Channel.CONFLATED)

        /** B-06: czy usługa faktycznie działa jako pierwszoplanowa — worker-siatka nie dubluje wtedy pobrań. */
        @Volatile var running: Boolean = false
            private set

        /**
         * Uruchamia odbiór. Gdy usługa JUŻ działa, tylko każe jej przeliczyć serwery — bez ponownego
         * startForegroundService, który na Androidzie 14+ przywracał stałe powiadomienie zsunięte przez użytkownika
         * (a każde startForegroundService wymaga startForeground w ciągu kilku sekund).
         */
        fun start(context: Context) {
            if (running) {
                resync.trySend(Unit)
                return
            }
            runCatching { ContextCompat.startForegroundService(context, Intent(context, EmbeddedPushService::class.java)) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, EmbeddedPushService::class.java)) }
        }

        /**
         * Ekran ustawień kanału stałego powiadomienia — użytkownik może go wyłączyć jednym przełącznikiem:
         * powiadomienie znika, a odbiór działa dalej (usługa pierwszoplanowa nie zależy od widoczności kanału).
         */
        fun hideNotificationIntent(context: Context): Intent =
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
