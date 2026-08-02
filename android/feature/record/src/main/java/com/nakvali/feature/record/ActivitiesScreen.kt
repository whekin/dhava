package com.nakvali.feature.record

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nakvali.core.recording.LocalRecording
import com.nakvali.core.recording.RecordingStatus
import com.nakvali.core.recording.UploadState
import com.nakvali.core.recording.needsRecoveryAttention
import com.nakvali.core.ui.NakvaliDivider
import com.nakvali.core.ui.NakvaliEmptyState
import com.nakvali.core.ui.NakvaliScreenHeader
import com.nakvali.core.ui.NakvaliSpacing
import com.nakvali.core.ui.NakvaliStatusPill
import com.nakvali.core.ui.NakvaliTheme

@Composable
fun ActivitiesScreen(
    onOpenActivity: (String) -> Unit,
    onFinishSaving: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RecordViewModel = viewModel(),
) {
    val recordings by viewModel.recordings.collectAsState()
    val uploads by viewModel.uploads.collectAsState()
    val finished = recordings.filter { it.status != RecordingStatus.RECORDING }
    val needsAttention = finished.count(LocalRecording::needsRecoveryAttention)

    ActivitiesContent(
        recordings = finished,
        needsAttention = needsAttention,
        uploads = uploads,
        onOpenActivity = onOpenActivity,
        onFinishSaving = onFinishSaving,
        onRetry = viewModel::retryUpload,
        modifier = modifier,
    )
}

@Composable
private fun ActivitiesContent(
    recordings: List<LocalRecording>,
    needsAttention: Int,
    uploads: Map<String, UploadState>,
    onOpenActivity: (String) -> Unit,
    onFinishSaving: (String) -> Unit,
    onRetry: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        NakvaliScreenHeader(
            eyebrow = "On this device",
            title = "Activities",
            description = if (recordings.isEmpty()) {
                null
            } else if (needsAttention > 0) {
                "${recordings.size} rides · $needsAttention need attention"
            } else {
                "${recordings.size} recorded ${if (recordings.size == 1) "ride" else "rides"}"
            },
            modifier = Modifier.padding(
                start = NakvaliSpacing.screen,
                end = NakvaliSpacing.screen,
                top = NakvaliSpacing.xLarge,
                bottom = NakvaliSpacing.large,
            ),
        )
        if (recordings.isEmpty()) {
            NakvaliEmptyState(
                title = "Your trail starts here",
                description = "Finished rides stay on this phone and appear here automatically.",
                icon = Icons.AutoMirrored.Filled.List,
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(recordings, key = { it.id }) { recording ->
                    ActivityRow(
                        recording = recording,
                        uploadState = uploads[recording.id],
                        onOpen = { onOpenActivity(recording.id) },
                        onFinishSaving = { onFinishSaving(recording.id) },
                        onRetry = { onRetry(recording.id) },
                    )
                    NakvaliDivider(Modifier.padding(horizontal = NakvaliSpacing.screen))
                }
            }
        }
    }
}

@Composable
private fun ActivityRow(
    recording: LocalRecording,
    uploadState: UploadState?,
    onOpen: () -> Unit,
    onFinishSaving: () -> Unit,
    onRetry: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = NakvaliSpacing.screen, vertical = NakvaliSpacing.large),
        horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.large),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = recording.title ?: formatStartTime(recording.startedAtMs),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    formatElapsed(recording.endedAtMs - recording.startedAtMs),
                    formatSize(recording.sizeBytes),
                    recording.bikeName,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (recording.recovered) {
                Text(
                    if (recording.recoveryFailed) {
                        "Interrupted · raw file kept"
                    } else {
                        "Interrupted · raw data recovered"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (recording.recoveryFailed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.tertiary
                    },
                )
            }
        }
        when {
            recording.status == RecordingStatus.RECORDED -> TextButton(onClick = onFinishSaving) {
                Text("Save")
            }
            recording.status == RecordingStatus.FAILED -> TextButton(onClick = onRetry) { Text("Retry") }
            recording.status == RecordingStatus.UPLOADED -> Icon(
                Icons.Filled.Check,
                contentDescription = "Uploaded",
                tint = MaterialTheme.colorScheme.tertiary,
            )
            else -> NakvaliStatusPill(statusLabel(recording, uploadState))
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
        )
    }
}

private fun statusLabel(recording: LocalRecording, uploadState: UploadState?): String = when {
    uploadState is UploadState.Uploading -> "Uploading"
    uploadState is UploadState.Retrying -> "Retrying"
    recording.status == RecordingStatus.RECORDED -> "Local"
    recording.status == RecordingStatus.PENDING_UPLOAD -> "Queued"
    else -> recording.status.name
}

@Preview(name = "Activities · populated", widthDp = 412, heightDp = 760)
@Composable
private fun ActivitiesContentPreview() {
    NakvaliTheme(darkTheme = true) {
        ActivitiesContent(
            recordings = listOf(
                LocalRecording(
                    id = "preview",
                    startedAtMs = 1_767_000_000_000,
                    endedAtMs = 1_767_003_420_000,
                    sizeBytes = 48_234_120,
                    status = RecordingStatus.RECORDED,
                    title = "Morning laps at Turtle Lake",
                    bikeName = "Enduro",
                ),
            ),
            needsAttention = 0,
            uploads = emptyMap(),
            onOpenActivity = {},
            onFinishSaving = {},
            onRetry = {},
        )
    }
}
