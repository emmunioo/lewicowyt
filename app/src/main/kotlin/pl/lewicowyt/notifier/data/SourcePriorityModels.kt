package pl.lewicowyt.notifier.data

/**
 * Początkowy prior przygotowany przed kompilacją. Jest mapowany na lokalny
 * sourceKey, ale sam asset używa stabilnego type + externalId.
 */
data class SourcePrioritySeed(
    val sourceKey: String,
    val priorRatePerDay: Double,
    val priorExposureDays: Double,
)

/**
 * Lokalny stan lekkiego modelu aktywności źródła.
 *
 * eventMass i exposureDays tworzą estymator Gamma–Poisson. Dane pozostają
 * wyłącznie na urządzeniu użytkownika.
 */
data class SourcePriorityStats(
    val sourceKey: String,
    val initialized: Boolean,
    val priorRatePerDay: Double,
    val priorExposureDays: Double,
    val eventMass: Double,
    val exposureDays: Double,
    val lastModelUpdateMillis: Long,
    val lastAttemptMillis: Long,
    val lastSuccessfulCheckMillis: Long,
    val lastHitMillis: Long,
    val consecutiveFailures: Int,
)

data class SourcePriorityUpdate(
    val sourceKey: String,
    val eventMass: Double,
    val exposureDays: Double,
    val lastModelUpdateMillis: Long,
    val lastAttemptMillis: Long,
    val lastHitMillis: Long,
    val consecutiveFailures: Int,
)
