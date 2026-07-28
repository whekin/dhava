package com.dhava.feature.activity

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.dhava.core.recording.Bike
import com.dhava.core.recording.BikeType
import com.dhava.core.recording.CanonicalQuality
import com.dhava.core.recording.LocalRecording
import com.dhava.core.recording.RecordingStatus
import com.dhava.core.recording.StravaConnectionState
import com.dhava.core.recording.StravaExportStatus
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
    val quality by viewModel.quality.collectAsState()
    val bikes by viewModel.bikes.collectAsState()
    val healthLogAvailable by viewModel.healthLogAvailable.collectAsState()
    val stravaConnection by viewModel.stravaConnection.collectAsState()
    val context = LocalContext.current

    // Pops the screen once the entry disappears (deleted here or elsewhere).
    // Guarded on "seen at least once" so the initial null emitted while the
    // index is still loading never pops a freshly opened screen.
    var recordingSeen by remember { mutableStateOf(false) }
    LaunchedEffect(recording) {
        if (recording != null) {
            recordingSeen = true
        } else if (recordingSeen) {
            onBack()
        }
    }

    ActivityDetailContent(
        recording = recording,
        track = track,
        analysis = analysis,
        diagnostics = diagnostics,
        quality = quality,
        bikes = bikes,
        healthLogAvailable = healthLogAvailable,
        stravaConnection = stravaConnection,
        onBack = onBack,
        onExport = { kind ->
            viewModel.export(kind) { result ->
                val file = result.getOrElse { error ->
                    Toast.makeText(
                        context,
                        error.message ?: "Export failed",
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@export
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                context.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = kind.mimeType
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        },
                        when (kind) {
                            ActivityExportKind.RAW_RECORDING -> "Share raw recording"
                            ActivityExportKind.HEALTH_LOG -> "Share recording health log"
                            else -> "Share GPX"
                        },
                    ),
                )
            }
        },
        onAddBike = viewModel::addBike,
        onEditSave = viewModel::updateMetadata,
        onDelete = viewModel::deleteActivity,
        onConnectStrava = {
            viewModel.beginStravaConnect { result ->
                val authorizeUrl = result.getOrElse { error ->
                    Toast.makeText(
                        context,
                        error.message ?: "Could not connect Strava",
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@beginStravaConnect
                }
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authorizeUrl)))
            }
        },
        onExportStrava = viewModel::exportToStrava,
        onRetryStrava = viewModel::retryStravaExport,
        onViewStrava = { activityId ->
            context.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.strava.com/activities/$activityId"),
                ),
            )
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
    quality: CanonicalQuality?,
    bikes: List<Bike>,
    healthLogAvailable: Boolean,
    stravaConnection: StravaConnectionState,
    onBack: () -> Unit,
    onExport: (ActivityExportKind) -> Unit,
    onAddBike: (name: String, type: BikeType) -> Bike,
    onEditSave: (title: String, description: String, bike: Bike?) -> Unit,
    onDelete: () -> Unit,
    onConnectStrava: () -> Unit,
    onExportStrava: () -> Unit,
    onRetryStrava: () -> Unit,
    onViewStrava: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var trackMode by remember { mutableStateOf(TrackMode.Compare) }
    var showEdit by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val replay = (diagnostics as? DiagnosticTrackState.Loaded)?.replay
    val rawPoints = replay?.rawTrack?.map {
        MapTrackPoint(it.lat, it.lon, it.sectionId, it.accuracyM)
    }
        ?: (track as? TrackState.Loaded)?.points?.mapIndexed { index, point ->
            // Without Rust replay the pause boundaries are unknown. Keep each
            // fix isolated rather than drawing a potentially false bridge.
            MapTrackPoint(point.lat, point.lon, index, point.accuracyM)
        }.orEmpty()
    val fusedPoints = replay?.finalizedTrack
        ?.ifEmpty { replay.fusedTrack }
        ?.map { MapTrackPoint(it.lat, it.lon, it.sectionId) }
        .orEmpty()
    val accuracyColors = rememberGpsAccuracyColors()
    val hasAccuracy = rawPoints.any { it.accuracyM?.isFinite() == true && it.accuracyM >= 0.0 }
    val processedExportAvailable = replay?.finalizedTrack?.isNotEmpty() == true

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
            is TrackState.Failed -> DhavaEmptyState(
                title = "Activity data unavailable",
                description = track.message,
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

        if (trackMode != TrackMode.Fusion && hasAccuracy) {
            GpsAccuracyLegend(
                colors = accuracyColors,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(
                        top = if (fusedPoints.isNotEmpty()) 84.dp else DhavaSpacing.medium,
                        end = DhavaSpacing.medium,
                    )
                    .size(width = 176.dp, height = 72.dp),
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
                    ExportMenu(
                        rawGpsAvailable = track is TrackState.Loaded,
                        processedAvailable = processedExportAvailable,
                        processedLoading = diagnostics is DiagnosticTrackState.Loading,
                        // The raw file is worth exporting even when it cannot
                        // be decoded (that is the diagnostics use case) — only
                        // a missing file makes the option pointless.
                        rawRecordingAvailable = !(track is TrackState.Failed && track.rawFileMissing),
                        healthLogAvailable = healthLogAvailable,
                        stravaConnection = stravaConnection,
                        recording = recording,
                        onExport = onExport,
                        onConnectStrava = onConnectStrava,
                        onExportStrava = onExportStrava,
                        onRetryStrava = onRetryStrava,
                        onViewStrava = onViewStrava,
                    )
                    ActivityOverflowMenu(
                        enabled = recording != null,
                        onEdit = { showEdit = true },
                        onDelete = { confirmDelete = true },
                    )
                }
                DhavaDivider(Modifier.padding(vertical = DhavaSpacing.large))
                ActivityMetrics(recording, analysis, quality)
                // Hidden until the canonical artifact provides real numbers,
                // so a computing or legacy artifact never flashes wrong data.
                quality?.let {
                    ActivityQualityRow(
                        quality = it,
                        modifier = Modifier.padding(top = DhavaSpacing.large),
                    )
                }
            }
        }
    }

    val currentRecording = recording
    if (showEdit && currentRecording != null) {
        ActivityEditDialog(
            recording = currentRecording,
            bikes = bikes,
            onAddBike = onAddBike,
            onSave = { title, description, bike ->
                onEditSave(title, description, bike)
                showEdit = false
            },
            onDismiss = { showEdit = false },
        )
    }
    if (confirmDelete && currentRecording != null) {
        DeleteActivityDialog(
            activityName = currentRecording.title ?: formatStartTime(currentRecording.startedAtMs),
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/**
 * Second step of the two-step delete (overflow item → confirm). Names the
 * activity so there is no doubt what is about to disappear. Deleting the raw
 * file at the user's explicit, confirmed request is the intended exception to
 * the raw-forever principle (see RecordingRepository.deleteActivity).
 */
@Composable
private fun DeleteActivityDialog(
    activityName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete activity?") },
        text = {
            Text(
                "“$activityName” will be deleted from this phone, " +
                    "including its raw sensor recording. This cannot be undone.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun ActivityOverflowMenu(
    enabled: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled) {
            Icon(Icons.Filled.MoreVert, contentDescription = "More actions")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("Edit") },
                onClick = {
                    expanded = false
                    onEdit()
                },
            )
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                onClick = {
                    expanded = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun ExportMenu(
    rawGpsAvailable: Boolean,
    processedAvailable: Boolean,
    processedLoading: Boolean,
    rawRecordingAvailable: Boolean,
    healthLogAvailable: Boolean,
    stravaConnection: StravaConnectionState,
    recording: LocalRecording?,
    initiallyExpanded: Boolean = false,
    onExport: (ActivityExportKind) -> Unit,
    onConnectStrava: () -> Unit,
    onExportStrava: () -> Unit,
    onRetryStrava: () -> Unit,
    onViewStrava: (Long) -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Box {
        IconButton(
            onClick = { expanded = true },
            enabled = rawGpsAvailable || rawRecordingAvailable || healthLogAvailable,
        ) {
            Icon(Icons.Filled.Share, contentDescription = "Export")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = {
                    ExportOptionText(
                        title = "Processed · 5 Hz",
                        description = when {
                            processedAvailable -> "GPS-bounded finalized track"
                            processedLoading -> "Preparing finalized track…"
                            else -> "Not available for this ride"
                        },
                    )
                },
                enabled = processedAvailable,
                onClick = {
                    expanded = false
                    onExport(ActivityExportKind.PROCESSED_5_HZ)
                },
            )
            DropdownMenuItem(
                text = {
                    ExportOptionText(
                        title = "Raw GPS",
                        description = "Original recorded fixes",
                    )
                },
                enabled = rawGpsAvailable,
                onClick = {
                    expanded = false
                    onExport(ActivityExportKind.RAW_GPS)
                },
            )
            StravaExportMenuItem(
                connection = stravaConnection,
                recording = recording,
                processedAvailable = processedAvailable,
                onClick = { action ->
                    expanded = false
                    when (action) {
                        StravaMenuAction.Connect -> onConnectStrava()
                        StravaMenuAction.Export -> onExportStrava()
                        StravaMenuAction.Retry -> onRetryStrava()
                        is StravaMenuAction.View -> onViewStrava(action.activityId)
                    }
                },
            )
            DropdownMenuItem(
                text = {
                    ExportOptionText(
                        title = "Recording health (.jsonl)",
                        description = "Memory, thermal, writer and restart diagnostics",
                    )
                },
                enabled = healthLogAvailable,
                onClick = {
                    expanded = false
                    onExport(ActivityExportKind.HEALTH_LOG)
                },
            )
            DropdownMenuItem(
                text = {
                    ExportOptionText(
                        title = "Raw recording (.jsonl.gz)",
                        description = "Full sensor data for diagnostics",
                    )
                },
                enabled = rawRecordingAvailable,
                onClick = {
                    expanded = false
                    onExport(ActivityExportKind.RAW_RECORDING)
                },
            )
        }
    }
}

private sealed interface StravaMenuAction {
    data object Connect : StravaMenuAction
    data object Export : StravaMenuAction
    data object Retry : StravaMenuAction
    data class View(val activityId: Long) : StravaMenuAction
}

@Composable
private fun StravaExportMenuItem(
    connection: StravaConnectionState,
    recording: LocalRecording?,
    processedAvailable: Boolean,
    onClick: (StravaMenuAction) -> Unit,
) {
    val exportStatus = recording?.stravaExportStatus
    val activityId = recording?.stravaActivityId
    val title: String
    val description: String
    val enabled: Boolean
    val action: StravaMenuAction

    when {
        exportStatus == StravaExportStatus.UPLOADED && activityId != null -> {
            title = "View on Strava"
            description = "Open the uploaded activity"
            enabled = true
            action = StravaMenuAction.View(activityId)
        }
        exportStatus == StravaExportStatus.QUEUED -> {
            title = "Strava upload queued"
            description = "Will send when a network is available"
            enabled = false
            action = StravaMenuAction.Export
        }
        exportStatus == StravaExportStatus.PROCESSING -> {
            title = "Sending to Strava…"
            description = "The upload is being processed"
            enabled = false
            action = StravaMenuAction.Export
        }
        exportStatus == StravaExportStatus.FAILED &&
            connection is StravaConnectionState.Connected -> {
            title = "Retry Strava export"
            description = recording.stravaError ?: "The previous upload failed"
            enabled = processedAvailable
            action = StravaMenuAction.Retry
        }
        connection is StravaConnectionState.Connected -> {
            title = "Export to Strava"
            description = connection.athleteName
                .takeIf(String::isNotBlank)
                ?.let { "Connected as $it" }
                ?: "Send the processed 5 Hz track"
            enabled = recording != null && processedAvailable
            action = StravaMenuAction.Export
        }
        connection == StravaConnectionState.Loading ||
            connection == StravaConnectionState.Connecting -> {
            title = "Strava"
            description = "Checking connection…"
            enabled = false
            action = StravaMenuAction.Connect
        }
        else -> {
            title = "Connect Strava"
            description = (connection as? StravaConnectionState.Unavailable)?.message
                ?: "Set up one-tap activity uploads"
            enabled = true
            action = StravaMenuAction.Connect
        }
    }

    DropdownMenuItem(
        text = {
            ExportOptionText(
                title = title,
                description = description,
            )
        },
        enabled = enabled,
        onClick = { onClick(action) },
    )
}

@Composable
private fun ExportOptionText(title: String, description: String) {
    Column {
        Text(text = title, style = MaterialTheme.typography.labelLarge)
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
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
private fun ActivityMetrics(
    recording: LocalRecording?,
    analysis: RideAnalysis?,
    quality: CanonicalQuality?,
) {
    val durationMs = recording
        ?.takeIf { it.endedAtMs > it.startedAtMs }
        ?.let { it.endedAtMs - it.startedAtMs }
        ?: analysis?.let { it.endedAtMs - it.startedAtMs }
    val metrics = listOf(
        "Duration" to (durationMs?.let(::formatElapsed) ?: Placeholder),
        "Distance" to (analysis?.let { formatDistance(it.distanceM) } ?: Placeholder),
        "Avg speed" to (analysis?.avgMovingSpeedMps?.let(::formatSpeed) ?: Placeholder),
        "Max speed" to (analysis?.maxSpeedMps?.let(::formatSpeed) ?: Placeholder),
        descentMetricLabel(quality) to
            (analysis?.let { formatDistance(it.descentM) } ?: Placeholder),
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
            quality = null,
            bikes = emptyList(),
            healthLogAvailable = true,
            stravaConnection = StravaConnectionState.Connected("Alex Rider"),
            onBack = {},
            onExport = { _ -> },
            onAddBike = { name, type -> Bike("preview-bike", name, type) },
            onEditSave = { _, _, _ -> },
            onDelete = {},
            onConnectStrava = {},
            onExportStrava = {},
            onRetryStrava = {},
            onViewStrava = {},
        )
    }
}

@Preview(name = "Activity detail · export menu", widthDp = 412, heightDp = 760)
@Composable
private fun ExportMenuPreview() {
    DhavaTheme(darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(DhavaSpacing.xLarge),
                contentAlignment = Alignment.TopEnd,
            ) {
                ExportMenu(
                    rawGpsAvailable = true,
                    processedAvailable = true,
                    processedLoading = false,
                    rawRecordingAvailable = true,
                    healthLogAvailable = true,
                    stravaConnection = StravaConnectionState.Disconnected,
                    recording = LocalRecording(
                        id = "preview",
                        startedAtMs = 1_767_000_000_000,
                    ),
                    initiallyExpanded = true,
                    onExport = {},
                    onConnectStrava = {},
                    onExportStrava = {},
                    onRetryStrava = {},
                    onViewStrava = {},
                )
            }
        }
    }
}
