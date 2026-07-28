package pl.lewicowyt.notifier

import android.Manifest
import android.app.AlarmManager
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
    private var exactAlarmAccessGranted by mutableStateOf(true)
    private var batteryOptimizationIgnored by mutableStateOf(true)
    private var whatsNewVisible by mutableStateOf(false)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationPermissionGranted = granted
        if (granted) viewModel.syncNow()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationPermissionGranted = hasNotificationPermission()
        exactAlarmAccessGranted = hasExactAlarmAccess()
        batteryOptimizationIgnored = isBatteryOptimizationIgnored()
        whatsNewVisible = appUpdateTracker.shouldShowWhatsNew(BuildConfig.VERSION_CODE)
        handleIntent(intent)
        enableEdgeToEdge()
        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            LewicowYTTheme(
                themeMode = state.settings.themeMode,
                accentColorArgb = state.settings.accentColorArgb,
            ) {
                LewicowYTApp(
                    viewModel = viewModel,
                    exactAlarmAccessGranted = exactAlarmAccessGranted,
                    batteryOptimizationIgnored = batteryOptimizationIgnored,
                    whatsNewVisible = whatsNewVisible,
                    acknowledgeWhatsNew = {
                        appUpdateTracker.acknowledge(BuildConfig.VERSION_CODE)
                        whatsNewVisible = false
                    },
                    requestExactAlarmAccess = ::requestExactAlarmAccess,
                    openBatteryOptimizationSettings = ::openBatteryOptimizationSettings,
                )
            }
        }
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        val notificationsGrantedNow = hasNotificationPermission()
        if (notificationsGrantedNow && !notificationPermissionGranted) {
            viewModel.syncNow()
        }
        notificationPermissionGranted = notificationsGrantedNow

        val exactAlarmGrantedNow = hasExactAlarmAccess()
        if (exactAlarmGrantedNow != exactAlarmAccessGranted) {
            exactAlarmAccessGranted = exactAlarmGrantedNow
            viewModel.refreshBackgroundSchedule()
        }
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

    private fun hasExactAlarmAccess(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    private fun requestExactAlarmAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || hasExactAlarmAccess()) return
        val packageUri = "package:$packageName".toUri()
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri))
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
        } catch (_: SecurityException) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
        }
    }

    private fun openBatteryOptimizationSettings() {
        val packageUri = "package:$packageName".toUri()
        if (isBatteryOptimizationIgnored()) {
            openApplicationDetails(packageUri)
            return
        }
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    packageUri,
                ),
            )
        } catch (_: ActivityNotFoundException) {
            openApplicationDetails(packageUri)
        } catch (_: SecurityException) {
            openApplicationDetails(packageUri)
        }
    }

    private fun isBatteryOptimizationIgnored(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(packageName)
    }

    private fun openApplicationDetails(packageUri: android.net.Uri) {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
    }

    companion object {
        const val EXTRA_OPEN_NOTIFICATIONS = "open_notifications"
    }
}
