package com.nakvali.feature.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nakvali.core.recording.CanonicalElevationSource
import com.nakvali.core.recording.CanonicalQuality
import com.nakvali.core.ui.NakvaliSpacing
import com.nakvali.core.ui.NakvaliTheme
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Compact quality chips for the detail panel. All numbers are Rust-derived
 * (heuristic v0, display only); the row is simply hidden while the canonical
 * artifact is still computing or absent.
 */

internal enum class GpsQualityBucket(val label: String) {
    GOOD("Good"),
    FAIR("Fair"),
    POOR("Poor"),
}

internal fun gpsQualityBucket(medianAccuracyM: Double?): GpsQualityBucket? = when {
    medianAccuracyM == null || !medianAccuracyM.isFinite() -> null
    medianAccuracyM <= 5.0 -> GpsQualityBucket.GOOD
    medianAccuracyM <= 10.0 -> GpsQualityBucket.FAIR
    else -> GpsQualityBucket.POOR
}

internal fun elevationChipText(quality: CanonicalQuality): String = when (quality.elevationSource) {
    CanonicalElevationSource.BAROMETRIC -> "Elevation: Barometric"
    CanonicalElevationSource.GPS_INTERPOLATED ->
        quality.elevationUncertaintyM
            ?.let { "Elevation: GPS net (±${it.roundToInt()} m)" }
            ?: "Elevation: GPS net"
    CanonicalElevationSource.NONE -> "No elevation"
}

internal fun descentMetricLabel(quality: CanonicalQuality?): String =
    if (quality?.elevationSource == CanonicalElevationSource.GPS_INTERPOLATED) {
        "Net drop"
    } else {
        "Descent"
    }

internal fun ascentMetricLabel(quality: CanonicalQuality?): String =
    if (quality?.elevationSource == CanonicalElevationSource.GPS_INTERPOLATED) {
        "Net climb"
    } else {
        "Ascent"
    }

/** Null when the recording carries no accuracy estimates to bucket. */
internal fun gpsChipText(quality: CanonicalQuality): String? {
    val bucket = gpsQualityBucket(quality.medianAccuracyM) ?: return null
    val base = String.format(
        Locale.US,
        "GPS: %s · %.1f m",
        bucket.label,
        quality.medianAccuracyM,
    )
    if (quality.gpsGapCount <= 0) return base
    val gaps = if (quality.gpsGapCount == 1) "1 gap" else "${quality.gpsGapCount} gaps"
    return "$base · $gaps"
}

