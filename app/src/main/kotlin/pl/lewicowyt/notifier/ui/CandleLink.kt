package pl.lewicowyt.notifier.ui

import android.annotation.SuppressLint
import android.os.Build
import android.view.ContextThemeWrapper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.ByteArrayInputStream
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import pl.lewicowyt.notifier.links.isSafeExternalHttpsUrl
import pl.lewicowyt.notifier.network.BROWSER_USER_AGENT

private const val CANDLE_URL = "https://emmunioo.github.io/muru/"
private const val CANDLE_HOST = "emmunioo.github.io"
private const val CANDLE_DESCRIPTION = "świeczka dla mojego kota"

/** E-02: w oknie zostają tylko strony spod adresu świeczki; reszta idzie do przeglądarki (tylko https). */
internal fun isCandlePageUrl(url: String): Boolean = url.startsWith(CANDLE_URL)

/**
 * J-10: zasoby strony (CSS, skrypty, czcionki, obrazy) wolno pobierać tylko z hosta strony po https. Reszta
 * (np. Google Fonts) dostaje pustą odpowiedź — WebView jest jedynym klientem poza PrivacyHttpClient, więc
 * nie może łączyć się z dowolnymi hostami. Czcionki spadają wtedy na zastępcze z CSS strony.
 */
internal fun isCandleResourceUrl(url: String): Boolean =
    url.toHttpUrlOrNull()?.let { it.isHttps && it.host.equals(CANDLE_HOST, ignoreCase = true) } == true

/** Świeczka obok licencji — otwiera stronę wewnątrz aplikacji (WebView), nie w przeglądarce. */
@Composable
fun CandleLink() {
    var open by rememberSaveable { mutableStateOf(false) }
    TextButton(
        onClick = { open = true },
        modifier = Modifier.semantics { contentDescription = CANDLE_DESCRIPTION },
    ) {
        Text("🕯️", style = MaterialTheme.typography.titleLarge)
    }
    if (open) CandleWebDialog(onClose = { open = false })
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun CandleWebDialog(onClose: () -> Unit) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    val uriHandler = LocalUriHandler.current
    // motyw aplikacji (może różnić się od systemowego) — strona przełącza się przez prefers-color-scheme
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BackHandler {
            val view = webView
            if (view != null && view.canGoBack()) view.goBack() else onClose()
        }
        Surface(modifier = Modifier.fillMaxSize()) {
            Column {
                TextButton(onClick = onClose) { Text("Zamknij — $CANDLE_DESCRIPTION") }
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        // Android 13+: WebView bierze prefers-color-scheme z isLightTheme motywu kontekstu
                        val themed = ContextThemeWrapper(
                            context,
                            if (dark) android.R.style.Theme_DeviceDefault else android.R.style.Theme_DeviceDefault_Light,
                        )
                        WebView(themed).apply {
                            if (Build.VERSION.SDK_INT in Build.VERSION_CODES.Q until Build.VERSION_CODES.TIRAMISU) {
                                // Android 10–12: forceDark ustawia też prefers-color-scheme
                                @Suppress("DEPRECATION")
                                settings.forceDark = if (dark) android.webkit.WebSettings.FORCE_DARK_ON else android.webkit.WebSettings.FORCE_DARK_OFF
                            }
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            // E-02: bez dostępu do plików i content:// aplikacji; jedna znana strona — bez Safe Browsing
                            // (każdy adres szedłby do Google)
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.safeBrowsingEnabled = false
                            // J-10: ten sam UA przeglądarki co reszta ruchu aplikacji — bez modelu telefonu i wersji
                            // Androida z domyślnego UA WebView (B-14). DNS WebView jest systemowy (bez DoH) — świadomie:
                            // jeden znany host, otwierany tylko po dotknięciu świeczki.
                            settings.userAgentString = BROWSER_USER_AGENT
                            webViewClient = object : WebViewClient() {
                                override fun shouldInterceptRequest(
                                    view: WebView,
                                    request: WebResourceRequest,
                                ): WebResourceResponse? =
                                    if (isCandleResourceUrl(request.url.toString())) {
                                        null
                                    } else {
                                        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                                    }

                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val url = request.url.toString()
                                    if (isCandlePageUrl(url)) return false
                                    // obce strony nie otwierają się w tym oknie bez paska adresu
                                    if (request.isForMainFrame && isSafeExternalHttpsUrl(url)) {
                                        runCatching { uriHandler.openUri(url) }
                                    }
                                    return true
                                }
                            }
                            loadUrl(CANDLE_URL)
                            webView = this
                        }
                    },
                    onRelease = {
                        it.destroy()
                        // E-02: cookies i localStorage strony nie przeżywają zamknięcia okna (ani „Nowej tożsamości")
                        WebStorage.getInstance().deleteAllData()
                        CookieManager.getInstance().removeAllCookies(null)
                    },
                    update = {
                        it.setBackgroundColor(if (dark) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                    },
                )
            }
        }
    }
}
