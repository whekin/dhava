package com.nakvali.feature.activity

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nakvali.core.ui.NakvaliSpacing
import com.nakvali.fusion.ActivityState
import com.nakvali.fusion.RideProfile
import com.nakvali.fusion.RideProfilePoint
import java.util.Locale
import kotlin.math.abs

/**
 * Pause-aware elevation instrument for Activity Detail.
 *
 * Geometry, elevation and gradient are authored by Rust. Compose only renders
 * the returned samples and maps a horizontal gesture to the nearest one.
 */
@Composable
internal fun ActivityElevationProfile(
    profile: RideProfile,
    selected: RideProfilePoint?,
    selectedTrackPoint: MapTrackPoint?,
    onSelected: (RideProfilePoint) -> Unit,
    modifier: Modifier = Modifier,
) {
    val descendColor = MaterialTheme.colorScheme.primary
    val climbColor = MaterialTheme.colorScheme.tertiary
    val flatColor = MaterialTheme.colorScheme.outline
    val guideColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val selectedColor = MaterialTheme.colorScheme.onSurface
    val surfaceColor = MaterialTheme.colorScheme.surface
    val currentOnSelected = rememberUpdatedState(onSelected)
    val drawable = profile.points.size >= 2 &&
        profile.minAltitudeM != null &&
        profile.maxAltitudeM != null

    fun inspectAt(x: Float, width: Float) {
        if (width <= 0f) return
        val distance = (x / width).coerceIn(0f, 1f) * profile.lengthM
        profile.closestPointToDistance(distance)?.let(currentOnSelected.value)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NakvaliSpacing.small),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "ELEVATION",
                style = MaterialTheme.typography.labelSmall,
                color = labelColor,
            )
            Text(
                text = if (drawable) "Drag to inspect" else "Unavailable",
                style = MaterialTheme.typography.labelSmall,
                color = labelColor,
            )
        }

        Text(
            text = selected?.selectionSummary(selectedTrackPoint)
                ?: profile.rangeSummary(),
            style = MaterialTheme.typography.titleMedium,
            color = if (selected == null) labelColor else selectedColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(156.dp)
                .semantics {
                    contentDescription = profile.accessibilityDescription(selected)
                }
                .pointerInput(profile.points) {
                    detectTapGestures { offset ->
                        inspectAt(offset.x, size.width.toFloat())
                    }
                }
                .pointerInput(profile.points) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            inspectAt(offset.x, size.width.toFloat())
                        },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            inspectAt(change.position.x, size.width.toFloat())
                        },
                    )
                },
        ) {
            val baseline = size.height - 18.dp.toPx()
            drawLine(
                color = guideColor,
                start = Offset(0f, baseline),
                end = Offset(size.width, baseline),
                strokeWidth = 1.dp.toPx(),
            )
            if (!drawable) return@Canvas

            val minimum = profile.minAltitudeM ?: return@Canvas
            val maximum = profile.maxAltitudeM ?: return@Canvas
            val range = (maximum - minimum).coerceAtLeast(1.0)
            val top = 8.dp.toPx()
            val chartHeight = baseline - top
            fun x(point: RideProfilePoint): Float = if (profile.lengthM <= 0.0) {
                0f
            } else {
                (point.distanceM / profile.lengthM * size.width).toFloat()
            }
            fun y(altitude: Double): Float =
                (top + (maximum - altitude) / range * chartHeight).toFloat()

            // A restrained fill gives the profile enough mass without turning
            // the detail sheet into a generic analytics dashboard.
            profile.points.continuousAltitudeSections().forEach { section ->
                if (section.size < 2) return@forEach
                val fill = Path().apply {
                    moveTo(x(section.first()), baseline)
                    section.forEach { point ->
                        point.altitudeM?.let { lineTo(x(point), y(it)) }
                    }
                    lineTo(x(section.last()), baseline)
                    close()
                }
                drawPath(fill, color = descendColor.copy(alpha = 0.11f))
            }

            profile.points.forEachIndexed { index, point ->
                if (index == 0 || !point.continues) return@forEachIndexed
                val previous = profile.points[index - 1]
                val from = previous.altitudeM ?: return@forEachIndexed
                val to = point.altitudeM ?: return@forEachIndexed
                val color = when {
                    (point.gradientPercent ?: 0.0) <= -1.0 -> descendColor
                    (point.gradientPercent ?: 0.0) >= 1.0 -> climbColor
                    else -> flatColor
                }
                drawLine(
                    color = color,
                    start = Offset(x(previous), y(from)),
                    end = Offset(x(point), y(to)),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }

            selected?.let { point ->
                val altitude = point.altitudeM ?: return@let
                val selectedX = x(point)
                val selectedY = y(altitude)
                drawLine(
                    color = selectedColor.copy(alpha = 0.72f),
                    start = Offset(selectedX, top),
                    end = Offset(selectedX, baseline),
                    strokeWidth = 1.5.dp.toPx(),
                )
                drawCircle(
                    color = surfaceColor,
                    radius = 7.dp.toPx(),
                    center = Offset(selectedX, selectedY),
                )
                drawCircle(
                    color = selectedColor,
                    radius = 4.dp.toPx(),
                    center = Offset(selectedX, selectedY),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("DESCENT", style = MaterialTheme.typography.labelSmall, color = descendColor)
            Text("CLIMB", style = MaterialTheme.typography.labelSmall, color = climbColor)
        }
    }
}

internal fun RideProfile.closestPointToDistance(distanceM: Double): RideProfilePoint? =
    points.minByOrNull { point -> abs(point.distanceM - distanceM) }

private fun List<RideProfilePoint>.continuousAltitudeSections(): List<List<RideProfilePoint>> {
    val sections = mutableListOf<MutableList<RideProfilePoint>>()
    var breakBefore = true
    forEach { point ->
        if (point.altitudeM == null) {
            breakBefore = true
            return@forEach
        }
        if (sections.isEmpty() || breakBefore || !point.continues) {
            sections.add(mutableListOf())
        }
        sections.last() += point
        breakBefore = false
    }
    return sections
}

private fun RideProfile.rangeSummary(): String {
    val minimum = minAltitudeM ?: return "No usable elevation profile"
    val maximum = maxAltitudeM ?: return "No usable elevation profile"
    return String.format(
        Locale.US,
        "%.0f–%.0f m · %s",
        minimum,
        maximum,
        formatProfileDistance(lengthM),
    )
}

private fun RideProfilePoint.selectionSummary(trackPoint: MapTrackPoint?): String = buildString {
    append(formatProfileDistance(distanceM))
    altitudeM?.let { append(String.format(Locale.US, " · %.0f m", it)) }
    gradientPercent?.let { append(String.format(Locale.US, " · %+.0f%%", it)) }
    trackPoint?.speedMps?.let { append(String.format(Locale.US, " · %.1f km/h", it * 3.6)) }
    trackPoint?.activityState?.displayLabel()?.let { append(" · $it") }
}

private fun formatProfileDistance(meters: Double): String = when {
    meters >= 1_000.0 -> String.format(Locale.US, "%.2f km", meters / 1_000.0)
    else -> String.format(Locale.US, "%.0f m", meters)
}

private fun ActivityState.displayLabel(): String = when (this) {
    ActivityState.UNKNOWN -> "Uncertain"
    ActivityState.STILL -> "Still"
    ActivityState.DOWNHILL -> "Downhill"
    ActivityState.TRANSIT -> "Transit"
    ActivityState.LIKELY_MOTORIZED -> "Transport"
}

private fun RideProfile.accessibilityDescription(selected: RideProfilePoint?): String = buildString {
    append("Elevation profile")
    minAltitudeM?.let { append(String.format(Locale.US, ", low %.0f meters", it)) }
    maxAltitudeM?.let { append(String.format(Locale.US, ", high %.0f meters", it)) }
    selected?.let {
        append(", selected ")
        append(formatProfileDistance(it.distanceM))
        it.altitudeM?.let { altitude ->
            append(String.format(Locale.US, " at %.0f meters elevation", altitude))
        }
    }
    append(". Drag horizontally to inspect the ride on the map.")
}