@Composable
internal fun ActivityQualityRow(quality: CanonicalQuality, modifier: Modifier = Modifier) {
    var showDetails by remember { mutableStateOf(false) }
    val accuracyColors = rememberGpsAccuracyColors()

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.small),
    ) {
        val elevationTone = when (quality.elevationSource) {
            CanonicalElevationSource.BAROMETRIC -> ChipTone(
                container = MaterialTheme.colorScheme.tertiaryContainer,
                content = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            CanonicalElevationSource.GPS_INTERPOLATED -> ChipTone(
                container = MaterialTheme.colorScheme.surfaceContainerHighest,
                content = accuracyColors.fair,
            )
            CanonicalElevationSource.NONE -> ChipTone(
                container = MaterialTheme.colorScheme.surfaceContainerHighest,
                content = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        QualityChip(
            text = elevationChipText(quality),
            tone = elevationTone,
            onClick = { showDetails = true },
        )

        gpsChipText(quality)?.let { text ->
            val tone = when (gpsQualityBucket(quality.medianAccuracyM)) {
                GpsQualityBucket.GOOD -> ChipTone(
                    container = MaterialTheme.colorScheme.tertiaryContainer,
                    content = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                GpsQualityBucket.FAIR -> ChipTone(
                    container = MaterialTheme.colorScheme.surfaceContainerHighest,
                    content = accuracyColors.fair,
                )
                else -> ChipTone(
                    container = MaterialTheme.colorScheme.surfaceContainerHighest,
                    content = accuracyColors.weak,
                )
            }
            QualityChip(text = text, tone = tone, onClick = { showDetails = true })
        }
    }

    if (showDetails) {
        QualityDetailsDialog(quality = quality, onDismiss = { showDetails = false })
    }
}

private data class ChipTone(val container: Color, val content: Color)

@Composable
private fun QualityChip(text: String, tone: ChipTone, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = tone.container,
        contentColor = tone.content,
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun QualityDetailsDialog(quality: CanonicalQuality, onDismiss: () -> Unit) {
    val acceptedPercent = if (quality.gpsFixCount > 0) {
        quality.gpsAcceptedCount * 100.0 / quality.gpsFixCount
    } else {
        0.0
    }
    val uncertaintyLabel =
        if (quality.elevationSource == CanonicalElevationSource.GPS_INTERPOLATED) {
            "Net uncertainty"
        } else {
            "Elevation uncertainty"
        }
    val rows = buildList {
        add("GPS fixes" to "${quality.gpsFixCount}")
        add(
            "Accepted (≤20 m)" to String.format(
                Locale.US,
                "%d (%.0f%%)",
                quality.gpsAcceptedCount,
                acceptedPercent,
            ),
        )
        add("Median accuracy" to formatMeters(quality.medianAccuracyM))
        add("P90 accuracy" to formatMeters(quality.p90AccuracyM))
        add("Gaps > 5 s" to "${quality.gpsGapCount}")
        if (quality.gpsGapCount > 0) {
            add("Longest gap" to String.format(Locale.US, "%.1f s", quality.longestGapS))
        }
        add("Barometer samples" to "${quality.baroSampleCount}")
        add(
            "Elevation source" to when (quality.elevationSource) {
                CanonicalElevationSource.BAROMETRIC -> "Barometric"
                CanonicalElevationSource.GPS_INTERPOLATED -> "GPS-interpolated track"
                CanonicalElevationSource.NONE -> "None"
            },
        )
        add(
            "Elevation metric" to when (quality.elevationSource) {
                CanonicalElevationSource.BAROMETRIC -> "Accumulated ascent/descent"
                CanonicalElevationSource.GPS_INTERPOLATED -> "Net change per section"
                CanonicalElevationSource.NONE -> "—"
            },
        )
        add(
            uncertaintyLabel to (
                quality.elevationUncertaintyM
                    ?.let { String.format(Locale.US, "±%.1f m", it) }
                    ?: "—"
                ),
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Signal quality") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(NakvaliSpacing.small)) {
                rows.forEach { (label, value) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = value,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

private fun formatMeters(value: Double?): String =
    value?.let { String.format(Locale.US, "%.1f m", it) } ?: "—"

@Preview(name = "Quality chips · GPS only", widthDp = 380)
@Composable
private fun ActivityQualityRowGpsOnlyPreview() {
    NakvaliTheme(darkTheme = true) {
        ActivityQualityRow(
            quality = CanonicalQuality(
                elevationSource = CanonicalElevationSource.GPS_INTERPOLATED,
                baroSampleCount = 0,
                gpsFixCount = 167,
                gpsAcceptedCount = 158,
                medianAccuracyM = 3.8,
                p90AccuracyM = 9.4,
                gpsGapCount = 2,
                longestGapS = 12.4,
                elevationUncertaintyM = 18.8,
            ),
            modifier = Modifier.padding(NakvaliSpacing.medium),
        )
    }
}

@Preview(name = "Quality chips · barometric", widthDp = 380)
@Composable
private fun ActivityQualityRowBarometricPreview() {
    NakvaliTheme(darkTheme = false) {
        ActivityQualityRow(
            quality = CanonicalQuality(
                elevationSource = CanonicalElevationSource.BAROMETRIC,
                baroSampleCount = 2_512,
                gpsFixCount = 640,
                gpsAcceptedCount = 640,
                medianAccuracyM = 4.2,
                p90AccuracyM = 6.9,
                gpsGapCount = 0,
                longestGapS = 0.0,
                elevationUncertaintyM = 3.1,
            ),
            modifier = Modifier.padding(NakvaliSpacing.medium),
        )
    }
}
