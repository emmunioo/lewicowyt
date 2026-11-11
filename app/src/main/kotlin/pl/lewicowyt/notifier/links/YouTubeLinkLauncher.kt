package pl.lewicowyt.notifier.links

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import java.net.URI
import java.util.Locale
import pl.lewicowyt.notifier.data.YouTubeLinkTarget
import pl.lewicowyt.notifier.data.isSafeAndroidPackageName
import pl.lewicowyt.notifier.diagnostics.DiagnosticCategory
import pl.lewicowyt.notifier.diagnostics.DiagnosticLevel
import pl.lewicowyt.notifier.diagnostics.DiagnosticLogStore
import pl.lewicowyt.notifier.diagnostics.DiagnosticReasonCode

internal enum class YouTubeLinkRoute {
    SYSTEM,
    CHOOSER,
    YOUTUBE,
    BROWSER,
    OTHER_APP,
    NONE,
}

data class ExternalAppOption(
    val packageName: String,
    val label: String,
)

internal data class YouTubeLinkAvailability(
    val system: Boolean,
    val browserPackage: String?,
)

internal data class YouTubeLinkPlan(
    val route: YouTubeLinkRoute,
    val packageName: String? = null,
    val fallbackReason: DiagnosticReasonCode? = null,
)

internal fun planYouTubeLinkOpen(
    target: YouTubeLinkTarget,
    availability: YouTubeLinkAvailability,
    otherAppPackage: String? = null,
): YouTubeLinkPlan = when (target) {
    YouTubeLinkTarget.SYSTEM_DEFAULT -> if (availability.system) {
        YouTubeLinkPlan(YouTubeLinkRoute.SYSTEM)
    } else {
        YouTubeLinkPlan(
            YouTubeLinkRoute.NONE,
            fallbackReason = DiagnosticReasonCode.NO_LINK_HANDLER,
        )
    }
    YouTubeLinkTarget.ALWAYS_ASK -> if (availability.system) {
        YouTubeLinkPlan(YouTubeLinkRoute.CHOOSER)
    } else {
        YouTubeLinkPlan(
            YouTubeLinkRoute.NONE,
            fallbackReason = DiagnosticReasonCode.NO_LINK_HANDLER,
        )
    }
    // „YouTube" nie wymusza żadnej konkretnej apki – zwykły ACTION_VIEW, więc
    // link idzie tam, gdzie Android kieruje linki YouTube (cokolwiek user ustawił
    // jako domyślny handler)
    YouTubeLinkTarget.YOUTUBE -> if (availability.system) {
        YouTubeLinkPlan(YouTubeLinkRoute.SYSTEM)
    } else {
        YouTubeLinkPlan(
            YouTubeLinkRoute.NONE,
            fallbackReason = DiagnosticReasonCode.NO_LINK_HANDLER,
        )
    }
    YouTubeLinkTarget.BROWSER -> when {
        availability.browserPackage != null -> YouTubeLinkPlan(
            route = YouTubeLinkRoute.BROWSER,
            packageName = availability.browserPackage,
        )
        availability.system -> YouTubeLinkPlan(
            route = YouTubeLinkRoute.SYSTEM,
            fallbackReason = DiagnosticReasonCode.BROWSER_NOT_AVAILABLE,
        )
        else -> YouTubeLinkPlan(
            route = YouTubeLinkRoute.NONE,
            fallbackReason = DiagnosticReasonCode.NO_LINK_HANDLER,
        )
    }
    YouTubeLinkTarget.OTHER_APP -> if (
        otherAppPackage != null && isSafeAndroidPackageName(otherAppPackage)
    ) {
        YouTubeLinkPlan(
            route = YouTubeLinkRoute.OTHER_APP,
            packageName = otherAppPackage,
        )
    } else {
        YouTubeLinkPlan(
            route = YouTubeLinkRoute.NONE,
            fallbackReason = DiagnosticReasonCode.APP_NOT_AVAILABLE,
        )
    }
}

class YouTubeLinkLauncher(context: Context) {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager

