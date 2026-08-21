package pl.lewicowyt.notifier.updates

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.StatFs
import androidx.core.app.NotificationCompat
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    private val deltaFailureStore = DeltaFailureStore(context)
    private val patchDecoder: VcdiffPatchDecoder = StreamingVcdiffPatchDecoder()

    suspend fun prepare(update: AvailableUpdate): PreparedUpdate {
        val apkFile = PROCESS_UPDATE_PREPARATION.run(update.preparationTargetKey()) {
            prepareSerialized(update)
        }
        return PreparedUpdate(update, apkFile)
    }

    private suspend fun prepareSerialized(update: AvailableUpdate): File {
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
                return pendingApk
            }
            pendingApk.delete()
        }

        prepareDeltaOrNull(update)?.let { return it }

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
            return pendingApk
        } catch (error: Exception) {
            temporaryApk.delete()
            throw error
        }
    }

    private suspend fun prepareDeltaOrNull(update: AvailableUpdate): File? {
        val manifestAsset = update.releaseAssets[DELTA_MANIFEST_ASSET_NAME]
            ?: return null
        val manifestFile = File(updatesDirectory, DELTA_MANIFEST_ASSET_NAME)
        val patchFile = File(updatesDirectory, DELTA_PATCH_TEMP_NAME)
        val reconstructedApk = File(updatesDirectory, DELTA_TARGET_TEMP_NAME)
        listOf(manifestFile, patchFile, reconstructedApk).forEach(File::delete)

        var selected: SelectedDelta? = null
        var patchVerified = false
        try {
            downloadAsset(
                downloadUrl = manifestAsset.downloadUrl,
                expectedName = manifestAsset.name,
                expectedSha256 = manifestAsset.sha256Digest,
                declaredSize = manifestAsset.sizeBytes,
                maximumBytes = MAX_DELTA_MANIFEST_BYTES.toLong(),
                target = manifestFile,
                accept = "application/json",
                kind = "manifest",
            )
            val manifest = DeltaUpdateManifestParser.parse(
                manifestFile.readText(Charsets.UTF_8),
            )
            val installed = installedApkIdentity()
            val selection = selectDeltaUpdate(
                manifest = manifest,
                update = update,
                installed = installed,
                rejectedFingerprint = deltaFailureStore.rejectedFingerprint(),
            )
            if (selection is DeltaSelectionResult.UseFullApk) {
                diagnosticDelta("DELTA_SKIPPED", selection.reason)
                return null
            }
            selected = (selection as DeltaSelectionResult.UseDelta).selected
            deltaFailureStore.clearIfDifferent(selected.fingerprint)
            diagnosticDelta(
                event = "DELTA_ELIGIBLE",
                fields = mapOf(
                    "fromVersion" to selected.entry.fromVersionName,
                    "patchBytes" to selected.entry.patchSize,
                    "targetBytes" to selected.target.apkSize,
                ),
            )

            downloadAsset(
                downloadUrl = selected.asset.downloadUrl,
                expectedName = selected.entry.patchName,
                expectedSha256 = selected.entry.patchSha256,
                declaredSize = selected.entry.patchSize,
                maximumBytes = MAX_DELTA_PATCH_BYTES,
                target = patchFile,
                accept = "application/octet-stream",
                kind = "delta",
            )
            patchVerified = true
            diagnosticDelta("DELTA_PATCH_VERIFIED")

            val requiredFreeBytes = selected.target.apkSize + MIN_DELTA_WORKSPACE_BYTES
            if (StatFs(updatesDirectory.absolutePath).availableBytes < requiredFreeBytes) {
                throw DeltaPreparationException(
                    DeltaFallbackReason.DELTA_NO_SPACE,
                    "Brak miejsca na bezpieczne odtworzenie APK.",
                )
            }

            val coroutineContext = currentCoroutineContext()
            try {
                patchDecoder.apply(
                    sourceApk = File(context.applicationInfo.sourceDir),
                    patch = patchFile,
                    targetApk = reconstructedApk,
                    cancellationCheck = PatchCancellationCheck {
                        coroutineContext.ensureActive()
                    },
                )
            } catch (unavailable: LinkageError) {
                throw DeltaPreparationException(
                    DeltaFallbackReason.DELTA_DECODER_UNAVAILABLE,
                    "Dekoder VCDIFF nie jest dostępny.",
                    unavailable,
                )
            }
            val actualTargetHash = sha256(reconstructedApk)
            if (!actualTargetHash.equals(selected.target.apkSha256, ignoreCase = true)) {
                throw DeltaPreparationException(
                    DeltaFallbackReason.DELTA_TARGET_HASH_MISMATCH,
                    "Suma SHA-256 odtworzonego APK jest nieprawidłowa.",
                )
            }
            try {
                validateApk(reconstructedApk, update)
            } catch (error: ApkValidationException) {
                throw DeltaPreparationException(error.deltaReason, error.message.orEmpty(), error)
            }
            moveToPending(reconstructedApk)
            diagnosticDelta(
                event = "DELTA_SUCCESS",
                fields = mapOf(
                    "patchBytes" to selected.entry.patchSize,
                    "savedBytes" to (selected.target.apkSize - selected.entry.patchSize),
                ),
            )
            return pendingApk
        } catch (cancelled: CancellationException) {
            diagnosticDelta("DELTA_FALLBACK_FULL", DeltaFallbackReason.DELTA_CANCELLED)
            throw cancelled
        } catch (error: Exception) {
            val reason = when (error) {
                is DeltaPreparationException -> error.reason
                is SecurityException -> DeltaFallbackReason.DELTA_MANIFEST_INVALID
                is IOException -> DeltaFallbackReason.DELTA_IO_ERROR
                else -> DeltaFallbackReason.DELTA_APPLY_FAILED
            }
            if (patchVerified && selected != null && reason.deterministic) {
                deltaFailureStore.reject(selected.fingerprint)
            }
            diagnosticDelta(
                event = "DELTA_FALLBACK_FULL",
                reason = reason,
                fields = mapOf("error" to error.javaClass.simpleName),
            )
            return null
        } finally {
            listOf(manifestFile, patchFile, reconstructedApk).forEach(File::delete)
        }
    }

    fun removeStalePreparedUpdate() {
        listOf(
            File(updatesDirectory, DELTA_MANIFEST_ASSET_NAME),
            File(updatesDirectory, DELTA_PATCH_TEMP_NAME),
            File(updatesDirectory, DELTA_TARGET_TEMP_NAME),
            File(updatesDirectory, "$PENDING_APK_NAME.part"),
        ).forEach(File::delete)
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

    private suspend fun downloadApk(update: AvailableUpdate, target: File) {
        downloadAsset(
            downloadUrl = update.apkDownloadUrl,
            expectedName = update.apkName,
            expectedSha256 = update.sha256Digest,
            declaredSize = update.apkSizeBytes,
            maximumBytes = MAX_APK_BYTES,
            target = target,
            accept = "application/vnd.android.package-archive",
            kind = "APK",
        )
    }

    private suspend fun downloadAsset(
        downloadUrl: String,
        expectedName: String,
        expectedSha256: String?,
        declaredSize: Long?,
        maximumBytes: Long,
        target: File,
        accept: String,
        kind: String,
    ) {
        if (declaredSize != null && declaredSize !in 1..maximumBytes) {
            throw IOException("Plik $kind ma niedozwolony rozmiar.")
        }
        val request = Request.Builder()
            .url(downloadUrl)
            .header("Accept", accept)
            .header("User-Agent", "lewicowYT-updater")
            .build()
        val client = httpClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.MINUTES)
            .build()

        openReleaseAssetResponse(client, request, expectedName).use { response ->
            if (!response.request.url.isHttps) {
                throw IOException("Przekierowanie pobierania nie używa HTTPS.")
            }
            val body = response.body
            val declaredLength = body.contentLength()
            if (
                declaredLength > maximumBytes ||
                (declaredSize != null && declaredLength >= 0L && declaredLength != declaredSize)
            ) {
                throw IOException("Rozmiar pliku $kind nie odpowiada metadanym wydania.")
            }

            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            body.byteStream().use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        if (copied > maximumBytes) {
                            throw IOException("Plik $kind przekracza dozwolony rozmiar.")
                        }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            if (declaredSize != null && copied != declaredSize) {
                throw IOException("Pobrany rozmiar pliku $kind nie odpowiada metadanym wydania.")
            }

            expectedSha256?.let { expected ->
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(expected, ignoreCase = true)) {
                    DiagnosticLogStore.event(
                        DiagnosticCategory.UPDATE,
                        DiagnosticLevel.ERROR,
                        "SHA256_FAILED",
                        reason = DiagnosticReasonCode.SHA256_MISMATCH,
                    )
                    throw DeltaPreparationException(
                        if (kind == "delta") {
                            DeltaFallbackReason.DELTA_PATCH_HASH_MISMATCH
                        } else {
                            DeltaFallbackReason.DELTA_MANIFEST_INVALID
                        },
                        "Suma SHA-256 pobranego pliku $kind jest nieprawidłowa.",
                    )
                }
                DiagnosticLogStore.event(
                    DiagnosticCategory.UPDATE,
                    DiagnosticLevel.INFO,
                    "SHA256_OK",
                )
            }
        }
    }

    private fun openReleaseAssetResponse(
        client: OkHttpClient,
        initialRequest: Request,
        expectedName: String,
    ): Response {
        var currentUrl = requireSafeReleaseAssetDownloadUrl(
            value = initialRequest.url.toString(),
            allowGitHubAssetHost = false,
            expectedName = expectedName,
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
                    val accepted = requireSafeReleaseAssetDownloadUrl(
                        value = redirected.toString(),
                        allowGitHubAssetHost = true,
                        expectedName = expectedName,
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
        throw IOException("GitHub przekroczył limit przekierowań podczas pobierania pliku wydania.")
    }

    private fun installedApkIdentity(): InstalledApkIdentity {
        val info = installedPackageInfo(context.packageManager)
        val sourceApk = File(context.applicationInfo.sourceDir)
        if (!sourceApk.isFile || sourceApk.length() !in 1..MAX_RECONSTRUCTED_APK_BYTES) {
            throw DeltaPreparationException(
                DeltaFallbackReason.DELTA_SOURCE_HASH_MISMATCH,
                "Nie można bezpiecznie odczytać zainstalowanego bazowego APK.",
            )
        }
        return InstalledApkIdentity(
            versionName = normalizeVersion(info.versionName),
            versionCode = PackageInfoCompat.getLongVersionCode(info),
            apkSha256 = sha256(sourceApk),
        )
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun moveToPending(source: File) {
        if (pendingApk.exists() && !pendingApk.delete()) {
            throw IOException("Nie można zastąpić poprzedniego pliku aktualizacji.")
        }
        if (!source.renameTo(pendingApk)) {
            source.copyTo(pendingApk, overwrite = true)
            source.delete()
        }
    }

    private fun diagnosticDelta(
        event: String,
        reason: DeltaFallbackReason? = null,
        fields: Map<String, Any?> = emptyMap(),
    ) {
        if (!DiagnosticLogStore.isEnabled()) return
        DiagnosticLogStore.event(
            DiagnosticCategory.UPDATE,
            if (event == "DELTA_SUCCESS" || event == "DELTA_ELIGIBLE" || event == "DELTA_PATCH_VERIFIED") {
                DiagnosticLevel.INFO
            } else {
                DiagnosticLevel.WARNING
            },
            event,
            reason = reason?.toDiagnosticReasonCode(),
            fields = fields,
        )
    }

    private fun validateApk(file: File, update: AvailableUpdate) {
        val packageManager = context.packageManager
        val archiveInfo = packageInfoFromArchive(packageManager, file)
            ?: throw ApkValidationException(
                DeltaFallbackReason.DELTA_FORMAT_INVALID,
                "Pobrany plik nie jest poprawnym APK.",
            )
        val installedInfo = installedPackageInfo(packageManager)

        if (archiveInfo.packageName != context.packageName) {
            DiagnosticLogStore.event(
                DiagnosticCategory.UPDATE,
                DiagnosticLevel.ERROR,
                "PACKAGE_ID_FAILED",
                reason = DiagnosticReasonCode.PACKAGE_ID_MISMATCH,
            )
            throw ApkValidationException(
                DeltaFallbackReason.DELTA_TARGET_PACKAGE_INVALID,
                "APK ma inny identyfikator aplikacji.",
            )
        }
        DiagnosticLogStore.event(
            DiagnosticCategory.UPDATE,
            DiagnosticLevel.INFO,
            "PACKAGE_ID_OK",
        )
        if (!hasCompatibleSigningLineage(installedInfo, archiveInfo)) {
            DiagnosticLogStore.event(
                DiagnosticCategory.UPDATE,
                DiagnosticLevel.ERROR,
                "SIGNATURE_FAILED",
                reason = DiagnosticReasonCode.SIGNATURE_MISMATCH,
            )
            throw ApkValidationException(
                DeltaFallbackReason.DELTA_TARGET_SIGNATURE_INVALID,
                "APK nie jest podpisany kluczem zainstalowanej aplikacji.",
            )
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
            throw ApkValidationException(
                DeltaFallbackReason.DELTA_TARGET_VERSION_INVALID,
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
            throw ApkValidationException(
                DeltaFallbackReason.DELTA_TARGET_VERSION_INVALID,
                "Wersja wewnątrz APK nie odpowiada wydaniu GitHub.",
            )
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
    private fun hasCompatibleSigningLineage(
        installed: PackageInfo,
        candidate: PackageInfo,
    ): Boolean {
        val installedActive = activeSigningCertificates(installed)
        val candidateActive = activeSigningCertificates(candidate)
        if (installedActive.isEmpty() || candidateActive.isEmpty()) return false
        if (installedActive == candidateActive) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val installedMultiple = installed.signingInfo?.hasMultipleSigners() == true
        val candidateMultiple = candidate.signingInfo?.hasMultipleSigners() == true
        if (installedMultiple || candidateMultiple) return false
        // Legalna rotacja: aktywny certyfikat zainstalowanej wersji musi należeć
        // do kryptograficznie zweryfikowanej historii kandydata. Kandydat
        // podpisany wyłącznie dawnym kluczem nie zawiera aktywnego nowego
        // certyfikatu i zostanie odrzucony.
        return candidateSigningHistory(candidate).containsAll(installedActive)
    }

    @Suppress("DEPRECATION")
    private fun activeSigningCertificates(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners ?: return emptySet()
        } else info.signatures
        return certificateDigests(signatures)
    }

    private fun candidateSigningHistory(info: PackageInfo): Set<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return emptySet()
        val signingInfo = info.signingInfo ?: return emptySet()
        if (signingInfo.hasMultipleSigners()) return emptySet()
        return certificateDigests(signingInfo.signingCertificateHistory)
    }

    private fun certificateDigests(signatures: Array<out android.content.pm.Signature>?): Set<String> {
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
        private val PROCESS_UPDATE_PREPARATION =
            UpdatePreparationSingleFlight<UpdatePreparationTargetKey, File>()

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
        private const val DELTA_PATCH_TEMP_NAME = "lewicowyt-update.xdelta.part"
        private const val DELTA_TARGET_TEMP_NAME = "lewicowyt-update-reconstructed.apk.part"
        private const val MIN_DELTA_WORKSPACE_BYTES = 8L * 1024L * 1024L
        private const val MAX_APK_REDIRECTS = 5
        private val APK_REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

internal fun requireSafeApkDownloadUrl(
    value: String,
    allowGitHubAssetHost: Boolean,
): HttpUrl = requireSafeReleaseAssetDownloadUrl(
    value = value,
    allowGitHubAssetHost = allowGitHubAssetHost,
    expectedName = value.substringAfterLast('/').substringBefore('?').takeIf {
        it.endsWith(".apk", ignoreCase = true)
    } ?: "update.apk",
)

internal fun requireSafeReleaseAssetDownloadUrl(
    value: String,
    allowGitHubAssetHost: Boolean,
    expectedName: String,
): HttpUrl {
    require(isSafeReleaseAssetName(expectedName)) { "Nieprawidłowa nazwa pliku wydania" }
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
            url.encodedPath.endsWith("/$expectedName", ignoreCase = true)
    val isGitHubAsset = allowGitHubAssetHost &&
        (host == "objects.githubusercontent.com" || host.endsWith(".githubusercontent.com"))
    require(isGitHubRelease || isGitHubAsset) {
        "Adres pobierania APK nie należy do GitHub Releases"
    }
    return url
}

private class DeltaPreparationException(
    val reason: DeltaFallbackReason,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

private class ApkValidationException(
    val deltaReason: DeltaFallbackReason,
    message: String,
) : SecurityException(message)

private fun DeltaFallbackReason.toDiagnosticReasonCode(): DiagnosticReasonCode =
    DiagnosticReasonCode.valueOf(name)

private fun String.toUri(): android.net.Uri = android.net.Uri.parse(this)
