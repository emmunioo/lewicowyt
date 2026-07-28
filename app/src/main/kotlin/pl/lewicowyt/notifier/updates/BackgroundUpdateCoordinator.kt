package pl.lewicowyt.notifier.updates

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import pl.lewicowyt.notifier.BuildConfig
import pl.lewicowyt.notifier.data.AppSettings
import pl.lewicowyt.notifier.data.PreferencesRepository

class BackgroundUpdateCoordinator(
    private val preferences: PreferencesRepository,
    private val checker: GitHubUpdateChecker,
    private val manager: AppUpdateManager,
) {
    suspend fun checkAfterYouTubeSync(settings: AppSettings) {
        if (!preferences.reserveBackgroundUpdateCheck(MIN_BACKGROUND_CHECK_INTERVAL_MILLIS)) {
            return
        }

        try {
            when (val result = checker.check(BuildConfig.VERSION_NAME)) {
                UpdateCheckResult.NotConfigured,
                is UpdateCheckResult.UpToDate,
                -> Unit

                is UpdateCheckResult.Available -> {
                    val mandatory = result.update.policy != UpdatePolicy.OPTIONAL
                    if (settings.automaticUpdatesEnabled || mandatory) {
                        try {
                            val prepared = manager.prepare(result.update)
                            manager.notifyReady(prepared)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            if (mandatory) {
                                manager.notifyMandatoryFailure(
                                    error.message ?: error.javaClass.simpleName,
                                )
                            }
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Zwykła awaria GitHuba nie może oznaczać awarii synchronizacji
            // YouTube ani powodować dodatkowych alarmów i zużycia baterii.
        }
    }

    private companion object {
        val MIN_BACKGROUND_CHECK_INTERVAL_MILLIS = TimeUnit.HOURS.toMillis(2)
    }
}
