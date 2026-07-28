package pl.lewicowyt.notifier.worker

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

internal enum class SyncNetworkAccess {
    UNAVAILABLE,
    METERED,
    UNMETERED,
}

/**
 * Synchronizacja może korzystać z sieci taryfowej tylko po zgodzie użytkownika.
 * Sieć oznaczona przez Androida jako bez limitu jest dozwolona zawsze.
 */
internal fun SyncNetworkAccess.allowsSync(allowMobileData: Boolean): Boolean = when (this) {
    SyncNetworkAccess.UNAVAILABLE -> false
    SyncNetworkAccess.METERED -> allowMobileData
    SyncNetworkAccess.UNMETERED -> true
}

internal fun currentSyncNetworkAccess(context: Context): SyncNetworkAccess {
    val manager = context.getSystemService(ConnectivityManager::class.java)
        ?: return SyncNetworkAccess.UNAVAILABLE
    val network = manager.activeNetwork ?: return SyncNetworkAccess.UNAVAILABLE
    val capabilities = manager.getNetworkCapabilities(network)
        ?: return SyncNetworkAccess.UNAVAILABLE
    val hasInternet =
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    if (!hasInternet) return SyncNetworkAccess.UNAVAILABLE

    return if (
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    ) {
        SyncNetworkAccess.UNMETERED
    } else {
        SyncNetworkAccess.METERED
    }
}
