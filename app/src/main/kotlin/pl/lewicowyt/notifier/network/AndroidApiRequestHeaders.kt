package pl.lewicowyt.notifier.network

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest
import java.util.Locale

/**
 * Pozwala używać klucza ograniczonego w Google Cloud do identyfikatora pakietu
 * i certyfikatu podpisującego zainstalowane APK.
 */
@Suppress("DEPRECATION")
internal fun androidApiRequestHeaders(context: Context): Map<String, String> = runCatching {
    val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_SIGNING_CERTIFICATES,
        )
    } else {
        context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_SIGNATURES,
        )
    }
    val signature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        packageInfo.signingInfo?.apkContentsSigners?.firstOrNull()
    } else {
        packageInfo.signatures?.firstOrNull()
    } ?: return emptyMap()
    val fingerprint = MessageDigest.getInstance("SHA-1")
        .digest(signature.toByteArray())
        .joinToString(separator = "") { byte ->
            String.format(Locale.ROOT, "%02X", byte.toInt() and 0xFF)
        }
    mapOf(
        "X-Android-Package" to context.packageName,
        "X-Android-Cert" to fingerprint,
    )
}.getOrDefault(emptyMap())
