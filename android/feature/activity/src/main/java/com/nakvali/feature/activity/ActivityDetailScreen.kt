package com.nakvali.feature.activity

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nakvali.core.recording.Bike
import com.nakvali.core.recording.BikeType
import com.nakvali.core.recording.CanonicalQuality
import com.nakvali.core.recording.CanonicalRideTotals
import com.nakvali.core.recording.LocalRecording
import com.nakvali.core.recording.RecordingStatus
import com.nakvali.core.recording.RideSegmentRun
import com.nakvali.core.recording.RecorderSettings
import com.nakvali.core.recording.StravaConnectionState
import com.nakvali.core.recording.StravaExportStatus
import com.nakvali.core.ui.NakvaliDivider
import com.nakvali.core.ui.NakvaliEmptyState
import com.nakvali.core.ui.NakvaliMetric
import com.nakvali.core.ui.NakvaliSectionLabel
import com.nakvali.core.ui.NakvaliSpacing
import com.nakvali.core.ui.NakvaliStatusPill
import com.nakvali.core.ui.NakvaliTheme
import com.nakvali.fusion.ActivityState
import com.nakvali.fusion.RideAnalysis
import com.nakvali.fusion.RideProfilePoint
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Map-led local activity detail; canonical numbers still come from Rust. */
@Composable
fun ActivityDetailScreen(
    recordingId: String,
    onBack: () -> Unit,
    onCreateSegment: () -> Unit = {},
    onOpenSegment: (String) -> Unit = {},
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
    val ride by viewModel.ride.collectAsState()
    val rideInsights by viewModel.rideInsights.collectAsState()
    val segmentRuns by viewModel.segmentRuns.collectAsState()
    val bikes by viewModel.bikes.collectAsState()
    val healthLogAvailable by viewModel.healthLogAvailable.collectAsState()
    val stravaConnection by viewModel.stravaConnection.collectAsState()
    val context = LocalContext.current
    val developerMode = remember(context) {
        RecorderSettings.developerModeEnabled(context)
    }

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
        ride = ride,
        rideInsights = rideInsights,
        segmentRuns = segmentRuns,
        bikes = bikes,
        healthLogAvailable = healthLogAvailable,
        stravaConnection = stravaConnection,
        developerMode = developerMode,
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
        onCreateSegment = onCreateSegment,
        onOpenSegment = onOpenSegment,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActivityDetailContent(
    recording: LocalRecording?,
    track: TrackState,
    analysis: RideAnalysis?,
    diagnostics: DiagnosticTrackState,
    quality: CanonicalQuality?,
    ride: CanonicalRideTotals?,
    rideInsights: ActivityRideInsights?,
    segmentRuns: List<RideSegmentRun>?,
    bikes: List<Bike>,
    healthLogAvailable: Boolean,
    stravaConnection: StravaConnectionState,
    developerMode: Boolean,
    onBack: () -> Unit,
    onExport: (ActivityExportKind) -> Unit,
    onCreateSegment: () -> Unit,
    onOpenSegment: (String) -> Unit,
    onAddBike: (name: String, type: BikeType) -> Bike,
    onEditSave: (title: String, description: String, bike: Bike?) -> Unit,
    onDelete: () -> Unit,
    onConnectStrava: () -> Unit,
    onExportStrava: () -> Unit,
    onRetryStrava: () -> Unit,
    onViewStrava: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var trackMode by remember(developerMode) { mutableStateOf(TrackMode.Fusion) }
    var showMapLegend by rememberSaveable { mutableStateOf(false) }
    var inspectedProfilePosition by rememberSaveable(recording?.id) {
        mutableStateOf<Double?>(null)
    }
    var showEdit by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val replay = (diagnostics as? DiagnosticTrackState.Loaded)?.replay
    val rawPoints = replay?.rawTrack?.map {
        MapTrackPoint(
            lat = it.lat,
            lon = it.lon,
            sectionId = it.sectionId,
            accuracyM = it.accuracyM,
            timestampMs = it.timestampMs,
        )
    }
        ?: (track as? TrackState.Loaded)?.points?.mapIndexed { index, point ->
            // Without Rust replay the pause boundaries are unknown. Keep each
            // fix isolated rather than drawing a potentially false bridge.
            MapTrackPoint(point.lat, point.lon, index, point.accuracyM)
        }.orEmpty()
    val fusedPoints = rideInsights?.track?.map { point ->
        MapTrackPoint(
            lat = point.lat,
            lon = point.lon,
            sectionId = point.sectionId,
            timestampMs = point.timestampMs,
            activityState = point.activityState,
            activityConfidence = point.activityConfidence,
            altitudeM = point.altitudeM,
            speedMps = point.speedMps,
        )
    } ?: replay?.finalizedTrack
        ?.ifEmpty { replay.fusedTrack }
        ?.map { point ->
            MapTrackPoint(
                lat = point.lat,
                lon = point.lon,
                sectionId = point.sectionId,
                timestampMs = point.timestampMs,
                activityState = point.activityState
                    ?: if (point.stationary == true) ActivityState.STILL else null,
                activityConfidence = point.activityConfidence,
            )
        }
        .orEmpty()
    // The attempt indices address the finalized track the matcher was given,
    // which is exactly the list behind fusedPoints on this path. On the replay
    // fallback that correspondence is not guaranteed, so nothing is drawn
    // rather than a stretch of trail in the wrong place.
    val segmentRunLines = if (rideInsights != null) {
        segmentRuns.orEmpty().mapNotNull { run ->
            val from = run.attempt.startIndex
            val to = run.attempt.endIndex
            if (from < 0 || to < from || to >= fusedPoints.size) {
                null
            } else {
                fusedPoints.subList(from, to + 1)
            }
        }
    } else {
        emptyList()
    }
    val inspectedProfilePoint = inspectedProfilePosition?.let { position ->
        rideInsights?.profile?.points?.minByOrNull { point -> abs(point.position - position) }
    }
    val inspectedMapPoint = inspectedProfilePoint?.position
        ?.roundToInt()
        ?.let(fusedPoints::getOrNull)
    val accuracyColors = rememberGpsAccuracyColors()
    val activityStateColors = rememberActivityStateColors()
    val segmentHighlightColor = rememberSegmentHighlightColor()
    val hasAccuracy = rawPoints.any { it.accuracyM?.isFinite() == true && it.accuracyM >= 0.0 }
    val hasActivityStates = fusedPoints.any { it.activityState != null }
    val effectiveTrackMode = if (fusedPoints.isEmpty()) TrackMode.Gps else trackMode
    val legendSections = mapLegendSections(
        mode = effectiveTrackMode,
        hasActivityStates = hasActivityStates,
        hasAccuracy = hasAccuracy,
    )
    val processedExportAvailable = replay?.finalizedTrack?.isNotEmpty() == true
    val sheetState = rememberStandardBottomSheetState(
        initialValue = SheetValue.PartiallyExpanded,
        skipHiddenState = true,
    )
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetContent = {
            ActivityDetailsSheet(
                recording = recording,
                track = track,
                analysis = analysis,
                diagnostics = diagnostics,
                quality = quality,
                ride = ride,
                rideInsights = rideInsights,
                segmentRuns = segmentRuns,
                inspectedProfilePoint = inspectedProfilePoint,
                inspectedMapPoint = inspectedMapPoint,
                onProfilePointSelected = { point ->
                    inspectedProfilePosition = point.position
                },
                processedExportAvailable = processedExportAvailable,
                healthLogAvailable = healthLogAvailable,
                stravaConnection = stravaConnection,
                onExport = onExport,
                onCreateSegment = onCreateSegment,
                onOpenSegment = onOpenSegment,
                onConnectStrava = onConnectStrava,
                onExportStrava = onExportStrava,
                onRetryStrava = onRetryStrava,
                onViewStrava = onViewStrava,
                onEdit = { showEdit = true },
                onDelete = { confirmDelete = true },
            )
        },
        modifier = modifier.fillMaxSize(),
        sheetPeekHeight = ActivitySheetPeekHeight,
        sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        sheetContainerColor = MaterialTheme.colorScheme.surface,
        sheetContentColor = MaterialTheme.colorScheme.onSurface,
        sheetTonalElevation = 0.dp,
        sheetShadowElevation = 8.dp,
        sheetDragHandle = {
            BottomSheetDefaults.DragHandle(
                color = MaterialTheme.colorScheme.outline,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            when (track) {
                TrackState.Loading -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
                TrackState.Empty -> NakvaliEmptyState(
                    title = "No usable GPS track",
                    description = "The raw recording is still preserved on this phone.",
                    modifier = Modifier.fillMaxSize(),
                )
                is TrackState.Failed -> NakvaliEmptyState(
                    title = "Activity data unavailable",
                    description = track.message,
                    modifier = Modifier.fillMaxSize(),
                )
                is TrackState.Loaded -> TrackMap(
                    rawPoints = rawPoints,
                    fusedPoints = fusedPoints,
                    segmentRuns = segmentRunLines,
                    segmentColor = segmentHighlightColor,
                    mode = effectiveTrackMode,
                    rawColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    fusedColor = MaterialTheme.colorScheme.primary,
                    inspectedPoint = inspectedMapPoint,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            DetailTopBar(
                onBack = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(NakvaliSpacing.medium),
            )

            if (developerMode && fusedPoints.isNotEmpty()) {
                TrackModeControl(
                    selected = trackMode,
                    onSelected = {
                        trackMode = it
                        showMapLegend = false
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(NakvaliSpacing.medium),
                )
            }

            if (legendSections.isNotEmpty()) {
                MapLegendControl(
                    sections = legendSections,
                    expanded = showMapLegend,
                    onExpandedChange = { showMapLegend = it },
                    activityStateColors = activityStateColors,
                    accuracyColors = accuracyColors,
                    segmentColor = segmentHighlightColor.takeIf { segmentRunLines.isNotEmpty() },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(
                            top = if (developerMode && fusedPoints.isNotEmpty()) {
                                84.dp
                            } else {
                                NakvaliSpacing.medium
                            },
                            end = NakvaliSpacing.medium,
                        ),
                )
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

private val ActivitySheetPeekHeight = 112.dp

@Composable
private fun ActivityDetailsSheet(
    recording: LocalRecording?,
    track: TrackState,
    analysis: RideAnalysis?,
    diagnostics: DiagnosticTrackState,
    quality: CanonicalQuality?,
    ride: CanonicalRideTotals?,
    rideInsights: ActivityRideInsights?,
    segmentRuns: List<RideSegmentRun>?,
    inspectedProfilePoint: RideProfilePoint?,
    inspectedMapPoint: MapTrackPoint?,
    onProfilePointSelected: (RideProfilePoint) -> Unit,
    processedExportAvailable: Boolean,
    healthLogAvailable: Boolean,
    stravaConnection: StravaConnectionState,
    onExport: (ActivityExportKind) -> Unit,
    onCreateSegment: () -> Unit,
    onOpenSegment: (String) -> Unit,
    onConnectStrava: () -> Unit,
    onExportStrava: () -> Unit,
    onRetryStrava: () -> Unit,
    onViewStrava: (Long) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight * 0.72f)
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = NakvaliSpacing.xLarge),
                horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.medium),
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
                    // A segment is timed on the canonical finalized track, so
                    // it can only be authored once that track exists.
                    canCreateSegment = processedExportAvailable,
                    onEdit = onEdit,
                    onCreateSegment = onCreateSegment,
                    onDelete = onDelete,
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = NakvaliSpacing.xLarge),
            ) {
                NakvaliDivider(Modifier.padding(vertical = NakvaliSpacing.large))
                ActivityMetrics(recording, analysis, ride, quality, rideInsights)
                segmentRuns?.takeIf { it.isNotEmpty() }?.let { runs ->
                    ActivitySegmentRuns(
                        runs = runs,
                        onOpenSegment = onOpenSegment,
                        modifier = Modifier.padding(top = NakvaliSpacing.xLarge),
                    )
                }
                rideInsights?.profile
                    ?.takeIf { it.points.size >= 2 }
                    ?.let { profile ->
                        ActivityElevationProfile(
                            profile = profile,
                            selected = inspectedProfilePoint,
                            selectedTrackPoint = inspectedMapPoint,
                            onSelected = onProfilePointSelected,
                            modifier = Modifier.padding(top = NakvaliSpacing.xLarge),
                        )
                    }
                analysis
                    ?.takeIf { it.airtimeWindows.isNotEmpty() }
                    ?.let { rideAnalysis ->
                        AirtimeMetrics(
                            analysis = rideAnalysis,
                            modifier = Modifier.padding(top = NakvaliSpacing.xLarge),
                        )
                    }
                // Hidden until the canonical artifact provides real numbers,
                // so a computing or legacy artifact never flashes wrong data.
                quality?.let {
                    ActivityQualityRow(
                        quality = it,
                        modifier = Modifier.padding(top = NakvaliSpacing.large),
                    )
                }
                Box(Modifier.size(NakvaliSpacing.xLarge))
            }
        }
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
    canCreateSegment: Boolean,
    onEdit: () -> Unit,
    onCreateSegment: () -> Unit,
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
                text = { Text("Create segment") },
                enabled = canCreateSegment,
                onClick = {
                    expanded = false
                    onCreateSegment()
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

internal enum class MapLegendSection {
    ActivityState,
    GpsAccuracy,
}

internal fun mapLegendSections(
    mode: TrackMode,
    hasActivityStates: Boolean,
    hasAccuracy: Boolean,
): List<MapLegendSection> = buildList {
    if (mode != TrackMode.Gps && hasActivityStates) {
        add(MapLegendSection.ActivityState)
    }
    if (mode != TrackMode.Fusion && hasAccuracy) {
        add(MapLegendSection.GpsAccuracy)
    }
}

@Composable
private fun MapLegendControl(
    sections: List<MapLegendSection>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    activityStateColors: ActivityStateColors,
    accuracyColors: GpsAccuracyColors,
    segmentColor: Color?,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        Surface(
            onClick = { onExpandedChange(!expanded) },
            modifier = Modifier
                .size(48.dp)
                .semantics {
                    contentDescription = if (expanded) "Hide map legend" else "Show map legend"
                    stateDescription = if (expanded) "Expanded" else "Collapsed"
                    role = Role.Button
                },
            shape = CircleShape,
            color = if (expanded) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
            },
            contentColor = if (expanded) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            shadowElevation = 3.dp,
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                modifier = Modifier.padding(12.dp),
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            modifier = Modifier.width(232.dp),
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
            tonalElevation = 0.dp,
            shadowElevation = 4.dp,
        ) {
            sections.forEachIndexed { index, section ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = NakvaliSpacing.medium),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                when (section) {
                    MapLegendSection.ActivityState -> ActivityStateLegendContent(
                        colors = activityStateColors,
                        modifier = Modifier.fillMaxWidth(),
                        segmentColor = segmentColor,
                    )
                    MapLegendSection.GpsAccuracy -> GpsAccuracyLegendContent(
                        colors = accuracyColors,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
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
    NakvaliStatusPill(
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
    ride: CanonicalRideTotals?,
    quality: CanonicalQuality?,
    rideInsights: ActivityRideInsights?,
) {
    var showMore by rememberSaveable(recording?.id) { mutableStateOf(false) }
    val durationMs = recording
        ?.takeIf { it.endedAtMs > it.startedAtMs }
        ?.let { it.endedAtMs - it.startedAtMs }
        ?: analysis?.let { it.endedAtMs - it.startedAtMs }
    // Every headline number describes riding. A shuttle lap's kilometres and
    // climb belong to the vehicle, and counting them makes the ride's own
    // figures meaningless. Legacy artifacts predate the split and fall back to
    // whole-recording numbers rather than showing nothing.
    val moving = (ride?.movingTimeS ?: analysis?.movingTimeS)
        ?.let { formatElapsed((it * 1_000.0).toLong()) }
        ?: Placeholder
    val distance = (ride?.distanceM ?: analysis?.distanceM)?.let(::formatDistance) ?: Placeholder
    val descent = (ride?.descentM ?: analysis?.descentM)?.let(::formatDistance) ?: Placeholder
    val ascent = (ride?.ascentM ?: analysis?.ascentM)?.let(::formatDistance) ?: Placeholder
    val avgSpeed = (ride?.avgMovingSpeedMps ?: analysis?.avgMovingSpeedMps)
        ?.let(::formatSpeed) ?: Placeholder
    val maxSpeed = (ride?.maxSpeedMps ?: analysis?.maxSpeedMps)?.let(::formatSpeed) ?: Placeholder
    val transport = ride
        ?.takeIf { it.transportDistanceM >= 100.0 }
        ?.let { totals ->
            "${formatDistance(totals.transportDistanceM)} · " +
                formatElapsed((totals.transportTimeS * 1_000.0).toLong())
        }
    val elevationRange = rideInsights?.profile?.let { profile ->
        val low = profile.minAltitudeM
        val high = profile.maxAltitudeM
        if (low != null && high != null) {
            "${formatAltitude(low)}–${formatAltitude(high)}"
        } else {
            Placeholder
        }
    } ?: Placeholder

    Column {
        NakvaliSectionLabel("Ride summary")
        Spacer(Modifier.size(NakvaliSpacing.medium))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.xLarge),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1.15f)) {
                Text(
                    text = distance,
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "DISTANCE",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(
                modifier = Modifier.weight(0.85f),
                verticalArrangement = Arrangement.spacedBy(NakvaliSpacing.large),
            ) {
                NakvaliMetric(value = moving, label = "Moving")
                NakvaliMetric(
                    value = descent,
                    label = descentMetricLabel(quality),
                )
            }
        }
        // The day still has to add up: what was left out is said, not hidden.
        transport?.let { summary ->
            Spacer(Modifier.size(NakvaliSpacing.medium))
            Text(
                text = "Not counted · $summary by transport",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.size(NakvaliSpacing.xLarge))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(NakvaliSpacing.large),
                horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.large),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NakvaliMetric(avgSpeed, "Avg speed", Modifier.weight(1f))
                VerticalDivider(
                    modifier = Modifier.heightIn(min = 44.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                NakvaliMetric(maxSpeed, "Max speed", Modifier.weight(1f))
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showMore = !showMore }
                .padding(vertical = NakvaliSpacing.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (showMore) "Fewer ride details" else "More ride details",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Icon(
                imageVector = if (showMore) {
                    Icons.Filled.KeyboardArrowUp
                } else {
                    Icons.Filled.KeyboardArrowDown
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }

        if (showMore) {
            NakvaliDivider(Modifier.padding(bottom = NakvaliSpacing.large))
            Column(verticalArrangement = Arrangement.spacedBy(NakvaliSpacing.large)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.xLarge),
                ) {
                    NakvaliMetric(
                        value = durationMs?.let(::formatElapsed) ?: Placeholder,
                        label = "Total time",
                        modifier = Modifier.weight(1f),
                    )
                    NakvaliMetric(
                        value = ascent,
                        label = ascentMetricLabel(quality),
                        modifier = Modifier.weight(1f),
                    )
                }
                NakvaliMetric(
                    value = elevationRange,
                    label = "Elevation range",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun AirtimeMetrics(analysis: RideAnalysis, modifier: Modifier = Modifier) {
    val longestMs = analysis.airtimeWindows.maxOfOrNull { it.durationMs } ?: 0L
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NakvaliSpacing.medium),
    ) {
        NakvaliSectionLabel("Jumps")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.medium),
        ) {
            NakvaliMetric(
                value = analysis.airtimeWindows.size.toString(),
                label = "Detected",
                modifier = Modifier.weight(1f),
            )
            NakvaliMetric(
                value = formatAirDuration(analysis.airtimeTotalMs),
                label = "Total air",
                modifier = Modifier.weight(1f),
            )
            NakvaliMetric(
                value = formatAirDuration(longestMs),
                label = "Longest",
                modifier = Modifier.weight(1f),
            )
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

private fun formatAltitude(meters: Double): String = String.format(Locale.US, "%.0f m", meters)

private fun formatAirDuration(milliseconds: Long): String =
    String.format(Locale.US, "%.1f s", milliseconds.coerceAtLeast(0L) / 1_000.0)

@Preview(name = "Activity detail · no track", widthDp = 412, heightDp = 760)
@Composable
private fun ActivityDetailContentPreview() {
    NakvaliTheme(darkTheme = true) {
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
            ride = null,
            rideInsights = null,
            segmentRuns = emptyList(),
            bikes = emptyList(),
            healthLogAvailable = true,
            stravaConnection = StravaConnectionState.Connected("Alex Rider"),
            developerMode = false,
            onBack = {},
            onExport = { _ -> },
            onCreateSegment = {},
            onOpenSegment = {},
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
    NakvaliTheme(darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(NakvaliSpacing.xLarge),
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
