package pl.lewicowyt.notifier.worker

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

internal enum class SyncNetworkAccess {
    UNAVAILABLE,
    METERED,
    UNMETERED,
}

internal enum class DiagnosticNetworkType {
    WIFI,
    CELLULAR,
    ETHERNET,
    OTHER,
    NONE,
}

internal data class DiagnosticNetworkState(
    val type: DiagnosticNetworkType,
    val available: Boolean,
    val metered: Boolean,
)

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

internal fun currentDiagnosticNetworkState(context: Context): DiagnosticNetworkState {
    val manager = context.getSystemService(ConnectivityManager::class.java)
        ?: return DiagnosticNetworkState(DiagnosticNetworkType.NONE, false, false)
    val network = manager.activeNetwork
        ?: return DiagnosticNetworkState(DiagnosticNetworkType.NONE, false, false)
    val capabilities = manager.getNetworkCapabilities(network)
        ?: return DiagnosticNetworkState(DiagnosticNetworkType.NONE, false, false)
    val available = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    val type = when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
            DiagnosticNetworkType.WIFI
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
            DiagnosticNetworkType.CELLULAR
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
            DiagnosticNetworkType.ETHERNET
        available -> DiagnosticNetworkType.OTHER
        else -> DiagnosticNetworkType.NONE
    }
    return DiagnosticNetworkState(
        type = type,
        available = available,
        metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
    )
}
