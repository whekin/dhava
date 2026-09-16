package com.nakvali.feature.activity

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
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
import androidx.compose.material.icons.filled.AddRoad
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.BottomSheetScaffold
import com.nakvali.core.ui.NakvaliLoading
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
import androidx.compose.material3.rememberBottomSheetState
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
import com.nakvali.core.ui.NakvaliStatusTone
import com.nakvali.core.ui.NakvaliTheme
import com.nakvali.fusion.ActivityState
import com.nakvali.fusion.RideAnalysis
import com.nakvali.fusion.RideRun
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
    val exportState by viewModel.exportState.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val transportEditor by viewModel.transportEditor.collectAsState()
    val trimEditor by viewModel.trimEditor.collectAsState()
    val ridingRuns by viewModel.ridingRuns.collectAsState()
    val context = LocalContext.current
    val developerMode = remember(context) {
        RecorderSettings.developerModeEnabled(context)
    }

    // Save the source path with the activity-result registration so rotation or
    // process recreation while the system picker is open can finish the copy.
    var pendingSavePath by rememberSaveable(recordingId) { mutableStateOf<String?>(null) }
    val saveFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val path = pendingSavePath
        pendingSavePath = null
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null && path != null) {
            viewModel.saveExport(File(path), uri)
        } else {
            viewModel.exportFeedback(message = "Save cancelled")
        }
    }
    LaunchedEffect(exportState.prepared) {
        val prepared = exportState.prepared ?: return@LaunchedEffect
        try {
            when (prepared.destination) {
                ExportDestination.SAVE -> {
                    pendingSavePath = prepared.file.absolutePath
                    saveFileLauncher.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = prepared.kind.mimeType
                        putExtra(Intent.EXTRA_TITLE, prepared.file.name)
                    })
                    viewModel.exportFeedback()
                }
                ExportDestination.SHARE -> {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", prepared.file)
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = prepared.kind.mimeType
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = android.content.ClipData.newRawUri(prepared.file.name, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "Share file"))
                    viewModel.exportFeedback()
                }
            }
        } catch (error: Exception) {
            pendingSavePath = null
            viewModel.exportFeedback(error = error.message ?: "Could not open the selected action")
        }
    }

    // The export sheet closes the moment the file is handed to the system, so
    // whatever happened next has to be said somewhere the sheet no longer is.
    // Only results that follow a hand-off are announced: a failure to prepare
    // the file happens while the sheet is still open and reads it out there.
    var awaitingExportResult by remember { mutableStateOf(false) }
    LaunchedEffect(exportState.prepared) {
        if (exportState.prepared != null) awaitingExportResult = true
    }
    LaunchedEffect(exportState.message, exportState.error, exportState.busy) {
        if (!awaitingExportResult || exportState.busy) return@LaunchedEffect
        val feedback = exportState.error ?: exportState.message ?: return@LaunchedEffect
        awaitingExportResult = false
        Toast.makeText(context, feedback, Toast.LENGTH_SHORT).show()
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

    transportEditor?.let { state ->
        TransportEditorSheet(
            state = state, onDraftsChanged = viewModel::editTransportDrafts,
            onReset = viewModel::resetTransportDrafts, onApply = viewModel::applyTransportDrafts,
            onDismiss = viewModel::dismissTransportEditor,
        )
    }

    trimEditor?.let { state ->
        TrimEditorSheet(
            state = state, onDraftChanged = viewModel::editTrimDraft,
            onReset = viewModel::resetTrimDraft, onApply = viewModel::applyTrimDraft,
            onDismiss = viewModel::dismissTrimEditor,
        )
    }

    ActivityDetailContent(
        recording = recording,
        ridingRuns = ridingRuns,
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
        loading = loading,
        exportState = exportState,
        onExport = viewModel::prepareExport,
        onEditTransport = viewModel::openTransportEditor,
        onEditTrim = viewModel::openTrimEditor,
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
    ridingRuns: List<RideRun>,
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
    loading: ActivityLoadingState,
    exportState: ActivityExportState,
    onExport: (ActivityExportKind, ExportDestination, ActivityExportScope) -> Unit,
    onEditTransport: () -> Unit,
    onEditTrim: () -> Unit,
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
    val rawPoints = remember(replay, track, loading.preview) { replay?.rawTrack?.map {
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
        } ?: loading.preview.mapIndexed { index, point ->
            MapTrackPoint(point.lat, point.lon, index, point.accuracyM, point.timestampMs)
        }
    }
    val fusedPoints = remember(rideInsights, replay) { rideInsights?.track?.map { point ->
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
    }
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
                MapSegmentRun(name = run.segmentName, points = fusedPoints.subList(from, to + 1))
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
    val processedExportAvailable = replay?.finalizedTrack?.isNotEmpty() == true && loading.phase != ActivityLoadPhase.FAILED
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.PartiallyExpanded,
        // Anchors are named explicitly: from material3 alpha21 the PartiallyExpanded
        // anchor is no longer dropped by layout, and omitting Hidden is what the old
        // skipHiddenState flag did. These sheets are peek-or-expand, never dismissable.
        enabledValues = setOf(SheetValue.PartiallyExpanded, SheetValue.Expanded),
    )
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetContent = {
            ActivityDetailsSheet(
                recording = recording,
                ridingRuns = ridingRuns,
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
                loading = loading,
                exportState = exportState,
                onExport = onExport,
                onEditTransport = onEditTransport,
                onEditTrim = onEditTrim,
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
        sheetPeekHeight = ActivitySheetPeekHeight + if (loading.phase == ActivityLoadPhase.READY) 0.dp else 96.dp,
        sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        // A raised container, not `surface`: over a map the sheet has to own its
        // own edge, and in the light scheme `surface` and the basemap ground sat
        // close enough in value that the sheet lost its boundary entirely.
        sheetContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
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
            if (rawPoints.isNotEmpty() || fusedPoints.isNotEmpty()) {
                // One map instance survives the transition from preview to canonical.
                TrackMap(
                    rawPoints = rawPoints, fusedPoints = fusedPoints, segmentRuns = segmentRunLines,
                    segmentColor = segmentHighlightColor, mode = effectiveTrackMode,
                    rawColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    fusedColor = MaterialTheme.colorScheme.primary,
                    inspectedPoint = inspectedMapPoint, respectUserCamera = true,
                    modifier = Modifier.fillMaxSize(),
                )
            } else when (track) {
                TrackState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Opening the recorded track…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TrackState.Empty -> NakvaliEmptyState(
                    title = "No usable GPS track", description = "The raw recording is still preserved on this phone.",
                    modifier = Modifier.fillMaxSize(),
                )
                is TrackState.Failed -> NakvaliEmptyState(
                    title = "Activity data unavailable", description = track.message, modifier = Modifier.fillMaxSize(),
                )
                is TrackState.Loaded -> Unit
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
    ridingRuns: List<RideRun>,
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
    loading: ActivityLoadingState,
    exportState: ActivityExportState,
    onExport: (ActivityExportKind, ExportDestination, ActivityExportScope) -> Unit,
    onEditTransport: () -> Unit,
    onEditTrim: () -> Unit,
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
                // Expanded means expanded: the sheet holds a summary, a
                // profile, segment runs and the quality row, and capping it at
                // 72% of the screen left the rider scrolling a small window
                // over a long page with a map they were not looking at behind
                // it. What is left is the drag handle and a thumb-width of map,
                // so the way back down stays obvious.
                .heightIn(max = maxHeight * 0.94f)
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = NakvaliSpacing.xLarge),
                horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The title owns the whole line. It shared it with the status
                // pill and two action buttons before, which left "Morning ride"
                // showing as "Mornin…" on a phone; the pill says the same thing
                // one line down, where the space is already the subtitle's.
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
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.small),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = activitySubtitle(it),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            RecordingStatusPill(it.status)
                        }
                    }
                }
                ActivityExportButton(
                    runs = ridingRuns,
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
                    ride = ride,
                    exportState = exportState,
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
                    onEditTransport = onEditTransport,
                    onEditTrim = onEditTrim,
                    onCreateSegment = onCreateSegment,
                    onDelete = onDelete,
                )
            }

            if (loading.phase != ActivityLoadPhase.READY) ActivityLoadingStatus(loading)
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
    onEditTransport: () -> Unit,
    onEditTrim: () -> Unit,
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
            // Icons, because this menu is reached mid-ride with gloves on and
            // read at a glance: the shape finds the item before the word does.
            DropdownMenuItem(
                text = { Text("Edit") },
                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                onClick = {
                    expanded = false
                    onEdit()
                },
            )
            DropdownMenuItem(
                text = { Text("Transport episodes") },
                leadingIcon = { Icon(Icons.Filled.DirectionsCar, contentDescription = null) },
                enabled = canCreateSegment,
                onClick = { expanded = false; onEditTransport() },
            )
            DropdownMenuItem(
                text = { Text("Trim start and finish") },
                leadingIcon = { Icon(Icons.Filled.ContentCut, contentDescription = null) },
                enabled = canCreateSegment,
                onClick = { expanded = false; onEditTrim() },
            )
            DropdownMenuItem(
                text = { Text("Create segment") },
                leadingIcon = { Icon(Icons.Filled.AddRoad, contentDescription = null) },
                enabled = canCreateSegment,
                onClick = {
                    expanded = false
                    onCreateSegment()
                },
            )
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                leadingIcon = {
                    Icon(
                        Icons.Filled.DeleteOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = {
                    expanded = false
                    onDelete()
                },
            )
        }
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
    val presentation: Pair<String, NakvaliStatusTone> = when (status) {
        RecordingStatus.UPLOADED -> "Uploaded" to NakvaliStatusTone.Live
        RecordingStatus.FAILED -> "Upload failed" to NakvaliStatusTone.Alert
        // Queued is unsettled rather than wrong, which is what Held is for.
        RecordingStatus.PENDING_UPLOAD -> "Queued" to NakvaliStatusTone.Held
        RecordingStatus.RECORDED -> "Local" to NakvaliStatusTone.Neutral
        RecordingStatus.RECORDING -> "Recording" to NakvaliStatusTone.Live
    }
    NakvaliStatusPill(text = presentation.first, tone = presentation.second)
}

