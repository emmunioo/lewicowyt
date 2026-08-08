package pl.lewicowyt.notifier.ui.theme

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HighContrastInstrumentedTest {
    @Test
    fun primaryUsedForTextAlwaysMeetsWcagAaContrast() {
        val accents = listOf(
            0xFFFF0000.toInt(),
            0xFFFF00FF.toInt(),
            0xFF00B7FF.toInt(),
            0xFF00FF00.toInt(),
            0xFFFFFF00.toInt(),
            0xFF777777.toInt(),
            0xFF000000.toInt(),
            0xFFFFFFFF.toInt(),
        )

        for (darkTheme in listOf(false, true)) {
            val background = if (darkTheme) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            accents.forEach { accent ->
                val corrected = highContrastPrimaryArgb(accent, darkTheme)
                val ratio = contrastRatio(corrected, background)
                assertTrue(
                    "Kontrast ${ratio}:1 dla akcentu ${accent.toUInt().toString(16)} " +
                        "w trybie dark=$darkTheme jest mniejszy niż 4.5:1",
                    ratio >= 4.5,
                )
            }
        }
    }
}
