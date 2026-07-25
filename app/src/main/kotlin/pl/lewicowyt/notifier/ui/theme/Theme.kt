package pl.lewicowyt.notifier.ui.theme

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import pl.lewicowyt.notifier.data.ThemeMode

private fun accentColorScheme(accentArgb: Long, dark: Boolean) = run {
    val accent = accentArgb.toInt()
    val primary = Color(accent)
    val onPrimary = readableOnColor(accent)
    val primaryContainerInt = blend(
        accent,
        if (dark) AndroidColor.BLACK else AndroidColor.WHITE,
        if (dark) 0.66f else 0.78f,
    )
    val primaryContainer = Color(primaryContainerInt)
    val onPrimaryContainer = readableOnColor(primaryContainerInt)

    if (dark) {
        darkColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondary = primary,
            tertiary = primary,
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondary = primary,
            tertiary = primary,
        )
    }
}

@Composable
fun LewicowYTTheme(
    themeMode: ThemeMode,
    accentColorArgb: Long,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = remember(dark, accentColorArgb) {
        accentColorScheme(accentColorArgb, dark)
    }
    MaterialTheme(colorScheme = colors, content = content)
}

private fun readableOnColor(color: Int): Color {
    val luminance = (
        0.299 * AndroidColor.red(color) +
            0.587 * AndroidColor.green(color) +
            0.114 * AndroidColor.blue(color)
        ) / 255.0
    return if (luminance > 0.56) Color.Black else Color.White
}

private fun blend(first: Int, second: Int, secondWeight: Float): Int {
    val weight = secondWeight.coerceIn(0f, 1f)
    return AndroidColor.rgb(
        (AndroidColor.red(first) * (1f - weight) + AndroidColor.red(second) * weight).toInt(),
        (AndroidColor.green(first) * (1f - weight) + AndroidColor.green(second) * weight).toInt(),
        (AndroidColor.blue(first) * (1f - weight) + AndroidColor.blue(second) * weight).toInt(),
    )
}
