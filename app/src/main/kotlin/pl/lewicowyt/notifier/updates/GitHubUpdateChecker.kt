package pl.lewicowyt.notifier.updates

import java.io.IOException
import java.math.BigInteger
import java.net.URI
import org.json.JSONArray
import pl.lewicowyt.notifier.network.HttpTextClient
import pl.lewicowyt.notifier.diagnostics.DiagnosticCategory
import pl.lewicowyt.notifier.diagnostics.DiagnosticLevel
import pl.lewicowyt.notifier.diagnostics.DiagnosticLogStore

sealed interface UpdateCheckResult {
    data object NotConfigured : UpdateCheckResult
    data class UpToDate(val latestVersion: String) : UpdateCheckResult
    data class Available(val update: AvailableUpdate) : UpdateCheckResult
}

enum class UpdatePolicy {
    OPTIONAL,
    MANDATORY_SECURITY_UPDATE,
}

data class AvailableUpdate(
    val version: String,
    val releasePageUrl: String,
    val apkDownloadUrl: String,
    val apkName: String,
    val apkSizeBytes: Long?,
    val sha256Digest: String?,
    val releaseNotes: String,
    val policy: UpdatePolicy = UpdatePolicy.OPTIONAL,
    val releaseAssets: Map<String, ReleaseAsset> = emptyMap(),
)

data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val sha256Digest: String?,
)

