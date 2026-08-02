package com.nakvali.feature.record

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PedalBike
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nakvali.core.recording.Bike
import com.nakvali.core.recording.BikeType
import com.nakvali.core.ui.NakvaliMetric
import com.nakvali.core.ui.NakvaliPanel
import com.nakvali.core.ui.NakvaliScreenHeader
import com.nakvali.core.ui.NakvaliSectionLabel
import com.nakvali.core.ui.NakvaliTextField
import com.nakvali.core.ui.NakvaliSizes
import com.nakvali.core.ui.NakvaliSpacing
import com.nakvali.core.ui.NakvaliTheme
import java.time.Instant
import java.time.ZoneId

@Composable
fun SaveRecordingScreen(
    recordingId: String,
    onFinished: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RecordViewModel = viewModel(),
) {
    val recordings by viewModel.recordings.collectAsState()
    val bikes by viewModel.bikes.collectAsState()
    val lastUsedBikeId by viewModel.lastUsedBikeId.collectAsState()
    val recording = recordings.firstOrNull { it.id == recordingId }

    if (recording != null) {
        SaveContent(
            recordingId = recording.id,
            startedAtMs = recording.startedAtMs,
            durationMs = recording.endedAtMs - recording.startedAtMs,
            bikes = bikes,
            lastUsedBikeId = lastUsedBikeId,
            onAddBike = viewModel::addBike,
            onSave = { title, description, bike ->
                viewModel.save(recording.id, title, description, bike)
                onFinished()
            },
            onDiscard = {
                viewModel.discard(recording.id)
                onFinished()
            },
            onBack = onBack,
            modifier = modifier,
        )
    }
}

/**
 * Save sheet shown after Stop (and reopened from the list for unsaved
 * recordings): title, description, visual bike picker, Save / Discard.
 *
 * Save is a purely local operation — it writes metadata to the index and
 * enqueues a network-constrained upload job, so it works in airplane mode.
 */
