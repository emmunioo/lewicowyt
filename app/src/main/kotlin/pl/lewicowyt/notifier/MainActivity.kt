package pl.lewicowyt.notifier

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.lewicowyt.notifier.ui.AppViewModel
import pl.lewicowyt.notifier.ui.LewicowYTApp
import pl.lewicowyt.notifier.ui.theme.LewicowYTTheme
import pl.lewicowyt.notifier.updates.AppUpdateTracker

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels { AppViewModel.Factory(application) }
    private val appUpdateTracker by lazy { AppUpdateTracker(this) }
    private var notificationPermissionGranted = false
    private var whatsNewVisible by mutableStateOf(false)
    private var batteryOptimizationIgnored by mutableStateOf(true)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationPermissionGranted = granted
        if (granted) viewModel.syncNow()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationPermissionGranted = hasNotificationPermission()
        batteryOptimizationIgnored = isBatteryOptimizationIgnored()
        whatsNewVisible = appUpdateTracker.shouldShowWhatsNew(BuildConfig.VERSION_CODE)
        handleIntent(intent)
        enableEdgeToEdge()
        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            LewicowYTTheme(
                themeMode = state.settings.themeMode,
                accentColorArgb = state.settings.accentColorArgb,
                highContrast = state.settings.highContrastEnabled,
            ) {
                LewicowYTApp(
                    viewModel = viewModel,
                    whatsNewVisible = whatsNewVisible,
                    acknowledgeWhatsNew = {
                        appUpdateTracker.acknowledge(BuildConfig.VERSION_CODE)
                        whatsNewVisible = false
                    },
                    batteryOptimizationIgnored = batteryOptimizationIgnored,
                    openBatteryOptimizationSettings = ::openBatteryOptimizationSettings,
                )
            }
        }
        requestNotificationPermissionIfNeeded()
    }

    // T-01: cykliczne odświeżanie społeczności działa tylko przy widocznym ekranie (po powrocie nadrabia zaległe)
    override fun onStart() {
        super.onStart()
        viewModel.setUiVisible(true)
    }

    override fun onStop() {
        viewModel.setUiVisible(false)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        val notificationsGrantedNow = hasNotificationPermission()
        if (notificationsGrantedNow && !notificationPermissionGranted) {
            viewModel.syncNow()
        }
        notificationPermissionGranted = notificationsGrantedNow
        batteryOptimizationIgnored = isBatteryOptimizationIgnored()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_NOTIFICATIONS, false) == true) {
            viewModel.openNotifications()
            intent.removeExtra(EXTRA_OPEN_NOTIFICATIONS)
        }
        // z powiadomienia odtwarzacza dźwięku (sesja multimediów) — karta z odtwarzaczem jest w Społeczności
        if (intent?.getBooleanExtra(EXTRA_OPEN_COMMUNITY, false) == true) {
            viewModel.openCommunity()
            intent.removeExtra(EXTRA_OPEN_COMMUNITY)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasNotificationPermission()
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    private fun isBatteryOptimizationIgnored(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(packageName) == true
    }

    private fun openBatteryOptimizationSettings() {
        // bez wrażliwego REQUEST_IGNORE_BATTERY_OPTIMIZATIONS – wchodzimy wprost
        // na ekran szczegółów naszej apki, gdzie user ustawia baterię „Bez
        // ograniczeń" (Play-friendly, kilka kliknięć, bez grzebania w liście wszystkich apek)
        openApplicationDetails("package:$packageName".toUri())
    }

    private fun openApplicationDetails(packageUri: android.net.Uri) {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
        } catch (_: ActivityNotFoundException) {
            // nie ma ekranu ustawień apki – nic więcej nie zrobimy
        }
    }

    companion object {
        const val EXTRA_OPEN_NOTIFICATIONS = "open_notifications"
        const val EXTRA_OPEN_COMMUNITY = "open_community"
    }
}
