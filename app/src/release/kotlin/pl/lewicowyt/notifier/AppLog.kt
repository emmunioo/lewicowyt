package pl.lewicowyt.notifier

/**
 * Publiczne wydanie nie zapisuje szczegółów błędów sieciowych do systemowego
 * logcat. Stan synchronizacji pozostaje dostępny użytkownikowi w interfejsie.
 */
@Suppress("UNUSED_PARAMETER")
internal object AppLog {
    fun warning(tag: String, message: String, error: Throwable) = Unit

    fun error(tag: String, message: String, error: Throwable) = Unit
}
