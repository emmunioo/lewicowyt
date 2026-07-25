package pl.lewicowyt.notifier

import android.util.Log

internal object AppLog {
    fun warning(tag: String, message: String, error: Throwable) {
        Log.w(tag, message, error)
    }

    fun error(tag: String, message: String, error: Throwable) {
        Log.e(tag, message, error)
    }
}
