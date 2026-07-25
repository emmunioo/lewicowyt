package pl.lewicowyt.notifier.data

internal data class DataRetentionCutoffs(
    val historyBeforeMillis: Long,
    val notificationsBeforeMillis: Long,
)

internal object DataRetentionPolicy {
    const val HISTORY_DAYS = 60
    const val NOTIFICATION_DAYS = 14
    const val DAY_MILLIS = 24L * 60L * 60L * 1_000L

    fun cutoffs(nowMillis: Long): DataRetentionCutoffs = DataRetentionCutoffs(
        historyBeforeMillis = nowMillis - HISTORY_DAYS * DAY_MILLIS,
        notificationsBeforeMillis = nowMillis - NOTIFICATION_DAYS * DAY_MILLIS,
    )
}
