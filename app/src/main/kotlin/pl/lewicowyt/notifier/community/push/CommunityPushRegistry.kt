package pl.lewicowyt.notifier.community.push

import pl.lewicowyt.notifier.community.AppTerms
import pl.lewicowyt.notifier.community.CommunityDefaults
import android.content.Context
import java.security.SecureRandom
import org.json.JSONArray
import org.json.JSONObject
import pl.lewicowyt.notifier.community.CommunityFcmConfig

/**
 * Minimalny magazyn klucz→wartość, który [CommunityPushStore] czyta/pisze. Produkcyjnie opakowuje
 * `SharedPreferences`; w testach — zwykłą mapę w pamięci. Dzięki temu całą logikę rejestru (instancje,
 * endpointy, rotacja, sprzątanie) można pokryć testami JVM bez Androida/Robolectric.
 */
interface PushKeyValue {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

/**
 * Czysta (bez zależności od Androida) logika rejestru push: mapy serverId→instancja i instancja→endpoint,
 * rotacja instancji (#8/M-1) oraz kolejka opóźnionych wyrejestrowań. Operuje wyłącznie na [PushKeyValue].
 */
class CommunityPushStore(private val kv: PushKeyValue) {

    private fun obj(key: String): JSONObject =
        runCatching { JSONObject(kv.get(key) ?: "{}") }.getOrDefault(JSONObject())

    private fun save(key: String, o: JSONObject) = kv.put(key, o.toString())

    // --- per-serwer instancje UnifiedPush (#8/M-1) ------------------------------------------------

    fun instanceForServer(serverId: String): String? =
        obj(KEY_INSTANCES).optString(serverId).ifBlank { null }

    fun ensureInstance(serverId: String): String {
        instanceForServer(serverId)?.let { return it }
        val id = newInstanceId()
        save(KEY_INSTANCES, obj(KEY_INSTANCES).put(serverId, id))
        return id
    }

    /**
     * Rotacja instancji serwera: nadaje NOWĄ instancję (nowy endpoint od dystrybutora, niepowiązywalny ze
     * starym) i zwraca parę (stara?, nowa). Stary endpoint zostaje do wyrejestrowania przez wołającego.
     */
    fun rotateInstance(serverId: String): Pair<String?, String> {
        val old = instanceForServer(serverId)
        val fresh = newInstanceId()
        save(KEY_INSTANCES, obj(KEY_INSTANCES).put(serverId, fresh))
        return old to fresh
    }

    fun serverForInstance(instance: String): String? {
        val o = obj(KEY_INSTANCES)
        o.keys().forEach { id -> if (o.optString(id) == instance) return id }
        return null
    }

    fun endpointForInstance(instance: String): String? =
        obj(KEY_ENDPOINTS).optString(instance).ifBlank { null }

    fun endpointForServer(serverId: String): String? =
        instanceForServer(serverId)?.let { endpointForInstance(it) }

    fun setEndpointForInstance(instance: String, endpoint: String?) {
        val o = obj(KEY_ENDPOINTS)
        if (endpoint.isNullOrBlank()) o.remove(instance) else o.put(instance, endpoint)
        save(KEY_ENDPOINTS, o)
    }

    /** usuwa instancję (po wyrejestrowaniu/rotacji): czyści jej endpoint i mapowania serverId→ta instancja. */
    fun forgetInstance(instance: String) {
        setEndpointForInstance(instance, null)
        val o = obj(KEY_INSTANCES)
        val toRemove = o.keys().asSequence().filter { o.optString(it) == instance }.toList()
        if (toRemove.isNotEmpty()) {
            toRemove.forEach { o.remove(it) }
            save(KEY_INSTANCES, o)
        }
    }

    fun allInstances(): Map<String, String> {
        val o = obj(KEY_INSTANCES)
        val out = LinkedHashMap<String, String>()
        o.keys().forEach { id -> o.optString(id).ifBlank { null }?.let { out[id] = it } }
        return out
    }

    // --- adresy push per serwer (z manifestu) ----------------------------------------------------

