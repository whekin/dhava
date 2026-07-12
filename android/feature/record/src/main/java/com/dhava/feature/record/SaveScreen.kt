package com.dhava.feature.record

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dhava.core.recording.Bike
import com.dhava.core.recording.BikeType
import java.time.Instant
import java.time.ZoneId

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
) {
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
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
      Column(
          modifier = Modifier
              .fillMaxSize()
              .verticalScroll(rememberScrollState())
              .imePadding()
              .navigationBarsPadding()
              .padding(horizontal = 24.dp, vertical = 24.dp),
      ) {
        Text("RIDE COMPLETE", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        Text("Keep the good one.", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(18.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            modifier = Modifier.fillMaxWidth(),
        ) {
          Row(
              Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            RideSummaryMetric(formatElapsed(durationMs), "DURATION")
            RideSummaryMetric(formatStartTime(startedAtMs), "STARTED")
            RideSummaryMetric("LOCAL", "STORAGE")
          }
        }
        Spacer(modifier = Modifier.height(28.dp))

        Text("DETAILS", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Ride title") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = description,
            onValueChange = { description = it },
            label = { Text("Notes (optional)") },
            minLines = 2,
            maxLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "BIKE",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
        )
        BikePicker(
            bikes = bikes,
            selectedBikeId = selectedBikeId,
            onSelect = { pickedBikeId = it },
            onAddBike = { showAddBike = true },
        )
        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = {
                onSave(title, description, bikes.firstOrNull { it.id == selectedBikeId })
            },
            enabled = title.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Text("Save activity", style = MaterialTheme.typography.titleMedium)
        }
        TextButton(onClick = { confirmDiscard = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Discard recording", color = MaterialTheme.colorScheme.error)
        }
        Text(
            "The raw recording remains on this phone.",
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

@Composable
private fun RideSummaryMetric(value: String, label: String) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            ) {
                Column(
                    modifier = Modifier
                        .width(120.dp)
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Add bike",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
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
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = bike.name,
                style = MaterialTheme.typography.titleMedium,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Spacer(modifier = Modifier.height(2.dp))
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
        onDismissRequest = onDismiss,
        title = { Text("Add bike") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
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
