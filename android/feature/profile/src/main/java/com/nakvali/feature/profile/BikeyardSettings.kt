package com.nakvali.feature.profile

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nakvali.core.recording.bikeyard.*
import com.nakvali.core.ui.NakvaliPrimaryButton
import com.nakvali.core.ui.NakvaliSectionLabel
import com.nakvali.core.ui.NakvaliSpacing

@Composable
internal fun BikeyardSettings(viewModel: ProfileViewModel) {
    val state by viewModel.bikeyard.collectAsState()
    val context = LocalContext.current
    var confirmAutomatic by remember { mutableStateOf(false) }
    var confirmAutomaticMetrics by remember { mutableStateOf(false) }
    var showMountingMenu by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = NakvaliSpacing.large), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NakvaliSectionLabel("BIKEYARD")
        Text(if (state.connected) state.riderName ?: "Connected rider" else "Your rides in BIKEYARD", style = MaterialTheme.typography.titleLarge)
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!state.connected && !state.connecting) {
            NakvaliPrimaryButton("Connect BIKEYARD", enabled = !state.loading, modifier = Modifier.fillMaxWidth(), onClick = {
                viewModel.connectBikeyard { result ->
                    result.onSuccess { url ->
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                            .onFailure { viewModel.cancelBikeyard(); Toast.makeText(context, "No browser is available", Toast.LENGTH_LONG).show() }
                    }.onFailure { Toast.makeText(context, "Could not start BIKEYARD connection", Toast.LENGTH_LONG).show() }
                }
            })
        }
        if (state.connecting) {
            Text("Finish connecting in your browser. If the link stays there, enable Open supported links for Nakvali in Android settings.", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = viewModel::cancelBikeyard) { Text("Cancel connection") }
        }
        if (state.connected) {
            Text("Visibility for new uploads", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BikeyardVisibility.entries.forEach { visibility ->
                    FilterChip(selected = state.visibility == visibility,
                        onClick = { viewModel.bikeyardSettings(state.automatic, visibility) }, label = { Text(visibility.label) })
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Automatic uploads", style = MaterialTheme.typography.titleMedium)
                    Text("New saved rides upload when online. Existing rides stay on this phone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = state.automatic, onCheckedChange = {
                    if (it) confirmAutomatic = true else viewModel.bikeyardSettings(false, state.visibility)
                }, modifier = Modifier.semantics { contentDescription = "Automatic BIKEYARD uploads" })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Experimental airtime", style = MaterialTheme.typography.titleMedium)
                    Text("Send candidate events with new private rides. Phone G stays local.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = state.automaticMetrics,
                    enabled = state.automatic && state.visibility == BikeyardVisibility.PRIVATE,
                    onCheckedChange = {
                        if (it) confirmAutomaticMetrics = true
                        else viewModel.bikeyardMetricsSettings(false, state.metricsMounting)
                    },
                    modifier = Modifier.semantics { contentDescription = "Automatic experimental airtime sync" },
                )
            }
            if (state.automaticMetrics) Box {
                TextButton(onClick = { showMountingMenu = true }) {
                    Text("Phone position: ${state.metricsMounting.label}")
                }
                DropdownMenu(expanded = showMountingMenu, onDismissRequest = { showMountingMenu = false }) {
                    BikeyardMounting.entries.forEach { mounting ->
                        DropdownMenuItem(text = { Text(mounting.label) }, onClick = {
                            viewModel.bikeyardMetricsSettings(true, mounting)
                            showMountingMenu = false
                        })
                    }
                }
            }
            Text("Queued rides keep the visibility chosen when queued. Edits after an upload are not synced.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { confirmDisconnect = true }) { Text("Disconnect BIKEYARD") }
        }
        state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    if (confirmAutomatic) AlertDialog(
        onDismissRequest = { confirmAutomatic = false }, title = { Text("Upload new rides automatically?") },
        text = { Text("This turns off Offline mode. New rides you save will upload to ${state.riderName}, with visibility ${state.visibility.label.lowercase()}. Processed tracks and ride details are sent; raw sensor files stay here.") },
        confirmButton = { TextButton(onClick = { viewModel.bikeyardSettings(true, state.visibility); confirmAutomatic = false }) { Text("Enable uploads") } },
        dismissButton = { TextButton(onClick = { confirmAutomatic = false }) { Text("Cancel") } },
    )
    if (confirmAutomaticMetrics) AlertDialog(
        onDismissRequest = { confirmAutomaticMetrics = false },
        title = { Text("Sync possible airtime automatically?") },
        text = { Text("For new private rides, Nakvali will send candidate event times, measured " +
            "sensor coverage and phone position after the track is accepted. Raw sensor files, " +
            "GPS coordinates and phone G peaks stay on this device. Candidates are experimental.") },
        confirmButton = { TextButton(onClick = {
            viewModel.bikeyardMetricsSettings(true, state.metricsMounting)
            confirmAutomaticMetrics = false
        }) { Text("Enable airtime sync") } },
        dismissButton = { TextButton(onClick = { confirmAutomaticMetrics = false }) { Text("Cancel") } },
    )
    if (confirmDisconnect) AlertDialog(
        onDismissRequest = { confirmDisconnect = false }, title = { Text("Disconnect BIKEYARD?") },
        text = { Text("Queued uploads stop and connection data is removed from this phone. A ride already accepted by BIKEYARD stays there. Your local recordings are kept.") },
        confirmButton = { TextButton(onClick = { viewModel.disconnectBikeyard(); confirmDisconnect = false }) { Text("Disconnect") } },
        dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Cancel") } },
    )
}