    fun servers(): Map<String, CommunityPushRegistry.ServerPush> {
        val o = runCatching { JSONObject(kv.get(KEY_SERVERS) ?: return emptyMap()) }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, CommunityPushRegistry.ServerPush>()
        o.keys().forEach { id ->
            val so = o.optJSONObject(id) ?: return@forEach
            val r = so.optString("r").ifBlank { null } ?: return@forEach
            val f = so.optJSONObject("fcm")?.let {
                val projectId = it.optString("projectId")
                val appId = it.optString("appId")
                val apiKey = it.optString("apiKey")
                val senderId = it.optString("senderId")
                if (projectId.isBlank() || appId.isBlank() || apiKey.isBlank() || senderId.isBlank()) null
                else CommunityFcmConfig(projectId, appId, apiKey, senderId)
            }
            out[id] = CommunityPushRegistry.ServerPush(r, so.optString("u").ifBlank { null }, f)
        }
        return out
    }

    /**
     * nadpisuje cały zbiór serwerów push (po odświeżeniu manifestów). Instancje/endpointy serwerów, które
     * zniknęły z zestawu, sprzątamy, żeby nie zostawały martwe nonce'y bez odpowiadającego serwera.
     */
    fun replaceServers(entries: Map<String, CommunityPushRegistry.ServerPush>) {
        val o = JSONObject()
        entries.forEach { (id, sp) ->
            val so = JSONObject().put("r", sp.register)
            sp.unregister?.let { so.put("u", it) }
            sp.fcm?.let { so.put("fcm", JSONObject().put("projectId", it.projectId).put("appId", it.appId).put("apiKey", it.apiKey).put("senderId", it.senderId)) }
            o.put(id, so)
        }
        kv.put(KEY_SERVERS, o.toString())
        val keep = entries.keys
        val inst = obj(KEY_INSTANCES)
        val stale = inst.keys().asSequence().filter { it !in keep }.toList()
        if (stale.isNotEmpty()) {
            stale.forEach { srv -> inst.optString(srv).ifBlank { null }?.let { setEndpointForInstance(it, null) }; inst.remove(srv) }
            save(KEY_INSTANCES, inst)
        }
    }

    /** R-03: usuwa serwer z rejestru push (KEY_SERVERS) i zapomina jego instancję/endpoint. */
    fun removeServer(serverId: String) {
        val o = runCatching { JSONObject(kv.get(KEY_SERVERS) ?: "{}") }.getOrDefault(JSONObject())
        if (o.has(serverId)) {
            o.remove(serverId)
            kv.put(KEY_SERVERS, o.toString())
        }
        instanceForServer(serverId)?.let { forgetInstance(it) }
    }

    // --- opóźnione wyrejestrowania ---------------------------------------------------------------

    fun addPendingUnregister(items: List<CommunityPushRegistry.Pending>) {
        if (items.isEmpty()) return
        val arr = runCatching { JSONArray(kv.get(KEY_PENDING_UNREG) ?: "[]") }.getOrDefault(JSONArray())
        items.forEach { p ->
            arr.put(JSONObject().put("url", p.url).apply {
                p.token1?.let { put("token1", it) }
                p.endpoint?.let { put("endpoint", it) }
            })
        }
        kv.put(KEY_PENDING_UNREG, arr.toString())
    }

