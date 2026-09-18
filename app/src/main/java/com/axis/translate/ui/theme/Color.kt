package com.axis.translate.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/** Core brand indigo (#4F46E5 family) and supporting accent tones. */
val AxisIndigo = Color(0xFF4F46E5)
val AxisIndigoLight = Color(0xFFC4C0FF)
val AxisIndigoDark = Color(0xFF352FC9)
val AxisIndigoContainer = Color(0xFFE3E0FF)

/** Accent used for favorite stars. */
val AxisAmber = Color(0xFFF5B301)

/** Accent used for success feedback. */
val AxisSuccess = Color(0xFF2E7D32)

/** Light color scheme: indigo primary, cool slate secondary, muted rose tertiary. */
val LightColorScheme = lightColorScheme(
    primary = AxisIndigo,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = AxisIndigoContainer,
    onPrimaryContainer = Color(0xFF16006B),
    inversePrimary = AxisIndigoLight,
    secondary = Color(0xFF5B5B72),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE0E0F9),
    onSecondaryContainer = Color(0xFF181A2C),
    tertiary = Color(0xFF77536D),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD7F1),
    onTertiaryContainer = Color(0xFF2D1229),
    background = Color(0xFFFCF8FF),
    onBackground = Color(0xFF1B1B21),
    surface = Color(0xFFFCF8FF),
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
)

/** Dark color scheme: soft periwinkle primary on deep charcoal surfaces. */
val DarkColorScheme = darkColorScheme(
    primary = AxisIndigoLight,
    onPrimary = Color(0xFF19009A),
    primaryContainer = AxisIndigoDark,
    onPrimaryContainer = Color(0xFFE3E0FF),
    inversePrimary = AxisIndigo,
    secondary = Color(0xFFC4C4DD),
    onSecondary = Color(0xFF2D2D42),
    secondaryContainer = Color(0xFF434359),
    onSecondaryContainer = Color(0xFFE0E0F9),
    tertiary = Color(0xFFE6BAD7),
    onTertiary = Color(0xFF44263E),
    tertiaryContainer = Color(0xFF5D3C55),
    onTertiaryContainer = Color(0xFFFFD7F1),
    background = Color(0xFF131318),
    onBackground = Color(0xFFE4E1E9),
    surface = Color(0xFF131318),
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
)