    fun open(
        url: String,
        target: YouTubeLinkTarget,
        otherAppPackage: String? = null,
    ): Boolean {
        val uri = url.takeIf(::isSafeYouTubeExternalUrl)?.let(Uri::parse)
        if (uri == null) {
            log(target, "FAILED", DiagnosticReasonCode.INVALID_LINK)
            showNoHandler()
            return false
        }

        val systemIntent = viewIntent(uri)
        val browserPackage = findBrowserPackage(uri)
        val availability = YouTubeLinkAvailability(
            // nie blokujemy ACTION_VIEW wynikiem resolveActivity() – Android może
            // znać domyślny handler linku YT, którego PackageManager nie zdradza
            // przed faktycznym odpaleniem
            system = true,
            browserPackage = browserPackage,
        )
        val plan = planYouTubeLinkOpen(target, availability, otherAppPackage)
        if (plan.route == YouTubeLinkRoute.NONE) {
            log(target, "FAILED", plan.fallbackReason)
            showNoHandler()
            return false
        }

        return try {
            start(buildIntent(plan, uri, availability))
            log(
                target,
                if (plan.fallbackReason == null) "SUCCESS" else "FALLBACK_SYSTEM",
                plan.fallbackReason,
            )
            true
        } catch (_: ActivityNotFoundException) {
            handleLaunchFailure(target, systemIntent, plan.route)
        } catch (_: SecurityException) {
            handleLaunchFailure(target, systemIntent, plan.route)
        }
    }

    /**
     * Otwiera DOWOLNY bezpieczny link https wg wybranej preferencji (target + opcjonalny pakiet). Ta sama
     * logika co [open], ale bez ograniczenia do hostów YouTube — dla platform deklarowanych przez serwery
     * społeczności (twitter/twitch/…). Target YOUTUBE traktujemy jak SYSTEM (brak wymuszania apki).
     */
    fun openExternal(
        url: String,
        target: YouTubeLinkTarget,
        otherAppPackage: String? = null,
    ): Boolean {
        val uri = url.takeIf(::isSafeExternalHttpsUrl)?.let(Uri::parse)
        if (uri == null) { showNoHandler(); return false }
        val systemIntent = viewIntent(uri)
        val availability = YouTubeLinkAvailability(system = true, browserPackage = findBrowserPackage(uri))
        val plan = planYouTubeLinkOpen(target, availability, otherAppPackage)
        if (plan.route == YouTubeLinkRoute.NONE) { showNoHandler(); return false }
        return try {
            // R-12: nie zapamiętujemy wyboru z choosera dla linków platform społeczności
            start(buildIntent(plan, uri, availability, rememberChoice = false)); true
        } catch (_: ActivityNotFoundException) {
            handleLaunchFailure(target, systemIntent, plan.route)
        } catch (_: SecurityException) {
            handleLaunchFailure(target, systemIntent, plan.route)
        }
    }

