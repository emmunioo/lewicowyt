package pl.lewicowyt.notifier.community.push

/**
 * Ile czekać przed kolejnym long-pollem jednego serwera (czysta logika — LepPollPolicyTest).
 *
 *  - przyszły budziki → OD RAZU kolejny long-poll (dawniej stała 1 s: kolejny budzik czekał ją bez potrzeby);
 *  - zwykły koniec long-polla bez budzików (serwer trzymał połączenie ~50 s) → od razu;
 *  - pusta odpowiedź NATYCHMIAST (serwer nie trzyma połączenia — zepsuty albo obcy relay) → 1 s, żeby taki
 *    serwer nie wpędził telefonu w pętlę zapytań bez przerwy;
 *  - celowe przerwanie (rotacja tematu, nowa sieć) → od razu, bez liczenia jako błąd;
 *  - błąd → backoff wykładniczy 1 s … 5 min (A-24), przerywany powrotem sieci / sygnałem [EmbeddedPush.wakeUps].
 */
internal object LepPollPolicy {
    const val MAX_BACKOFF_MS = 5L * 60 * 1000
    const val QUICK_EMPTY_WAIT_MS = 1_000L

    // krótsza odpowiedź bez budzików = serwer nie trzymał połączenia (LEP trzyma ~50 s)
    const val MIN_HOLD_MS = 5_000L

    data class Plan(val failures: Int, val waitMs: Long)

    fun next(result: EmbeddedPush.Poll, failures: Int, elapsedMs: Long): Plan = when (result) {
        is EmbeddedPush.Poll.Ok -> when {
            result.received > 0 -> Plan(0, 0L)
            elapsedMs >= MIN_HOLD_MS -> Plan(0, 0L)
            else -> Plan(0, QUICK_EMPTY_WAIT_MS)
        }
        EmbeddedPush.Poll.Interrupted -> Plan(0, 0L)
        EmbeddedPush.Poll.Failed -> {
            val n = (failures + 1).coerceAtMost(16)
            Plan(n, (1_000L shl (n - 1).coerceAtMost(9)).coerceAtMost(MAX_BACKOFF_MS))
        }
    }
}