@Composable
private fun ActivityMetrics(
    recording: LocalRecording?,
    analysis: RideAnalysis?,
    ride: CanonicalRideTotals?,
    quality: CanonicalQuality?,
    rideInsights: ActivityRideInsights?,
) {
    var showMore by rememberSaveable(recording?.id) { mutableStateOf(false) }
    // Duration is the one headline number that does not come from Rust totals,
    // so it has to follow the rider's trim explicitly or a three-minute descent
    // would still be labelled with the forty-one minutes of the whole recording.
    val durationMs = recording
        ?.takeIf { it.ridingEndedAtMs > it.ridingStartedAtMs }
        ?.ridingDurationMs
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
        recording?.rideBounds?.let { bounds ->
            val head = bounds.startedAtMs - recording.startedAtMs
            val tail = recording.endedAtMs - bounds.endedAtMs
            Spacer(Modifier.size(NakvaliSpacing.medium))
            Text(
                text = "Trimmed · ${formatElapsed(head)} off the start, " +
                    "${formatElapsed(tail)} off the finish",
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
            ridingRuns = emptyList(),
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
            loading = ActivityLoadingState(phase = ActivityLoadPhase.READY),
            exportState = ActivityExportState(),
            onExport = { _, _, _ -> },
            onEditTransport = {},
            onEditTrim = {},
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
                ActivityExportButton(
                    runs = emptyList(),
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
                    exportState = ActivityExportState(),
                    ride = null,
                    onExport = { _, _, _ -> },
                    onConnectStrava = {},
                    onExportStrava = {},
                    onRetryStrava = {},
                    onViewStrava = {},
                )
            }
        }
    }
}
