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

private fun accentColorScheme(accentArgb: Long, dark: Boolean, highContrast: Boolean) = run {
    val accent = accentArgb.toInt()
    val backgroundInt = if (dark) AndroidColor.BLACK else AndroidColor.WHITE
    val primaryInt = if (highContrast) {
        highContrastPrimaryArgb(accent, dark)
    } else {
        accent
    }
    val primary = Color(primaryInt)
    val onPrimary = readableOnColor(primaryInt)
    val primaryContainerInt = blend(
        primaryInt,
        backgroundInt,
        if (highContrast) 0.38f else if (dark) 0.66f else 0.78f,
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
            background = if (highContrast) Color.Black else darkColorScheme().background,
            onBackground = if (highContrast) Color.White else darkColorScheme().onBackground,
            surface = if (highContrast) Color.Black else darkColorScheme().surface,
            onSurface = if (highContrast) Color.White else darkColorScheme().onSurface,
            surfaceVariant = if (highContrast) Color(0xFF202020) else
                darkColorScheme().surfaceVariant,
            onSurfaceVariant = if (highContrast) Color.White else
                darkColorScheme().onSurfaceVariant,
            outline = if (highContrast) Color.White else darkColorScheme().outline,
            outlineVariant = if (highContrast) Color(0xFFBDBDBD) else
                darkColorScheme().outlineVariant,
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondary = primary,
            tertiary = primary,
            background = if (highContrast) Color.White else lightColorScheme().background,
            onBackground = if (highContrast) Color.Black else lightColorScheme().onBackground,
            surface = if (highContrast) Color.White else lightColorScheme().surface,
            onSurface = if (highContrast) Color.Black else lightColorScheme().onSurface,
            surfaceVariant = if (highContrast) Color(0xFFE4E4E4) else
                lightColorScheme().surfaceVariant,
            onSurfaceVariant = if (highContrast) Color.Black else
                lightColorScheme().onSurfaceVariant,
            outline = if (highContrast) Color.Black else lightColorScheme().outline,
            outlineVariant = if (highContrast) Color(0xFF424242) else
                lightColorScheme().outlineVariant,
        )
    }
}

@Composable
fun LewicowYTTheme(
    themeMode: ThemeMode,
    accentColorArgb: Long,
    highContrast: Boolean,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = remember(dark, accentColorArgb, highContrast) {
        accentColorScheme(accentColorArgb, dark, highContrast)
    }
    MaterialTheme(colorScheme = colors, content = content)
}

private fun readableOnColor(color: Int): Color {
    val blackContrast = contrastRatio(color, AndroidColor.BLACK)
    val whiteContrast = contrastRatio(color, AndroidColor.WHITE)
    return if (blackContrast >= whiteContrast) Color.Black else Color.White
}

private fun ensureContrastAgainst(color: Int, background: Int, minimumRatio: Double): Int {
    if (contrastRatio(color, background) >= minimumRatio) return color
    val target = if (background == AndroidColor.BLACK) AndroidColor.WHITE else AndroidColor.BLACK
    for (step in 1..20) {
        val candidate = blend(color, target, step / 20f)
        if (contrastRatio(candidate, background) >= minimumRatio) return candidate
    }
    return target
}

internal fun highContrastPrimaryArgb(accent: Int, dark: Boolean): Int =
    ensureContrastAgainst(
        color = accent,
        background = if (dark) AndroidColor.BLACK else AndroidColor.WHITE,
        minimumRatio = HIGH_CONTRAST_TEXT_RATIO,
    )

internal fun contrastRatio(first: Int, second: Int): Double {
    val firstLuminance = relativeLuminance(first)
    val secondLuminance = relativeLuminance(second)
    val lighter = maxOf(firstLuminance, secondLuminance)
    val darker = minOf(firstLuminance, secondLuminance)
    return (lighter + 0.05) / (darker + 0.05)
}

private const val HIGH_CONTRAST_TEXT_RATIO = 4.5

private fun relativeLuminance(color: Int): Double {
    fun channel(value: Int): Double {
        val normalized = value / 255.0
        return if (normalized <= 0.04045) normalized / 12.92
        else Math.pow((normalized + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * channel(AndroidColor.red(color)) +
        0.7152 * channel(AndroidColor.green(color)) +
        0.0722 * channel(AndroidColor.blue(color))
}

private fun blend(first: Int, second: Int, secondWeight: Float): Int {
    val weight = secondWeight.coerceIn(0f, 1f)
    return AndroidColor.rgb(
        (AndroidColor.red(first) * (1f - weight) + AndroidColor.red(second) * weight).toInt(),
        (AndroidColor.green(first) * (1f - weight) + AndroidColor.green(second) * weight).toInt(),
        (AndroidColor.blue(first) * (1f - weight) + AndroidColor.blue(second) * weight).toInt(),
    )
}
