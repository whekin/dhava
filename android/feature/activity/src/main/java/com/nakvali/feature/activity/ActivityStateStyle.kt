package com.nakvali.feature.activity

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
import com.nakvali.core.ui.NakvaliSpacing
import com.nakvali.core.ui.NakvaliTheme
import com.nakvali.fusion.ActivityState

internal const val ACTIVITY_STATE_PROPERTY = "activity_state"
internal const val ACTIVITY_CONFIDENCE_PROPERTY = "activity_confidence"

internal const val ACTIVITY_STATE_UNCLASSIFIED = "unclassified"
internal const val ACTIVITY_STATE_UNKNOWN = "unknown"
internal const val ACTIVITY_STATE_STILL = "still"
internal const val ACTIVITY_STATE_DOWNHILL = "downhill"
internal const val ACTIVITY_STATE_TRANSIT = "transit"
internal const val ACTIVITY_STATE_LIKELY_MOTORIZED = "likely_motorized"

private const val ACTIVITY_STATE_LEGEND_DESCRIPTION =
    "Track state: green solid downhill, beige solid transit, blue dashed likely transport, ring stop, gray dotted uncertain"

internal data class ActivityStateColors(
    val downhill: Color,
    val transit: Color,
    val likelyMotorized: Color,
    val still: Color,
    val unknown: Color,
)

/**
 * The highlight laid under a stretch that is an authored segment.
 *
 * Deliberately not one of the activity-state colours: a segment is not a
 * *kind* of riding, it is a claim on a piece of trail, and it has to be
 * readable underneath whichever state colour sits on top of it.
 */
@Composable
internal fun rememberSegmentHighlightColor(): Color {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return remember(dark) { if (dark) Color(0xFFF2B457) else Color(0xFFB2751A) }
}

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
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        shadowElevation = 3.dp,
    ) {
        ActivityStateLegendContent(colors = colors)
    }
}

@Composable
internal fun ActivityStateLegendContent(
    colors: ActivityStateColors,
    modifier: Modifier = Modifier,
    /** Null when this ride crossed no authored segment, so the row is absent. */
    segmentColor: Color? = null,
) {
    Column(
        modifier = modifier
            .semantics { contentDescription = ACTIVITY_STATE_LEGEND_DESCRIPTION }
            .padding(
                horizontal = NakvaliSpacing.medium,
                vertical = NakvaliSpacing.small,
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
        if (segmentColor != null) {
            StateLegendItem(
                label = "Segment",
                color = segmentColor,
                kind = StateSampleKind.Highlight,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private enum class StateSampleKind { Solid, SolidThin, Dashed, Dotted, Stop, Highlight }

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
                StateSampleKind.Highlight -> {
                    // The glow and the line on top of it, as the map draws them.
                    drawLine(
                        color = color.copy(alpha = 0.22f),
                        start = Offset(0f, centerY),
                        end = Offset(size.width, centerY),
                        strokeWidth = 12.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = color,
                        start = Offset(0f, centerY),
                        end = Offset(size.width, centerY),
                        strokeWidth = 3.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
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
    NakvaliTheme(darkTheme = true) {
        ActivityStateLegend(
            colors = rememberActivityStateColors(),
            modifier = Modifier.padding(NakvaliSpacing.medium),
        )
    }
}

@Preview(name = "Track state · light", widthDp = 220, heightDp = 140)
@Composable
private fun ActivityStateLegendLightPreview() {
    NakvaliTheme(darkTheme = false) {
        ActivityStateLegend(
            colors = rememberActivityStateColors(),
            modifier = Modifier.padding(NakvaliSpacing.medium),
        )
    }
}
