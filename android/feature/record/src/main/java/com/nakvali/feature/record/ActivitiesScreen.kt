package com.nakvali.feature.record

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PedalBike
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nakvali.core.recording.LocalRecording
import com.nakvali.core.recording.RecordingStatus
import com.nakvali.core.recording.UploadState
import com.nakvali.core.recording.needsRecoveryAttention
import com.nakvali.core.ui.NakvaliEmptyState
import com.nakvali.core.ui.NakvaliPanel
import com.nakvali.core.ui.NakvaliScreenHeader
import com.nakvali.core.ui.NakvaliSpacing
import com.nakvali.core.ui.NakvaliStatusPill
import com.nakvali.core.ui.NakvaliTheme

@Composable
fun ActivitiesScreen(
    onOpenActivity: (String) -> Unit,
    onFinishSaving: (String) -> Unit,
    onStartRecording: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RecordViewModel = viewModel(),
) {
    val recordings by viewModel.recordings.collectAsState()
    val uploads by viewModel.uploads.collectAsState()
    val finished = recordings.filter { it.status != RecordingStatus.RECORDING }
    val needsAttention = finished.count {
        it.needsRecoveryAttention() || it.needsSaveAction()
    }

    ActivitiesContent(
        recordings = finished,
        needsAttention = needsAttention,
        uploads = uploads,
        onOpenActivity = onOpenActivity,
        onFinishSaving = onFinishSaving,
        onStartRecording = onStartRecording,
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
    onStartRecording: () -> Unit,
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
                "${recordings.size} ${if (recordings.size == 1) "ride" else "rides"} · " +
                    "$needsAttention " +
                    if (needsAttention == 1) "needs attention" else "need attention"
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
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(NakvaliSpacing.screen),
                contentAlignment = Alignment.TopCenter,
            ) {
                NakvaliPanel(Modifier.fillMaxWidth()) {
                    NakvaliEmptyState(
                        title = "No rides yet",
                        description = "Finish a recording and it will stay here on this device.",
                        icon = Icons.AutoMirrored.Filled.List,
                        action = {
                            FilledTonalButton(onClick = onStartRecording) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(NakvaliSpacing.small))
                                Text("Record a ride")
                            }
                        },
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = NakvaliSpacing.screen,
                    end = NakvaliSpacing.screen,
                    top = NakvaliSpacing.small,
                    bottom = NakvaliSpacing.xLarge,
                ),
                verticalArrangement = Arrangement.spacedBy(NakvaliSpacing.medium),
            ) {
                items(recordings, key = { it.id }) { recording ->
                    ActivityRow(
                        recording = recording,
                        uploadState = uploads[recording.id],
                        onOpen = { onOpenActivity(recording.id) },
                        onFinishSaving = { onFinishSaving(recording.id) },
                        onRetry = { onRetry(recording.id) },
                    )
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
    val needsAttention = recording.needsSaveAction() || recording.needsRecoveryAttention()
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        border = BorderStroke(
            1.dp,
            if (needsAttention) {
                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(NakvaliSpacing.large),
            horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = CircleShape,
                color = if (needsAttention) {
                    MaterialTheme.colorScheme.tertiaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
                contentColor = if (needsAttention) {
                    MaterialTheme.colorScheme.onTertiaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.PedalBike, contentDescription = null, Modifier.size(22.dp))
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = recording.title ?: "Unfinished ride",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatStartTime(recording.startedAtMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(
                        formatElapsed(recording.endedAtMs - recording.startedAtMs),
                        recording.bikeName,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
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
                recording.needsSaveAction() -> TextButton(onClick = onFinishSaving) {
                    Text("Finish")
                }
                recording.status == RecordingStatus.FAILED -> TextButton(onClick = onRetry) {
                    Text("Retry")
                }
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
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

internal fun LocalRecording.needsSaveAction(): Boolean =
    status == RecordingStatus.RECORDED && savedAtMs == null

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
                    id = "unfinished",
                    startedAtMs = 1_767_004_000_000,
                    endedAtMs = 1_767_004_042_000,
                    sizeBytes = 42_000,
                    status = RecordingStatus.RECORDED,
                ),
                LocalRecording(
                    id = "preview",
                    startedAtMs = 1_767_000_000_000,
                    endedAtMs = 1_767_003_420_000,
                    sizeBytes = 48_234_120,
                    status = RecordingStatus.RECORDED,
                    title = "Morning laps at Turtle Lake",
                    bikeName = "Enduro",
                    savedAtMs = 1_767_003_500_000,
                ),
            ),
            needsAttention = 1,
            uploads = emptyMap(),
            onOpenActivity = {},
            onFinishSaving = {},
            onStartRecording = {},
            onRetry = {},
        )
    }
}
