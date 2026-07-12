package com.dhava.feature.record

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dhava.core.recording.LocalRecording
import com.dhava.core.recording.RecordingState
import com.dhava.core.recording.RecordingStatus
import com.dhava.core.recording.UploadState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Record screen: start/stop ride recording, glance at live sensor stats,
 * save finished recordings (title / description / bike) and watch their
 * background upload progress. Tapping a finished recording reports its id
 * through [onOpenActivity]; navigation itself is wired by the app module so
 * this feature stays free of navigation dependencies.
 */
@Composable
fun RecordScreen(
    modifier: Modifier = Modifier,
    onOpenActivity: (String) -> Unit = {},
    viewModel: RecordViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val recordings by viewModel.recordings.collectAsState()
    val bikes by viewModel.bikes.collectAsState()
    val lastUsedBikeId by viewModel.lastUsedBikeId.collectAsState()
    val reopenedSaveId by viewModel.reopenedSaveId.collectAsState()
    val startError by viewModel.startError.collectAsState()
    val diagnosticsEnabled = remember {
        context.getSharedPreferences("recorder_settings", 0).getBoolean("sensor_diagnostics", false)
    }

    var permissionDenied by remember { mutableStateOf(false) }
    var showBatteryDialog by remember { mutableStateOf(false) }
    var mapFollowing by remember { mutableStateOf(true) }
    var recenterRequest by remember { mutableIntStateOf(0) }

    // Recording is never blocked on the battery dialog: it starts right
    // away, the dialog is shown on top of it.
    fun startAndMaybeAskBattery() {
        viewModel.startRecording()
        if (viewModel.shouldAskBatteryExemption()) showBatteryDialog = true
    }

    // ACCESS_FINE_LOCATION and POST_NOTIFICATIONS (33+) are runtime
    // permissions; HIGH_SAMPLING_RATE_SENSORS is install-time (normal
    // protection level), so declaring it in the manifest is enough.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val locationGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
        permissionDenied = !locationGranted
        if (locationGranted) startAndMaybeAskBattery()
    }

    fun startWithPermissions() {
        val hasLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (hasLocation) {
            permissionDenied = false
            startAndMaybeAskBattery()
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

    // The save sheet target: the recording that just finished, or an unsaved
    // entry reopened from the list ("Finish saving").
    val saveTarget: SaveTarget? = when {
        state is RecordingState.Finished -> {
            val summary = (state as RecordingState.Finished).summary
            SaveTarget(summary.id, summary.startedAtMs, summary.endedAtMs - summary.startedAtMs)
        }
        reopenedSaveId != null -> recordings
            .firstOrNull { it.id == reopenedSaveId }
            ?.let { SaveTarget(it.id, it.startedAtMs, it.endedAtMs - it.startedAtMs) }
        else -> null
    }

    Box(modifier = modifier.fillMaxSize()) {
        LiveTrackMap(
            points = (state as? RecordingState.Recording)?.liveTrack.orEmpty(),
            trackColor = MaterialTheme.colorScheme.primary,
            following = mapFollowing,
            recenterRequest = recenterRequest,
            onUserMovedMap = { mapFollowing = false },
            modifier = Modifier.fillMaxSize(),
        )
        if (!mapFollowing && saveTarget == null) {
            Button(
                onClick = {
                    mapFollowing = true
                    recenterRequest++
                },
                shape = CircleShape,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp).size(56.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) { Text("◎", style = MaterialTheme.typography.headlineSmall) }
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
                )
                else -> IdleContent(
                    errorMessage = startError ?: if (permissionDenied) "Dhava needs precise location. Grant location access in system settings." else null,
                    onStart = ::startWithPermissions,
                )
        }
    }

    if (showBatteryDialog) {
        BatteryExemptionDialog(onDismiss = { showBatteryDialog = false })
    }
}

