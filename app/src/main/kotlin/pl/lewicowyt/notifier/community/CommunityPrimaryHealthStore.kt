package pl.lewicowyt.notifier.community

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Trwały stan weryfikacji „serwera głównego" (SharedPreferences, pojedynczy blob JSON). Dzięki temu
 * baner z rozbieżnościami i licznik kolejnych nieudanych sprawdzeń [CommunityPrimaryHealth.consecutiveFailures]
 * przeżywają restart apki — inaczej po każdym starcie zaczynalibyśmy liczyć próby od zera i nigdy nie
 * przekroczylibyśmy progu ostrzeżenia. To wyłącznie lokalny stan; do serwera nic stąd nie leci.
 */
object CommunityPrimaryHealthStore {
    private const val PREFS = "community_primary_health"
    private const val KEY = "state"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): CommunityPrimaryHealth? {
        val raw = prefs(context).getString(KEY, null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            val diffsArr = o.optJSONArray("differences") ?: JSONArray()
            val diffs = (0 until diffsArr.length()).mapNotNull { i ->
                val d = diffsArr.optJSONObject(i) ?: return@mapNotNull null
                val kind = runCatching { PrimaryHealthKind.valueOf(d.optString("kind")) }.getOrNull()
                    ?: return@mapNotNull null
                PrimaryHealthDifference(kind, d.optString("detail"))
            }
            CommunityPrimaryHealth(
                serverId = o.optString("serverId"),
                consecutiveFailures = o.optInt("consecutiveFailures"),
                differences = diffs,
                checkedAtMillis = o.optLong("checkedAtMillis"),
            )
        }.getOrNull()
    }

    fun save(context: Context, health: CommunityPrimaryHealth) {
        val diffs = JSONArray()
        health.differences.forEach { d ->
            diffs.put(JSONObject().put("kind", d.kind.name).put("detail", d.detail))
        }
        val o = JSONObject()
            .put("serverId", health.serverId)
            .put("consecutiveFailures", health.consecutiveFailures)
            .put("differences", diffs)
            .put("checkedAtMillis", health.checkedAtMillis)
        prefs(context).edit().putString(KEY, o.toString()).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY).apply()
    }
}
