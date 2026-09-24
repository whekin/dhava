package com.nakvali.feature.activity

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nakvali.core.ui.NakvaliMetric
import com.nakvali.core.ui.NakvaliSectionLabel
import com.nakvali.core.ui.NakvaliSpacing
import com.nakvali.core.ui.NakvaliTheme
import com.nakvali.fusion.AirtimeWindow
import com.nakvali.fusion.RideAnalysis
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A selected sensor event, with measurement provenance kept close to each number. */
@Composable
internal fun AirtimeAnalysisSection(
    analysis: RideAnalysis,
    selectedIndex: Int?,
    placedEventIndices: Set<Int>,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val windows = analysis.airtimeWindows
    if (windows.isEmpty()) return
    val index = selectedIndex?.takeIf { it in windows.indices } ?: 0
    val event = windows[index]
    val longestMs = windows.maxOf { it.durationMs }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NakvaliSpacing.large),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(NakvaliSpacing.xSmall)) {
            NakvaliSectionLabel("Possible airtime")
            Text(
                "Phone-sensor candidates · not verified jumps",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.large),
        ) {
            NakvaliMetric(windows.size.toString(), "Events", Modifier.weight(1f))
            NakvaliMetric(formatAirSeconds(analysis.airtimeTotalMs), "Total air", Modifier.weight(1f))
            NakvaliMetric(formatAirSeconds(longestMs), "Longest", Modifier.weight(1f))
        }
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(NakvaliSpacing.large),
                verticalArrangement = Arrangement.spacedBy(NakvaliSpacing.medium),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "AIR ${index + 1} / ${windows.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = { onSelected(index - 1) },
                        enabled = index > 0,
                    ) { Icon(Icons.Filled.KeyboardArrowLeft, "Previous airtime") }
                    IconButton(
                        onClick = { onSelected(index + 1) },
                        enabled = index < windows.lastIndex,
                    ) { Icon(Icons.Filled.KeyboardArrowRight, "Next airtime") }
                }
                Column {
                    Text(
                        formatAirSeconds(event.durationMs),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "airborne candidate",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AirtimeTimeline()
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        formatEventTime(event.startMs),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        formatEventTime(event.startMs + event.durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.large),
                ) {
                    NakvaliMetric(
                        formatPhoneG(event.takeoffPeakG),
                        "Before air · phone",
                        Modifier.weight(1f),
                    )
                    NakvaliMetric(
                        formatPhoneG(event.landingPeakG),
                        "After air · phone",
                        Modifier.weight(1f),
                    )
                }
                Text(
                    "Maximum single phone sample in each 300 ms window. " +
                        "A shock in the mount or trail vibration can raise it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (index in placedEventIndices) {
                        "Approximate location on the track. Zoom in to inspect."
                    } else {
                        "No reliable map position for this event."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            "These peaks include gravity and describe the phone, not rider load. " +
                "Takeoff angle and landing smoothness need mounting calibration and field validation.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AirtimeTimeline() {
    val primary = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(18.dp)) {
        val inset = 6.dp.toPx()
        val y = size.height / 2f
        drawLine(primary, Offset(inset, y), Offset(size.width - inset, y), strokeWidth = 3.dp.toPx())
        drawCircle(primary, radius = 5.dp.toPx(), center = Offset(inset, y))
        drawCircle(primary, radius = 5.dp.toPx(), center = Offset(size.width - inset, y), style = Stroke(2.dp.toPx()))
    }
}

private val eventTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.US)
    .withZone(ZoneId.systemDefault())

private fun formatEventTime(epochMs: Long): String = eventTimeFormatter.format(Instant.ofEpochMilli(epochMs))

internal fun formatAirSeconds(milliseconds: Long): String =
    String.format(Locale.US, "%.2f s", milliseconds.coerceAtLeast(0L) / 1_000.0)

internal fun formatPhoneG(value: Double?): String = value
    ?.takeIf { it.isFinite() && it >= 0.0 }
    ?.let { String.format(Locale.US, "%.1f g", it) }
    ?: "—"

@Preview(name = "Airtime analysis · dark", widthDp = 360)
@Composable
private fun AirtimeAnalysisPreview() {
    NakvaliTheme(darkTheme = true) {
        Surface {
            AirtimeAnalysisSection(
                analysis = RideAnalysis(
                    startedAtMs = 0,
                    endedAtMs = 10_000,
                    movingTimeS = 10.0,
                    distanceM = 120.0,
                    ascentM = 0.0,
                    descentM = 8.0,
                    maxSpeedMps = 12.0,
                    avgMovingSpeedMps = 8.0,
                    airtimeTotalMs = 810,
                    airtimeWindows = listOf(
                        AirtimeWindow(1_000, 420, 3.4, 1.7),
                        AirtimeWindow(5_000, 390, 2.8, null),
                    ),
                    track = emptyList(),
                    gpsCount = 10u,
                    imuCount = 2_000u,
                    algorithmVersion = "preview",
                ),
                selectedIndex = 0,
                placedEventIndices = setOf(0),
                onSelected = {},
                modifier = Modifier.padding(NakvaliSpacing.screen),
            )
        }
    }
}
