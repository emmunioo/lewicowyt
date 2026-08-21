package pl.lewicowyt.notifier.updates

import android.content.Context

internal class DeltaFailureStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun rejectedFingerprint(): String? = preferences.getString(REJECTED_FINGERPRINT, null)

    fun reject(fingerprint: String) {
        preferences.edit().putString(REJECTED_FINGERPRINT, fingerprint).apply()
    }

    fun clearIfDifferent(fingerprint: String) {
        if (rejectedFingerprint() != null && rejectedFingerprint() != fingerprint) {
            preferences.edit().remove(REJECTED_FINGERPRINT).apply()
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "delta_update_failures"
        const val REJECTED_FINGERPRINT = "rejected_fingerprint"
    }
}