/**
 * One-time ask for a battery-optimization exemption. Aggressive OEM power
 * managers (the 2026-07 OnePlus "o-kill" incident) kill even foreground
 * services mid-ride; the exemption is the strongest signal we can request.
 * Declining never blocks recording.
 */
@Composable
private fun BatteryExemptionDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Keep recording alive") },
        text = {
            Text(
                "Aggressive battery managers (OnePlus, Xiaomi…) kill recording " +
                    "mid-ride. Allow Dhava to run unrestricted.",
            )
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
            ) {
                Text("Allow")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Not now")
            }
        },
    )
}

/** What the save sheet is currently editing. */
private data class SaveTarget(val id: String, val startedAtMs: Long, val durationMs: Long)

@Composable
private fun IdleContent(
    errorMessage: String?,
    onStart: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Box(Modifier.fillMaxSize()) {
      Surface(
          color = MaterialTheme.colorScheme.surface,
          shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
          shadowElevation = 14.dp,
          modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
      ) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
              Text("MOUNTAIN BIKE", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
              Text("Ready to record", style = MaterialTheme.typography.titleLarge)
            }
            Button(onClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onStart() }, shape = CircleShape, modifier = Modifier.size(88.dp)) {
              Icon(Icons.Filled.PlayArrow, "Start recording", modifier = Modifier.size(42.dp))
            }
          }
          Text("GPS and motion sensors will warm up before capture starts.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
        }
      }
    if (errorMessage != null) {
        Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(18.dp), modifier = Modifier.align(Alignment.TopCenter).padding(16.dp)) { Text(
            text = errorMessage,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(16.dp),
        ) }
    }
    }
}

@Composable
private fun PreparingContent(state: RecordingState.Preparing, onCancel: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
      Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(24.dp), shadowElevation = 12.dp, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("PREPARING · ${((5_000 - state.elapsedMs).coerceAtLeast(0) + 999) / 1000}s", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            ReadinessRow("GPS lock", state.gpsReady, state.lastAccuracyM?.let { "±${it.toInt()} m" } ?: "searching")
            ReadinessRow("Motion sensors", state.imuReady, if (state.imuReady) "stable" else "warming up")
            TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) { Text("Cancel") }
        }
      }
    }
}

@Composable
private fun ReadinessRow(label: String, ready: Boolean, detail: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Text(if (ready) "●  $detail" else "○  $detail", color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
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
    var confirmFinish by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxSize()) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(24.dp),
            shadowElevation = 12.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 124.dp),
        ) {
          Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Metric(formatElapsed(state.elapsedMs), "TIME")
            Metric(
                text = state.lastSpeedMps?.let {
                    String.format(Locale.US, "%.1f", it * 3.6f)
                } ?: "—",
                label = "KM/H",
            )
            if (showDiagnostics) Metric(state.lastAccuracyM?.let { "±${it.toInt()}" } ?: "—", "GPS M")
          }
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (state.paused) Button(onClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); confirmFinish = true }, shape = CircleShape, modifier = Modifier.size(88.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)) { Text("■", style = MaterialTheme.typography.headlineSmall) }
            Button(onClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); if (state.paused) onResume() else onPause() }, shape = CircleShape, modifier = Modifier.size(88.dp)) {
                if (state.paused) Icon(Icons.Filled.PlayArrow, "Resume", modifier = Modifier.size(36.dp))
                else Text("Ⅱ", style = MaterialTheme.typography.headlineMedium)
            }
        }
    }
    if (confirmFinish) AlertDialog(
        onDismissRequest = { confirmFinish = false },
        title = { Text("Finish this ride?") },
        text = { Text("Recording will stop and the activity will be ready to save.") },
        confirmButton = { TextButton(onClick = { confirmFinish = false; onStop() }) { Text("Finish ride") } },
        dismissButton = { TextButton(onClick = { confirmFinish = false }) { Text("Keep paused") } },
    )
}

