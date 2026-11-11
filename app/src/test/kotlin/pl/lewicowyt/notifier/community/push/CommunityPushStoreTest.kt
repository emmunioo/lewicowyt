package pl.lewicowyt.notifier.community.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testy czystej logiki rejestru push (#8/M-1). Bez Androida — magazyn to mapa w pamięci. Sprawdzamy to,
 * czego nie da się zweryfikować bez urządzenia w [CommunityPushManager]: per-serwer instancje i endpointy,
 * rotację (niepowiązywalność) oraz sprzątanie martwych wpisów.
 */
class CommunityPushStoreTest {

    private class MemoryKv : PushKeyValue {
        val map = HashMap<String, String?>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
    }

    private fun store() = CommunityPushStore(MemoryKv())

    @Test
    fun ensureInstanceIsStablePerServerAndDistinctAcrossServers() {
        val s = store()
        val a1 = s.ensureInstance("serverA")
        val a2 = s.ensureInstance("serverA")
        val b = s.ensureInstance("serverB")

        assertEquals("ta sama instancja przy ponownym wywołaniu dla tego samego serwera", a1, a2)
        assertNotEquals("różne serwery dostają różne instancje (sedno M-1)", a1, b)
        assertEquals("serverA", s.serverForInstance(a1))
        assertEquals("serverB", s.serverForInstance(b))
    }

    @Test
    fun endpointsAreIsolatedPerServer() {
        val s = store()
        val ia = s.ensureInstance("serverA")
        val ib = s.ensureInstance("serverB")
        s.setEndpointForInstance(ia, "https://ntfy.example/AAA")
        s.setEndpointForInstance(ib, "https://ntfy.example/BBB")

        assertEquals("https://ntfy.example/AAA", s.endpointForServer("serverA"))
        assertEquals("https://ntfy.example/BBB", s.endpointForServer("serverB"))
        // dwa serwery NIGDY nie widzą tego samego adresu dostarczenia
        assertNotEquals(s.endpointForServer("serverA"), s.endpointForServer("serverB"))
    }

    @Test
    fun rotateInstanceYieldsFreshUnlinkableInstance() {
        val s = store()
        val old = s.ensureInstance("serverA")
        s.setEndpointForInstance(old, "https://ntfy.example/OLD")

        val (returnedOld, fresh) = s.rotateInstance("serverA")
        assertEquals(old, returnedOld)
        assertNotEquals("nowa instancja nie może równać się starej", old, fresh)
        assertEquals("serverA wskazuje teraz nową instancję", fresh, s.instanceForServer("serverA"))
        // stary endpoint wciąż osiągalny po STAREJ instancji (do opóźnionego wyrejestrowania), nie po serwerze
        assertEquals("https://ntfy.example/OLD", s.endpointForInstance(old))
        assertNull("nowa instancja nie ma jeszcze endpointu", s.endpointForServer("serverA"))
    }

    @Test
    fun forgetInstanceClearsEndpointAndMapping() {
        val s = store()
        val i = s.ensureInstance("serverA")
        s.setEndpointForInstance(i, "https://ntfy.example/AAA")

        s.forgetInstance(i)

        assertNull(s.endpointForInstance(i))
        assertNull(s.serverForInstance(i))
        assertNull(s.instanceForServer("serverA"))
    }

    @Test
    fun replaceServersParsesEntriesAndPrunesStaleInstances() {
        val s = store()
        // dwa serwery z instancjami i endpointami
        val ia = s.ensureInstance("serverA")
        val ib = s.ensureInstance("serverB")
        s.setEndpointForInstance(ia, "https://ntfy.example/AAA")
        s.setEndpointForInstance(ib, "https://ntfy.example/BBB")

        // nowy zestaw manifestów zawiera już tylko serverA
        s.replaceServers(
            mapOf("serverA" to CommunityPushRegistry.ServerPush("https://a.example/push/register", null, null)),
        )

        val servers = s.servers()
        assertEquals(setOf("serverA"), servers.keys)
        assertEquals("https://a.example/push/register", servers.getValue("serverA").register)
        // serverA zachowuje instancję i endpoint
        assertEquals(ia, s.instanceForServer("serverA"))
        assertEquals("https://ntfy.example/AAA", s.endpointForServer("serverA"))
        // serverB zniknął z zestawu → jego instancja i endpoint sprzątnięte (brak martwych nonce'ów)
        assertNull(s.instanceForServer("serverB"))
        assertNull(s.endpointForInstance(ib))
    }

    @Test
    fun pendingUnregisterRoundTripsAndClears() {
        val s = store()
        s.addPendingUnregister(
            listOf(
                CommunityPushRegistry.Pending("https://a.example/push/unregister", token1 = "tok", endpoint = null),
                CommunityPushRegistry.Pending("https://b.example/push/unregister", token1 = null, endpoint = "https://ntfy.example/BBB"),
            ),
        )

        val taken = s.takePendingUnregister()
        assertEquals(2, taken.size)
        assertEquals("tok", taken[0].token1)
        assertEquals("https://ntfy.example/BBB", taken[1].endpoint)
        // po odczycie kolejka jest pusta (atomowe take)
        assertTrue(s.takePendingUnregister().isEmpty())
    }
}
