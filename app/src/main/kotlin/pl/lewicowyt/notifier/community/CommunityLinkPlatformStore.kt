package pl.lewicowyt.notifier.community

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Trwały cache platform linków deklarowanych przez serwery (SharedPreferences, per serverId).
 * Manifest odtwarzany z bazy (offline/po restarcie) nie zawiera `linkPlatforms`, więc — jak przy push
 * i altEndpoints — trzymamy je w osobnym, lekkim magazynie zapisywanym po każdym pobraniu manifestu.
 * Serwer NIE dostaje stąd nic — to wyłącznie lokalny cache.
 */
object CommunityLinkPlatformStore {
    private const val PREFS = "community_link_platforms"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** zapisuje platformy danego serwera (pusta lista = kasuje wpis). */
    fun save(context: Context, serverId: String, platforms: List<CommunityLinkPlatform>) {
        val p = prefs(context)
        if (platforms.isEmpty()) { p.edit().remove(serverId).apply(); return }
        val arr = JSONArray()
        platforms.forEach { pl ->
            arr.put(
                JSONObject()
                    .put("id", pl.id)
                    .put("name", pl.name)
                    .put("hosts", JSONArray(pl.hosts)),
            )
        }
        p.edit().putString(serverId, arr.toString()).apply()
    }

    /** platformy jednego serwera. */
    fun load(context: Context, serverId: String): List<CommunityLinkPlatform> {
        val raw = prefs(context).getString(serverId, null) ?: return emptyList()
        return parse(raw)
    }

    /** usuwa wpis serwera (np. po usunięciu serwera). */
    fun remove(context: Context, serverId: String) {
        prefs(context).edit().remove(serverId).apply()
    }

    /** zagregowane platformy dla podanych serwerów, bez duplikatów po id (ostatnia nazwa wygrywa). */
    fun aggregate(context: Context, serverIds: Collection<String>): List<CommunityLinkPlatform> {
        val byId = LinkedHashMap<String, CommunityLinkPlatform>()
        serverIds.forEach { id -> load(context, id).forEach { pl -> byId[pl.id] = pl } }
        return byId.values.toList()
    }

    private fun parse(raw: String): List<CommunityLinkPlatform> = runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").ifBlank { return@mapNotNull null }
            val name = o.optString("name").ifBlank { return@mapNotNull null }
            val hostsArr = o.optJSONArray("hosts")
            val hosts = if (hostsArr == null) emptyList() else
                (0 until hostsArr.length()).mapNotNull { j -> hostsArr.optString(j).ifBlank { null } }
            CommunityLinkPlatform(id, name, hosts)
        }
    }.getOrDefault(emptyList())
}
