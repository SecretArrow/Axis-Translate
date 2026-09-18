package com.axis.translate.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Brand palette: indigo (#4F46E5 family) expressed as proper Material 3 tonal
 * roles. The named tones below are the source ramp for [LightColorScheme] and
 * [DarkColorScheme]; the schemes additionally define the full surface
 * container scale so tonal elevation resolves to indigo-tinted neutrals
 * instead of the M3 baseline purple.
 */

/** Core brand indigo tones. */
val AxisIndigo = Color(0xFF4F46E5)
val AxisIndigoLight = Color(0xFFC4BFFF)
val AxisIndigoDark = Color(0xFF3730A9)
val AxisIndigoContainer = Color(0xFFE4E2FF)

/** Accent used for favorite stars (not a themed role). */
val AxisAmber = Color(0xFFF5B301)

/** Accent used for success feedback (not a themed role). */
val AxisSuccess = Color(0xFF2E7D32)

/** Dark-mode companion of [AxisSuccess] for use on dark surfaces. */
val AxisSuccessDark = Color(0xFF81C995)

/**
 * Light color scheme: indigo primary, cool slate secondary, muted rose
 * tertiary, and an indigo-tinted neutral surface ramp.
 */
val LightColorScheme = lightColorScheme(
    primary = AxisIndigo,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = AxisIndigoContainer,
    onPrimaryContainer = Color(0xFF0E0764),
    inversePrimary = AxisIndigoLight,
    secondary = Color(0xFF5C5D72),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE1E0F9),
    onSecondaryContainer = Color(0xFF191A2C),
    tertiary = Color(0xFF77536D),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD7F1),
    onTertiaryContainer = Color(0xFF2D1229),
    background = Color(0xFFFBF8FF),
    onBackground = Color(0xFF1B1B21),
    surface = Color(0xFFFBF8FF),
    onSurface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFFE4E1EC),
    onSurfaceVariant = Color(0xFF47464F),
    surfaceTint = AxisIndigo,
    inverseSurface = Color(0xFF303036),
    inverseOnSurface = Color(0xFFF3EFF6),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF77767F),
    outlineVariant = Color(0xFFC8C5D0),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFBF8FF),
    surfaceDim = Color(0xFFDBD9E4),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F2FB),
    surfaceContainer = Color(0xFFEFEDF5),
    surfaceContainerHigh = Color(0xFFE9E7EF),
    surfaceContainerHighest = Color(0xFFE3E1EA)
)

/**
 * Dark color scheme: soft periwinkle primary on deep charcoal surfaces with
 * the matching indigo-tinted container ramp.
 */
val DarkColorScheme = darkColorScheme(
    primary = AxisIndigoLight,
    onPrimary = Color(0xFF1E1790),
    primaryContainer = AxisIndigoDark,
    onPrimaryContainer = Color(0xFFE4E2FF),
    inversePrimary = AxisIndigo,
    secondary = Color(0xFFC6C4DC),
    onSecondary = Color(0xFF2E2F42),
    secondaryContainer = Color(0xFF454559),
    onSecondaryContainer = Color(0xFFE2E0F9),
    tertiary = Color(0xFFE5BAD6),
    onTertiary = Color(0xFF43293D),
    tertiaryContainer = Color(0xFF5C3F54),
    onTertiaryContainer = Color(0xFFFFD7F1),
    background = Color(0xFF121318),
    onBackground = Color(0xFFE4E1E9),
    surface = Color(0xFF121318),
    onSurface = Color(0xFFE4E1E9),
    surfaceVariant = Color(0xFF47464F),
    onSurfaceVariant = Color(0xFFC8C5D0),
    surfaceTint = AxisIndigoLight,
    inverseSurface = Color(0xFFE4E1E9),
    inverseOnSurface = Color(0xFF303036),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF91909A),
    outlineVariant = Color(0xFF47464F),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF38393F),
    surfaceDim = Color(0xFF121318),
    surfaceContainerLowest = Color(0xFF0D0E13),
    surfaceContainerLow = Color(0xFF1A1B21),
    surfaceContainer = Color(0xFF1E1F25),
    surfaceContainerHigh = Color(0xFF28292F),
    surfaceContainerHighest = Color(0xFF33343A)
)
