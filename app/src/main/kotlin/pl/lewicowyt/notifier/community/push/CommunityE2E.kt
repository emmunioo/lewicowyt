package pl.lewicowyt.notifier.community.push

import android.content.Context
import android.util.Base64
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject
import pl.lewicowyt.notifier.community.CommunitySecretBox

/**
 * Szyfrowanie E2E budzika (ECIES, P-256). Klucz PRYWATNY apki (per serwer) nie opuszcza urządzenia;
 * serwer dostaje tylko klucz publiczny i szyfruje do niego ładunek. Byte-zgodne z e2e.js:
 *   shared = ECDH(priv, ephPub) → 32 B; key = HKDF-SHA256(shared, salt="", info) ; AES-256-GCM.
 * Klucz publiczny wystawiamy jako punkt nieskompresowany (0x04|X|Y, 65 B), base64.
 */
object CommunityE2E {
    private const val PREFS = "community_push_e2e"
    private const val CURVE = "secp256r1"
    private val INFO = "lewicowyt/push/e2e/v1".toByteArray()

    // Okno współistnienia starego klucza po rotacji: budziki zaszyfrowane do STAREGO pubkey (wysłane
    // zanim serwer przyjął nową rejestrację) wciąż odszyfrowujemy. 15 min > okno opóźnionego
    // wyrejestrowania (do 5 min) z zapasem na zaległe dostarczenia.
    private const val RETIRE_WINDOW_MS = 15L * 60 * 1000

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // A-29: generowanie/rotacja/odczyt kluczy pod JEDNĄ blokadą (sync z UI vs worker) i zapis synchroniczny
    // (commit) — inaczej klucz zwrócony serwerowi mógł się różnić od zapisanego.
    private val lock = Any()

