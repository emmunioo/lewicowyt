package pl.lewicowyt.notifier.updates

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File

class UpdateInstallActivity : ComponentActivity() {
    private val unknownSourcesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (packageManager.canRequestPackageInstalls()) {
            launchSystemInstaller()
        } else {
            Toast.makeText(
                this,
                "Android nie zezwolił aplikacji na instalowanie aktualizacji.",
                Toast.LENGTH_LONG,
            ).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (packageManager.canRequestPackageInstalls()) {
            launchSystemInstaller()
        } else {
            try {
                unknownSourcesLauncher.launch(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        "package:$packageName".toUri(),
                    ),
                )
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(
                    this,
                    "Otwórz ustawienia aplikacji i zezwól na instalowanie nieznanych aplikacji.",
                    Toast.LENGTH_LONG,
                ).show()
                finish()
            }
        }
    }

    private fun launchSystemInstaller() {
        val apk = File(
            File(cacheDir, AppUpdateManager.UPDATE_CACHE_DIRECTORY),
            AppUpdateManager.PENDING_APK_NAME,
        )
        if (!apk.isFile) {
            Toast.makeText(this, "Pobrany plik aktualizacji już nie istnieje.", Toast.LENGTH_LONG)
                .show()
            finish()
            return
        }

        val uri = FileProvider.getUriForFile(
            this,
            "$packageName.update-files",
            apk,
        )
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, APK_MIME_TYPE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Na urządzeniu nie znaleziono instalatora APK.", Toast.LENGTH_LONG)
                .show()
        } finally {
            finish()
        }
    }

    private companion object {
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
