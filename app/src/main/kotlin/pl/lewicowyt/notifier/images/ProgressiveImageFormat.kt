package pl.lewicowyt.notifier.images

/**
 * Rozpoznaje po pierwszych bajtach, czy obraz da się pokazywać PROGRESYWNIE w trakcie pobierania — tzn. czy
 * już niepełny plik daje sensowny podgląd CAŁEGO obrazu (a nie tylko górny pasek):
 *  - JPEG XL — przebiegi (najpierw obraz 1:8, potem ostrzejsze), własny dekoder strumieniowy;
 *  - JPEG PROGRESYWNY (znacznik SOF2/SOF6/SOF10/SOF14) — kolejne skany coraz ostrzejsze;
 *  - PNG Z PRZEPLOTEM (Adam7) — najpierw co 8. piksel, potem gęściej.
 * Zwykły (sekwencyjny) JPEG/PNG, WebP i AVIF nie mają przebiegów — tych nie podglądamy (byłby to tylko
 * „rosnący od góry pasek", a każdy podgląd to pełne dekodowanie = bateria).
 * Czysta logika bez Androida — ProgressiveImageFormatTest.
 */
internal object ProgressiveImageFormat {
    enum class Kind { JXL, PROGRESSIVE_JPEG, INTERLACED_PNG, NONE }

    // nagłówki JPEG z dużym EXIF-em/ICC potrafią mieć dziesiątki KB przed znacznikiem SOF — dalej nie szukamy
    const val MAX_SCAN_BYTES = 256 * 1024

    /** @return rodzaj albo null, gdy bajtów jest jeszcze za mało, by rozstrzygnąć. */
    fun detect(b: ByteArray, size: Int = b.size): Kind? {
        if (size < 2) return null
        if (b[0] == 0xFF.toByte() && b[1] == 0x0A.toByte()) return Kind.JXL // goły kodestrumień JXL
        if (b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()) return jpeg(b, size)
        if (size < 12) return null
        if (JXL_BOX.indices.all { b[it] == JXL_BOX[it] }) return Kind.JXL
        if (PNG_SIGNATURE.indices.all { b[it] == PNG_SIGNATURE[it] }) return png(b, size)
        return Kind.NONE
    }

    private fun png(b: ByteArray, size: Int): Kind? {
        if (size < 29) return null
        // pierwszy chunk to IHDR: długość(4) "IHDR"(4) szer(4) wys(4) głębia(1) kolor(1) kompresja(1) filtr(1) PRZEPLOT(1)
        val ihdr = b[12] == 'I'.code.toByte() && b[13] == 'H'.code.toByte() &&
            b[14] == 'D'.code.toByte() && b[15] == 'R'.code.toByte()
        return if (ihdr && b[28].toInt() == 1) Kind.INTERLACED_PNG else Kind.NONE
    }

    private fun jpeg(b: ByteArray, size: Int): Kind? {
        var i = 2
        while (true) {
            if (i >= MAX_SCAN_BYTES) return Kind.NONE
            if (i + 1 >= size) return null
            if (b[i] != 0xFF.toByte()) return Kind.NONE // uszkodzona struktura — bez podglądu
            val marker = b[i + 1].toInt() and 0xFF
            when {
                marker == 0xFF -> { i += 1; continue } // bajt wypełnienia
                marker == 0xC2 || marker == 0xC6 || marker == 0xCA || marker == 0xCE -> return Kind.PROGRESSIVE_JPEG
                marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC -> return Kind.NONE // SOF sekwencyjny
                marker == 0xD9 || marker == 0xDA -> return Kind.NONE // koniec / dane przed SOF — nietypowe
                marker == 0x01 || marker in 0xD0..0xD8 -> { i += 2; continue } // znaczniki bez długości
                else -> {
                    if (i + 3 >= size) return null
                    val length = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
                    if (length < 2) return Kind.NONE
                    i += 2 + length
                }
            }
        }
    }

    private val JXL_BOX = byteArrayOf(0, 0, 0, 0x0C, 0x4A, 0x58, 0x4C, 0x20, 0x0D, 0x0A, 0x87.toByte(), 0x0A)
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
}
