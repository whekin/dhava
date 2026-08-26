package com.nakvali.core.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Tabular figures plus a slashed zero. Both matter on a phone clamped to a bar:
 * `tnum` stops the speed readout jittering sideways as digits change, and `zero`
 * keeps a zero from being read as a capital O at a glance. Measurements only —
 * running prose keeps the plain zero.
 */
private const val MetricNumerals = "tnum, zero"

/** Tabular figures without the slash, for numbers that sit inside sentences. */
private const val TabularNumerals = "tnum"

/**
 * Archivo carries both axes Nakvali needs: `wght` 100..900 and `wdth` 62..125.
 * One variable file covers every role, and the width axis is what separates the
 * signage voice from the reading voice — see [NakvaliSignage].
 */
private fun archivo(weight: FontWeight, width: Float) = Font(
    resId = R.font.archivo_variable,
    weight = weight,
    variationSettings = FontVariation.Settings(
        weight = weight,
        style = FontStyle.Normal,
        FontVariation.width(width),
    ),
)

/** Reading and instrument voice: normal width, the whole weight range. */
val NakvaliText = FontFamily(
    archivo(FontWeight.Normal, NORMAL_WIDTH),
    archivo(FontWeight.Medium, NORMAL_WIDTH),
    archivo(FontWeight.SemiBold, NORMAL_WIDTH),
    archivo(FontWeight.Bold, NORMAL_WIDTH),
)

/**
 * Signage voice: slightly expanded. Used for screen headlines and the all-caps
 * eyebrows above them — the one place in the app where type has a voice rather
 * than a job. Expanded rather than condensed because trail markers are read
 * from a distance, not squeezed into a column.
 */
val NakvaliSignage = FontFamily(
    archivo(FontWeight.SemiBold, SIGNAGE_WIDTH),
    archivo(FontWeight.Bold, SIGNAGE_WIDTH),
)

private const val NORMAL_WIDTH = 100f
private const val SIGNAGE_WIDTH = 113f

/**
 * Nakvali typography.
 *
 * Display styles are deliberately oversized: the record screen shows live
 * timing (speed, elapsed time, vertical drop) that must be readable at a
 * glance with the phone mounted on bars or strapped to a backpack.
 *
 * Archivo is more compact than the platform default at the same point size, so
 * tracking is looser here than the Roboto-era values it replaces.
 */
val NakvaliTypography = Typography(
    // Every style below inherits this unless it names its own family. Added in
    // material3 1.5.0-alpha19; before it, the family had to be repeated fifteen times.
    fontFamily = NakvaliText,
    // Hero numerals — live timer / current speed.
    displayLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 96.sp,
        lineHeight = 96.sp,
        letterSpacing = (-3).sp,
        fontFeatureSettings = MetricNumerals,
    ),
    // Secondary live metrics.
    displayMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 64.sp,
        lineHeight = 66.sp,
        letterSpacing = (-1.6).sp,
        fontFeatureSettings = MetricNumerals,
    ),
    displaySmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 44.sp,
        lineHeight = 48.sp,
        letterSpacing = (-0.9).sp,
        fontFeatureSettings = MetricNumerals,
    ),
    // Screen headlines speak in the signage voice.
    headlineLarge = TextStyle(
        fontFamily = NakvaliSignage,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.8).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = NakvaliSignage,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = NakvaliSignage,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.3).sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 23.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = TabularNumerals,
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = TabularNumerals,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp,
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    // Navigation labels and inline hints. Deliberately *not* the signage voice:
    // expanded widths plus tracking pushed "Activities" past its column in the
    // navigation bar and wrapped it onto two lines.
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
    ),
    // The eyebrow above every screen headline, and the unit under every metric.
    // Short strings only, which is what lets it carry the tracking.
    labelSmall = TextStyle(
        fontFamily = NakvaliSignage,
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.5.sp,
    ),
)
