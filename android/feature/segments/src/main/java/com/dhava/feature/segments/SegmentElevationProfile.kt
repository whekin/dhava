package com.dhava.feature.segments

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dhava.core.recording.StoredSegment
import com.dhava.core.ui.DhavaMetric
import com.dhava.core.ui.DhavaPanel
import com.dhava.core.ui.DhavaSectionLabel
import com.dhava.core.ui.DhavaSpacing

/**
 * Compact elevation story for one authored segment.
 *
 * The sampled profile and accumulated climb/descent are produced by Rust when
 * the segment is authored. This composable only scales those values to pixels.
 */
@Composable
internal fun SegmentElevationProfile(
    segment: StoredSegment,
    modifier: Modifier = Modifier,
) {
    val profile = segment.elevationProfile
    if (profile.size < 2) return

    val minAltitude = remember(profile) { profile.minOf { it.altitudeM } }
    val maxAltitude = remember(profile) { profile.maxOf { it.altitudeM } }
    val maxDistance = remember(profile) { profile.maxOf { it.distanceM }.coerceAtLeast(1.0) }
    val altitudeRange = (maxAltitude - minAltitude).coerceAtLeast(1.0)
    val lineColor = MaterialTheme.colorScheme.primary
    val guideColor = MaterialTheme.colorScheme.outlineVariant

    Column(modifier = modifier) {
        DhavaSectionLabel("Elevation")
        Spacer(Modifier.height(DhavaSpacing.small))
        DhavaPanel(
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = buildString {
                        append("Elevation profile from ")
                        append(SegmentFormat.altitude(profile.first().altitudeM))
                        append(" to ")
                        append(SegmentFormat.altitude(profile.last().altitudeM))
                        segment.ascentM?.let {
                            append(", ")
                            append(SegmentFormat.ascent(it))
                            append(" climb")
                        }
                        segment.descentM?.let {
                            append(", ")
                            append(SegmentFormat.descent(it))
                            append(" descent")
                        }
                    }
                },
        ) {
            Column(modifier = Modifier.padding(DhavaSpacing.large)) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    DhavaMetric(
                        value = SegmentFormat.ascent(segment.ascentM) ?: "—",
                        label = "Climb",
                        modifier = Modifier.weight(1f),
                    )
                    DhavaMetric(
                        value = SegmentFormat.descent(segment.descentM) ?: "—",
                        label = "Descent",
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(DhavaSpacing.medium))
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                ) {
                    val bottom = size.height
                    drawLine(
                        color = guideColor,
                        start = Offset(0f, bottom),
                        end = Offset(size.width, bottom),
                        strokeWidth = 1.dp.toPx(),
                    )

                    val line = Path()
                    profile.forEachIndexed { index, point ->
                        val x = (point.distanceM / maxDistance * size.width).toFloat()
                        val y = (
                            (maxAltitude - point.altitudeM) /
                                altitudeRange *
                                (size.height - 6.dp.toPx())
                            ).toFloat()
                        if (index == 0) line.moveTo(x, y) else line.lineTo(x, y)
                    }
                    val fill = Path().apply {
                        addPath(line)
                        lineTo(size.width, bottom)
                        lineTo(0f, bottom)
                        close()
                    }
                    drawPath(fill, color = lineColor.copy(alpha = 0.16f))
                    drawPath(
                        line,
                        color = lineColor,
                        style = Stroke(width = 3.dp.toPx()),
                    )
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = SegmentFormat.altitude(profile.first().altitudeM),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = SegmentFormat.altitude(profile.last().altitudeM),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
