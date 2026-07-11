package com.dhava.core.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Dhava application theme.
 *
 * Material 3 Expressive theme with the expressive motion scheme. Dark is the
 * primary/default look; the light scheme and (optional) Android 12+ dynamic
 * color are secondary.
 *
 * @param darkTheme whether to use the dark scheme. Defaults to the system
 *   setting, but dark is the look the app is designed around.
 * @param dynamicColor use Android 12+ wallpaper-based dynamic color instead of
 *   the Dhava brand scheme. Off by default so the dirt-red identity wins.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DhavaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DhavaDarkColorScheme
        else -> DhavaLightColorScheme
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        typography = DhavaTypography,
        content = content,
    )
}
