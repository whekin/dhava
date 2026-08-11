package com.nakvali.feature.activity

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
import com.nakvali.core.ui.NakvaliSpacing
import com.nakvali.core.ui.NakvaliTheme

internal const val GPS_ACCURACY_PROPERTY = "accuracy_m"
internal const val UNKNOWN_GPS_ACCURACY_STYLE_VALUE = -1.0

private const val GPS_ACCURACY_LEGEND_DESCRIPTION =
    "GPS accuracy scale: mint 5 meters or better, yellow 10 meters, gold 20 meters, red above 20 meters"

internal data class GpsAccuracyColors(
    val good: Color,
    val fair: Color,
    val weak: Color,
    val rejected: Color,
    val unknown: Color,
)

@Composable
internal fun rememberGpsAccuracyColors(): GpsAccuracyColors {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    return remember(colors, dark) {
        GpsAccuracyColors(
            // The finalized path is now green. A cool mint keeps accurate GPS
            // fixes visibly separate when their dots sit above it in Compare.
            good = if (dark) Color(0xFF9FE7E2) else Color(0xFF176B68),
            fair = if (dark) Color(0xFFFFE082) else Color(0xFF8A6400),
            weak = if (dark) Color(0xFFD7A928) else Color(0xFFA06F00),
            rejected = colors.error,
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
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        shadowElevation = 3.dp,
    ) {
        GpsAccuracyLegendContent(colors = colors)
    }
}

@Composable
internal fun GpsAccuracyLegendContent(
    colors: GpsAccuracyColors,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .semantics { contentDescription = GPS_ACCURACY_LEGEND_DESCRIPTION }
            .padding(
                horizontal = NakvaliSpacing.medium,
                vertical = NakvaliSpacing.small,
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
                            0.85f to colors.weak,
                            0.96f to colors.weak,
                            1f to colors.rejected,
                        ),
                    ),
                    shape = CircleShape,
                ),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            AccuracyLegendLabel("≤5", colors.good)
            AccuracyLegendLabel("10", colors.fair)
            AccuracyLegendLabel("20", colors.weak)
            AccuracyLegendLabel(">20 m", colors.rejected)
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
    NakvaliTheme(darkTheme = true) {
        GpsAccuracyLegend(
            colors = rememberGpsAccuracyColors(),
            modifier = Modifier.padding(NakvaliSpacing.medium),
        )
    }
}

@Preview(name = "GPS accuracy · light", widthDp = 220, heightDp = 96)
@Composable
private fun GpsAccuracyLegendLightPreview() {
    NakvaliTheme(darkTheme = false) {
        GpsAccuracyLegend(
            colors = rememberGpsAccuracyColors(),
            modifier = Modifier.padding(NakvaliSpacing.medium),
        )
    }
}
