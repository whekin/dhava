package com.dhava.feature.activity

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dhava.core.recording.LocalRecording
import com.dhava.core.recording.RecordingStatus
import com.dhava.core.ui.DhavaDivider
import com.dhava.core.ui.DhavaEmptyState
import com.dhava.core.ui.DhavaMetric
import com.dhava.core.ui.DhavaPanel
import com.dhava.core.ui.DhavaSpacing
import com.dhava.core.ui.DhavaStatusPill
import com.dhava.core.ui.DhavaTheme
import com.dhava.fusion.RideAnalysis
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Map-led local activity detail; canonical numbers still come from Rust. */
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
    val diagnostics by viewModel.diagnostics.collectAsState()
    val context = LocalContext.current

    ActivityDetailContent(
        recording = recording,
        track = track,
        analysis = analysis,
        diagnostics = diagnostics,
        onBack = onBack,
        onExport = {
            viewModel.exportGpx { file ->
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                context.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "application/gpx+xml"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        },
                        "Share GPX",
                    ),
                )
            }
        },
        modifier = modifier,
    )
}

@Composable
private fun ActivityDetailContent(
    recording: LocalRecording?,
    track: TrackState,
    analysis: RideAnalysis?,
    diagnostics: DiagnosticTrackState,
    onBack: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var trackMode by remember { mutableStateOf(TrackMode.Compare) }
    val replay = (diagnostics as? DiagnosticTrackState.Loaded)?.replay
    val rawPoints = replay?.rawTrack?.map { MapTrackPoint(it.lat, it.lon, it.sectionId) }
        ?: (track as? TrackState.Loaded)?.points?.mapIndexed { index, point ->
            // Without Rust replay the pause boundaries are unknown. Keep each
            // fix isolated rather than drawing a potentially false bridge.
            MapTrackPoint(point.lat, point.lon, index)
        }.orEmpty()
    val fusedPoints = replay?.fusedTrack
        ?.map { MapTrackPoint(it.lat, it.lon, it.sectionId) }
        .orEmpty()

    Box(modifier = modifier.fillMaxSize()) {
        when (track) {
            TrackState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            TrackState.Empty -> DhavaEmptyState(
                title = "No usable GPS track",
                description = "The raw recording is still preserved on this phone.",
                modifier = Modifier.fillMaxSize(),
            )
            is TrackState.Loaded -> TrackMap(
                rawPoints = rawPoints,
                fusedPoints = fusedPoints,
                mode = if (fusedPoints.isEmpty()) TrackMode.Gps else trackMode,
                rawColor = MaterialTheme.colorScheme.onSurfaceVariant,
                fusedColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxSize(),
            )
        }

        DetailTopBar(
            onBack = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(DhavaSpacing.medium),
        )

        if (fusedPoints.isNotEmpty()) {
            TrackModeControl(
                selected = trackMode,
                onSelected = { trackMode = it },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(DhavaSpacing.medium),
            )
        }

        DhavaPanel(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(DhavaSpacing.medium),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.padding(DhavaSpacing.xLarge)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(DhavaSpacing.medium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = recording?.title
                                ?: recording?.let { formatStartTime(it.startedAtMs) }
                                ?: "Activity",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        recording?.let {
                            Text(
                                text = activitySubtitle(it),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    recording?.let { RecordingStatusPill(it.status) }
                    IconButton(onClick = onExport, enabled = track is TrackState.Loaded) {
                        Icon(Icons.Filled.Share, contentDescription = "Share GPX")
                    }
                }
                DhavaDivider(Modifier.padding(vertical = DhavaSpacing.large))
                ActivityMetrics(recording, analysis)
            }
        }
    }
}

@Composable
private fun TrackModeControl(
    selected: TrackMode,
    onSelected: (TrackMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        shadowElevation = 4.dp,
    ) {
        Row(modifier = Modifier.padding(4.dp)) {
            TrackMode.entries.forEach { mode ->
                TextButton(
                    onClick = { onSelected(mode) },
                    colors = ButtonDefaults.textButtonColors(
                        containerColor = if (selected == mode) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            Color.Transparent
                        },
                        contentColor = if (selected == mode) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    ),
                ) {
                    Text(mode.label, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun DetailTopBar(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.size(52.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 4.dp,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
    }
}

@Composable
private fun RecordingStatusPill(status: RecordingStatus) {
    val presentation = when (status) {
        RecordingStatus.UPLOADED -> StatusPresentation(
            "Uploaded",
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )
        RecordingStatus.FAILED -> StatusPresentation(
            "Upload failed",
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
        RecordingStatus.PENDING_UPLOAD -> StatusPresentation(
            "Queued",
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
        RecordingStatus.RECORDED -> StatusPresentation(
            "Local",
            MaterialTheme.colorScheme.surfaceContainerHighest,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RecordingStatus.RECORDING -> StatusPresentation(
            "Recording",
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
    DhavaStatusPill(
        text = presentation.label,
        containerColor = presentation.container,
        contentColor = presentation.content,
    )
}

private data class StatusPresentation(val label: String, val container: Color, val content: Color)

@Composable
private fun ActivityMetrics(recording: LocalRecording?, analysis: RideAnalysis?) {
    val durationMs = recording
        ?.takeIf { it.endedAtMs > it.startedAtMs }
        ?.let { it.endedAtMs - it.startedAtMs }
        ?: analysis?.let { it.endedAtMs - it.startedAtMs }
    val metrics = listOf(
        "Duration" to (durationMs?.let(::formatElapsed) ?: Placeholder),
        "Distance" to (analysis?.let { formatDistance(it.distanceM) } ?: Placeholder),
        "Avg speed" to (analysis?.avgMovingSpeedMps?.let(::formatSpeed) ?: Placeholder),
        "Max speed" to (analysis?.maxSpeedMps?.let(::formatSpeed) ?: Placeholder),
        "Descent" to (analysis?.let { formatDistance(it.descentM) } ?: Placeholder),
        "Airtime" to (analysis?.let(::formatAirtime) ?: Placeholder),
    )
    Column(verticalArrangement = Arrangement.spacedBy(DhavaSpacing.large)) {
        metrics.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DhavaSpacing.medium),
            ) {
                row.forEach { (label, value) ->
                    DhavaMetric(value = value, label = label, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

private const val Placeholder = "—"

private val startTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.US).withZone(ZoneId.systemDefault())

private fun formatStartTime(epochMs: Long): String =
    startTimeFormatter.format(Instant.ofEpochMilli(epochMs))

private fun activitySubtitle(recording: LocalRecording): String = listOfNotNull(
    formatStartTime(recording.startedAtMs).takeIf { recording.title != null },
    recording.bikeName?.let { name -> recording.bikeType?.let { "$name · ${it.label}" } ?: name },
).joinToString(" · ").ifEmpty { "Stored on this phone" }

private fun formatElapsed(elapsedMs: Long): String {
    val totalSeconds = elapsedMs.coerceAtLeast(0) / 1_000
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

private fun formatSpeed(mps: Double): String = String.format(Locale.US, "%.1f km/h", mps * 3.6)

private fun formatAirtime(analysis: RideAnalysis): String {
    val jumps = analysis.airtimeWindows.size
    if (jumps == 0) return "0"
    return String.format(Locale.US, "%.1f s × %d", analysis.airtimeTotalMs / 1000.0, jumps)
}

@Preview(name = "Activity detail · no track", widthDp = 412, heightDp = 760)
@Composable
private fun ActivityDetailContentPreview() {
    DhavaTheme(darkTheme = true) {
        ActivityDetailContent(
            recording = LocalRecording(
                id = "preview",
                startedAtMs = 1_767_000_000_000,
                endedAtMs = 1_767_003_420_000,
                status = RecordingStatus.RECORDED,
                title = "Morning laps at Turtle Lake",
                bikeName = "Enduro",
            ),
            track = TrackState.Empty,
            analysis = null,
            diagnostics = DiagnosticTrackState.Unavailable,
            onBack = {},
            onExport = {},
        )
    }
}
