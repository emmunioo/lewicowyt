package pl.lewicowyt.notifier.links

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import java.net.URI
import java.util.Locale
import pl.lewicowyt.notifier.data.YouTubeLinkTarget
import pl.lewicowyt.notifier.diagnostics.DiagnosticCategory
import pl.lewicowyt.notifier.diagnostics.DiagnosticLevel
import pl.lewicowyt.notifier.diagnostics.DiagnosticLogStore
import pl.lewicowyt.notifier.diagnostics.DiagnosticReasonCode

internal enum class YouTubeLinkRoute {
    SYSTEM,
    CHOOSER,
    YOUTUBE,
    NEWPIPE,
    BROWSER,
    NONE,
}

internal data class YouTubeLinkAvailability(
    val system: Boolean,
    val youtube: Boolean,
    val newPipe: Boolean,
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
    YouTubeLinkTarget.YOUTUBE -> preferredApplicationPlan(
        available = availability.youtube,
        route = YouTubeLinkRoute.YOUTUBE,
        packageName = YOUTUBE_PACKAGE,
        systemAvailable = availability.system,
    )
    YouTubeLinkTarget.NEWPIPE -> preferredApplicationPlan(
        available = availability.newPipe,
        route = YouTubeLinkRoute.NEWPIPE,
        packageName = NEWPIPE_PACKAGE,
        systemAvailable = availability.system,
    )
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
}

private fun preferredApplicationPlan(
    available: Boolean,
    route: YouTubeLinkRoute,
    packageName: String,
    systemAvailable: Boolean,
): YouTubeLinkPlan = when {
    available -> YouTubeLinkPlan(route, packageName)
    systemAvailable -> YouTubeLinkPlan(
        YouTubeLinkRoute.SYSTEM,
        fallbackReason = DiagnosticReasonCode.APP_NOT_AVAILABLE,
    )
    else -> YouTubeLinkPlan(
        YouTubeLinkRoute.NONE,
        fallbackReason = DiagnosticReasonCode.NO_LINK_HANDLER,
    )
}

class YouTubeLinkLauncher(context: Context) {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager

    fun open(url: String, target: YouTubeLinkTarget): Boolean {
        val uri = url.takeIf(::isSafeYouTubeExternalUrl)?.let(Uri::parse)
        if (uri == null) {
            log(target, "FAILED", DiagnosticReasonCode.INVALID_LINK)
            showNoHandler()
            return false
        }

        val systemIntent = viewIntent(uri)
        val availability = YouTubeLinkAvailability(
            system = canHandle(systemIntent),
            youtube = canHandle(viewIntent(uri, YOUTUBE_PACKAGE)),
            newPipe = canHandle(viewIntent(uri, NEWPIPE_PACKAGE)),
            browserPackage = findBrowserPackage(uri),
        )
        val plan = planYouTubeLinkOpen(target, availability)
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
            fallbackAfterLaunchFailure(target, systemIntent, plan.route)
        } catch (_: SecurityException) {
            fallbackAfterLaunchFailure(target, systemIntent, plan.route)
        }
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
    ): Intent = when (plan.route) {
        YouTubeLinkRoute.SYSTEM -> viewIntent(uri)
        YouTubeLinkRoute.CHOOSER -> createAlwaysAskChooser(
            targetIntent = viewIntent(uri),
            initialIntents = buildList {
                // Android potrafi ukryć te aplikacje za zweryfikowanym deep linkiem YouTube.
                // Jawne kandydatury trafiają wyłącznie do systemowego choosera.
                if (availability.newPipe) add(viewIntent(uri, NEWPIPE_PACKAGE))
                availability.browserPackage?.let { add(viewIntent(uri, it)) }
            },
        )
        YouTubeLinkRoute.YOUTUBE,
        YouTubeLinkRoute.NEWPIPE,
        YouTubeLinkRoute.BROWSER,
        -> viewIntent(uri, requireNotNull(plan.packageName))
        YouTubeLinkRoute.NONE -> error("Brak trasy nie może tworzyć Intentu")
    }

    private fun start(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
    }

    private fun canHandle(intent: Intent): Boolean =
        packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null

    /**
     * Kandydatów na przeglądarkę szukamy neutralnym adresem HTTPS, dzięki czemu
     * aplikacje obsługujące wyłącznie deep linki YouTube nie są kandydatami.
     * Nie zapisujemy ani nie logujemy otrzymanej listy pakietów.
     */
    private fun findBrowserPackage(youtubeUri: Uri): String? {
        val neutralIntent = viewIntent(Uri.parse(BROWSER_PROBE_URL))
        val candidates = packageManager.queryIntentActivities(
            neutralIntent,
            PackageManager.MATCH_DEFAULT_ONLY,
        ).mapNotNull { it.activityInfo?.packageName }
            .distinct()
            .filter { canHandle(viewIntent(youtubeUri, it)) }
        val defaultPackage = packageManager.resolveActivity(
            neutralIntent,
            PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName
            ?.takeUnless { it == "android" }
        return defaultPackage?.takeIf { it in candidates } ?: candidates.singleOrNull()
    }

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
 * Android może domyślnie pominąć chooser, gdy znajdzie tylko jednego kandydata.
 * Wyłączenie automatycznego uruchomienia sprawia, że tryb "Pytaj za każdym razem"
 * rzeczywiście pokazuje systemowy ekran wyboru także w takim przypadku.
 */
internal fun createAlwaysAskChooser(
    targetIntent: Intent,
    initialIntents: List<Intent> = emptyList(),
): Intent =
    Intent.createChooser(targetIntent, "Otwórz link w").apply {
        putExtra(Intent.EXTRA_AUTO_LAUNCH_SINGLE_CHOICE, false)
        if (initialIntents.isNotEmpty()) {
            putExtra(Intent.EXTRA_INITIAL_INTENTS, initialIntents.toTypedArray())
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

private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
private const val NEWPIPE_PACKAGE = "org.schabi.newpipe"
private const val BROWSER_PROBE_URL = "https://example.com/"
