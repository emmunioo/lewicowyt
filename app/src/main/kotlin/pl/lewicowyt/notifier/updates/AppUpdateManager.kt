package pl.lewicowyt.notifier.updates

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import pl.lewicowyt.notifier.R
import pl.lewicowyt.notifier.diagnostics.DiagnosticCategory
import pl.lewicowyt.notifier.diagnostics.DiagnosticLevel
import pl.lewicowyt.notifier.diagnostics.DiagnosticLogStore
import pl.lewicowyt.notifier.diagnostics.DiagnosticReasonCode

data class PreparedUpdate(
    val update: AvailableUpdate,
    val apkFile: File,
)

class AppUpdateManager(
    private val context: Context,
    private val httpClient: OkHttpClient,
) {
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val updatesDirectory = File(context.cacheDir, UPDATE_CACHE_DIRECTORY)
    private val pendingApk = File(updatesDirectory, PENDING_APK_NAME)

    fun prepare(update: AvailableUpdate): PreparedUpdate {
        DiagnosticLogStore.event(
            DiagnosticCategory.UPDATE,
            DiagnosticLevel.INFO,
            "DOWNLOAD_START",
            fields = mapOf("version" to update.version, "asset" to update.apkName),
        )
        createNotificationChannel()
        updatesDirectory.mkdirs()

        if (pendingApk.isFile) {
            runCatching {
                validateApk(pendingApk, update)
                return PreparedUpdate(update, pendingApk)
            }
            pendingApk.delete()
        }

        val temporaryApk = File(updatesDirectory, "$PENDING_APK_NAME.part")
        temporaryApk.delete()
        try {
            downloadApk(update, temporaryApk)
            DiagnosticLogStore.event(
                DiagnosticCategory.UPDATE,
                DiagnosticLevel.INFO,
                "DOWNLOAD_COMPLETE",
                fields = mapOf("bytes" to temporaryApk.length()),
            )
            validateApk(temporaryApk, update)
            if (pendingApk.exists() && !pendingApk.delete()) {
                throw IOException("Nie można zastąpić poprzedniego pliku aktualizacji.")
            }
            if (!temporaryApk.renameTo(pendingApk)) {
                temporaryApk.copyTo(pendingApk, overwrite = true)
                temporaryApk.delete()
            }
            return PreparedUpdate(update, pendingApk)
        } catch (error: Exception) {
            temporaryApk.delete()
            throw error
        }
    }

    fun removeStalePreparedUpdate() {
        if (!pendingApk.isFile) return
        val packageManager = context.packageManager
        val archive = packageInfoFromArchive(packageManager, pendingApk)
        val installed = runCatching { installedPackageInfo(packageManager) }.getOrNull()
        val stale = archive == null ||
            installed == null ||
            archive.packageName != context.packageName ||
            PackageInfoCompat.getLongVersionCode(archive) <=
            PackageInfoCompat.getLongVersionCode(installed)
        if (stale) {
            pendingApk.delete()
            notificationManager.cancel(UPDATE_NOTIFICATION_ID)
        }
    }

    fun launchInstaller() {
        DiagnosticLogStore.event(
            DiagnosticCategory.UPDATE,
            DiagnosticLevel.INFO,
            "INSTALLER_LAUNCHED",
        )
        context.startActivity(
            Intent(context, UpdateInstallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }

    fun notifyReady(prepared: PreparedUpdate) {
        createNotificationChannel()
        val installIntent = PendingIntent.getActivity(
            context,
            INSTALL_REQUEST_CODE,
            Intent(context, UpdateInstallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val projectIntent = PendingIntent.getActivity(
            context,
            PROJECT_REQUEST_CODE,
            Intent(Intent.ACTION_VIEW, PROJECT_URL.toUri()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val isRollback = prepared.update.policy == UpdatePolicy.SECURITY_ROLLBACK
        val isMandatory = prepared.update.policy != UpdatePolicy.OPTIONAL
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                if (isMandatory) {
                    "Wymagana aktualizacja bezpieczeństwa"
                } else {
                    "Aktualizacja ${prepared.update.version} jest gotowa"
                },
            )
            .setContentText(
                if (isRollback) {
                    SECURITY_ROLLBACK_MESSAGE
                } else {
                    "Pobrano APK. Dotknij, aby rozpocząć instalację."
                },
            )
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    if (isRollback) {
                        SECURITY_ROLLBACK_MESSAGE
                    } else {
                        "Wersja ${prepared.update.version} została pobrana i sprawdzona. " +
                            "Dotknij, aby potwierdzić instalację w systemie Android."
                    },
                ),
            )
            .setContentIntent(installIntent)
            .addAction(0, "Strona projektu", projectIntent)
            .setAutoCancel(false)
            .setOngoing(isMandatory)
            .setPriority(
                if (isMandatory) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT,
            )
            .build()
        notificationManager.notify(UPDATE_NOTIFICATION_ID, notification)
    }

    fun notifyMandatoryFailure(message: String) {
        createNotificationChannel()
        val projectIntent = PendingIntent.getActivity(
            context,
            PROJECT_REQUEST_CODE,
            Intent(Intent.ACTION_VIEW, PROJECT_URL.toUri()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        notificationManager.notify(
            UPDATE_NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Nie udało się przygotować aktualizacji bezpieczeństwa")
                .setContentText(message.take(MAX_NOTIFICATION_TEXT_CHARS))
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "$message Więcej informacji znajdziesz na stronie projektu.",
                    ),
                )
                .setContentIntent(projectIntent)
                .setAutoCancel(false)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build(),
        )
    }

    private fun downloadApk(update: AvailableUpdate, target: File) {
        val request = Request.Builder()
            .url(update.apkDownloadUrl)
            .header("Accept", "application/vnd.android.package-archive")
            .header("User-Agent", "lewicowYT-updater")
            .build()
        val client = httpClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.MINUTES)
            .build()

        openApkResponse(client, request).use { response ->
            if (!response.request.url.isHttps) {
                throw IOException("Przekierowanie pobierania nie używa HTTPS.")
            }
            val body = response.body
            val declaredLength = body.contentLength()
            if (declaredLength > MAX_APK_BYTES) {
                throw IOException("Plik aktualizacji przekracza dozwolony rozmiar.")
            }

            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            body.byteStream().use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        if (copied > MAX_APK_BYTES) {
                            throw IOException("Plik aktualizacji przekracza dozwolony rozmiar.")
                        }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            if (copied <= 0L) throw IOException("GitHub zwrócił pusty plik APK.")

            update.sha256Digest?.let { expected ->
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(expected, ignoreCase = true)) {
                    DiagnosticLogStore.event(
                        DiagnosticCategory.UPDATE,
                        DiagnosticLevel.ERROR,
                        "SHA256_FAILED",
                        reason = DiagnosticReasonCode.SHA256_MISMATCH,
                    )
                    throw SecurityException("Suma SHA-256 pobranego APK jest nieprawidłowa.")
                }
                DiagnosticLogStore.event(
                    DiagnosticCategory.UPDATE,
                    DiagnosticLevel.INFO,
                    "SHA256_OK",
                )
            }
        }
    }

    private fun openApkResponse(client: OkHttpClient, initialRequest: Request): Response {
        var currentUrl = requireSafeApkDownloadUrl(
            value = initialRequest.url.toString(),
            allowGitHubAssetHost = false,
        )
        repeat(MAX_APK_REDIRECTS + 1) {
            val request = initialRequest.newBuilder().url(currentUrl).build()
            val response = client.newCall(request).execute()
            if (response.code in APK_REDIRECT_CODES) {
                val location = response.header("Location")
                val redirected = location?.let(currentUrl::resolve)
                response.close()
                if (redirected == null) {
                    throw IOException("GitHub zwrócił nieprawidłowe przekierowanie APK.")
                }
                currentUrl = try {
                    val accepted = requireSafeApkDownloadUrl(
                        value = redirected.toString(),
                        allowGitHubAssetHost = true,
                    )
                    DiagnosticLogStore.event(
                        DiagnosticCategory.UPDATE,
                        DiagnosticLevel.INFO,
                        "REDIRECT_ACCEPTED",
                        fields = mapOf(
                            "from" to currentUrl.host,
                            "to" to accepted.host,
                        ),
                    )
                    accepted
                } catch (_: IllegalArgumentException) {
                    DiagnosticLogStore.event(
                        DiagnosticCategory.UPDATE,
                        DiagnosticLevel.ERROR,
                        "REDIRECT_REJECTED",
                        reason = DiagnosticReasonCode.HOST_NOT_ALLOWED,
                        fields = mapOf("from" to currentUrl.host),
                    )
                    throw IOException("GitHub przekierował pobieranie APK do niedozwolonego hosta.")
                }
                return@repeat
            }
            if (!response.isSuccessful) {
                val statusCode = response.code
                response.close()
                throw IOException("GitHub zwrócił HTTP $statusCode podczas pobierania APK.")
            }
            return response
        }
        throw IOException("GitHub przekroczył limit przekierowań podczas pobierania APK.")
    }

    private fun validateApk(file: File, update: AvailableUpdate) {
        val packageManager = context.packageManager
        val archiveInfo = packageInfoFromArchive(packageManager, file)
            ?: throw SecurityException("Pobrany plik nie jest poprawnym APK.")
        val installedInfo = installedPackageInfo(packageManager)

        if (archiveInfo.packageName != context.packageName) {
            DiagnosticLogStore.event(
                DiagnosticCategory.UPDATE,
                DiagnosticLevel.ERROR,
                "PACKAGE_ID_FAILED",
                reason = DiagnosticReasonCode.PACKAGE_ID_MISMATCH,
            )
            throw SecurityException("APK ma inny identyfikator aplikacji.")
        }
        DiagnosticLogStore.event(
            DiagnosticCategory.UPDATE,
            DiagnosticLevel.INFO,
            "PACKAGE_ID_OK",
        )
        if (!signingCertificates(archiveInfo).any { it in signingCertificates(installedInfo) }) {
            DiagnosticLogStore.event(
                DiagnosticCategory.UPDATE,
                DiagnosticLevel.ERROR,
                "SIGNATURE_FAILED",
                reason = DiagnosticReasonCode.SIGNATURE_MISMATCH,
            )
            throw SecurityException("APK nie jest podpisany kluczem zainstalowanej aplikacji.")
        }
        DiagnosticLogStore.event(
            DiagnosticCategory.UPDATE,
            DiagnosticLevel.INFO,
            "SIGNATURE_OK",
        )

        val installedCode = PackageInfoCompat.getLongVersionCode(installedInfo)
        val archiveCode = PackageInfoCompat.getLongVersionCode(archiveInfo)
        if (archiveCode <= installedCode) {
            DiagnosticLogStore.event(
                DiagnosticCategory.UPDATE,
                DiagnosticLevel.ERROR,
                "VERSION_CODE_FAILED",
                reason = DiagnosticReasonCode.VERSION_CODE_INVALID,
            )
            val rollbackHint = if (update.policy == UpdatePolicy.SECURITY_ROLLBACK) {
                " Awaryjny APK musi zawierać starszy kod aplikacji, ale wyższy versionCode."
            } else {
                ""
            }
            throw SecurityException(
                "Android odrzuci APK, ponieważ jego versionCode nie jest wyższy.$rollbackHint",
            )
        }
        DiagnosticLogStore.event(
            DiagnosticCategory.UPDATE,
            DiagnosticLevel.INFO,
            "VERSION_CODE_OK",
            fields = mapOf("versionCode" to archiveCode),
        )
        if (normalizeVersion(archiveInfo.versionName) != normalizeVersion(update.version)) {
            throw SecurityException("Wersja wewnątrz APK nie odpowiada wydaniu GitHub.")
        }
    }

    @Suppress("DEPRECATION")
    private fun packageInfoFromArchive(
        packageManager: PackageManager,
        file: File,
    ): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        return packageManager.getPackageArchiveInfo(file.absolutePath, flags)
    }

    @Suppress("DEPRECATION")
    private fun installedPackageInfo(packageManager: PackageManager): PackageInfo {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        return packageManager.getPackageInfo(context.packageName, flags)
    }

    @Suppress("DEPRECATION")
    private fun signingCertificates(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return emptySet()
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
        } else {
            info.signatures
        }
        return signatures.orEmpty().mapTo(mutableSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }

    private fun createNotificationChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Aktualizacje aplikacji",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Pobrane aktualizacje i pilne wycofania wersji"
                setShowBadge(true)
            },
        )
    }

    private fun normalizeVersion(value: String?): String =
        value.orEmpty().trim().removePrefix("v").removePrefix("V")

    companion object {
        const val UPDATE_CACHE_DIRECTORY = "updates"
        const val PENDING_APK_NAME = "lewicowyt-update.apk"
        const val SECURITY_ROLLBACK_MESSAGE =
            "Z powodu zagrożenia bezpieczeństwa wymuszono cofnięcie wersji aplikacji. " +
                "Więcej informacji znajdziesz na stronie projektu."
        const val PROJECT_URL = "https://emmunioo.github.io/lewicowyt"

        private const val CHANNEL_ID = "app_updates"
        private const val UPDATE_NOTIFICATION_ID = 0x555044
        private const val INSTALL_REQUEST_CODE = 0x5551
        private const val PROJECT_REQUEST_CODE = 0x5552
        private const val MAX_NOTIFICATION_TEXT_CHARS = 180
        private const val MAX_APK_BYTES = 200L * 1024L * 1024L
        private const val MAX_APK_REDIRECTS = 5
        private val APK_REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

internal fun requireSafeApkDownloadUrl(
    value: String,
    allowGitHubAssetHost: Boolean,
): HttpUrl {
    val url = value.toHttpUrlOrNull()
    require(
        url != null &&
            url.isHttps &&
            url.username.isEmpty() &&
            url.password.isEmpty() &&
            url.port == 443,
    ) {
        "Adres pobierania APK musi używać czystego HTTPS"
    }
    val host = url.host.trimEnd('.').lowercase()
    val isGitHubRelease =
        host == "github.com" &&
            "/releases/download/" in url.encodedPath &&
            url.encodedPath.endsWith(".apk", ignoreCase = true)
    val isGitHubAsset = allowGitHubAssetHost &&
        (host == "objects.githubusercontent.com" || host.endsWith(".githubusercontent.com"))
    require(isGitHubRelease || isGitHubAsset) {
        "Adres pobierania APK nie należy do GitHub Releases"
    }
    return url
}

private fun String.toUri(): android.net.Uri = android.net.Uri.parse(this)
