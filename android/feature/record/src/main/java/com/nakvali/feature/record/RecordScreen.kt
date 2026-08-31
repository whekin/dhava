package com.nakvali.feature.record

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nakvali.core.recording.ActiveSegmentRun
import com.nakvali.core.recording.LiveSegmentRun
import com.nakvali.core.recording.LocalRecording
import com.nakvali.core.recording.RecordingState
import com.nakvali.core.recording.RecorderSettings
import com.nakvali.core.recording.canContinueRecording
import com.nakvali.core.recording.needsRecoveryAttention
import com.nakvali.core.map.NakvaliMapDetail
import com.nakvali.core.ui.NakvaliControlTone
import com.nakvali.core.ui.NakvaliMetric
import com.nakvali.core.ui.NakvaliMetricEmphasis
import com.nakvali.core.ui.NakvaliMetricRow
import com.nakvali.core.ui.NakvaliPanel
import com.nakvali.core.ui.NakvaliPrimaryButton
import com.nakvali.core.ui.NakvaliRideControl
import com.nakvali.core.ui.NakvaliRideControlBar
import com.nakvali.core.ui.NakvaliSectionLabel
import com.nakvali.core.ui.NakvaliSizes
import com.nakvali.core.ui.NakvaliSpacing
import com.nakvali.core.ui.NakvaliStatusPill
import com.nakvali.core.ui.NakvaliStatusTone
import com.nakvali.core.ui.NakvaliTheme
import com.nakvali.core.ui.NakvaliTopScrim

/**
 * Height of the recording sheet at rest: status, both metric rows, the controls
 * and the line that says how to finish. Everything a rider reads at speed is
 * above this line, so the sheet never has to be touched mid-run.
 */
private val RecordSheetPeekHeight = 356.dp