    fun launchableApplications(): List<ExternalAppOption> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        // A-44: filtry launchera zwykle NIE mają CATEGORY_DEFAULT, więc MATCH_DEFAULT_ONLY zwracał prawie pustą
        // listę „Inna aplikacja". Flaga 0 = wszystkie aktywności MAIN+LAUNCHER (widoczne dzięki <queries>).
        return packageManager.queryIntentActivities(
            launcherIntent,
            0,
        ).mapNotNull { info ->
            val packageName = info.activityInfo?.packageName
                ?.takeIf(::isSafeAndroidPackageName)
                ?.takeUnless { it == appContext.packageName }
                ?: return@mapNotNull null
            ExternalAppOption(
                packageName = packageName,
                label = info.loadLabel(packageManager).toString()
                    .trim()
                    .ifBlank { packageName },
            )
        }.distinctBy(ExternalAppOption::packageName)
            .sortedBy { it.label.lowercase(Locale.ROOT) }
    }

    fun applicationLabel(packageName: String?): String? {
        if (packageName == null || !isSafeAndroidPackageName(packageName)) return null
        return runCatching {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(packageName, 0),
            ).toString().trim().ifBlank { packageName }
        }.getOrNull()
    }

    private fun handleLaunchFailure(
        target: YouTubeLinkTarget,
        systemIntent: Intent,
        attemptedRoute: YouTubeLinkRoute,
    ): Boolean {
        if (attemptedRoute == YouTubeLinkRoute.OTHER_APP) {
            return failAfterFallback(target)
        }
        return fallbackAfterLaunchFailure(target, systemIntent, attemptedRoute)
    }

    fun notificationPendingIntent(url: String, requestCode: Int): PendingIntent? {
        if (!isSafeYouTubeExternalUrl(url)) return null
        return PendingIntent.getActivity(
            appContext,
            requestCode,
            Intent(appContext, YouTubeLinkActivity::class.java)
                .putExtra(YouTubeLinkActivity.EXTRA_URL, url),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun fallbackAfterLaunchFailure(
        target: YouTubeLinkTarget,
        systemIntent: Intent,
        attemptedRoute: YouTubeLinkRoute,
    ): Boolean {
        if (attemptedRoute != YouTubeLinkRoute.SYSTEM && canHandle(systemIntent)) {
            return try {
                start(systemIntent)
                log(target, "FALLBACK_SYSTEM", DiagnosticReasonCode.LINK_LAUNCH_FAILED)
                true
            } catch (_: ActivityNotFoundException) {
                failAfterFallback(target)
            } catch (_: SecurityException) {
                failAfterFallback(target)
            }
        }
        return failAfterFallback(target)
    }

    private fun failAfterFallback(target: YouTubeLinkTarget): Boolean {
        log(target, "FAILED", DiagnosticReasonCode.NO_LINK_HANDLER)
        showNoHandler()
        return false
    }

    private fun buildIntent(
        plan: YouTubeLinkPlan,
        uri: Uri,
        availability: YouTubeLinkAvailability,
        // R-12: czy zapamiętać wybór z choosera jako domyślną apkę do linków YouTube. TYLKO dla linków
        // YouTube (open). Dla platform społeczności (openExternal: twitter/twitch/…) = false — inaczej wybór
        // apki do np. Twitcha przestawiałby aplikację do linków YouTube (setOtherYouTubeAppPackage).
        rememberChoice: Boolean = true,
    ): Intent = when (plan.route) {
        YouTubeLinkRoute.SYSTEM -> viewIntent(uri)
        YouTubeLinkRoute.CHOOSER -> createAlwaysAskChooser(
            targetIntent = viewIntent(uri),
            initialIntents = buildList {
                // przeglądarkę dokładamy jawnie, bo Android potrafi ją schować za
                // zweryfikowanym deep linkiem YT. reszta choosera to po prostu to,
                // co user ma zainstalowane – niczego nie podpowiadamy
                availability.browserPackage?.let { add(viewIntent(uri, it)) }
            }.distinctBy { it.`package` }.take(MAX_CHOOSER_INITIAL_INTENTS),
            // system zgłasza wybraną apkę do receivera, a ten ją zapamiętuje — tylko dla linków YouTube
            chosenComponentSender = if (rememberChoice) rememberChoiceSender() else null,
        )
        YouTubeLinkRoute.YOUTUBE,
        YouTubeLinkRoute.BROWSER,
        YouTubeLinkRoute.OTHER_APP,
        -> viewIntent(uri, requireNotNull(plan.packageName))
        YouTubeLinkRoute.NONE -> error("Brak trasy nie może tworzyć Intentu")
    }

    private fun start(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
    }

    // IntentSender, którym chooser zgłasza wybraną apkę do ChosenYouTubeAppReceiver
    // (zapamiętanie). FLAG_MUTABLE, bo system dopisuje EXTRA_CHOSEN_COMPONENT;
    // komponent jest jawny i nieeksportowany, więc mutowalność nic nie otwiera
    private fun rememberChoiceSender(): IntentSender =
        PendingIntent.getBroadcast(
            appContext,
            CHOSEN_APP_REQUEST_CODE,
            Intent(appContext, ChosenYouTubeAppReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        ).intentSender

    private fun canHandle(intent: Intent): Boolean =
        packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null

    /**
     * przeglądarki szukamy neutralnym adresem HTTPS, żeby apki obsługujące tylko
     * deep linki YT nie wchodziły w grę. listy pakietów nie zapisujemy ani nie logujemy.
     */
    private fun findBrowserPackage(youtubeUri: Uri): String? {
        val neutralIntent = viewIntent(Uri.parse(BROWSER_PROBE_URL))
        val candidates = findBrowserPackages()
            .filter { canHandle(viewIntent(youtubeUri, it)) }
        val defaultPackage = packageManager.resolveActivity(
            neutralIntent,
            PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName
            ?.takeUnless { it == "android" }
        return defaultPackage?.takeIf { it in candidates } ?: candidates.singleOrNull()
    }

    private fun findBrowserPackages(): List<String> =
        packageManager.queryIntentActivities(
            viewIntent(Uri.parse(BROWSER_PROBE_URL)),
            PackageManager.MATCH_DEFAULT_ONLY,
        ).mapNotNull { it.activityInfo?.packageName }
            .distinct()

    private fun showNoHandler() {
        Toast.makeText(
            appContext,
            "Brak aplikacji mogącej otworzyć ten link.",
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun log(
        target: YouTubeLinkTarget,
        result: String,
        reason: DiagnosticReasonCode?,
    ) {
        DiagnosticLogStore.event(
            category = DiagnosticCategory.APP,
            level = if (result == "FAILED") DiagnosticLevel.WARNING else DiagnosticLevel.INFO,
            name = "LINK_OPEN",
            reason = reason,
            fields = mapOf("target" to target.name, "result" to result),
        )
    }

    private fun viewIntent(uri: Uri, packageName: String? = null): Intent =
        Intent(Intent.ACTION_VIEW, uri)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .apply { packageName?.let(::setPackage) }
}

/**
 * przy jednym kandydacie Android lubi pominąć chooser. wyłączenie auto-launch
 * sprawia, że "Pytaj za każdym razem" faktycznie pokazuje ekran wyboru i wtedy.
 */
internal fun createAlwaysAskChooser(
    targetIntent: Intent,
    initialIntents: List<Intent> = emptyList(),
    chosenComponentSender: IntentSender? = null,
): Intent =
    Intent.createChooser(targetIntent, "Otwórz link w").apply {
        putExtra(Intent.EXTRA_AUTO_LAUNCH_SINGLE_CHOICE, false)
        if (initialIntents.isNotEmpty()) {
            putExtra(Intent.EXTRA_INITIAL_INTENTS, initialIntents.toTypedArray())
        }
        // po wyborze system zgłasza komponent tym senderem (zapamiętanie)
        chosenComponentSender?.let {
            putExtra(Intent.EXTRA_CHOSEN_COMPONENT_INTENT_SENDER, it)
        }
    }

internal fun isSafeYouTubeExternalUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    val host = uri.host?.lowercase(Locale.ROOT)
    uri.scheme.equals("https", ignoreCase = true) &&
        uri.userInfo == null &&
        uri.port in setOf(-1, 443) &&
        (host == "youtu.be" || host == "youtube.com" || host?.endsWith(".youtube.com") == true)
}.getOrDefault(false)

/** dowolny bezpieczny link zewnętrzny: https, bez userinfo, host obecny. D-16: dowolny poprawny port (np. :2137,
 *  domyślny port serwera społeczności) — TLS chroni tak samo; wcześniej takie linki były po cichu ignorowane. */
internal fun isSafeExternalHttpsUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme.equals("https", ignoreCase = true) &&
        uri.userInfo == null &&
        (uri.port == -1 || uri.port in 1..65535) &&
        !uri.host.isNullOrBlank()
}.getOrDefault(false)

private const val BROWSER_PROBE_URL = "https://example.com/"
private const val MAX_CHOOSER_INITIAL_INTENTS = 2
private const val CHOSEN_APP_REQUEST_CODE = 0x59_54_43