@Composable
internal fun SaveContent(
    recordingId: String,
    startedAtMs: Long,
    durationMs: Long,
    bikes: List<Bike>,
    lastUsedBikeId: String?,
    onAddBike: (String, BikeType) -> Bike,
    onSave: (title: String, description: String, bike: Bike?) -> Unit,
    onDiscard: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    // Field state survives configuration changes and process death; keyed on
    // the recording id so a different recording never inherits stale input.
    var title by rememberSaveable(recordingId) { mutableStateOf(defaultTitle(startedAtMs)) }
    var description by rememberSaveable(recordingId) { mutableStateOf("") }

    // null = untouched → preselect the last-used bike once it loads.
    var pickedBikeId by rememberSaveable(recordingId) { mutableStateOf<String?>(null) }
    val selectedBikeId = pickedBikeId ?: lastUsedBikeId

    var showAddBike by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
      Column(
          modifier = Modifier
              .fillMaxSize()
              .imePadding()
              .verticalScroll(rememberScrollState())
              .padding(horizontal = NakvaliSpacing.large, vertical = NakvaliSpacing.large),
      ) {
        Row(verticalAlignment = Alignment.Top) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            NakvaliScreenHeader(
                eyebrow = "Ride complete",
                title = "Save the good one",
                description = "The raw recording stays on this phone.",
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(NakvaliSpacing.large))

        NakvaliPanel(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
          Row(
              Modifier.fillMaxWidth().padding(NakvaliSpacing.medium),
              horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.medium),
          ) {
            NakvaliMetric(formatElapsed(durationMs), "Duration", Modifier.weight(1f))
            NakvaliMetric(formatStartClock(startedAtMs), "Started", Modifier.weight(1f))
            NakvaliMetric("Local", "Storage", Modifier.weight(0.7f))
          }
        }
        Spacer(modifier = Modifier.height(NakvaliSpacing.large))

        NakvaliSectionLabel("Details")
        Spacer(modifier = Modifier.height(NakvaliSpacing.small))

        NakvaliTextField(
            value = title,
            onValueChange = { title = it },
            label = "Ride title",
            imeAction = ImeAction.Next,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(NakvaliSpacing.large))
        NakvaliTextField(
            value = description,
            onValueChange = { description = it },
            label = "Notes (optional)",
            placeholder = "Conditions, feel, anything worth remembering",
            singleLine = false,
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(NakvaliSpacing.large))

        NakvaliSectionLabel("Bike", Modifier.padding(bottom = NakvaliSpacing.small))
        BikePicker(
            bikes = bikes,
            selectedBikeId = selectedBikeId,
            onSelect = { pickedBikeId = it },
            onAddBike = { showAddBike = true },
        )
        Spacer(modifier = Modifier.height(NakvaliSpacing.large))

        Button(
            onClick = {
                onSave(title, description, bikes.firstOrNull { it.id == selectedBikeId })
            },
            enabled = title.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(NakvaliSizes.primaryActionHeight),
        ) {
            Text("Save activity", style = MaterialTheme.typography.titleMedium)
        }
        TextButton(onClick = { confirmDiscard = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Discard recording", color = MaterialTheme.colorScheme.error)
        }
        Text(
            "Nothing is uploaded while Offline mode is on.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
      }
    }

    if (showAddBike) {
        AddBikeDialog(
            onDismiss = { showAddBike = false },
            onAdd = { name, type ->
                val bike = onAddBike(name, type)
                pickedBikeId = bike.id
                showAddBike = false
            },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard recording?") },
            text = { Text("The raw recording file will be deleted. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; onDiscard() }) {
                    Text("Discard", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

/**
 * Horizontally scrollable selectable bike cards (visual, not a dropdown —
 * per docs/VISION.md "Look & feel"; images/3D later), plus an "Add bike"
 * card at the end.
 */
@Composable
private fun BikePicker(
    bikes: List<Bike>,
    selectedBikeId: String?,
    onSelect: (String) -> Unit,
    onAddBike: () -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.medium),
    ) {
        items(bikes, key = { it.id }) { bike ->
            BikeCard(
                bike = bike,
                selected = bike.id == selectedBikeId,
                onClick = { onSelect(bike.id) },
            )
        }
        item(key = "add-bike") {
            Card(
                onClick = onAddBike,
                modifier = Modifier.size(width = 128.dp, height = 80.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = NakvaliSpacing.medium),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(modifier = Modifier.width(NakvaliSpacing.small))
                    Text(
                        text = "Add bike",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun BikeCard(
    bike: Bike,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.size(width = 184.dp, height = 80.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
        border = if (selected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = NakvaliSpacing.medium),
            horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(38.dp),
                shape = androidx.compose.foundation.shape.CircleShape,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
                contentColor = if (selected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            ) {
                Icon(
                    imageVector = Icons.Filled.PedalBike,
                    contentDescription = null,
                    modifier = Modifier.padding(8.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = bike.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                )
                Text(
                    text = bike.type.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddBikeDialog(
    onDismiss: () -> Unit,
    onAdd: (name: String, type: BikeType) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf(BikeType.FULL_SUS) }

    AlertDialog(
        // Edge-to-edge windows are not resized for the keyboard, so a centred
        // dialog would otherwise sit behind it.
        modifier = Modifier.imePadding(),
        onDismissRequest = onDismiss,
        title = { Text("Add bike") },
        text = {
            Column {
                NakvaliTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "Name",
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BikeType.entries.forEach { candidate ->
                        FilterChip(
                            selected = type == candidate,
                            onClick = { type = candidate },
                            label = { Text(candidate.label) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(name.trim(), type) },
                enabled = name.isNotBlank(),
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

/** Sensible default title by local time of day of the ride start. */
internal fun defaultTitle(startedAtMs: Long): String {
    val hour = Instant.ofEpochMilli(startedAtMs).atZone(ZoneId.systemDefault()).hour
    return when (hour) {
        in 5..11 -> "Morning ride"
        in 12..16 -> "Afternoon ride"
        in 17..21 -> "Evening ride"
        else -> "Night ride"
    }
}

@Preview(name = "Save ride", widthDp = 412, heightDp = 820)
@Composable
private fun SaveContentPreview() {
    NakvaliTheme(darkTheme = true) {
        SaveContent(
            recordingId = "preview",
            startedAtMs = 1_767_000_000_000,
            durationMs = 3_420_000,
            bikes = listOf(Bike("enduro", "Enduro", BikeType.FULL_SUS)),
            lastUsedBikeId = "enduro",
            onAddBike = { name, type -> Bike("new", name, type) },
            onSave = { _, _, _ -> },
            onDiscard = {},
            onBack = {},
        )
    }
}