/** Map-first ride recorder. Platform work stays in the ViewModel/service. */
@Composable
fun RecordScreen(
    modifier: Modifier = Modifier,
    onImmersiveChanged: (Boolean) -> Unit = {},
    onSaveRecovered: (String) -> Unit = {},
    viewModel: RecordViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val bikes by viewModel.bikes.collectAsState()
    val recordings by viewModel.recordings.collectAsState()
    val lastUsedBikeId by viewModel.lastUsedBikeId.collectAsState()
    val startError by viewModel.startError.collectAsState()
    val diagnosticsEnabled = remember {
        val preferences = RecorderSettings.preferences(context)
        preferences.getBoolean(RecorderSettings.DEVELOPER_MODE, false) &&
            preferences.getBoolean(RecorderSettings.SENSOR_DIAGNOSTICS, false)
    }

    var permissionDenied by remember { mutableStateOf(false) }
    var showBatteryDialog by remember { mutableStateOf(false) }
    var showLocationPowerSavingDialog by remember { mutableStateOf(false) }
    var showBackgroundLocationDialog by remember { mutableStateOf(false) }
    var backgroundPromptDeclinedThisRun by remember { mutableStateOf(false) }
    var mapFollowing by remember { mutableStateOf(true) }
    var recenterRequest by remember { mutableIntStateOf(0) }
    var previewAccuracyM by remember { mutableStateOf<Float?>(null) }
    var pendingContinueId by remember { mutableStateOf<String?>(null) }

    val interruptedRecording = recordings.firstOrNull {
        it.needsRecoveryAttention()
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var mapVisible by remember {
        mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    // The idle panel must not claim it is warming up GPS before the permission
    // exists — on a fresh install that was the first thing the rider read, and it
    // was false. Refreshed on ON_START so returning from the system dialog or
    // from Settings updates it.
    fun locationPermitted(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    var locationGranted by remember { mutableStateOf(locationPermitted()) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    mapVisible = true
                    locationGranted = locationPermitted()
                }
                Lifecycle.Event.ON_STOP -> mapVisible = false
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    fun beginRecording() {
        pendingContinueId?.let(viewModel::continueRecording) ?: viewModel.startRecording()
        pendingContinueId = null
    }

    /**
     * The battery-manager prompt is asked *before* the recorder starts.
     *
     * It used to fire immediately after, which put a modal dialog on top of the
     * five-second "Finding a clean start" countdown — two things competing for
     * the rider in the seconds they most want to be riding, with the dialog's
     * scrim also swallowing any gesture aimed at the screen behind it. Asked
     * here it sits on the idle screen, where there is nothing to interrupt.
     */
    fun startAndMaybeAskBattery() {
        if (viewModel.shouldAskBatteryExemption()) showBatteryDialog = true else beginRecording()
    }

    fun startAfterLocationPowerCheck() {
        if (viewModel.isScreenOffLocationBlocked()) {
            showLocationPowerSavingDialog = true
        } else {
            startAndMaybeAskBattery()
        }
    }

    fun hasBackgroundLocation(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    fun continueAfterForegroundLocation() {
        permissionDenied = false
        if (!hasBackgroundLocation() && !backgroundPromptDeclinedThisRun) {
            showBackgroundLocationDialog = true
        } else {
            startAfterLocationPowerCheck()
        }
    }

    val backgroundSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        showBackgroundLocationDialog = false
        startAfterLocationPowerCheck()
    }

    val powerSavingSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        showLocationPowerSavingDialog = false
        startAfterLocationPowerCheck()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val granted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
        locationGranted = granted
        permissionDenied = !granted
        if (granted) continueAfterForegroundLocation()
    }

    fun startWithPermissions(continueId: String? = null) {
        pendingContinueId = continueId
        val hasLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (hasLocation) {
            continueAfterForegroundLocation()
        } else {
            val permissions = buildList {
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.POST_NOTIFICATIONS)
                // Optional: lets the recorder recognize a vehicle in flat city
                // traffic and drop to power-saving rates. Declining changes
                // nothing about recording.
                add(Manifest.permission.ACTIVITY_RECOGNITION)
            }
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    val saveTarget = (state as? RecordingState.Finished)?.summary?.let { summary ->
        SaveTarget(summary.id, summary.startedAtMs, summary.endedAtMs - summary.startedAtMs)
    }
    val immersive = state !is RecordingState.Idle || saveTarget != null
    LaunchedEffect(immersive) { onImmersiveChanged(immersive) }
    DisposableEffect(Unit) { onDispose { onImmersiveChanged(false) } }

    Box(modifier = modifier.fillMaxSize()) {
        val mapOverlayBottomPadding = when (state) {
            is RecordingState.Recording -> RecordSheetPeekHeight
            is RecordingState.Preparing -> 260.dp
            else -> if (interruptedRecording != null) 320.dp else 190.dp
        }
        if (mapVisible && saveTarget == null) {
            val recordingState = state as? RecordingState.Recording
            LiveTrackMap(
                points = recordingState?.liveTrack.orEmpty(),
                positionAccuracyM = recordingState?.lastAccuracyM,
                previewLocationEnabled = state is RecordingState.Idle,
                cameraBottomPadding = mapOverlayBottomPadding,
                following = mapFollowing,
                recenterRequest = recenterRequest,
                onUserMovedMap = { mapFollowing = false },
                onPreviewAccuracyChanged = { previewAccuracyM = it },
                modifier = Modifier.fillMaxSize(),
                // Once the recorder is running the basemap becomes an
                // instrument: the trail, the terrain and the track, without
                // the cafés and street shields a rider cannot use at speed.
                detail = if (state is RecordingState.Idle) {
                    NakvaliMapDetail.Browse
                } else {
                    NakvaliMapDetail.Instrument
                },
            )
        } else {
            Surface(
                color = MaterialTheme.colorScheme.background,
                modifier = Modifier.fillMaxSize(),
            ) {}
        }

        // Basemap labels used to collide with the status-bar clock at the top of
        // a full-bleed map. Edge-to-edge is right; the scrim is what makes it
        // legible.
        if (mapVisible && saveTarget == null) {
            NakvaliTopScrim(modifier = Modifier.align(Alignment.TopCenter))
        }

        if (mapVisible && !mapFollowing && saveTarget == null) {
            MapControl(
                onClick = {
                    mapFollowing = true
                    recenterRequest++
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = NakvaliSpacing.large,
                        bottom = mapOverlayBottomPadding + NakvaliSpacing.medium,
                    ),
            )
        }

        when {
            state is RecordingState.Preparing -> PreparingContent(
                state = state as RecordingState.Preparing,
                onCancel = viewModel::stopRecording,
            )
            state is RecordingState.Recording -> RecordingContent(
                state = state as RecordingState.Recording,
                onStop = viewModel::stopRecording,
                onPause = viewModel::pauseRecording,
                onResume = viewModel::resumeRecording,
                showDiagnostics = diagnosticsEnabled,
            )
            saveTarget != null -> SaveContent(
                recordingId = saveTarget.id,
                startedAtMs = saveTarget.startedAtMs,
                durationMs = saveTarget.durationMs,
                bikes = bikes,
                lastUsedBikeId = lastUsedBikeId,
                onAddBike = viewModel::addBike,
                onSave = { title, description, bike ->
                    viewModel.save(saveTarget.id, title, description, bike)
                },
                onDiscard = { viewModel.discard(saveTarget.id) },
                onBack = viewModel::dismissSave,
            )
            else -> IdleContent(
                gpsAccuracyM = previewAccuracyM,
                locationGranted = locationGranted,
                interruptedRecording = interruptedRecording,
                errorMessage = startError ?: if (permissionDenied) {
                    "Precise location is required to record a ride."
                } else {
                    null
                },
                onContinue = {
                    interruptedRecording?.id?.let(::startWithPermissions)
                },
                onSaveRecovered = {
                    interruptedRecording?.id?.let(onSaveRecovered)
                },
                onStart = { startWithPermissions() },
            )
        }
    }

    if (showBatteryDialog) {
        BatteryExemptionDialog {
            showBatteryDialog = false
            beginRecording()
        }
    }
    if (showLocationPowerSavingDialog) {
        LocationPowerSavingDialog(
            onOpenSettings = {
                powerSavingSettingsLauncher.launch(
                    Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS),
                )
            },
            onDismiss = {
                showLocationPowerSavingDialog = false
                pendingContinueId = null
            },
        )
    }
    if (showBackgroundLocationDialog) {
        val optionLabel = context.packageManager.backgroundPermissionOptionLabel.toString()
        BackgroundLocationDialog(
            optionLabel = optionLabel,
            onAllow = {
                // From Android 11 the system stopped granting background
                // location from a prompt; it can only be chosen in settings.
                backgroundSettingsLauncher.launch(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            },
            onRecordAnyway = {
                showBackgroundLocationDialog = false
                backgroundPromptDeclinedThisRun = true
                startAfterLocationPowerCheck()
            },
        )
    }
}

private data class SaveTarget(val id: String, val startedAtMs: Long, val durationMs: Long)

@Composable
private fun IdleContent(
    gpsAccuracyM: Float?,
    locationGranted: Boolean,
    interruptedRecording: LocalRecording?,
    errorMessage: String?,
    onContinue: () -> Unit,
    onSaveRecovered: () -> Unit,
    onStart: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Box(Modifier.fillMaxSize()) {
        // Over a map the panel has to declare its own edge. `surface` sat at
        // almost the same value as the basemap in both schemes, so the card
        // read as a rectangle that had failed to reach the bottom of the
        // screen rather than as something deliberately floating above the
        // trail. A raised container plus a hairline settles it.
        NakvaliPanel(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(NakvaliSpacing.medium),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            outlined = true,
        ) {
            if (interruptedRecording != null) {
                InterruptedRecordingContent(
                    recording = interruptedRecording,
                    onContinue = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onContinue()
                    },
                    onSave = onSaveRecovered,
                    onStartNew = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onStart()
                    },
                )
            } else {
                Row(
                    modifier = Modifier.padding(NakvaliSpacing.xLarge),
                    horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.large),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        NakvaliSectionLabel("Mountain bike")
                        Spacer(Modifier.height(NakvaliSpacing.small))
                        Text("Ready to ride", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(NakvaliSpacing.small))
                        Text(
                            when {
                                !locationGranted ->
                                    "Location permission is needed — Nakvali asks when you start."
                                gpsAccuracyM == null -> "GPS warming up while this screen is open."
                                gpsAccuracyM <= 15f -> "GPS ready · ±${gpsAccuracyM.toInt()} m"
                                else -> "GPS refining · ±${gpsAccuracyM.toInt()} m"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            // Green for ready, ochre for still settling. When
                            // tertiary was a sage green these read the same;
                            // now that it is the signal ochre, colouring a good
                            // fix with it would say the opposite of what it means.
                            color = when {
                                gpsAccuracyM == null -> MaterialTheme.colorScheme.onSurfaceVariant
                                gpsAccuracyM <= 15f -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.tertiary
                            },
                        )
                    }
                    NakvaliRideControl(
                        icon = Icons.Filled.PlayArrow,
                        contentDescription = "Start recording",
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onStart()
                        },
                    )
                }
            }
        }
        if (errorMessage != null) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(NakvaliSpacing.large),
            ) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(NakvaliSpacing.large),
                )
            }
        }
    }
}

