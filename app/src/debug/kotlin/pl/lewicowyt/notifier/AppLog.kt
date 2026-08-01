package pl.lewicowyt.notifier

import android.util.Log
import pl.lewicowyt.notifier.diagnostics.DiagnosticLogStore

internal object AppLog {
    fun warning(tag: String, message: String, error: Throwable) {
        Log.w(tag, message, error)
        DiagnosticLogStore.warning(tag, message, error)
    }

    fun error(tag: String, message: String, error: Throwable) {
        Log.e(tag, message, error)
        DiagnosticLogStore.error(tag, message, error)
    }
}