    private fun newPair(): Pair<String, String> {
        val gen = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(CURVE)) }
        val pair = gen.generateKeyPair()
        val w = (pair.public as ECPublicKey).w
        val uncompressed = byteArrayOf(0x04) + fixed(w.affineX, 32) + fixed(w.affineY, 32)
        // F-12: klucz prywatny opakowany kluczem z Keystore (w prefs tylko szyfrogram)
        return Base64.encodeToString(uncompressed, Base64.NO_WRAP) to
            CommunitySecretBox.wrap(Base64.encodeToString(pair.private.encoded, Base64.NO_WRAP))
    }

    private fun ecParams(): ECParameterSpec {
        val ap = AlgorithmParameters.getInstance("EC")
        ap.init(ECGenParameterSpec(CURVE))
        return ap.getParameterSpec(ECParameterSpec::class.java)
    }

    private fun fixed(b: BigInteger, len: Int): ByteArray {
        val a = b.toByteArray()
        if (a.size == len) return a
        val out = ByteArray(len)
        if (a.size > len) System.arraycopy(a, a.size - len, out, 0, len) // odetnij bajt znaku
        else System.arraycopy(a, 0, out, len - a.size, a.size)
        return out
    }

    /** Klucz publiczny apki dla serwera (base64, 65 B). Generuje i zapisuje parę, jeśli brak. */
    fun publicKeyB64(context: Context, serverId: String): String? = try {
        synchronized(lock) {
            val p = prefs(context)
            val key = "pub_$serverId"
            p.getString(key, null) ?: run {
                val (pubB64, wrappedPriv) = newPair()
                val saved = p.edit()
                    .putString(key, pubB64)
                    .putString("priv_$serverId", wrappedPriv)
                    .commit()
                if (saved) pubB64 else null
            }
        }
    } catch (_: Exception) { null }

    /**
     * A-28 (faza 1): przygotowuje NOWĄ parę jako „oczekującą" i zwraca jej pubkey do rejestracji. Bieżący klucz
     * zostaje bez zmian, dopóki serwer nie potwierdzi rejestracji ([commitRotation]); przy porażce
     * [abortRotation]. Oczekujący klucz jest już kandydatem do deszyfracji (serwer mógł przyjąć rejestrację,
     * a odpowiedź zginęła po drodze).
     */
    fun stageRotation(context: Context, serverId: String): String? = try {
        synchronized(lock) {
            val (pubB64, wrappedPriv) = newPair()
            val saved = prefs(context).edit()
                .putString("pending_pub_$serverId", pubB64)
                .putString("pending_priv_$serverId", wrappedPriv)
                .commit()
            if (saved) pubB64 else null
        }
    } catch (_: Exception) { null }

    /** A-28 (faza 2, sukces): oczekujący klucz staje się bieżącym, stary trafia do wycofanych (okno 15 min). */
    fun commitRotation(context: Context, serverId: String) = synchronized(lock) {
        val p = prefs(context)
        val pendingPub = p.getString("pending_pub_$serverId", null) ?: return@synchronized
        val pendingPriv = p.getString("pending_priv_$serverId", null) ?: return@synchronized
        val retired = prunedRetired(p.getString("retired_$serverId", null))
        p.getString("priv_$serverId", null)?.let {
            retired.put(JSONObject().put("priv", it).put("exp", System.currentTimeMillis() + RETIRE_WINDOW_MS))
        }
        p.edit()
            .putString("pub_$serverId", pendingPub)
            .putString("priv_$serverId", pendingPriv)
            .remove("pending_pub_$serverId")
            .remove("pending_priv_$serverId")
            .putString("retired_$serverId", retired.toString())
            .commit()
    }

    /**
     * A-28 (faza 2, porażka): bieżący klucz ZOSTAJE (serwer wciąż go zna). Oczekujący odkładamy do wycofanych
     * na okno 15 min — gdyby serwer jednak przyjął rejestrację, budziki do nowego pubkey nadal odszyfrujemy.
     */
    fun abortRotation(context: Context, serverId: String) = synchronized(lock) {
        val p = prefs(context)
        val pendingPriv = p.getString("pending_priv_$serverId", null)
        val retired = prunedRetired(p.getString("retired_$serverId", null))
        pendingPriv?.let {
            retired.put(JSONObject().put("priv", it).put("exp", System.currentTimeMillis() + RETIRE_WINDOW_MS))
        }
        p.edit()
            .remove("pending_pub_$serverId")
            .remove("pending_priv_$serverId")
            .putString("retired_$serverId", retired.toString())
            .commit()
    }

    /**
     * Identyfikuje serwer-nadawcę budzika: próbuje odszyfrować blob kluczem KAŻDEGO subskrybowanego
     * serwera; zwraca serverId tego, którym się udało (albo null → nadawca nieznany/niesubskrybowany
     * → aplikacja ignoruje budzik). Dzięki temu przy wspólnym token1 rozróżniamy serwery bez jawnych
     * znaczników i nie działamy na powiadomieniach serwerów, których user nie subskrybuje.
     */
    fun identify(context: Context, serverIds: Collection<String>, blobB64: String): String? =
        identifyAndDecrypt(context, serverIds, blobB64)?.first

    /**
     * T-03: identyfikacja nadawcy I odszyfrowanie w JEDNYM przejściu — (serverId, tekst jawny) albo null.
     * Dawniej [identify] odszyfrowywał budzik, by znaleźć nadawcę, a potem [decrypt] robił to samo drugi raz
     * (ponowne odpakowanie kluczy przez Keystore i ponowne ECDH) tylko po to, żeby dostać tekst jawny.
     */
    fun identifyAndDecrypt(context: Context, serverIds: Collection<String>, blobB64: String): Pair<String, String>? {
        for (serverId in serverIds) {
            decrypt(context, serverId, blobB64)?.let { return serverId to it }
        }
        return null
    }

    // wycofane klucze bez wygasłych (JSON [ {priv, exp} ]); zawsze zwraca tablicę (może pustą)
    private fun prunedRetired(raw: String?): JSONArray {
        val arr = runCatching { JSONArray(raw ?: "[]") }.getOrDefault(JSONArray())
        val now = System.currentTimeMillis()
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optLong("exp") > now) out.put(o)
        }
        return out
    }

    // bieżący priv + niewygasłe wycofane privy (base64) — kandydaci do odszyfrowania budzika
    private fun candidatePrivs(context: Context, serverId: String): List<String> = synchronized(lock) {
        val p = prefs(context)
        val retired = prunedRetired(p.getString("retired_$serverId", null))
        // opróżnienie z wygasłych zapisujemy z powrotem (leniwe sprzątanie) — C-22b: tylko gdy coś wygasło
        val pruned = retired.toString()
        if (pruned != (p.getString("retired_$serverId", null) ?: "[]")) p.edit().putString("retired_$serverId", pruned).apply()
        // F-12: wartości w prefs są opakowane Keystore (albo legacy-plaintext) — odpakowujemy do base64 PKCS#8
        buildList {
            p.getString("priv_$serverId", null)?.let { CommunitySecretBox.unwrap(it)?.let(::add) }
            // A-28: oczekujący (rotacja w toku) — serwer mógł już przyjąć nowy pubkey
            p.getString("pending_priv_$serverId", null)?.let { CommunitySecretBox.unwrap(it)?.let(::add) }
            for (i in 0 until retired.length()) {
                val pv = retired.optJSONObject(i)?.optString("priv")?.takeIf { it.isNotBlank() } ?: continue
                CommunitySecretBox.unwrap(pv)?.let(::add)
            }
        }
    }

    /** Odszyfrowuje blob (base64 JSON) do tekstu jawnego albo null (gdy żaden klucz nie pasuje / błąd). */
    fun decrypt(context: Context, serverId: String, blobB64: String): String? =
        candidatePrivs(context, serverId).firstNotNullOfOrNull { decryptWith(it, blobB64) }

    // próba odszyfrowania konkretnym kluczem prywatnym (base64); null gdy nie pasuje/błąd
    private fun decryptWith(privB64: String, blobB64: String): String? = try {
        val priv = KeyFactory.getInstance("EC")
            .generatePrivate(PKCS8EncodedKeySpec(Base64.decode(privB64, Base64.NO_WRAP)))
        val j = JSONObject(String(Base64.decode(blobB64, Base64.NO_WRAP)))
        val epk = Base64.decode(j.getString("epk"), Base64.NO_WRAP)
        val iv = Base64.decode(j.getString("iv"), Base64.NO_WRAP)
        val ct = Base64.decode(j.getString("ct"), Base64.NO_WRAP)
        val tag = Base64.decode(j.getString("tag"), Base64.NO_WRAP)

        val x = BigInteger(1, epk.copyOfRange(1, 33))
        val y = BigInteger(1, epk.copyOfRange(33, 65))
        val ephPub = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), ecParams()))
        val shared = KeyAgreement.getInstance("ECDH").apply { init(priv); doPhase(ephPub, true) }.generateSecret()

        val key = hkdfSha256(shared, ByteArray(32), INFO, 32)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        String(cipher.doFinal(ct + tag))
    } catch (_: Exception) { null }

    // RFC 5869 HKDF-SHA256 (salt pusty -> blok zer, jak w Node crypto.hkdfSync). L <= 32, wiec jeden blok.
    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, len: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(info)
        mac.update(0x01)
        return mac.doFinal().copyOf(len)
    }
}
