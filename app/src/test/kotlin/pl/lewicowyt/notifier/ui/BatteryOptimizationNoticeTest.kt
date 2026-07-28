package pl.lewicowyt.notifier.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryOptimizationNoticeTest {
    @Test
    fun `notice is shown when settings opens while optimization is active`() {
        assertTrue(
            shouldShowBatteryOptimizationNotice(
                isSettingsScreen = true,
                batteryOptimizationIgnored = false,
            ),
        )
    }

    @Test
    fun `notice is hidden outside settings or after unrestricted access`() {
        assertFalse(
            shouldShowBatteryOptimizationNotice(
                isSettingsScreen = false,
                batteryOptimizationIgnored = false,
            ),
        )
        assertFalse(
            shouldShowBatteryOptimizationNotice(
                isSettingsScreen = true,
                batteryOptimizationIgnored = true,
            ),
        )
    }
}
