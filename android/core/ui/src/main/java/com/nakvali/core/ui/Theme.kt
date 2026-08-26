package com.nakvali.core.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Nakvali application theme.
 *
 * Material 3 Expressive theme with the expressive motion scheme.
 *
 * **Both schemes are supported on purpose.** Following the system used to be an
 * unexamined default — the light scheme had never been looked at, and a sheet
 * drawn in `surface` over a light basemap lost its edge completely. That is
 * fixed rather than sidestepped: light now spells out its own surface-container
 * ramp instead of leaving it to Material's derivation, and panels over a map ask
 * for an outline. Forcing dark would have been the wrong call for a daylight
 * sport — a phone clamped to a bar in sun is far more legible light — and
 * forcing light is wrong at dusk. The rider's system setting decides.
 *
 * @param darkTheme whether to use the dark scheme. Defaults to the system
 *   setting; both schemes are designed, not just tolerated.
 * @param dynamicColor use Android 12+ wallpaper-based dynamic color instead of
 *   the Nakvali brand scheme. Off by default so the trail-green identity wins.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NakvaliTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> NakvaliDarkColorScheme
        else -> NakvaliLightColorScheme
    }

    // System bar icons follow the app's own scheme rather than `enableEdgeToEdge`'s
    // one-shot guess: the activity reads the night-mode configuration once at
    // startup, so a scheme decided here — or changed while the app is alive —
    // left white icons sitting on a light status bar.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        shapes = NakvaliShapes,
        typography = NakvaliTypography,
        content = content,
    )
}