    fun takePendingUnregister(): List<CommunityPushRegistry.Pending> {
        val raw = kv.get(KEY_PENDING_UNREG) ?: return emptyList()
        kv.put(KEY_PENDING_UNREG, null)
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val po = arr.optJSONObject(i) ?: continue
                val url = po.optString("url").ifBlank { null } ?: continue
                add(CommunityPushRegistry.Pending(url, po.optString("token1").ifBlank { null }, po.optString("endpoint").ifBlank { null }))
            }
        }
    }

    companion object {
        private const val KEY_SERVERS = "servers" // JSON: { serverId: {"r": registerUrl, "u": unregisterUrl?, "fcm"?} }
        private const val KEY_INSTANCES = "instances" // JSON: { serverId: instanceId } (per-serwer, UnifiedPush)
        private const val KEY_ENDPOINTS = "endpoints" // JSON: { instanceId: endpoint }
        private const val KEY_PENDING_UNREG = "pending_unreg" // JSON: [ {"url","token1"?,"endpoint"?} ]

        private val RNG = SecureRandom()

        /** losowy, nieodgadywalny identyfikator instancji (hex — bez android.util.Base64, działa też w testach JVM). */
        private fun newInstanceId(): String {
            val bytes = ByteArray(9).also { RNG.nextBytes(it) }
            return "s" + bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * Lekki, trwały rejestr powiązany z UnifiedPush (SharedPreferences): mapa serverId→adresy push z manifestu
 * oraz — #8 (M-1) — per-serwer INSTANCJA UnifiedPush i jej endpoint. Każdy serwer bez własnego FCM dostaje
 * OSOBNĄ instancję (losowy nonce) → INNY endpoint niż pozostałe, więc dwa serwery nie skorelują po adresie
 * dostarczenia, że to ten sam subskrybent. Cała logika jest w [CommunityPushStore] (testowalnej bez Androida);
 * ten obiekt tylko podpina ją pod `SharedPreferences`. To JEDYNE dane, jakie apka wysyła do serwera.
 */
object CommunityPushRegistry {
    private const val PREFS = "community_push"

    private fun store(context: Context): CommunityPushStore {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return CommunityPushStore(object : PushKeyValue {
            override fun get(key: String): String? = prefs.getString(key, null)
            override fun put(key: String, value: String?) {
                prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
            }
        })
    }

    /** adresy push per serwer + (dla FCM) config klienta, żeby worker mógł odtworzyć token do rotacji. */
    data class ServerPush(val register: String, val unregister: String?, val fcm: CommunityFcmConfig?)

    /**
     * Stara tożsamość dostarczania (token1/endpoint + adres wyrejestrowania) odłożona do wysłania PÓŹNIEJ,
     * w losowym momencie — po zmianie tożsamości Tor. Rozspójnia w czasie „stare zniknęło" od „nowe się
     * pojawiło", więc obserwator serwera nie połączy jednego z drugim.
     */
    data class Pending(val url: String, val token1: String?, val endpoint: String?)

    // B-12: ViewModel, worker push i receiver UnifiedPush modyfikują rejestr równolegle; każda operacja to
    // odczyt-modyfikacja-zapis JSON-a w SharedPreferences, więc bez blokady gubiły się wpisy (np. pending).
    private val LOCK = Any()

    private inline fun <T> withStore(context: Context, block: (CommunityPushStore) -> T): T =
        synchronized(LOCK) { block(store(context)) }

    fun instanceForServer(context: Context, serverId: String) = withStore(context) { it.instanceForServer(serverId) }
    fun ensureInstance(context: Context, serverId: String) = withStore(context) { it.ensureInstance(serverId) }
    fun rotateInstance(context: Context, serverId: String) = withStore(context) { it.rotateInstance(serverId) }
    fun serverForInstance(context: Context, instance: String) = withStore(context) { it.serverForInstance(instance) }
    fun endpointForInstance(context: Context, instance: String) = withStore(context) { it.endpointForInstance(instance) }
    fun endpointForServer(context: Context, serverId: String) = withStore(context) { it.endpointForServer(serverId) }
    fun setEndpointForInstance(context: Context, instance: String, endpoint: String?) =
        withStore(context) { it.setEndpointForInstance(instance, endpoint) }
    fun forgetInstance(context: Context, instance: String) = withStore(context) { it.forgetInstance(instance) }
    fun allInstances(context: Context): Map<String, String> = withStore(context) { it.allInstances() }
    fun servers(context: Context): Map<String, ServerPush> =
        withStore(context) { it.servers() }.filterKeys { !CommunityDefaults.isDefault(it) || AppTerms.accepted(context) }
    fun replaceServers(context: Context, entries: Map<String, ServerPush>) =
        withStore(context) { it.replaceServers(entries) }
    fun removeServer(context: Context, serverId: String) = withStore(context) { it.removeServer(serverId) }
    fun addPendingUnregister(context: Context, items: List<Pending>) =
        withStore(context) { it.addPendingUnregister(items) }
    fun takePendingUnregister(context: Context): List<Pending> = withStore(context) { it.takePendingUnregister() }
}