@Composable
private fun InterruptedRecordingContent(
    recording: LocalRecording,
    onContinue: () -> Unit,
    onSave: () -> Unit,
    onStartNew: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(NakvaliSpacing.xLarge),
        verticalArrangement = Arrangement.spacedBy(NakvaliSpacing.medium),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                NakvaliSectionLabel("Ride interrupted")
                Spacer(Modifier.height(NakvaliSpacing.small))
                Text(
                    if (recording.recoveryFailed) "Raw file kept" else "Your ride data is safe",
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            NakvaliStatusPill(
                text = if (recording.recoveryFailed) "Raw only" else "Recovered",
                tone = if (recording.recoveryFailed) {
                    NakvaliStatusTone.Alert
                } else {
                    NakvaliStatusTone.Held
                },
            )
        }
        Text(
            "${formatElapsed(recording.endedAtMs - recording.startedAtMs)} · " +
                formatSize(recording.sizeBytes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (recording.recoveryFailed) {
                "The original bytes are still on this phone. Save the entry to keep it visible and export diagnostics."
            } else {
                "Nakvali stopped unexpectedly. Continue this ride or save everything recorded so far."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (recording.canContinueRecording()) {
                NakvaliPrimaryButton(
                    text = "Continue ride",
                    onClick = onContinue,
                    icon = Icons.Filled.PlayArrow,
                    modifier = Modifier.weight(1f),
                )
            }
            TextButton(
                onClick = onSave,
                modifier = Modifier.height(NakvaliSizes.primaryActionHeight),
            ) {
                Text(if (recording.recoveryFailed) "Save raw" else "Save")
            }
        }
        TextButton(
            onClick = onStartNew,
            modifier = Modifier.align(Alignment.End),
        ) {
            Text("Start a new ride")
        }
    }
}

@Composable
private fun PreparingContent(state: RecordingState.Preparing, onCancel: () -> Unit) {
    val remainingSeconds = ((10_000 - state.elapsedMs).coerceAtLeast(0) + 999) / 1_000
    Box(Modifier.fillMaxSize()) {
        NakvaliPanel(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(NakvaliSpacing.medium),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            outlined = true,
        ) {
            Column(
                modifier = Modifier.padding(NakvaliSpacing.xLarge),
                verticalArrangement = Arrangement.spacedBy(NakvaliSpacing.large),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        NakvaliSectionLabel("Preparing")
                        Text("Finding a clean start", style = MaterialTheme.typography.titleLarge)
                    }
                    // A live countdown, not a disabled control: the neutral
                    // pill it used to wear made it look switched off.
                    NakvaliStatusPill("${remainingSeconds}s max", tone = NakvaliStatusTone.Held)
                }
                ReadinessRow(
                    label = "GPS lock",
                    ready = state.gpsReady,
                    detail = state.lastAccuracyM?.let { "±${it.toInt()} m" } ?: "Searching",
                )
                ReadinessRow(
                    label = "Motion sensors",
                    ready = state.imuReady,
                    detail = if (state.imuReady) "Stable" else "Warming up",
                )
                TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) {
                    Text("Cancel")
                }
            }
        }
    }
}

