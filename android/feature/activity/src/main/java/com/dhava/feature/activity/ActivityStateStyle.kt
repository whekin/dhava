package com.dhava.feature.activity

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dhava.core.ui.DhavaSpacing
import com.dhava.core.ui.DhavaTheme
import com.dhava.fusion.ActivityState

internal const val ACTIVITY_STATE_PROPERTY = "activity_state"
internal const val ACTIVITY_CONFIDENCE_PROPERTY = "activity_confidence"

internal const val ACTIVITY_STATE_UNCLASSIFIED = "unclassified"
internal const val ACTIVITY_STATE_UNKNOWN = "unknown"
internal const val ACTIVITY_STATE_STILL = "still"
internal const val ACTIVITY_STATE_DOWNHILL = "downhill"
internal const val ACTIVITY_STATE_TRANSIT = "transit"
internal const val ACTIVITY_STATE_LIKELY_MOTORIZED = "likely_motorized"

internal data class ActivityStateColors(
    val downhill: Color,
    val transit: Color,
    val likelyMotorized: Color,
    val still: Color,
    val unknown: Color,
)

@Composable
internal fun rememberActivityStateColors(): ActivityStateColors {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    return remember(colors, dark) {
        ActivityStateColors(
            downhill = colors.primary,
            transit = colors.secondary,
            likelyMotorized = if (dark) Color(0xFF79C7D0) else Color(0xFF356B73),
            still = colors.onSurfaceVariant,
            unknown = colors.outline,
        )
    }
}

internal fun ActivityState?.styleKey(): String = when (this) {
    null -> ACTIVITY_STATE_UNCLASSIFIED
    ActivityState.UNKNOWN -> ACTIVITY_STATE_UNKNOWN
    ActivityState.STILL -> ACTIVITY_STATE_STILL
    ActivityState.DOWNHILL -> ACTIVITY_STATE_DOWNHILL
    ActivityState.TRANSIT -> ACTIVITY_STATE_TRANSIT
    ActivityState.LIKELY_MOTORIZED -> ACTIVITY_STATE_LIKELY_MOTORIZED
}

@Composable
internal fun ActivityStateLegend(
    colors: ActivityStateColors,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.semantics {
            contentDescription =
                "Track state: orange solid downhill, beige solid transit, blue dashed likely transport, ring stop, gray dotted uncertain"
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
                text = "TRACK STATE",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StateLegendItem(
                    label = "DH",
                    color = colors.downhill,
                    kind = StateSampleKind.Solid,
                    modifier = Modifier.weight(1f),
                )
                StateLegendItem(
                    label = "Transit",
                    color = colors.transit,
                    kind = StateSampleKind.SolidThin,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StateLegendItem(
                    label = "Transport?",
                    color = colors.likelyMotorized,
                    kind = StateSampleKind.Dashed,
                    modifier = Modifier.weight(1f),
                )
                StateLegendItem(
                    label = "Stop",
                    color = colors.still,
                    kind = StateSampleKind.Stop,
                    modifier = Modifier.weight(1f),
                )
            }
            StateLegendItem(
                label = "Uncertain",
                color = colors.unknown,
                kind = StateSampleKind.Dotted,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private enum class StateSampleKind { Solid, SolidThin, Dashed, Dotted, Stop }

@Composable
private fun StateLegendItem(
    label: String,
    color: Color,
    kind: StateSampleKind,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Canvas(modifier = Modifier.size(width = 24.dp, height = 16.dp)) {
            val centerY = size.height / 2f
            when (kind) {
                StateSampleKind.Stop -> {
                    drawCircle(
                        color = color,
                        radius = size.minDimension * 0.25f,
                        center = Offset(size.width / 2f, centerY),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
                else -> drawLine(
                    color = color,
                    start = Offset(0f, centerY),
                    end = Offset(size.width, centerY),
                    strokeWidth = when (kind) {
                        StateSampleKind.Solid -> 4.dp.toPx()
                        StateSampleKind.SolidThin -> 3.dp.toPx()
                        else -> 2.5.dp.toPx()
                    },
                    cap = StrokeCap.Round,
                    pathEffect = when (kind) {
                        StateSampleKind.Dashed -> PathEffect.dashPathEffect(
                            floatArrayOf(7.dp.toPx(), 5.dp.toPx()),
                        )
                        StateSampleKind.Dotted -> PathEffect.dashPathEffect(
                            floatArrayOf(1.dp.toPx(), 5.dp.toPx()),
                        )
                        else -> null
                    },
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
    }
}

@Preview(name = "Track state · dark", widthDp = 220, heightDp = 140)
@Composable
private fun ActivityStateLegendDarkPreview() {
    DhavaTheme(darkTheme = true) {
        ActivityStateLegend(
            colors = rememberActivityStateColors(),
            modifier = Modifier.padding(DhavaSpacing.medium),
        )
    }
}

@Preview(name = "Track state · light", widthDp = 220, heightDp = 140)
@Composable
private fun ActivityStateLegendLightPreview() {
    DhavaTheme(darkTheme = false) {
        ActivityStateLegend(
            colors = rememberActivityStateColors(),
            modifier = Modifier.padding(DhavaSpacing.medium),
        )
    }
}
