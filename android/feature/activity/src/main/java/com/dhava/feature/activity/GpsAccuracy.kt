package com.dhava.feature.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dhava.core.ui.DhavaSpacing
import com.dhava.core.ui.DhavaTheme

internal const val GPS_ACCURACY_PROPERTY = "accuracy_m"
internal const val UNKNOWN_GPS_ACCURACY_STYLE_VALUE = -1.0

internal data class GpsAccuracyColors(
    val good: Color,
    val fair: Color,
    val poor: Color,
    val unknown: Color,
)

@Composable
internal fun rememberGpsAccuracyColors(): GpsAccuracyColors {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    return remember(colors, dark) {
        GpsAccuracyColors(
            good = colors.tertiary,
            fair = if (dark) Color(0xFFFFC857) else Color(0xFF8A5A00),
            poor = colors.error,
            unknown = colors.onSurfaceVariant,
        )
    }
}

@Composable
internal fun GpsAccuracyLegend(
    colors: GpsAccuracyColors,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.semantics {
            contentDescription =
                "GPS accuracy scale: green 5 meters or better, amber 10 meters, red 20 meters or worse"
        },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        shadowElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = DhavaSpacing.medium,
                vertical = DhavaSpacing.small,
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "GPS ACCURACY",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(
                        brush = Brush.horizontalGradient(
                            colorStops = arrayOf(
                                0f to colors.good,
                                0.25f to colors.good,
                                0.5f to colors.fair,
                                1f to colors.poor,
                            ),
                        ),
                        shape = CircleShape,
                    ),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                AccuracyLegendLabel("≤5 m", colors.good)
                AccuracyLegendLabel("10 m", colors.fair)
                AccuracyLegendLabel("20+ m", colors.poor)
            }
        }
    }
}

@Composable
private fun AccuracyLegendLabel(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
    )
}

@Preview(name = "GPS accuracy · dark", widthDp = 220, heightDp = 96)
@Composable
private fun GpsAccuracyLegendDarkPreview() {
    DhavaTheme(darkTheme = true) {
        GpsAccuracyLegend(
            colors = rememberGpsAccuracyColors(),
            modifier = Modifier.padding(DhavaSpacing.medium),
        )
    }
}

@Preview(name = "GPS accuracy · light", widthDp = 220, heightDp = 96)
@Composable
private fun GpsAccuracyLegendLightPreview() {
    DhavaTheme(darkTheme = false) {
        GpsAccuracyLegend(
            colors = rememberGpsAccuracyColors(),
            modifier = Modifier.padding(DhavaSpacing.medium),
        )
    }
}
