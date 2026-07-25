package pl.lewicowyt.notifier

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import pl.lewicowyt.notifier.ui.AppViewModel
import pl.lewicowyt.notifier.ui.LewicowYTApp
import pl.lewicowyt.notifier.ui.theme.LewicowYTTheme

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels { AppViewModel.Factory(application) }
    private var notificationPermissionGranted = false

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationPermissionGranted = granted
        if (granted) viewModel.syncNow()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationPermissionGranted = hasNotificationPermission()
        handleIntent(intent)
        enableEdgeToEdge()
        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            LewicowYTTheme(
                themeMode = state.settings.themeMode,
                accentColorArgb = state.settings.accentColorArgb,
            ) {
                LewicowYTApp(viewModel)
            }
        }
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        val grantedNow = hasNotificationPermission()
        if (grantedNow && !notificationPermissionGranted) {
            viewModel.syncNow()
        }
        notificationPermissionGranted = grantedNow
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

    companion object {
        const val EXTRA_OPEN_NOTIFICATIONS = "open_notifications"
    }
}
