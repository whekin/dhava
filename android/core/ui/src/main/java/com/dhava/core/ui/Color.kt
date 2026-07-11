package com.dhava.core.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Dhava palette, derived from a deep orange / dirt-red seed (#D84315).
 * Dark is the primary look: riders check the screen outdoors, often in
 * forest shade, and high-contrast warm accents on near-black read best.
 */

// Seed family
val DirtRed = Color(0xFFD84315)

// Dark scheme (primary look)
val DarkPrimary = Color(0xFFFFB59C)
val DarkOnPrimary = Color(0xFF5F1600)
val DarkPrimaryContainer = Color(0xFF862200)
val DarkOnPrimaryContainer = Color(0xFFFFDBCF)
val DarkSecondary = Color(0xFFE7BDB0)
val DarkOnSecondary = Color(0xFF442A21)
val DarkSecondaryContainer = Color(0xFF5D4036)
val DarkOnSecondaryContainer = Color(0xFFFFDBCF)
val DarkTertiary = Color(0xFFD6C68D)
val DarkOnTertiary = Color(0xFF393005)
val DarkTertiaryContainer = Color(0xFF51461A)
val DarkOnTertiaryContainer = Color(0xFFF3E2A7)
val DarkError = Color(0xFFFFB4AB)
val DarkOnError = Color(0xFF690005)
val DarkErrorContainer = Color(0xFF93000A)
val DarkOnErrorContainer = Color(0xFFFFDAD6)
val DarkBackground = Color(0xFF181210)
val DarkOnBackground = Color(0xFFF1DFDA)
val DarkSurface = Color(0xFF181210)
val DarkOnSurface = Color(0xFFF1DFDA)
val DarkSurfaceVariant = Color(0xFF53433E)
val DarkOnSurfaceVariant = Color(0xFFD8C2BB)
val DarkOutline = Color(0xFFA08D86)

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

val DhavaDarkColorScheme = darkColorScheme(
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
)

val DhavaLightColorScheme = lightColorScheme(
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
