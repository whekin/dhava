package com.dhava.feature.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.dhava.core.recording.Bike
import com.dhava.core.recording.BikeType
import com.dhava.core.recording.LocalRecording
import com.dhava.core.ui.DhavaSectionLabel
import com.dhava.core.ui.DhavaTextField
import com.dhava.core.ui.DhavaSpacing

/**
 * Post-save metadata editor for one activity: title, notes and bike.
 *
 * A dialog rather than a shared component with the save sheet — feature
 * modules must not depend on each other, and the two flows will diverge
 * (the save sheet gains segments/summary, this stays a plain editor).
 * Changes are local-only; an uploaded activity's server copy is not updated.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ActivityEditDialog(
    recording: LocalRecording,
    bikes: List<Bike>,
    onAddBike: (name: String, type: BikeType) -> Bike,
    onSave: (title: String, description: String, bike: Bike?) -> Unit,
    onDismiss: () -> Unit,
) {
    // Keyed on the recording id so a different activity never inherits input.
    var title by rememberSaveable(recording.id) { mutableStateOf(recording.title.orEmpty()) }
    var description by rememberSaveable(recording.id) {
        mutableStateOf(recording.description.orEmpty())
    }
    var selectedBikeId by rememberSaveable(recording.id) { mutableStateOf(recording.bikeId) }
    var showAddBike by remember { mutableStateOf(false) }

    AlertDialog(
        // Edge-to-edge windows are not resized for the keyboard, so a centred
        // dialog would otherwise sit behind it.
        modifier = Modifier.imePadding(),
        onDismissRequest = onDismiss,
        title = { Text("Edit activity") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                DhavaTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = "Ride title",
                    imeAction = ImeAction.Next,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(DhavaSpacing.large))
                DhavaTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = "Notes (optional)",
                    placeholder = "Conditions, feel, anything worth remembering",
                    singleLine = false,
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(DhavaSpacing.large))
                DhavaSectionLabel("Bike")
                Spacer(Modifier.height(DhavaSpacing.small))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    bikes.forEach { bike ->
                        FilterChip(
                            selected = bike.id == selectedBikeId,
                            onClick = {
                                // Tapping the selected bike deselects it — a
                                // saved ride may legitimately have no bike.
                                selectedBikeId =
                                    if (bike.id == selectedBikeId) null else bike.id
                            },
                            label = { Text(bike.name) },
                        )
                    }
                    AssistChip(
                        onClick = { showAddBike = true },
                        label = { Text("Add bike") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(title, description, bikes.firstOrNull { it.id == selectedBikeId })
                },
                enabled = title.isNotBlank(),
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )

    if (showAddBike) {
        AddBikeDialog(
            onDismiss = { showAddBike = false },
            onAdd = { name, type ->
                val bike = onAddBike(name, type)
                selectedBikeId = bike.id
                showAddBike = false
            },
        )
    }
}

/** Same shape as the save sheet's add-bike dialog (kept module-local). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddBikeDialog(
    onDismiss: () -> Unit,
    onAdd: (name: String, type: BikeType) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf(BikeType.FULL_SUS) }

    AlertDialog(
        modifier = Modifier.imePadding(),
        onDismissRequest = onDismiss,
        title = { Text("Add bike") },
        text = {
            Column {
                DhavaTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "Name",
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
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
