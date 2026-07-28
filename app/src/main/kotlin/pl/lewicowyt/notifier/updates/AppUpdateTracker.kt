package pl.lewicowyt.notifier.updates

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

class AppUpdateTracker(private val context: Context) {
    private val preferences = context.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun shouldShowWhatsNew(currentVersionCode: Int): Boolean {
        val installationWasUpdated = packageInfo().let { info ->
            info.lastUpdateTime > info.firstInstallTime
        }
        val acknowledgedVersionCode = preferences.getInt(
            KEY_ACKNOWLEDGED_VERSION_CODE,
            VERSION_NOT_RECORDED,
        )
        val shouldShow = shouldShowWhatsNewAfterUpdate(
            currentVersionCode = currentVersionCode,
            acknowledgedVersionCode = acknowledgedVersionCode,
            installationWasUpdated = installationWasUpdated,
        )

        // Pierwsza instalacja nie powinna wyświetlać listy zmian. Zapisujemy jej
        // wersję od razu, aby następna rzeczywista aktualizacja była wykrywalna.
        if (!installationWasUpdated && acknowledgedVersionCode != currentVersionCode) {
            acknowledge(currentVersionCode)
        }
        return shouldShow
    }

    fun acknowledge(currentVersionCode: Int) {
        preferences.edit()
            .putInt(KEY_ACKNOWLEDGED_VERSION_CODE, currentVersionCode)
            .apply()
    }

    @Suppress("DEPRECATION")
    private fun packageInfo() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }

    private companion object {
        const val PREFERENCES_NAME = "lewicowyt_release_notes"
        const val KEY_ACKNOWLEDGED_VERSION_CODE = "acknowledged_version_code"
        const val VERSION_NOT_RECORDED = 0
    }
}

internal fun shouldShowWhatsNewAfterUpdate(
    currentVersionCode: Int,
    acknowledgedVersionCode: Int,
    installationWasUpdated: Boolean,
): Boolean =
    installationWasUpdated &&
        currentVersionCode > acknowledgedVersionCode
