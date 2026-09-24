package com.nakvali.feature.activity

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nakvali.core.recording.LocalRecording
import com.nakvali.core.recording.bikeyard.*
import com.nakvali.core.ui.NakvaliSecondaryButton
import kotlinx.coroutines.launch

internal class BikeyardExportViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = BikeyardRepository.getInstance(application)
    val state = repository.state
    fun upload(recording: LocalRecording) = repository.upload(recording)
    fun syncMetrics(recordingId: String, mounting: BikeyardMounting) = repository.syncMetrics(recordingId, mounting)
    fun connect(onResult: (Result<String>) -> Unit) {
        viewModelScope.launch { onResult(runCatching { repository.beginConnect() }) }
    }
    fun cancelConnect() = repository.cancelConnect()
}

@Composable
internal fun BikeyardExportAction(recording: LocalRecording?, available: Boolean) {
    if (LocalInspectionMode.current) {
        Text("Connect BIKEYARD", style = MaterialTheme.typography.titleMedium)
        return
    }
    val viewModel: BikeyardExportViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val upload = recording?.let { state.uploadFor(it.id) }
    var confirmUpload by remember { mutableStateOf(false) }
    var confirmMetrics by remember { mutableStateOf(false) }
    var selectedMounting by remember { mutableStateOf(BikeyardMounting.UNKNOWN) }
    var mountingMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("BIKEYARD", style = MaterialTheme.typography.titleMedium)
        Text("Whole ride · compressed TCX without transport. Original recordings stay on this phone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val label = when {
            state.loading -> "Loading BIKEYARD…"
            state.connecting -> "Finish connecting in your browser"
            upload?.status == BikeyardUploadStatus.UPLOADED -> "Uploaded to BIKEYARD"
            upload?.status == BikeyardUploadStatus.DUPLICATE -> "Duplicate track — review in BIKEYARD"
            !state.connected -> "Connect BIKEYARD"
            upload?.status == BikeyardUploadStatus.QUEUED -> if (upload.uploadId != null) "BIKEYARD is processing the ride…" else "Queued · waiting for network or retry"
            upload?.status == BikeyardUploadStatus.UPLOADING -> "Uploading to BIKEYARD…"
            upload != null -> "Retry BIKEYARD upload"
            else -> "Upload to BIKEYARD"
        }
        val waiting = upload?.status in setOf(BikeyardUploadStatus.QUEUED, BikeyardUploadStatus.UPLOADING)
        val terminal = upload?.status in setOf(BikeyardUploadStatus.UPLOADED, BikeyardUploadStatus.DUPLICATE)
        NakvaliSecondaryButton(label, modifier = Modifier.fillMaxWidth(),
            enabled = !state.loading && !state.connecting && !waiting && !terminal && (!state.connected || (available && recording?.savedAtMs != null)),
            onClick = {
                if (state.connected) confirmUpload = true else viewModel.connect { result ->
                    result.onSuccess { url ->
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                            .onFailure { viewModel.cancelConnect(); Toast.makeText(context, "No browser is available", Toast.LENGTH_LONG).show() }
                    }.onFailure { Toast.makeText(context, "Could not connect BIKEYARD", Toast.LENGTH_LONG).show() }
                }
            })
        if (state.connecting) TextButton(onClick = viewModel::cancelConnect) { Text("Cancel connection") }
        upload?.let { Text("Visibility: ${it.visibility.label}", style = MaterialTheme.typography.bodySmall) }
        (upload?.error ?: state.message)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (upload?.status == BikeyardUploadStatus.UPLOADED && upload.rideId != null) {
            if (upload.sensorScopes.isEmpty()) {
                Text("Airtime sync needs a ride uploaded with this app version; earlier track boundaries were not saved.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (upload.visibility == BikeyardVisibility.PRIVATE) {
                val metricsWaiting = upload.metricsStatus == BikeyardMetricsStatus.QUEUED ||
                    upload.metricsStatus == BikeyardMetricsStatus.UPLOADING
                val metricsLabel = when (upload.metricsStatus) {
                    BikeyardMetricsStatus.NONE -> "Send experimental airtime"
                    BikeyardMetricsStatus.QUEUED, BikeyardMetricsStatus.UPLOADING -> "Syncing airtime…"
                    BikeyardMetricsStatus.UPLOADED -> "Update experimental airtime"
                    BikeyardMetricsStatus.FAILED -> "Retry airtime sync"
                    BikeyardMetricsStatus.NEEDS_AUTH -> "Reconnect to sync airtime"
                    BikeyardMetricsStatus.CANCELLED -> "Send airtime manually"
                }
                TextButton(onClick = { confirmMetrics = true }, enabled = state.connected && !metricsWaiting) {
                    Text(metricsLabel)
                }
                if (upload.metricsStatus == BikeyardMetricsStatus.UPLOADED) {
                    Text("Sensor metrics revision ${upload.metricsRevision ?: 1} in BIKEYARD",
                        style = MaterialTheme.typography.bodySmall)
                }
                upload.metricsError?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                Text("Experimental airtime sync is available for private rides only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (terminal) TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://yard.bike"))) }) { Text("Open BIKEYARD") }
        if (recording?.savedAtMs == null) Text("Save this ride before uploading.", style = MaterialTheme.typography.bodySmall)
    }
    if (confirmUpload && recording != null) AlertDialog(
        onDismissRequest = { confirmUpload = false }, title = { Text("Upload this ride?") },
        text = { Text("Send the whole processed ride to ${state.riderName}. Visibility: ${(upload?.visibility ?: state.visibility).label}. Change the default in Profile → BIKEYARD. A retry keeps the original queued file and settings.") },
        confirmButton = { TextButton(onClick = { viewModel.upload(recording); confirmUpload = false }) { Text("Upload") } },
        dismissButton = { TextButton(onClick = { confirmUpload = false }) { Text("Cancel") } },
    )
    if (confirmMetrics && recording != null) AlertDialog(
        onDismissRequest = { confirmMetrics = false },
        title = { Text("Send possible airtime?") },
        text = {
            Column {
                Text("Send candidate event times and measured sensor coverage for this private ride. " +
                    "No GPS coordinates, raw sensors or phone G peaks are included.")
                Box {
                    TextButton(onClick = { mountingMenu = true }) {
                        Text("Phone position: ${selectedMounting.label}")
                    }
                    DropdownMenu(expanded = mountingMenu, onDismissRequest = { mountingMenu = false }) {
                        BikeyardMounting.entries.forEach { mounting ->
                            DropdownMenuItem(text = { Text(mounting.label) }, onClick = {
                                selectedMounting = mounting
                                mountingMenu = false
                            })
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = {
            viewModel.syncMetrics(recording.id, selectedMounting)
            confirmMetrics = false
        }) { Text("Send candidates") } },
        dismissButton = { TextButton(onClick = { confirmMetrics = false }) { Text("Cancel") } },
    )
}
