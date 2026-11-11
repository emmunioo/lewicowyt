package pl.lewicowyt.notifier.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lewicowyt.notifier.data.AppSettings

class ActiveHoursTest {

    private fun settings(enabled: Boolean, start: Int, end: Int) = AppSettings(
        activeHoursEnabled = enabled,
        activeHoursStartMinutes = start,
        activeHoursEndMinutes = end,
    )

    @Test
    fun `disabled means always within`() {
        val s = settings(false, 8 * 60, 22 * 60)
        assertTrue(ActiveHours.isWithin(s, 3 * 60))
        assertTrue(ActiveHours.isWithin(s, 23 * 60))
        assertEquals(0L, ActiveHours.millisUntilNextOpen(s, System.currentTimeMillis()))
    }

    @Test
    fun `daytime window includes start, excludes end`() {
        val s = settings(true, 8 * 60, 22 * 60) // 08:00–22:00
        assertTrue(ActiveHours.isWithin(s, 8 * 60))       // 08:00 w oknie
        assertTrue(ActiveHours.isWithin(s, 12 * 60))      // 12:00
        assertTrue(ActiveHours.isWithin(s, 22 * 60 - 1))  // 21:59
        assertFalse(ActiveHours.isWithin(s, 8 * 60 - 1))  // 07:59
        assertFalse(ActiveHours.isWithin(s, 22 * 60))     // 22:00 już poza
        assertFalse(ActiveHours.isWithin(s, 23 * 60))     // 23:00
        assertFalse(ActiveHours.isWithin(s, 0))           // północ
    }

    @Test
    fun `overnight window wraps past midnight`() {
        val s = settings(true, 22 * 60, 6 * 60) // 22:00–06:00
        assertTrue(ActiveHours.isWithin(s, 23 * 60))      // 23:00
        assertTrue(ActiveHours.isWithin(s, 0))            // północ
        assertTrue(ActiveHours.isWithin(s, 5 * 60))       // 05:00
        assertFalse(ActiveHours.isWithin(s, 6 * 60))      // 06:00 poza
        assertFalse(ActiveHours.isWithin(s, 12 * 60))     // południe poza
    }

    @Test
    fun `equal start and end means full day`() {
        val s = settings(true, 9 * 60, 9 * 60)
        assertTrue(ActiveHours.isWithin(s, 9 * 60))
        assertTrue(ActiveHours.isWithin(s, 3 * 60))
        assertTrue(ActiveHours.isWithin(s, 20 * 60))
    }

    @Test
    fun `next open is zero within window and bounded when outside`() {
        val within = settings(true, 0, 0) // pełna doba → zawsze w oknie
        assertEquals(0L, ActiveHours.millisUntilNextOpen(within, System.currentTimeMillis()))

        // sekcja 7 audytu: STAŁY zegar zamiast System.currentTimeMillis() — 15 czerwca, 12:00 lokalnie
        // (bez zmiany czasu w okolicy); okno 03:00–03:01 otwiera się za dokładnie 15 h.
        val noon = java.time.LocalDate.of(2026, 6, 15).atTime(12, 0)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val narrow = settings(true, 3 * 60, 3 * 60 + 1)
        assertEquals(15L * 60 * 60 * 1000, ActiveHours.millisUntilNextOpen(narrow, noon))
        // w oknie → 0
        val atThree = java.time.LocalDate.of(2026, 6, 15).atTime(3, 0, 30)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(0L, ActiveHours.millisUntilNextOpen(narrow, atThree))
    }
}
