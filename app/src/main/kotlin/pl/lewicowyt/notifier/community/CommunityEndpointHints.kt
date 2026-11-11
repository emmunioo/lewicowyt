package pl.lewicowyt.notifier.community

import android.content.Context

/**
 * Podpowiedzi adresów serwera do obejścia zepsutego hairpin-NAT: serwer ogłasza w manifeście
 * `altEndpoints` (publiczny + LAN), a my zapamiętujemy ostatni DZIAŁAJĄCY adres i alternatywy,
 * żeby przy nieosiągalnym adresie głównym spróbować kolejnego (np. LAN, gdy w domu publiczny IP
 * nie wraca do wewnątrz).
 */
interface CommunityEndpointHints {
    /** kolejność prób: ostatni działający → adres główny → zapamiętane alternatywy (bez duplikatów). */
    fun candidates(serverId: String, primary: String): List<String>

    /** zapamiętuje adres, który zadziałał, oraz alternatywy ogłoszone w manifeście. */
    fun remember(serverId: String, working: String, altEndpoints: List<String>)

    /** kasuje zapamiętany adres i alternatywy serwera (przy usunięciu serwera / zmianie tożsamości). */
    fun forget(serverId: String)
}

/** domyślnie brak podpowiedzi (do testów bez Androida) — próbujemy tylko adresu głównego. */
object NoEndpointHints : CommunityEndpointHints {
    override fun candidates(serverId: String, primary: String): List<String> = listOf(primary)
    override fun remember(serverId: String, working: String, altEndpoints: List<String>) {}
    override fun forget(serverId: String) {}
}

/** trwałe podpowiedzi w SharedPreferences (produkcyjnie). */
class CommunityEndpointStore(context: Context) : CommunityEndpointHints {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun candidates(serverId: String, primary: String): List<String> {
        val preferred = prefs.getString("pref_$serverId", null)
        val alts = prefs.getString("alt_$serverId", null)
            ?.split("\n")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        // C-11: limit tylko na alternatywy (protokół: do 8 adresów LAN) — wcześniej wspólne take(6) gubiło ostatnie
        return (listOfNotNull(preferred) + primary + alts.take(MAX_ALTS)).distinct()
    }

    override fun remember(serverId: String, working: String, altEndpoints: List<String>) {
        prefs.edit()
            .putString("pref_$serverId", working)
            .putString("alt_$serverId", altEndpoints.joinToString("\n"))
            .apply()
    }

    override fun forget(serverId: String) {
        prefs.edit().remove("pref_$serverId").remove("alt_$serverId").apply()
    }

    private companion object {
        const val PREFS = "community_endpoints"
        const val MAX_ALTS = 8
    }
}
