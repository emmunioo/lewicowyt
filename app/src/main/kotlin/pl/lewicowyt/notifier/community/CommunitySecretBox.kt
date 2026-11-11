package pl.lewicowyt.notifier.community

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Opakowanie sekretów społeczności (klucze prywatne E2E, tożsamości) kluczem AES-256-GCM, którego
 * materiał NIGDY nie opuszcza Android Keystore. Dzięki temu w SharedPreferences leży tylko szyfrogram
 * (audyt F-12: wcześniej klucze prywatne były tam jawnym base64 PKCS#8).
 *
 * Format: `v1:` + base64(IV‖ciphertext‖tag). Migracja jest TRANSPARENTNA: wartość bez prefiksu `v1:`
 * traktujemy jako legacy-plaintext i zwracamy jak jest (zostanie opakowana przy najbliższym zapisie).
 */
object CommunitySecretBox {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "lewicowyt.community_secrets.v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PREFIX = "v1:"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    /** Szyfruje tekst kluczem z Keystore i zwraca `v1:base64(iv‖ct)`. */
    fun wrap(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ct = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        return PREFIX + Base64.encodeToString(cipher.iv + ct, Base64.NO_WRAP)
    }

    /**
     * Odszyfrowuje wartość zapisaną przez [wrap]. Wartość BEZ prefiksu `v1:` to legacy-plaintext —
     * zwracamy ją bez zmian (apka zapisze ją już opakowaną przy następnej rotacji/zapisie). null = błąd.
     */
    fun unwrap(stored: String): String? {
        if (!stored.startsWith(PREFIX)) return stored // legacy plaintext (migracja leniwa)
        return runCatching {
            val payload = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            require(payload.size > IV_LEN)
            val iv = payload.copyOfRange(0, IV_LEN)
            val ct = payload.copyOfRange(IV_LEN, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ct), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    // T-03: uchwyt klucza Keystore (sam klucz nigdy nie opuszcza Keystore) pobieramy RAZ na proces. Dawniej każde
    // wrap/unwrap ładowało KeyStore i szukało aliasu od nowa — przy każdym budziku kilka razy.
    @Volatile private var cachedKey: SecretKey? = null

    private fun key(): SecretKey = cachedKey ?: loadOrCreateKey().also { cachedKey = it }

    // L-6: @Synchronized — bez tego dwa wątki przy pierwszym użyciu mogłyby RÓWNOLEGLE wygenerować klucz
    // i nadpisać alias, przez co dane zaszyfrowane pierwszym kluczem stają się nieodszyfrowywalne.
    @Synchronized
    private fun loadOrCreateKey(): SecretKey {
        cachedKey?.let { return it }
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }
}
