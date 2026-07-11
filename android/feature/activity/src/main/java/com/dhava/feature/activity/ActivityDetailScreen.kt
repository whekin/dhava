package com.dhava.feature.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dhava.core.recording.LocalRecording
import com.dhava.core.recording.RecordingStatus
import com.dhava.fusion.RideAnalysis
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Activity detail: glanceable stats header plus the ride track on a map.
 *
 * The map polyline comes from a raw display-only GPS pass
 * (see [GpsTrackReader][com.dhava.core.recording.GpsTrackReader]); all stats
 * are computed by the Rust fusion-core via UniFFI ([RideAnalysis]).
 */
@Composable
fun ActivityDetailScreen(
    recordingId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ActivityDetailViewModel = viewModel(
        key = "activity-detail-$recordingId",
        factory = ActivityDetailViewModel.factory(recordingId),
    ),
) {
    val recording by viewModel.recording.collectAsState()
    val track by viewModel.track.collectAsState()
    val analysis by viewModel.analysis.collectAsState()

    Column(modifier = modifier.fillMaxSize()) {
        Header(recording = recording, onBack = onBack)
        StatTiles(recording = recording, analysis = analysis)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            when (val t = track) {
                TrackState.Loading -> CircularProgressIndicator()
                TrackState.Empty -> Text(
                    text = "No GPS track in this recording",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp),
                )
                is TrackState.Loaded -> TrackMap(
                    points = t.points,
                    trackColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun Header(recording: LocalRecording?, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = recording?.title
                    ?: recording?.let { formatStartTime(it.startedAtMs) }
                    ?: "Activity",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = recording?.let { entry ->
                listOfNotNull(
                    formatStartTime(entry.startedAtMs).takeIf { entry.title != null },
                    entry.bikeName?.let { name ->
                        entry.bikeType?.let { "$name · ${it.label}" } ?: name
                    },
                ).joinToString(" · ")
            }.orEmpty()
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        recording?.let { StatusChip(it.status, Modifier.padding(horizontal = 12.dp)) }
    }
}

@Composable
private fun StatusChip(status: RecordingStatus, modifier: Modifier = Modifier) {
    val (label, container, content) = when (status) {
        RecordingStatus.UPLOADED -> Triple(
            "Uploaded",
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        )
        RecordingStatus.FAILED -> Triple(
            "Upload failed",
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
        RecordingStatus.PENDING_UPLOAD -> Triple(
            "Queued",
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
        RecordingStatus.RECORDED -> Triple(
            "Not saved",
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RecordingStatus.RECORDING -> Triple(
            "Recording",
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = container,
        contentColor = content,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/**
 * All numbers except Duration come from the Rust fusion-core ([RideAnalysis]);
 * "—" while the analysis is still computing (or failed).
 */
@Composable
private fun StatTiles(recording: LocalRecording?, analysis: RideAnalysis?) {
    val durationMs = recording
        ?.takeIf { it.endedAtMs > it.startedAtMs }
        ?.let { it.endedAtMs - it.startedAtMs }
        ?: analysis?.let { it.endedAtMs - it.startedAtMs }
    val tiles = listOf(
        "Duration" to (durationMs?.let(::formatElapsed) ?: PLACEHOLDER),
        "Distance" to (analysis?.let { formatDistance(it.distanceM) } ?: PLACEHOLDER),
        "Avg speed" to (analysis?.avgMovingSpeedMps?.let(::formatSpeed) ?: PLACEHOLDER),
        "Max speed" to (analysis?.maxSpeedMps?.let(::formatSpeed) ?: PLACEHOLDER),
        "Descent" to (analysis?.let { formatDistance(it.descentM) } ?: PLACEHOLDER),
        "Airtime" to (analysis?.let(::formatAirtime) ?: PLACEHOLDER),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, value) ->
                    StatTile(label = label, value = value, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

// --- formatting helpers ------------------------------------------------------

private const val PLACEHOLDER = "—"

private val startTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.US).withZone(ZoneId.systemDefault())

private fun formatStartTime(epochMs: Long): String =
    startTimeFormatter.format(Instant.ofEpochMilli(epochMs))

private fun formatElapsed(elapsedMs: Long): String {
    val totalSeconds = elapsedMs / 1_000
    return String.format(
        Locale.US,
        "%02d:%02d:%02d",
        totalSeconds / 3_600,
        (totalSeconds % 3_600) / 60,
        totalSeconds % 60,
    )
}

private fun formatDistance(meters: Double): String = when {
    meters >= 1_000 -> String.format(Locale.US, "%.1f km", meters / 1_000)
    else -> String.format(Locale.US, "%.0f m", meters)
}

private fun formatSpeed(mps: Double): String =
    String.format(Locale.US, "%.1f km/h", mps * 3.6)

/** e.g. "1.2 s × 3" — total airtime and number of jumps. */
private fun formatAirtime(analysis: RideAnalysis): String {
    val jumps = analysis.airtimeWindows.size
    if (jumps == 0) return "0"
    val totalS = analysis.airtimeTotalMs / 1000.0
    return String.format(Locale.US, "%.1f s × %d", totalS, jumps)
}
