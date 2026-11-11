package pl.lewicowyt.notifier.community

/**
 * czysta decyzja o powiadomieniu. serwer TYLKO kataloguje typ/platformę pozycji –
 * czy poleci powiadomienie, rozstrzyga APKA na podstawie ustawień usera per serwer.
 */
object CommunityNotificationPolicy {

    /** platforma dla treści z YouTube (twórcy, posty). obce pozycje niosą własną. */
    const val PLATFORM_YOUTUBE = "youtube"

    fun shouldNotify(server: CommunityServerRecord, platform: String, creator: String? = null): Boolean =
        CommunityContentFilter.shouldNotify(server, platform, creator)

    /** na „serwer główny" można wybrać tylko taki, który sam to w manifeście zadeklarował. */
    fun canSelectAsPrimary(server: CommunityServerRecord): Boolean =
        server.enabled && server.canBePrimary
}