@Composable
private fun Metric(text: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, style = MaterialTheme.typography.headlineMedium)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun RecordingsList(
    recordings: List<LocalRecording>,
    uploads: Map<String, UploadState>,
    onOpen: (String) -> Unit,
    onFinishSaving: (String) -> Unit,
    onRetry: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider()
        Text(
            text = "Recordings",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn(modifier = Modifier.height(200.dp)) {
            items(recordings, key = { it.id }) { recording ->
                RecordingRow(
                    recording = recording,
                    uploadState = uploads[recording.id],
                    onOpen = { onOpen(recording.id) },
                    onFinishSaving = { onFinishSaving(recording.id) },
                    onRetry = { onRetry(recording.id) },
                )
            }
        }
    }
}

/** Recorder-first library used by the top-level Activities destination. */
@Composable
fun ActivitiesScreen(
    onOpenActivity: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RecordViewModel = viewModel(),
) {
    val recordings by viewModel.recordings.collectAsState()
    val uploads by viewModel.uploads.collectAsState()
    Column(modifier.fillMaxSize().padding(top = 28.dp)) {
        Text("ACTIVITIES", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 24.dp))
        Text("Ride archive", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        if (recordings.none { it.status != RecordingStatus.RECORDING }) {
            Text("Your finished rides will live here — on this device.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(recordings.filter { it.status != RecordingStatus.RECORDING }, key = { it.id }) { recording ->
                    RecordingRow(
                        recording = recording,
                        uploadState = uploads[recording.id],
                        onOpen = { onOpenActivity(recording.id) },
                        onFinishSaving = null,
                        onRetry = { viewModel.retryUpload(recording.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordingRow(
    recording: LocalRecording,
    uploadState: UploadState?,
    onOpen: () -> Unit,
    onFinishSaving: (() -> Unit)?,
    onRetry: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Opens the activity detail (map + stats) for this recording.
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = recording.title ?: formatStartTime(recording.startedAtMs),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = listOfNotNull(
                    formatElapsed(recording.endedAtMs - recording.startedAtMs),
                    formatSize(recording.sizeBytes),
                    recording.bikeName,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (recording.recovered) {
                // Crash-recovered ride: repaired from a truncated file, saved
                // through the normal "Finish saving" flow like any unsaved one.
                Text(
                    text = "Recovered after crash",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            when {
                uploadState is UploadState.Retrying -> Text(
                    text = "Upload failed, will retry: ${uploadState.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                recording.status == RecordingStatus.FAILED -> Text(
                    text = "Upload failed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        when {
            recording.status == RecordingStatus.UPLOADED -> Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Uploaded",
                tint = MaterialTheme.colorScheme.primary,
            )
            recording.status == RecordingStatus.RECORDED && onFinishSaving != null ->
                TextButton(onClick = onFinishSaving) { Text("Finish saving") }
            recording.status == RecordingStatus.RECORDED -> StatusLabel("Local")
            recording.status == RecordingStatus.FAILED -> TextButton(onClick = onRetry) {
                Text("Retry")
            }
            uploadState is UploadState.Uploading -> StatusLabel("Uploading…")
            uploadState is UploadState.Retrying -> StatusLabel("Retrying…")
            // PENDING_UPLOAD with no active attempt: WorkManager is waiting
            // for network (offline) or for its backoff window.
            else -> StatusLabel("Queued")
        }
    }
}

@Composable
private fun StatusLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// --- formatting helpers -----------------------------------------------------

private val startTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.US).withZone(ZoneId.systemDefault())

internal fun formatStartTime(epochMs: Long): String =
    startTimeFormatter.format(Instant.ofEpochMilli(epochMs))

internal fun formatElapsed(elapsedMs: Long): String {
    val totalSeconds = elapsedMs / 1_000
    return String.format(
        Locale.US,
        "%02d:%02d:%02d",
        totalSeconds / 3_600,
        (totalSeconds % 3_600) / 60,
        totalSeconds % 60,
    )
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1_024 -> String.format(Locale.US, "%.0f KB", bytes / 1_024.0)
    else -> "$bytes B"
}
