package com.nakvali.core.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Nakvali palette, derived from a deep orange / dirt-red seed (#D84315).
 * Dark surfaces resemble soil, rubber and workshop carbon rather than the
 * neutral blue-gray defaults. The brighter orange is reserved for action and
 * live state so it stays legible outdoors without turning every surface warm.
 */

// Seed family
val DirtRed = Color(0xFFD84315)

// Dark scheme (primary look)
val DarkPrimary = Color(0xFFFF7040)
val DarkOnPrimary = Color(0xFF2B0B00)
val DarkPrimaryContainer = Color(0xFF6D2108)
val DarkOnPrimaryContainer = Color(0xFFFFD8CA)
val DarkSecondary = Color(0xFFD0B9B0)
val DarkOnSecondary = Color(0xFF382D29)
val DarkSecondaryContainer = Color(0xFF443631)
val DarkOnSecondaryContainer = Color(0xFFF0D8CF)
val DarkTertiary = Color(0xFFB8C99A)
val DarkOnTertiary = Color(0xFF263313)
val DarkTertiaryContainer = Color(0xFF3B4928)
val DarkOnTertiaryContainer = Color(0xFFD4E6B4)
val DarkError = Color(0xFFFFB4AB)
val DarkOnError = Color(0xFF690005)
val DarkErrorContainer = Color(0xFF93000A)
val DarkOnErrorContainer = Color(0xFFFFDAD6)
val DarkBackground = Color(0xFF100E0D)
val DarkOnBackground = Color(0xFFF4EDE9)
val DarkSurface = Color(0xFF151210)
val DarkOnSurface = Color(0xFFF4EDE9)
val DarkSurfaceVariant = Color(0xFF352B27)
val DarkOnSurfaceVariant = Color(0xFFCFC0BA)
val DarkOutline = Color(0xFF8C7C75)
val DarkOutlineVariant = Color(0xFF453A35)
val DarkSurfaceContainerLowest = Color(0xFF0C0A09)
val DarkSurfaceContainerLow = Color(0xFF191513)
val DarkSurfaceContainer = Color(0xFF1E1916)
val DarkSurfaceContainerHigh = Color(0xFF261F1C)
val DarkSurfaceContainerHighest = Color(0xFF302722)

// Light scheme (secondary look)
val LightPrimary = Color(0xFFAC2F00)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFFFDBCF)
val LightOnPrimaryContainer = Color(0xFF3A0A00)
val LightSecondary = Color(0xFF77574C)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFFFDBCF)
val LightOnSecondaryContainer = Color(0xFF2C160D)
val LightTertiary = Color(0xFF695E2F)
val LightOnTertiary = Color(0xFFFFFFFF)
val LightTertiaryContainer = Color(0xFFF3E2A7)
val LightOnTertiaryContainer = Color(0xFF221B00)
val LightError = Color(0xFFBA1A1A)
val LightOnError = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFFFDAD6)
val LightOnErrorContainer = Color(0xFF410002)
val LightBackground = Color(0xFFFFF8F6)
val LightOnBackground = Color(0xFF231A17)
val LightSurface = Color(0xFFFFF8F6)
val LightOnSurface = Color(0xFF231A17)
val LightSurfaceVariant = Color(0xFFF5DED6)
val LightOnSurfaceVariant = Color(0xFF53433E)
val LightOutline = Color(0xFF85736D)

val NakvaliDarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    surfaceContainerLowest = DarkSurfaceContainerLowest,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest,
)

val NakvaliLightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = LightOnTertiary,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
)