class GitHubUpdateChecker(
    private val http: HttpTextClient,
    private val repository: String,
) {
    fun check(currentVersion: String): UpdateCheckResult {
        DiagnosticLogStore.event(
            DiagnosticCategory.UPDATE,
            DiagnosticLevel.INFO,
            "UPDATE_CHECK_START",
            fields = mapOf("currentVersion" to currentVersion),
        )
        val normalizedRepository = repository.trim().trim('/')
        if (!REPOSITORY_PATTERN.matches(normalizedRepository)) {
            return UpdateCheckResult.NotConfigured
        }

        val json = try {
            http.getText(
                // /latest pomija to, co GitHub oznaczył jako prerelease – lista
                // pozwala becie łapać kolejne bety i późniejsze stable
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
        val result = selectUpdateResultFromReleases(
            releases = releases,
            currentVersion = currentVersion,
            repository = normalizedRepository,
        )
        when (result) {
            is UpdateCheckResult.Available -> DiagnosticLogStore.event(
                DiagnosticCategory.UPDATE,
                DiagnosticLevel.INFO,
                "UPDATE_FOUND",
                fields = mapOf(
                    "version" to result.update.version,
                    "policy" to result.update.policy.name,
                ),
            )
            is UpdateCheckResult.UpToDate -> DiagnosticLogStore.event(
                DiagnosticCategory.UPDATE,
                DiagnosticLevel.INFO,
                "UPDATE_UP_TO_DATE",
                fields = mapOf("version" to result.latestVersion),
            )
            UpdateCheckResult.NotConfigured -> Unit
        }
        return result
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
    supportedAbis: List<String> = deviceSupportedAbis(),
): ReleaseCandidate? =
    installableReleaseCandidates(
        releases = releases,
        repository = repository,
        includePrereleases = currentVersionAllowsPrereleases(currentVersion),
        supportedAbis = supportedAbis,
    )
        .maxWithOrNull { left, right ->
            compareReleaseVersions(left.version, right.version)
        }

internal fun selectUpdateResultFromReleases(
    releases: JSONArray,
    currentVersion: String,
    repository: String,
    supportedAbis: List<String> = deviceSupportedAbis(),
): UpdateCheckResult {
    val allCandidates = installableReleaseCandidates(
        releases = releases,
        repository = repository,
        includePrereleases = true,
        supportedAbis = supportedAbis,
    )
    if (allCandidates.isEmpty()) {
        throw IllegalStateException(
            "Repozytorium nie ma publicznego wydania z poprawnym plikiem APK.",
        )
    }

    // M-11: proste i odporne na fałszywe alarmy — bierzemy NAJNOWSZE instalowalne wydanie nowsze niż bieżąca
    // wersja (GitHub zwraca najnowsze pierwsze, więc strona 1 wystarcza — paginacja zbędna). Gdy nic nowszego
    // nie ma (np. lokalna beta wyprzedza wydania albo repo ma >100 wydań, a to i tak najnowsze) → UpToDate.
    // Nie wnioskujemy już trybu awaryjnego z „bieżące wydanie nie istnieje na liście" (źródło błędnych rollbacków).
    val candidates = installableReleaseCandidates(
        releases = releases,
        repository = repository,
        includePrereleases = currentVersionAllowsPrereleases(currentVersion),
        supportedAbis = supportedAbis,
    )
    val newerRelease = candidates
        .filter { compareReleaseVersions(it.version, currentVersion) > 0 }
        .maxWithOrNull { left, right ->
            compareReleaseVersions(left.version, right.version)
        }
    // policy (OPTIONAL / MANDATORY) jest już wyznaczone per wydanie z JAWNEGO markera w opisie (patrz
    // installableReleaseCandidates) — tu tylko wybieramy cel.
    return newerRelease
        ?.let { UpdateCheckResult.Available(it.update) }
        ?: UpdateCheckResult.UpToDate(currentVersion)
}

/** Lista ABI urządzenia wg preferencji; BEZPIECZNIE (w czystym teście JVM Build.SUPPORTED_ABIS rzuca). */
internal fun deviceSupportedAbis(): List<String> =
    runCatching { android.os.Build.SUPPORTED_ABIS?.toList() }.getOrNull().orEmpty()

/** Tokeny nazw APK dla danego ABI (dopasowanie …-arm64.apk / …-armv7.apk / …-x64.apk). */
private fun abiNameTokens(abi: String): List<String> = when (abi.lowercase()) {
    "arm64-v8a" -> listOf("arm64", "aarch64")
    "armeabi-v7a" -> listOf("armv7", "armeabi-v7a", "armeabi")
    "x86_64" -> listOf("x86_64", "x64")
    "x86" -> listOf("x86")
    else -> emptyList()
}

/**
 * Czy pojedynczy APK nadaje się dla urządzenia: nazwa bez tokenu ABI = uniwersalny; z tokenem — musi to być
 * ABI z listy urządzenia. Pusta lista ABI (test JVM) niczego nie wyklucza.
 */
internal fun isApkCompatibleWithAbis(apkName: String, supportedAbis: List<String>): Boolean {
    if (supportedAbis.isEmpty()) return true
    val allTokens = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86").flatMap(::abiNameTokens).distinct()
    // dłuższe tokeny najpierw — „x86_64” nie może zostać rozpoznane jako „x86”
    val named = allTokens.sortedByDescending { it.length }.firstOrNull { apkName.contains(it, ignoreCase = true) }
        ?: return true
    return supportedAbis.any { abi -> abiNameTokens(abi).any { it.equals(named, ignoreCase = true) } }
}

/** Pierwszy APK pasujący do NAJBARDZIEJ preferowanego ABI urządzenia (kolejność [supportedAbis]). */
private fun selectApkForAbis(apks: List<ReleaseAsset>, supportedAbis: List<String>): ReleaseAsset? {
    for (abi in supportedAbis) {
        val tokens = abiNameTokens(abi)
        apks.firstOrNull { a -> tokens.any { a.name.contains(it, ignoreCase = true) } }?.let { return it }
    }
    return null
}

private fun installableReleaseCandidates(
    releases: JSONArray,
    repository: String,
    includePrereleases: Boolean,
    supportedAbis: List<String>,
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
                val releaseAssets = (0 until assets.length())
                    .mapNotNull(assets::optJSONObject)
                    .mapNotNull { asset ->
                        val name = asset.optString("name").trim()
                        val size = asset.optLong("size")
                        if (!isSafeReleaseAssetName(name) || size !in 1..MAX_RELEASE_ASSET_BYTES) {
                            return@mapNotNull null
                        }
                        val downloadUrl = requireSafeGitHubReleaseUrl(
                            value = asset.getString("browser_download_url"),
                            repository = repository,
                        )
                        ReleaseAsset(
                            name = name,
                            downloadUrl = downloadUrl,
                            sizeBytes = size,
                            sha256Digest = asset.optString("digest")
                                .takeIf { it.startsWith("sha256:") }
                                ?.removePrefix("sha256:")
                                ?.takeIf(SHA256::matches)
                                ?.lowercase(),
                        )
                    }
                    .associateBy(ReleaseAsset::name)
                // F-16: wydanie ma osobne APK per ABI (…-arm.apk / …-x64.apk). Wybierz pasujący do
                // urządzenia — inaczej instalacja padnie z INSTALL_FAILED_NO_MATCHING_ABIS. Dla wydań z
                // jednym (uniwersalnym) APK bierzemy go wprost; przy wielu, gdy żaden nie pasuje do ABI,
                // NIE instalujemy złego (zwracamy null).
                val apks = releaseAssets.values.filter { asset ->
                    asset.name.endsWith(".apk", ignoreCase = true) && asset.sizeBytes <= MAX_APK_SIZE_BYTES
                }
                val apk = when {
                    apks.isEmpty() -> return@runCatching null
                    // jeden APK: bierzemy wprost, CHYBA że jego nazwa wskazuje ABI, którego urządzenie nie obsługuje
                    // (B-90: np. tylko „…-arm64.apk” na telefonie armv7 = pobranie na darmo i błąd instalacji)
                    apks.size == 1 -> apks.first().takeIf { isApkCompatibleWithAbis(it.name, supportedAbis) }
                        ?: return@runCatching null
                    // L-13/O-1: wiele APK per ABI — wybieramy pasujący do NAJBARDZIEJ preferowanego ABI, które
                    // urządzenie faktycznie wspiera (kolejność z SUPPORTED_ABIS). Nigdy nie wybieramy APK dla
                    // ABI spoza listy urządzenia (np. arm64 na telefonie 32-bit = INSTALL_FAILED_NO_MATCHING_ABIS).
                    // gdy brak pliku dla ABI urządzenia — APK uniwersalny (…-universal.apk, bez tokenu ABI)
                    else -> selectApkForAbis(apks, supportedAbis)
                        ?: apks.firstOrNull { isApkCompatibleWithAbis(it.name, supportedAbis) }
                        ?: return@runCatching null
                }
                val apkName = apk.name
                val releasePageUrl = requireSafeGitHubReleaseUrl(
                    value = release.getString("html_url"),
                    repository = repository,
                )
                val apkDownloadUrl = apk.downloadUrl
                val body = release.optString("body").trim()
                // M-11: „wymagana" tylko przez JAWNY marker w opisie wydania (autor wydania świadomie go wstawia).
                // Wcześniej tryb awaryjny wywodził się z heurystyki „bieżące wydanie nie istnieje na liście", co
                // dawało fałszywe „Wymagana aktualizacja"/rollback dla niewydanych bet i repo z >100 wydaniami.
                val policy = if (body.contains(MANDATORY_MARKER, ignoreCase = true)) {
                    UpdatePolicy.MANDATORY_SECURITY_UPDATE
                } else {
                    UpdatePolicy.OPTIONAL
                }
                ReleaseCandidate(
                    version = version,
                    update = AvailableUpdate(
                        version = version,
                        releasePageUrl = releasePageUrl,
                        apkDownloadUrl = apkDownloadUrl,
                        apkName = apkName,
                        apkSizeBytes = apk.sizeBytes,
                        sha256Digest = apk.sha256Digest,
                        releaseNotes = body.take(MAX_RELEASE_NOTES_CHARS),
                        policy = policy,
                        releaseAssets = releaseAssets,
                    ),
                )
            }.getOrNull()
        }
}

private fun currentVersionAllowsPrereleases(currentVersion: String): Boolean =
    runCatching { parseReleaseVersion(currentVersion).prerelease.isNotEmpty() }
        .getOrDefault(false)

private val SHA256 = Regex("""[a-fA-F0-9]{64}""")
// M-11: JAWNY marker w opisie wydania oznaczający aktualizację wymaganą (bezpieczeństwa). Tylko on włącza
// tryb wymagany — bez niego każda nowsza wersja jest aktualizacją OPCJONALNĄ (żadnych fałszywych alarmów).
private const val MANDATORY_MARKER = "[security-mandatory]"
private const val MAX_VERSION_CHARS = 100
private const val MAX_ASSET_NAME_CHARS = 200
private const val MAX_RELEASE_NOTES_CHARS = 20_000
private const val MAX_APK_SIZE_BYTES = 200L * 1024L * 1024L
private const val MAX_RELEASE_ASSET_BYTES = 200L * 1024L * 1024L

internal fun isSafeReleaseAssetName(value: String): Boolean =
    value.length in 1..MAX_ASSET_NAME_CHARS &&
        !value.contains("..") &&
        '/' !in value &&
        '\\' !in value &&
        value.none(Char::isISOControl) &&
        SAFE_ASSET_NAME.matches(value)

private val SAFE_ASSET_NAME = Regex("""[A-Za-z0-9._-]+""")

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
    // J-11c: „..” (także zakodowane %2e) po normalizacji przez OkHttp mogłoby wyjść poza katalog repozytorium
    val rawPath = uri.rawPath.orEmpty()
    require(".." !in uri.path.orEmpty() && !rawPath.contains("%2e", ignoreCase = true)) {
        "Adres wydania zawiera niedozwoloną ścieżkę"
    }
    require(uri.path.startsWith(expectedPrefix, ignoreCase = true)) {
        "Adres wydania nie należy do skonfigurowanego repozytorium"
    }
    return uri.toASCIIString()
}
