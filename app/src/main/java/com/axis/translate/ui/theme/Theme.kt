package com.axis.translate.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.axis.translate.data.settings.ThemeMode

/**
 * App theme: static indigo palette (dynamic color intentionally NOT used so
 * the offline brand look is identical across devices). Dark scheme is applied
 * when the mode is [ThemeMode.DARK] or the system is in dark mode.
 */
@Composable
fun AxisTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AxisTypography,
        content = content,
    )
}
