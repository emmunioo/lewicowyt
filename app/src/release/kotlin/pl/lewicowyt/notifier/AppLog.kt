package pl.lewicowyt.notifier

import pl.lewicowyt.notifier.diagnostics.DiagnosticLogStore

/**
 * Publiczne wydanie nadal nie zapisuje szczegółów do systemowego logcat.
 * Po świadomym włączeniu ukrytej diagnostyki zapisuje jedynie zredagowane,
 * krótkie zdarzenia wewnątrz prywatnego katalogu aplikacji.
 */
internal object AppLog {
    fun warning(tag: String, message: String, error: Throwable) {
        DiagnosticLogStore.warning(tag, message, error)
    }

    fun error(tag: String, message: String, error: Throwable) {
        DiagnosticLogStore.error(tag, message, error)
    }
}
