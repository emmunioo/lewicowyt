package pl.lewicowyt.notifier.images

import java.io.File
import java.security.MessageDigest

/**
 * Nazwy obrazów wynikają z ich zawartości, a nie z adresu URL. Dzięki temu
 * różne filmy korzystające z identycznej miniatury wskazują ten sam plik.
 */
internal object ImageContentAddressing {
    fun urlKey(url: String): String = sha256(url.toByteArray(Charsets.UTF_8))

    fun contentKey(bytes: ByteArray): String = sha256(bytes)

    fun referenceFile(referenceDirectory: File, urlKey: String): File =
        File(referenceDirectory, "$urlKey.ref")

    fun contentFile(contentDirectory: File, contentKey: String, extension: String): File =
        File(contentDirectory, "$contentKey.$extension")

    fun readReference(file: File): String? {
        if (!file.isFile || file.length() !in DIGEST_LENGTH.toLong()..MAX_REFERENCE_BYTES) {
            return null
        }
        return runCatching {
            file.readText(Charsets.US_ASCII).trim().lowercase()
        }.getOrNull()?.takeIf(::isValidKey)
    }

    fun referencedContentKeys(referenceDirectory: File): Set<String> =
        referenceDirectory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.extension.equals("ref", ignoreCase = true) }
            .mapNotNull(::readReference)
            .toSet()

    fun isValidKey(value: String): Boolean =
        value.length == DIGEST_LENGTH && value.all { it in LOWER_HEX }

    private fun sha256(bytes: ByteArray): String = MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private const val DIGEST_LENGTH = 64
    private const val MAX_REFERENCE_BYTES = 66L
    private const val LOWER_HEX = "0123456789abcdef"
}
