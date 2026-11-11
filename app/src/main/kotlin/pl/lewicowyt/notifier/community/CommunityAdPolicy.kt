package pl.lewicowyt.notifier.community

/**
 * limit reklam egzekwowany po stronie APKI, niezależnie od tego, ile serwer wyśle.
 * regulamin serwerów pozwala na reklamę najwyżej raz na 20 pozycji – i tyle apka
 * maksymalnie pokaże (nadmiar po prostu obcinamy).
 */
object CommunityAdPolicy {
    const val MIN_ITEMS_PER_AD = 20

    /**
     * ile reklam wolno pokazać przy danej liczbie nie-reklamowych pozycji: NAJWYŻEJ 1 na pełne 20.
     * A-19: dawniej ceil(n/20) — 1 pozycja dawała już 1 reklamę, wbrew zasadzie „najwyżej 1 na 20".
     */
    fun allowedAdCount(nonAdItemCount: Int): Int =
        if (nonAdItemCount <= 0) 0 else nonAdItemCount / MIN_ITEMS_PER_AD
}
