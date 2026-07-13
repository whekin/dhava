package com.dhava.feature.record

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dhava.core.recording.RecordingState
import com.dhava.core.ui.DhavaControlTone
import com.dhava.core.ui.DhavaMetric
import com.dhava.core.ui.DhavaPanel
import com.dhava.core.ui.DhavaRideControl
import com.dhava.core.ui.DhavaSectionLabel
import com.dhava.core.ui.DhavaSizes
import com.dhava.core.ui.DhavaSpacing
import com.dhava.core.ui.DhavaStatusPill
import com.dhava.core.ui.DhavaTheme
import java.util.Locale

/** Map-first ride recorder. Platform work stays in the ViewModel/service. */
@Composable
fun RecordScreen(
    modifier: Modifier = Modifier,
    onImmersiveChanged: (Boolean) -> Unit = {},
    viewModel: RecordViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val bikes by viewModel.bikes.collectAsState()
    val lastUsedBikeId by viewModel.lastUsedBikeId.collectAsState()
    val startError by viewModel.startError.collectAsState()
    val diagnosticsEnabled = remember {
        context.getSharedPreferences("recorder_settings", 0).getBoolean("sensor_diagnostics", false)
    }

    var permissionDenied by remember { mutableStateOf(false) }
    var showBatteryDialog by remember { mutableStateOf(false) }
    var showBackgroundLocationDialog by remember { mutableStateOf(false) }
    var backgroundPromptDeclinedThisRun by remember { mutableStateOf(false) }
    var mapFollowing by remember { mutableStateOf(true) }
    var recenterRequest by remember { mutableIntStateOf(0) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var mapVisible by remember {
        mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapVisible = true
                Lifecycle.Event.ON_STOP -> mapVisible = false
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    fun startAndMaybeAskBattery() {
        viewModel.startRecording()
        if (viewModel.shouldAskBatteryExemption()) showBatteryDialog = true
    }

    fun hasBackgroundLocation(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED

    fun continueAfterForegroundLocation() {
        permissionDenied = false
        if (!hasBackgroundLocation() && !backgroundPromptDeclinedThisRun) {
            showBackgroundLocationDialog = true
        } else {
            startAndMaybeAskBattery()
        }
    }

    val backgroundPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        showBackgroundLocationDialog = false
        startAndMaybeAskBattery()
    }

    val backgroundSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        showBackgroundLocationDialog = false
        startAndMaybeAskBattery()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val locationGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
        permissionDenied = !locationGranted
        if (locationGranted) continueAfterForegroundLocation()
    }

    fun startWithPermissions() {
        val hasLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (hasLocation) {
            continueAfterForegroundLocation()
        } else {
            val permissions = buildList {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
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
        if (mapVisible && saveTarget == null) {
            LiveTrackMap(
                points = (state as? RecordingState.Recording)?.liveTrack.orEmpty(),
                trackColor = MaterialTheme.colorScheme.primary,
                following = mapFollowing,
                recenterRequest = recenterRequest,
                onUserMovedMap = { mapFollowing = false },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Surface(
                color = MaterialTheme.colorScheme.background,
                modifier = Modifier.fillMaxSize(),
            ) {}
        }

        if (mapVisible && !mapFollowing && saveTarget == null) {
            MapControl(
                onClick = {
                    mapFollowing = true
                    recenterRequest++
                },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = DhavaSpacing.large),
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
                errorMessage = startError ?: if (permissionDenied) {
                    "Precise location is required to record a ride."
                } else {
                    null
                },
                onStart = ::startWithPermissions,
            )
        }
    }

    if (showBatteryDialog) BatteryExemptionDialog { showBatteryDialog = false }
    if (showBackgroundLocationDialog) {
        val optionLabel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.backgroundPermissionOptionLabel.toString()
        } else {
            "Allow all the time"
        }
        BackgroundLocationDialog(
            optionLabel = optionLabel,
            onAllow = {
                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                    backgroundPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                } else {
                    backgroundSettingsLauncher.launch(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                }
            },
            onRecordAnyway = {
                showBackgroundLocationDialog = false
                backgroundPromptDeclinedThisRun = true
                startAndMaybeAskBattery()
            },
        )
    }
}

private data class SaveTarget(val id: String, val startedAtMs: Long, val durationMs: Long)

@Composable
private fun IdleContent(errorMessage: String?, onStart: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Box(Modifier.fillMaxSize()) {
        DhavaPanel(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(DhavaSpacing.medium),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Row(
                modifier = Modifier.padding(DhavaSpacing.xLarge),
                horizontalArrangement = Arrangement.spacedBy(DhavaSpacing.large),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    DhavaSectionLabel("Mountain bike")
                    Spacer(Modifier.height(DhavaSpacing.small))
                    Text("Ready to ride", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(DhavaSpacing.small))
                    Text(
                        "Sensors warm up before recording starts.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DhavaRideControl(
                    icon = Icons.Filled.PlayArrow,
                    contentDescription = "Start recording",
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onStart()
                    },
                )
            }
        }
        if (errorMessage != null) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(DhavaSpacing.large),
            ) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(DhavaSpacing.large),
                )
            }
        }
    }
}

