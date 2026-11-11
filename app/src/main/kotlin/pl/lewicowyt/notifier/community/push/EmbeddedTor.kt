package pl.lewicowyt.notifier.community.push

import android.content.Context
import android.util.Log
import io.matthewnelson.kmp.tor.resource.noexec.tor.ResourceLoaderTorNoExec
import io.matthewnelson.kmp.tor.runtime.Action.Companion.startDaemonAsync
import io.matthewnelson.kmp.tor.runtime.RuntimeEvent
import io.matthewnelson.kmp.tor.runtime.TorRuntime
import io.matthewnelson.kmp.tor.runtime.core.OnEvent
import io.matthewnelson.kmp.tor.runtime.core.config.TorOption
import io.matthewnelson.kmp.tor.runtime.core.ctrl.TorCmd
import io.matthewnelson.kmp.tor.runtime.core.net.Port.Ephemeral.Companion.toPortEphemeralOrNull
import io.matthewnelson.kmp.tor.runtime.core.util.executeAsync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Wbudowany Tor — oparty na **kmp-tor** (`io.matthewnelson.kmp-tor`) w wariancie **noexec**: natywny `tor`
 * (zasób `resource-noexec-tor`, wersja tora 0.4.9.x) jest ładowany WEWNĄTRZ procesu aplikacji przez mały
 * shim `libtorjni.so` (~13 KB, 16 KB-aligned), bez osobnego procesu i bez trwałego rozpakowywania wielkiej
 * biblioteki na dysk (Tor jest trzymany skompresowany i wczytywany do pamięci). Dzięki temu serwery
 * społeczności `.onion` działają BEZ osobnej aplikacji Orbot, przy mniejszym śladzie na dysku i w APK.
 *
 * Ustawiamy własny port SOCKS **9250** (nie 9050), żeby nie kolidować z ewentualnym Orbotem działającym
 * równolegle; jeśli 9250 jest zajęty, Tor dobiera inny, a my odczytujemy realny port ze zdarzenia
 * [RuntimeEvent.LISTENERS]. Gotowość („obwód zestawiony") sygnalizuje [RuntimeEvent.READY].
 *
 * Uwaga: pierwszy start bootstrapuje się kilka–kilkadziesiąt sekund. Ruch Tor nie jest testowalny w
 * buildzie — do potwierdzenia na urządzeniu.
 */
object EmbeddedTor {
    private const val TAG = "EmbeddedTor"

    /** Docelowy port lokalnego SOCKS wbudowanego Tora (świadomie inny niż 9050 Orbota). */
    const val SOCKS_PORT = 9250

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var runtime: TorRuntime? = null
    @Volatile private var started = false
    @Volatile private var ready = false
    @Volatile private var actualSocksPort = SOCKS_PORT
    // A-26: po porażce startu `started` wraca na false (możliwe ponowienie), ale z backoffem 30 s…10 min,
    // żeby każdy fetch .onion nie odpalał demona od nowa i nie czekał pełnych 60 s.
    @Volatile private var lastFailureMs = 0L
    @Volatile private var failures = 0

    fun isReady(): Boolean = ready
    fun isStarted(): Boolean = started

    /** Realny port SOCKS wbudowanego Tora (może różnić się od 9250, jeśli był zajęty). */
    fun socksPort(): Int = actualSocksPort

    /**
     * Realny port SOCKS, ale TYLKO gdy Tor jest już GOTOWY (port faktycznie ogłoszony przez zdarzenie
     * LISTENERS). null, gdy niegotowy — żeby NIE zgadywać portu i nie łączyć się na ślepo na 9250, gdzie
     * mogłaby siedzieć inna apka udająca SOCKS i podejrzeć ruch/rejestrację .onion (audyt H-3, fail-closed).
     */
    fun readySocksPort(): Int? = if (ready) actualSocksPort else null

    @Synchronized
    private fun buildRuntime(context: Context): TorRuntime {
        runtime?.let { return it }
        val app = context.applicationContext
        val workDir = File(app.filesDir, "tor").apply { mkdirs() }
        val cacheDir = File(app.cacheDir, "tor").apply { mkdirs() }

        // noexec: natywny tor ładowany w procesie (bez osobnego procesu, bez trwałej ekstrakcji .so)
        val env = TorRuntime.Environment.Builder(workDir, cacheDir, ResourceLoaderTorNoExec::getOrCreate)

        val rt = TorRuntime.Builder(env) {
            observerStatic(RuntimeEvent.READY, OnEvent.Executor.Immediate) { ready = true }
            observerStatic(RuntimeEvent.LISTENERS, OnEvent.Executor.Immediate) { listeners ->
                listeners.socks.firstOrNull()?.let { actualSocksPort = it.port.value }
            }
            observerStatic(RuntimeEvent.LOG.WARN, OnEvent.Executor.Immediate) { Log.w(TAG, it) }
            observerStatic(RuntimeEvent.ERROR, OnEvent.Executor.Immediate) { Log.w(TAG, "err: ${it.message}") }
            config { _ ->
                // stały SOCKS 9250; reassignable → Tor sam wybierze inny, gdy port zajęty (czytamy z LISTENERS)
                TorOption.__SocksPort.configure {
                    port(9250.toPortEphemeralOrNull()!!)
                    reassignable(true)
                    // F-04: izolacja strumieni — różne adresy/porty docelowe dostają OSOBNE obwody Tor,
                    // więc ruch do różnych serwerów .onion nie dzieli trasy (trudniejsza korelacja).
                    flagsIsolation {
                        IsolateDestAddr = true
                        IsolateDestPort = true
                    }
                }
            }
        }
        runtime = rt
        return rt
    }

    /** Uruchamia demona Tora (raz). Bezpieczne do wielokrotnego wołania; nie blokuje. */
    @Synchronized
    fun ensureStarted(context: Context) {
        if (started) return
        if (failures > 0) {
            val backoff = (30_000L shl (failures - 1).coerceAtMost(5)).coerceAtMost(10 * 60_000L)
            if (System.currentTimeMillis() - lastFailureMs < backoff) return
        }
        started = true
        val rt = runCatching { buildRuntime(context) }.getOrElse {
            Log.w(TAG, "build: ${it.message}")
            markFailed()
            return
        }
        scope.launch {
            runCatching { rt.startDaemonAsync() }
                .onSuccess { failures = 0 }
                .onFailure { Log.w(TAG, "start: ${it.message}"); markFailed() }
        }
    }

    private fun markFailed() {
        started = false
        ready = false
        failures = (failures + 1).coerceAtMost(10)
        lastFailureMs = System.currentTimeMillis()
    }

    /**
     * Startuje (jeśli trzeba) i czeka do [timeoutMs] na zestawienie obwodu Tor ([RuntimeEvent.READY]).
     * Zwraca true, gdy Tor jest gotowy do ruchu `.onion`.
     */
    suspend fun awaitReady(context: Context, timeoutMs: Long = 60_000L): Boolean {
        ensureStarted(context)
        if (ready) return true
        var waited = 0L
        val step = 250L
        // A-26: przerywamy od razu, gdy start się nie powiódł (started == false) — bez czekania pełnego limitu
        while (waited < timeoutMs && !ready && started) {
            delay(step)
            waited += step
        }
        return ready
    }

    /**
     * Wymusza NOWĄ tożsamość w sieci Tor (sygnał `NEWNYM` → nowe obwody, inne trasy/wyjścia). Kolejny ruch
     * `.onion` idzie nową ścieżką, niepowiązywalną ze starą. Startuje Tora, jeśli jeszcze nie działa.
     */
    suspend fun newIdentity(context: Context): Boolean {
        if (!awaitReady(context)) return false
        val rt = runtime ?: return false
        return runCatching { rt.executeAsync(TorCmd.Signal.NewNym); true }
            .getOrElse { Log.w(TAG, "newnym: ${it.message}"); false }
    }
}
