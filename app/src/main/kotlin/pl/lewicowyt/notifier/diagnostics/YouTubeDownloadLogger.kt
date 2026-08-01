package pl.lewicowyt.notifier.diagnostics

internal enum class DiagnosticDownloadArea(
    val label: String,
    val category: DiagnosticCategory,
) {
    HISTORY("historia", DiagnosticCategory.HISTORY),
    NOTIFICATIONS("powiadomienia", DiagnosticCategory.SYNC),
}

internal enum class DiagnosticYouTubeSource(val label: String) {
    RSS("YouTube RSS"),
    WEB("YouTube Web"),
    DATA_API("YouTube Data API v3"),
}

internal enum class DiagnosticDownloadRole(val label: String) {
    ITEMS("materiały"),
    CLASSIFICATION("klasyfikacja"),
}

/**
 * Rejestruje wyłącznie publiczne identyfikatory materiałów. Link youtu.be nie
 * ma parametrów zapytania, więc filtr sekretów nie usuwa z niego videoId.
 * Czas pobrania do sekundy zapisuje sam DiagnosticLogStore.
 */
internal fun logYouTubeDownload(
    area: DiagnosticDownloadArea,
    source: DiagnosticYouTubeSource,
    videoIds: Iterable<String>,
    role: DiagnosticDownloadRole = DiagnosticDownloadRole.ITEMS,
) {
    val links = videoIds
        .mapNotNull(::diagnosticYouTubeVideoUrl)
        .distinct()
    DiagnosticLogStore.info(
        area.category,
        "Pobranie zakończone | cel=${area.label} | źródło=${source.label} | " +
            "rola=${role.label} | liczba=${links.size}",
    )
    links.forEach { link ->
        DiagnosticLogStore.info(
            area.category,
            "Pobrano | cel=${area.label} | źródło=${source.label} | " +
                "rola=${role.label} | $link",
        )
    }
}

internal fun diagnosticYouTubeVideoUrl(videoId: String): String? =
    videoId.takeIf(YOUTUBE_VIDEO_ID::matches)?.let { "https://youtu.be/$it" }

private val YOUTUBE_VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
