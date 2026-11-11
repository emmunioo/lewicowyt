package pl.lewicowyt.notifier.community

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CommunityServerCodec (de)serializuje config push do push_json w community_servers. Flaga
 * `requiresNonPrivateTor` MUSI przetrwać round-trip — bo po zimnym starcie/304 UI czyta ją z zapisanego
 * rekordu (offline), żeby zablokować przełącznik powiadomień serwera .onion, gdy „Nieprywatny Tor" jest off.
 */
class CommunityServerCodecTest {

    @Test
    fun `requiresNonPrivateTor survives push json round trip`() {
        val push = CommunityPushConfig(
            registerUrl = "http://abcdefgh234567.onion:2137/register",
            unregisterUrl = "http://abcdefgh234567.onion:2137/unregister",
            fcm = CommunityFcmConfig("proj", "app", "key", "sender"),
            requiresNonPrivateTor = true,
        )
        val restored = CommunityServerCodec.pushFromJson(CommunityServerCodec.pushToJson(push))
        assertEquals(push, restored)
        assertTrue(restored!!.requiresNonPrivateTor)
    }

    @Test
    fun `requiresNonPrivateTor defaults to false when absent`() {
        val push = CommunityPushConfig(
            registerUrl = "https://serwer.example:2137/register",
            unregisterUrl = null,
            fcm = null,
        )
        val json = CommunityServerCodec.pushToJson(push)
        // nie zaśmiecamy JSON-a domyślną wartością (sprawdzamy KLUCZ, a nie podciąg — sekcja 7 audytu)
        assertFalse(org.json.JSONObject(json!!).has("nt"))
        val restored = CommunityServerCodec.pushFromJson(json)
        assertEquals(push, restored)
        assertFalse(restored!!.requiresNonPrivateTor)
    }

    @Test
    fun `blank json yields null push`() {
        assertNull(CommunityServerCodec.pushFromJson(null))
        assertNull(CommunityServerCodec.pushFromJson("{}")) // brak register = brak configu
    }
}
