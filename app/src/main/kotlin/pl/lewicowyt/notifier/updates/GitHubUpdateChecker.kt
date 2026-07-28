package pl.lewicowyt.notifier.updates

import java.io.IOException
import java.math.BigInteger
import java.net.URI
import org.json.JSONArray
import pl.lewicowyt.notifier.network.HttpTextClient

sealed interface UpdateCheckResult {
    data object NotConfigured : UpdateCheckResult
    data class UpToDate(val latestVersion: String) : UpdateCheckResult
    data class Available(val update: AvailableUpdate) : UpdateCheckResult
}

enum class UpdatePolicy {
    OPTIONAL,
    MANDATORY_SECURITY_UPDATE,
    SECURITY_ROLLBACK,
}

data class AvailableUpdate(
    val version: String,
    val releasePageUrl: String,
    val apkDownloadUrl: String,
    val apkName: String,
    val sha256Digest: String?,
    val releaseNotes: String,
    val policy: UpdatePolicy = UpdatePolicy.OPTIONAL,
)

class GitHubUpdateChecker(
    private val http: HttpTextClient,
    private val repository: String,
) {
    fun check(currentVersion: String): UpdateCheckResult {
        val normalizedRepository = repository.trim().trim('/')
        if (!REPOSITORY_PATTERN.matches(normalizedRepository)) {
            return UpdateCheckResult.NotConfigured
        }

        val json = try {
            http.getText(
                // /latest pomija wydania oznaczone przez GitHub jako prerelease.
                // Lista pozwala becie wykrywać kolejne bety i późniejsze stable.
                url = "https://api.github.com/repos/$normalizedRepository/releases?per_page=100",
                maxChars = 4_000_000,
                headers = mapOf("Accept" to "application/vnd.github+json"),
            )
        } catch (error: IOException) {
            if (error.message?.contains("HTTP 404") == true) {
                throw IllegalStateException(
                    "Repozytorium nie ma jeszcze opublikowanego GitHub Release.",
                    error,
                )
            }
            throw error
        }
        val releases = JSONArray(json)
        return selectUpdateResultFromReleases(
            releases = releases,
            currentVersion = currentVersion,
            repository = normalizedRepository,
        )
    }

    private companion object {
        val REPOSITORY_PATTERN = Regex("""[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+""")
    }
}

internal data class ReleaseCandidate(
    val version: String,
    val update: AvailableUpdate,
)

private data class ParsedVersion(
    val core: List<BigInteger>,
    val prerelease: List<String>,
)

internal fun compareReleaseVersions(left: String, right: String): Int {
    val leftVersion = parseReleaseVersion(left)
    val rightVersion = parseReleaseVersion(right)
    val maxCoreSize = maxOf(leftVersion.core.size, rightVersion.core.size)
    for (index in 0 until maxCoreSize) {
        val leftValue = leftVersion.core.getOrElse(index) { BigInteger.ZERO }
        val rightValue = rightVersion.core.getOrElse(index) { BigInteger.ZERO }
        if (leftValue != rightValue) return leftValue.compareTo(rightValue)
    }

    if (leftVersion.prerelease.isEmpty() && rightVersion.prerelease.isEmpty()) return 0
    if (leftVersion.prerelease.isEmpty()) return 1
    if (rightVersion.prerelease.isEmpty()) return -1

    val maxPrereleaseSize = maxOf(
        leftVersion.prerelease.size,
        rightVersion.prerelease.size,
    )
    for (index in 0 until maxPrereleaseSize) {
        val leftPart = leftVersion.prerelease.getOrNull(index) ?: return -1
        val rightPart = rightVersion.prerelease.getOrNull(index) ?: return 1
        val leftNumeric = leftPart.all(Char::isDigit)
        val rightNumeric = rightPart.all(Char::isDigit)
        val comparison = when {
            leftNumeric && rightNumeric ->
                BigInteger(leftPart).compareTo(BigInteger(rightPart))
            leftNumeric -> -1
            rightNumeric -> 1
            else -> leftPart.compareTo(rightPart)
        }
        if (comparison != 0) return comparison
    }
    return 0
}

private fun parseReleaseVersion(rawVersion: String): ParsedVersion {
    val normalized = rawVersion.trim()
        .removePrefix("v")
        .removePrefix("V")
        .substringBefore('+')
    val coreText = normalized.substringBefore('-')
    val prereleaseText = normalized.substringAfter('-', missingDelimiterValue = "")
    require(coreText.matches(Regex("""\d+(?:\.\d+)*"""))) {
        "Nieprawidłowy numer wersji GitHub Release: $rawVersion"
    }
    val prerelease = if (prereleaseText.isEmpty()) {
        emptyList()
    } else {
        require(prereleaseText.matches(Regex("""[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*"""))) {
            "Nieprawidłowa wersja wstępna GitHub Release: $rawVersion"
        }
        prereleaseText.split('.')
    }
    return ParsedVersion(
        core = coreText.split('.').map(::BigInteger),
        prerelease = prerelease,
    )
}

internal fun selectNewestInstallableRelease(
    releases: JSONArray,
    currentVersion: String,
    repository: String,
): ReleaseCandidate? =
    installableReleaseCandidates(
        releases = releases,
        repository = repository,
        includePrereleases = currentVersionAllowsPrereleases(currentVersion),
    )
        .maxWithOrNull { left, right ->
            compareReleaseVersions(left.version, right.version)
        }