@Composable
private fun ReadinessRow(label: String, ready: Boolean, detail: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.medium), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                // A passed readiness check is a green tick, not an ochre one.
                tint = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(22.dp),
            )
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordingContent(
    state: RecordingState.Recording,
    onStop: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    showDiagnostics: Boolean,
) {
    val haptics = LocalHapticFeedback.current
    val status = when {
        state.paused -> "Paused"
        state.stationary -> "Still"
        else -> "Moving"
    }
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.PartiallyExpanded,
        // Anchors are named explicitly: from material3 alpha21 the PartiallyExpanded
        // anchor is no longer dropped by layout, and omitting Hidden is what the old
        // skipHiddenState flag did. These sheets are peek-or-expand, never dismissable.
        enabledValues = setOf(SheetValue.PartiallyExpanded, SheetValue.Expanded),
    )
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)

    // A sheet rather than a fixed panel: the map is the instrument the rider
    // is actually reading, so it must never be traded away for the run list.
    // The peek carries everything needed at speed — status, the two metric
    // rows and the controls — and pulling up reveals the ride's segment runs
    // without covering the trail permanently.
    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = RecordSheetPeekHeight,
        // A raised container, not `surface`: over a map the sheet has to own its
        // own edge, and in the light scheme `surface` and the basemap ground sat
        // close enough in value that the sheet lost its boundary entirely.
        sheetContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        sheetContentColor = MaterialTheme.colorScheme.onSurface,
        // Transparent so the map drawn beneath this composable stays visible.
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        sheetContent = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = NakvaliSpacing.xLarge)
                    .padding(bottom = NakvaliSpacing.xLarge)
                    .navigationBarsPadding(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NakvaliStatusPill(
                        text = status,
                        // Paused is held, not wrong, and it has to be
                        // unmistakable at a glance — hence ochre rather than
                        // the resting neutral it used to borrow.
                        tone = if (state.paused) {
                            NakvaliStatusTone.Held
                        } else {
                            NakvaliStatusTone.Live
                        },
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // The rider should never have to wonder why the track
                        // got coarse: reduced sampling is always visible.
                        if (state.powerSaving) {
                            NakvaliStatusPill(
                                text = "Transport · saving power",
                                tone = NakvaliStatusTone.Neutral,
                            )
                        }
                        if (showDiagnostics) {
                            Text(
                                state.lastAccuracyM?.let { "GPS ±${it.toInt()} m" } ?: "GPS —",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(NakvaliSpacing.large))
                // Speed leads and everything else follows it. Before, speed and
                // elapsed time were set at weights close enough that neither
                // read as the subject, and the two columns pulled to opposite
                // edges left a ragged gutter down the middle. One emphasis
                // scale, one alignment.
                val speed = measuredSpeed(state.lastSpeedMps)
                NakvaliMetricRow {
                    NakvaliMetric(
                        value = speed.value,
                        unit = speed.unit,
                        label = "Speed",
                        emphasis = NakvaliMetricEmphasis.Hero,
                        modifier = Modifier.weight(1f),
                    )
                    NakvaliMetric(
                        value = formatElapsedShort(state.elapsedMs),
                        label = "Ride time",
                        emphasis = NakvaliMetricEmphasis.Primary,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(NakvaliSpacing.large))
                // Descent sits beside distance because the product is
                // downhill-first: the metres dropped are the ride, and the
                // rider should not have to wait for Finish to read them.
                val distance = measuredDistance(state.distanceM)
                val descent = measuredDescent(state.descentM)
                NakvaliMetricRow {
                    NakvaliMetric(
                        value = distance.value,
                        unit = distance.unit,
                        label = "Distance",
                        emphasis = NakvaliMetricEmphasis.Secondary,
                        modifier = Modifier.weight(1f),
                    )
                    NakvaliMetric(
                        value = descent.value,
                        unit = descent.unit,
                        label = "Descent",
                        emphasis = NakvaliMetricEmphasis.Secondary,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(NakvaliSpacing.large))
                NakvaliRideControlBar(
                    secondary = if (state.paused) {
                        {
                            NakvaliRideControl(
                                icon = Icons.Filled.Stop,
                                contentDescription = "Finish ride",
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onStop()
                                },
                                tone = NakvaliControlTone.Destructive,
                                size = NakvaliSizes.secondaryControl,
                            )
                        }
                    } else {
                        null
                    },
                ) {
                    NakvaliRideControl(
                        icon = if (state.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = if (state.paused) {
                            "Resume recording"
                        } else {
                            "Pause recording"
                        },
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (state.paused) onResume() else onPause()
                        },
                    )
                }
                // Finishing is deliberately behind Pause so a bar-mounted phone
                // cannot end a run by accident. That guard only works if the
                // rider knows about it, and nothing on screen used to say so.
                Text(
                    text = if (state.paused) "Stop to finish and save" else "Pause to finish",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = NakvaliSpacing.medium),
                )
                SegmentRunsSection(
                    activeSegment = state.activeSegment,
                    runs = state.segmentRuns,
                    nowMs = System.currentTimeMillis(),
                    modifier = Modifier.padding(top = NakvaliSpacing.large),
                )
            }
        },
    ) {
        Box(Modifier.fillMaxSize())
    }
}

