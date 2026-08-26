package com.nakvali.core.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Nakvali palette, derived from a vivid fern-green seed (#2E8B57).
 * Dark surfaces still resemble soil, rubber and workshop carbon rather than
 * neutral blue-gray defaults. The fresh green is reserved for action, the
 * canonical track and live state so it remains legible outdoors.
 *
 * Secondary and tertiary are written by hand rather than derived. Material's
 * tonal generator rotates a green seed into a desaturated rose, and because
 * every `FilledTonalButton` reads `secondaryContainer`, that rose was shipping
 * as the resting colour of half the app's controls. The three families now have
 * distinct jobs:
 *
 * - **primary** — fern green. Action, live recording state, canonical track.
 * - **secondary** — stone/graphite. Resting surfaces and secondary controls.
 *   Neutral by design so it never competes with an action.
 * - **tertiary** — trail ochre. The second signal: warming up, refining,
 *   armed, degraded. Reads clearly against green without fighting it.
 *
 * Neutrals carry a faint green-yellow bias (wet bark, not dust). The previous
 * warm-red bias read as pink once it met the rose secondary.
 */

// Seed family
val TrailGreen = Color(0xFF2E8B57)

// ---------------------------------------------------------------- dark scheme
// The primary look.

val DarkPrimary = Color(0xFF6FDD87)
val DarkOnPrimary = Color(0xFF00391A)
val DarkPrimaryContainer = Color(0xFF17512B)
val DarkOnPrimaryContainer = Color(0xFFA9F2B7)

val DarkSecondary = Color(0xFFB9C2BB)
val DarkOnSecondary = Color(0xFF253028)
val DarkSecondaryContainer = Color(0xFF333B34)
val DarkOnSecondaryContainer = Color(0xFFD6DFD7)

val DarkTertiary = Color(0xFFE0B15C)
val DarkOnTertiary = Color(0xFF3C2A00)
val DarkTertiaryContainer = Color(0xFF55400F)
val DarkOnTertiaryContainer = Color(0xFFFBDDA0)

val DarkError = Color(0xFFFFB4AB)
val DarkOnError = Color(0xFF690005)
val DarkErrorContainer = Color(0xFF8C1D16)
val DarkOnErrorContainer = Color(0xFFFFDAD6)

val DarkBackground = Color(0xFF0D0F0D)
val DarkOnBackground = Color(0xFFE9EDE8)
val DarkSurface = Color(0xFF121412)
val DarkOnSurface = Color(0xFFE9EDE8)
val DarkSurfaceVariant = Color(0xFF2A2F29)
val DarkOnSurfaceVariant = Color(0xFFBAC2B8)
val DarkOutline = Color(0xFF7C857A)
val DarkOutlineVariant = Color(0xFF3A413A)
val DarkSurfaceContainerLowest = Color(0xFF0A0B0A)
val DarkSurfaceContainerLow = Color(0xFF161916)
val DarkSurfaceContainer = Color(0xFF1A1E1A)
val DarkSurfaceContainerHigh = Color(0xFF222722)
val DarkSurfaceContainerHighest = Color(0xFF2B302B)

// --------------------------------------------------------------- light scheme
// Daylight on a bar mount. The container ramp is spelled out rather than left
// to Material's derivation: without it a sheet drawn in `surface` sat on a map
// ground of almost the same value and lost its edge entirely.

val LightPrimary = Color(0xFF176C35)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFA6F5B7)
val LightOnPrimaryContainer = Color(0xFF00210C)

val LightSecondary = Color(0xFF526054)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFD6E0D6)
val LightOnSecondaryContainer = Color(0xFF101D14)

val LightTertiary = Color(0xFF7A5A15)
val LightOnTertiary = Color(0xFFFFFFFF)
val LightTertiaryContainer = Color(0xFFFDDFA4)
val LightOnTertiaryContainer = Color(0xFF271900)

val LightError = Color(0xFFBA1A1A)
val LightOnError = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFFFDAD6)
val LightOnErrorContainer = Color(0xFF410002)

val LightBackground = Color(0xFFF7F8F4)
val LightOnBackground = Color(0xFF1A1D19)
val LightSurface = Color(0xFFFCFDFA)
val LightOnSurface = Color(0xFF1A1D19)
val LightSurfaceVariant = Color(0xFFE1E6DC)
val LightOnSurfaceVariant = Color(0xFF454B42)
val LightOutline = Color(0xFF757C71)
val LightOutlineVariant = Color(0xFFCDD4C8)
val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
val LightSurfaceContainerLow = Color(0xFFF6F8F3)
val LightSurfaceContainer = Color(0xFFF0F3EC)
val LightSurfaceContainerHigh = Color(0xFFEAEEE5)
val LightSurfaceContainerHighest = Color(0xFFE4E9DE)

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
    outlineVariant = LightOutlineVariant,
    surfaceContainerLowest = LightSurfaceContainerLowest,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
)
