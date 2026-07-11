package com.dhava.feature.record

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
 * background upload progress.
 */
@Composable
fun RecordScreen(
    modifier: Modifier = Modifier,
    viewModel: RecordViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val recordings by viewModel.recordings.collectAsState()
    val uploads by viewModel.uploads.collectAsState()
    val bikes by viewModel.bikes.collectAsState()
    val lastUsedBikeId by viewModel.lastUsedBikeId.collectAsState()
    val reopenedSaveId by viewModel.reopenedSaveId.collectAsState()

    var permissionDenied by remember { mutableStateOf(false) }

    // ACCESS_FINE_LOCATION and POST_NOTIFICATIONS (33+) are runtime
    // permissions; HIGH_SAMPLING_RATE_SENSORS is install-time (normal
    // protection level), so declaring it in the manifest is enough.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val locationGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
        permissionDenied = !locationGranted
        if (locationGranted) viewModel.startRecording()
    }

    fun startWithPermissions() {
        val hasLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (hasLocation) {
            permissionDenied = false
            viewModel.startRecording()
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

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                state is RecordingState.Recording -> RecordingContent(
                    state = state as RecordingState.Recording,
                    onStop = viewModel::stopRecording,
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
                    permissionDenied = permissionDenied,
                    onStart = ::startWithPermissions,
                )
            }
        }

        if (recordings.isNotEmpty() && state !is RecordingState.Recording && saveTarget == null) {
            RecordingsList(
                recordings = recordings,
                uploads = uploads,
                onFinishSaving = viewModel::openSave,
                onRetry = viewModel::retryUpload,
            )
        }
    }
}

/** What the save sheet is currently editing. */
private data class SaveTarget(val id: String, val startedAtMs: Long, val durationMs: Long)

@Composable
private fun IdleContent(
    permissionDenied: Boolean,
    onStart: () -> Unit,
) {
    Button(
        onClick = onStart,
        shape = CircleShape,
        modifier = Modifier.size(160.dp),
    ) {
        Text(
            text = "Start",
            style = MaterialTheme.typography.headlineMedium,
        )
    }
    if (permissionDenied) {
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Dhava needs precise location to time your runs. " +
                "Grant location access in system settings to start recording.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun RecordingContent(
    state: RecordingState.Recording,
    onStop: () -> Unit,
) {
    Text(
        text = formatElapsed(state.elapsedMs),
        style = MaterialTheme.typography.displayLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = state.lastSpeedMps?.let {
            String.format(Locale.US, "%.1f km/h", it * 3.6f)
        } ?: "— km/h",
        style = MaterialTheme.typography.displaySmall,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(modifier = Modifier.height(24.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        StatLabel(
            text = state.lastAccuracyM?.let {
                String.format(Locale.US, "±%.0f m", it)
            } ?: "no fix",
        )
        StatLabel(text = "gps ${state.gpsCount}")
        StatLabel(text = "imu ${state.imuCount}")
        StatLabel(text = "baro ${state.baroCount}")
    }
    Spacer(modifier = Modifier.height(40.dp))
    Button(
        onClick = onStop,
        shape = CircleShape,
        modifier = Modifier.size(120.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Text(
            text = "Stop",
            style = MaterialTheme.typography.headlineSmall,
        )
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
                    onFinishSaving = { onFinishSaving(recording.id) },
                    onRetry = { onRetry(recording.id) },
                )
            }
        }
    }
}

@Composable
private fun RecordingRow(
    recording: LocalRecording,
    uploadState: UploadState?,
    onFinishSaving: () -> Unit,
    onRetry: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
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
            recording.status == RecordingStatus.RECORDED -> TextButton(onClick = onFinishSaving) {
                Text("Finish saving")
            }
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