@Composable
private fun PreparingContent(state: RecordingState.Preparing, onCancel: () -> Unit) {
    val remainingSeconds = ((10_000 - state.elapsedMs).coerceAtLeast(0) + 999) / 1_000
    Box(Modifier.fillMaxSize()) {
        DhavaPanel(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(DhavaSpacing.medium),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(DhavaSpacing.xLarge),
                verticalArrangement = Arrangement.spacedBy(DhavaSpacing.large),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        DhavaSectionLabel("Preparing")
                        Text("Finding a clean start", style = MaterialTheme.typography.titleLarge)
                    }
                    DhavaStatusPill("${remainingSeconds}s max")
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
        Row(horizontalArrangement = Arrangement.spacedBy(DhavaSpacing.medium), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = if (ready) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(22.dp),
            )
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

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

    Box(Modifier.fillMaxSize()) {
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
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DhavaStatusPill(
                        text = status,
                        containerColor = if (state.paused) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        },
                        contentColor = if (state.paused) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        },
                    )
                    if (showDiagnostics) {
                        Text(
                            state.lastAccuracyM?.let { "GPS ±${it.toInt()} m" } ?: "GPS —",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(DhavaSpacing.large))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    DhavaMetric(
                        value = state.lastSpeedMps?.let { String.format(Locale.US, "%.1f", it * 3.6f) } ?: "—",
                        label = "km/h",
                        prominent = true,
                    )
                    DhavaMetric(
                        value = formatElapsed(state.elapsedMs),
                        label = "Ride time",
                        alignment = Alignment.End,
                    )
                }
                Spacer(Modifier.height(DhavaSpacing.xLarge))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.paused) {
                        DhavaRideControl(
                            icon = Icons.Filled.Stop,
                            contentDescription = "Finish ride",
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onStop()
                            },
                            tone = DhavaControlTone.Destructive,
                        )
                        Spacer(Modifier.size(DhavaSpacing.xLarge))
                    }
                    DhavaRideControl(
                        icon = if (state.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = if (state.paused) "Resume recording" else "Pause recording",
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (state.paused) onResume() else onPause()
                        },
                    )
                }
            }
        }
    }

}

@Composable
private fun MapControl(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.size(DhavaSizes.mapControl),
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
            Text("Some battery managers stop long recordings. Allow Dhava to run unrestricted during rides.")
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
                "If Android kills Dhava after the screen turns off, background location lets the recorder restart. " +
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
    DhavaTheme(darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.background) {
            IdleContent(errorMessage = null, onStart = {})
        }
    }
}

@Preview(name = "Record · preparing", widthDp = 412, heightDp = 760)
@Composable
private fun PreparingContentPreview() {
    DhavaTheme(darkTheme = true) {
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
    DhavaTheme(darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.background) {
            RecordingContent(
                state = RecordingState.Recording(
                    startedAtMs = 0,
                    elapsedMs = 1_842_000,
                    lastSpeedMps = 12.4f,
                    lastAccuracyM = 4.8f,
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
