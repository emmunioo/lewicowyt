package pl.lewicowyt.notifier.data

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal const val MAX_YOUTUBE_API_KEY_CHARS = 256

/**
 * Przechowuje klucz YouTube API zaszyfrowany kluczem AES, którego materiał
 * kryptograficzny pozostaje w Android Keystore i nie trafia do plików aplikacji.
 */
// Zapis musi zakończyć się synchronicznie, zanim DataStore oznaczy klucz jako
// aktywny lub wyłączony. apply() nie pozwala wykryć błędu trwałego zapisu.
@SuppressLint("ApplySharedPref")
internal class SecureApiKeyStore(context: Context) {
    private val preferences = context.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    @Synchronized
    fun read(): String {
        val encoded = preferences.getString(ENCRYPTED_API_KEY, null)
            ?.takeIf {
                it.startsWith(FORMAT_PREFIX) &&
                    it.length <= MAX_ENCODED_SECRET_CHARS
            }
            ?: return ""
        return runCatching {
            val payload = Base64.decode(encoded.removePrefix(FORMAT_PREFIX), Base64.NO_WRAP)
            require(payload.size > IV_LENGTH_BYTES)
            val iv = payload.copyOfRange(0, IV_LENGTH_BYTES)
            val ciphertext = payload.copyOfRange(IV_LENGTH_BYTES, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateSecretKey(),
                GCMParameterSpec(TAG_LENGTH_BITS, iv),
            )
            cipher.updateAAD(ADDITIONAL_AUTHENTICATED_DATA)
            String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
                .trim()
                .takeIf { it.length <= MAX_YOUTUBE_API_KEY_CHARS }
                .orEmpty()
        }.getOrDefault("")
    }

    @Synchronized
    fun write(value: String) {
        val normalized = value.trim()
        require(normalized.length <= MAX_YOUTUBE_API_KEY_CHARS) {
            "Klucz API jest zbyt długi"
        }
        if (normalized.isEmpty()) {
            clear()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        cipher.updateAAD(ADDITIONAL_AUTHENTICATED_DATA)
        val ciphertext = cipher.doFinal(normalized.toByteArray(StandardCharsets.UTF_8))
        val payload = cipher.iv + ciphertext
        preferences.edit()
            .putString(
                ENCRYPTED_API_KEY,
                FORMAT_PREFIX + Base64.encodeToString(payload, Base64.NO_WRAP),
            )
            .commit()
            .also { saved -> check(saved) { "Nie udało się zapisać zaszyfrowanego klucza API" } }
    }

    @Synchronized
    fun clear() {
        check(preferences.edit().remove(ENCRYPTED_API_KEY).commit()) {
            "Nie udało się usunąć zaszyfrowanego klucza API"
        }
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFERENCES_NAME = "lewicowyt_secrets"
        const val ENCRYPTED_API_KEY = "youtube_api_key"
        const val KEY_ALIAS = "lewicowyt.youtube_api_key.v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FORMAT_PREFIX = "v1:"
        const val IV_LENGTH_BYTES = 12
        const val TAG_LENGTH_BITS = 128
        const val MAX_ENCODED_SECRET_CHARS = 1_024
        val ADDITIONAL_AUTHENTICATED_DATA =
            "pl.lewicowyt.notifier:youtube-api-key".toByteArray(StandardCharsets.UTF_8)
    }
}