internal fun selectUpdateResultFromReleases(
    releases: JSONArray,
    currentVersion: String,
    repository: String,
): UpdateCheckResult {
    val allCandidates = installableReleaseCandidates(
        releases = releases,
        repository = repository,
        includePrereleases = true,
    )
    if (allCandidates.isEmpty()) {
        throw IllegalStateException(
            "Repozytorium nie ma publicznego wydania z poprawnym plikiem APK.",
        )
    }

    val currentReleaseExists = allCandidates.any {
        compareReleaseVersions(it.version, currentVersion) == 0
    }
    val candidates = if (currentReleaseExists) {
        installableReleaseCandidates(
            releases = releases,
            repository = repository,
            includePrereleases = currentVersionAllowsPrereleases(currentVersion),
        )
    } else {
        // Wycofanie wydania jest trybem awaryjnym. Wtedy instalacja stabilna
        // może otrzymać również jawnie oznaczoną wersję ratunkową prerelease.
        allCandidates
    }
    val newerRelease = candidates
        .filter { compareReleaseVersions(it.version, currentVersion) > 0 }
        .maxWithOrNull { left, right ->
            compareReleaseVersions(left.version, right.version)
        }

    if (currentReleaseExists) {
        return newerRelease
            ?.let { UpdateCheckResult.Available(it.update) }
            ?: UpdateCheckResult.UpToDate(currentVersion)
    }

    // Brak bieżącego wydania z APK jest sygnałem awaryjnym. Jeśli istnieje
    // nowsza wersja, jest bezpieczniejszym celem niż cofnięcie. Gdy jej nie ma,
    // wybieramy najnowsze starsze wydanie. Android zaakceptuje taki rollback
    // wyłącznie wtedy, gdy awaryjny APK ma wyższy techniczny versionCode.
    if (newerRelease != null) {
        return UpdateCheckResult.Available(
            newerRelease.update.copy(policy = UpdatePolicy.MANDATORY_SECURITY_UPDATE),
        )
    }

    val rollback = candidates
        .filter { compareReleaseVersions(it.version, currentVersion) < 0 }
        .maxWithOrNull { left, right ->
            compareReleaseVersions(left.version, right.version)
        }
        ?: throw IllegalStateException(
            "Bieżące wydanie zostało wycofane, ale nie ma awaryjnego APK.",
        )
    return UpdateCheckResult.Available(
        rollback.update.copy(policy = UpdatePolicy.SECURITY_ROLLBACK),
    )
}

private fun installableReleaseCandidates(
    releases: JSONArray,
    repository: String,
    includePrereleases: Boolean,
): List<ReleaseCandidate> {
    return (0 until releases.length())
        .mapNotNull { index ->
            val release = releases.optJSONObject(index) ?: return@mapNotNull null
            runCatching {
                if (release.optBoolean("draft", false)) return@runCatching null
                val rawTag = release.optString("tag_name").trim()
                if (rawTag.length !in 1..MAX_VERSION_CHARS) return@runCatching null
                val version = rawTag.removePrefix("v").removePrefix("V")
                val parsedVersion = parseReleaseVersion(version)
                if (
                    !includePrereleases &&
                    (release.optBoolean("prerelease", false) ||
                        parsedVersion.prerelease.isNotEmpty())
                ) {
                    return@runCatching null
                }

                val assets = release.optJSONArray("assets") ?: return@runCatching null
                val apk = (0 until assets.length())
                    .mapNotNull(assets::optJSONObject)
                    .firstOrNull { asset ->
                        val name = asset.optString("name").trim()
                        name.length in 1..MAX_ASSET_NAME_CHARS &&
                            name.endsWith(".apk", ignoreCase = true)
                    }
                    ?: return@runCatching null
                val apkName = apk.optString("name").trim()
                val releasePageUrl = requireSafeGitHubReleaseUrl(
                    value = release.getString("html_url"),
                    repository = repository,
                )
                val apkDownloadUrl = requireSafeGitHubReleaseUrl(
                    value = apk.getString("browser_download_url"),
                    repository = repository,
                )
                ReleaseCandidate(
                    version = version,
                    update = AvailableUpdate(
                        version = version,
                        releasePageUrl = releasePageUrl,
                        apkDownloadUrl = apkDownloadUrl,
                        apkName = apkName,
                        sha256Digest = apk.optString("digest")
                            .takeIf { it.startsWith("sha256:") }
                            ?.removePrefix("sha256:")
                            ?.takeIf(SHA256::matches),
                        releaseNotes = release.optString("body")
                            .trim()
                            .take(MAX_RELEASE_NOTES_CHARS),
                    ),
                )
            }.getOrNull()
        }
}

private fun currentVersionAllowsPrereleases(currentVersion: String): Boolean =
    runCatching { parseReleaseVersion(currentVersion).prerelease.isNotEmpty() }
        .getOrDefault(false)

private val SHA256 = Regex("""[a-fA-F0-9]{64}""")
private const val MAX_VERSION_CHARS = 100
private const val MAX_ASSET_NAME_CHARS = 200
private const val MAX_RELEASE_NOTES_CHARS = 20_000

internal fun requireSafeGitHubReleaseUrl(value: String, repository: String): String {
    val uri = runCatching { URI(value) }.getOrNull()
    require(
        uri != null &&
            uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals("github.com", ignoreCase = true) &&
            uri.userInfo == null &&
            uri.port in setOf(-1, 443),
    ) {
        "GitHub zwrócił niebezpieczny adres wydania"
    }
    val expectedPrefix = "/${repository.trim().trim('/')}/"
    require(uri.path.startsWith(expectedPrefix, ignoreCase = true)) {
        "Adres wydania nie należy do skonfigurowanego repozytorium"
    }
    return uri.toASCIIString()
}