/**
 * Segment feedback above the ride metrics: what is being timed right now, or
 * the run just finished with the rest of this ride's runs behind a tap.
 *
 * Every time here is provisional — live matching is causal, and the canonical
 * result after Finish is the one that lands in the segment's leaderboard. The
 * label says so once, next to the newest run, instead of on every row.
 */
@Composable
private fun SegmentRunsSection(
    activeSegment: ActiveSegmentRun?,
    runs: List<LiveSegmentRun>,
    nowMs: Long,
    modifier: Modifier = Modifier,
) {
    if (activeSegment == null && runs.isEmpty()) return
    var showAll by rememberSaveable { mutableStateOf(false) }
    // A finished run is news, not furniture: once it has been read it should
    // give the map back. Dismissal is per run, so the next one announces
    // itself again, and a run in progress is never dismissable — that card is
    // a live clock.
    var dismissedRunId by rememberSaveable { mutableStateOf<String?>(null) }
    val latest = runs.firstOrNull()
    val latestDismissed = latest != null && latest.segmentId + latest.finishedAtMs == dismissedRunId
    if (activeSegment == null && latestDismissed && runs.size == 1) {
        DismissedRunsHint(count = runs.size, onShow = { dismissedRunId = null })
        return
    }

    Column(modifier.fillMaxWidth()) {
        if (activeSegment != null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(NakvaliSpacing.large),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        NakvaliSectionLabel("On segment")
                        Text(
                            activeSegment.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        formatSegmentElapsed(nowMs - activeSegment.startedAtMs),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
            }
        } else if (latestDismissed) {
            DismissedRunsHint(count = runs.size, onShow = { dismissedRunId = null })
        } else {
            val latest = runs.first()
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = if (latest.personalRecord) {
                    MaterialTheme.colorScheme.tertiaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                contentColor = if (latest.personalRecord) {
                    MaterialTheme.colorScheme.onTertiaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            ) {
                Column(Modifier.padding(NakvaliSpacing.large)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            NakvaliSectionLabel(
                                if (latest.personalRecord) "Personal record" else "Segment done",
                            )
                            Text(
                                latest.name,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                formatSegmentElapsed(latest.elapsedMs),
                                style = MaterialTheme.typography.headlineSmall,
                            )
                            latest.deltaMs?.let { delta ->
                                Text(
                                    formatSegmentDelta(delta),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        }
                        IconButton(
                            onClick = {
                                dismissedRunId = latest.segmentId + latest.finishedAtMs
                                showAll = false
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Hide this run",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    Text(
                        "Provisional — confirmed when the ride is finished",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        val earlier = if (activeSegment != null || latestDismissed) runs else runs.drop(1)
        if (earlier.isNotEmpty()) {
            TextButton(onClick = { showAll = !showAll }) {
                Text(
                    if (showAll) {
                        "Hide earlier runs"
                    } else {
                        "${earlier.size} earlier ${if (earlier.size == 1) "run" else "runs"}"
                    },
                )
            }
            if (showAll) {
                earlier.forEach { run ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = NakvaliSpacing.small),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            run.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            buildString {
                                append(formatSegmentElapsed(run.elapsedMs))
                                if (run.personalRecord) append(" · PR")
                                run.deltaMs?.takeIf { !run.personalRecord }?.let {
                                    append(" · ${formatSegmentDelta(it)}")
                                }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(NakvaliSpacing.large))
    }
}

/**
 * The single line a dismissed run leaves behind, so the ride's runs are always
 * one tap away instead of gone.
 */
@Composable
private fun DismissedRunsHint(count: Int, onShow: () -> Unit) {
    TextButton(onClick = onShow) {
        Text("$count ${if (count == 1) "run" else "runs"} this ride")
    }
}

@Composable
private fun MapControl(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.size(NakvaliSizes.mapControl),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.primary,
        shadowElevation = 6.dp,
    ) {
        IconButton(onClick = onClick) {
            Icon(Icons.Filled.MyLocation, contentDescription = "Recenter map")
        }
    }
}

@Composable
private fun BatteryExemptionDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Keep recording alive") },
        text = {
            Text("Some battery managers stop long recordings. Allow Nakvali to run unrestricted during rides.")
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    context.startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                },
            ) { Text("Allow") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}

@Composable
private fun LocationPowerSavingDialog(
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Power saving blocks GPS") },
        text = {
            Text(
                "System Power saving is set to turn off or restrict location when the screen " +
                    "is locked. Nakvali cannot record the ride reliably, even with unrestricted " +
                    "app battery access. Turn Power saving off before starting.",
            )
        },
        confirmButton = {
            TextButton(onClick = onOpenSettings) { Text("Open settings") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun BackgroundLocationDialog(
    optionLabel: String,
    onAllow: () -> Unit,
    onRecordAnyway: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onRecordAnyway,
        title = { Text("Recover screen-off rides") },
        text = {
            Text(
                "If Android kills Nakvali after the screen turns off, background location lets the recorder restart. " +
                    "Choose Location → $optionLabel. You can still record without it, but automatic recovery may stop.",
            )
        },
        confirmButton = {
            TextButton(onClick = onAllow) { Text("Open settings") }
        },
        dismissButton = {
            TextButton(onClick = onRecordAnyway) { Text("Record anyway") }
        },
    )
}

@Preview(name = "Record · idle", widthDp = 412, heightDp = 760)
@Composable
private fun IdleContentPreview() {
    NakvaliTheme(darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.background) {
            IdleContent(
                gpsAccuracyM = 6f,
                locationGranted = true,
                interruptedRecording = null,
                errorMessage = null,
                onContinue = {},
                onSaveRecovered = {},
                onStart = {},
            )
        }
    }
}

@Preview(name = "Record · interrupted", widthDp = 412, heightDp = 760)
@Composable
private fun InterruptedContentPreview() {
    NakvaliTheme(darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.background) {
            IdleContent(
                gpsAccuracyM = 6f,
                locationGranted = true,
                interruptedRecording = LocalRecording(
                    id = "recovered",
                    startedAtMs = 1_780_000_000_000,
                    endedAtMs = 1_780_001_800_000,
                    sizeBytes = 24_500_000,
                    recovered = true,
                ),
                errorMessage = null,
                onContinue = {},
                onSaveRecovered = {},
                onStart = {},
            )
        }
    }
}

@Preview(name = "Record · preparing", widthDp = 412, heightDp = 760)
@Composable
private fun PreparingContentPreview() {
    NakvaliTheme(darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.background) {
            PreparingContent(
                state = RecordingState.Preparing(
                    elapsedMs = 2_400,
                    gpsReady = false,
                    imuReady = true,
                    lastAccuracyM = 31f,
                ),
                onCancel = {},
            )
        }
    }
}

@Preview(name = "Record · moving", widthDp = 412, heightDp = 760)
@Composable
private fun RecordingContentPreview() {
    NakvaliTheme(darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.background) {
            RecordingContent(
                state = RecordingState.Recording(
                    startedAtMs = 0,
                    elapsedMs = 1_842_000,
                    lastSpeedMps = 12.4f,
                    lastAccuracyM = 4.8f,
                    distanceM = 8_240.0,
                    descentM = 612.0,
                    stationary = false,
                    liveTrack = emptyList(),
                    gpsCount = 1_842,
                    imuCount = 92_100,
                    baroCount = 18_420,
                ),
                onStop = {},
                onPause = {},
                onResume = {},
                showDiagnostics = true,
            )
        }
    }
}
