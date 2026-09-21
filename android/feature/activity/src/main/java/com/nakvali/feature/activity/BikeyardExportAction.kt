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
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("BIKEYARD · ${if (state.environment == BikeyardEnvironment.SANDBOX) "sandbox" else "live"}", style = MaterialTheme.typography.titleMedium)
        Text("Whole ride · processed TCX without transport. Original recordings stay on this phone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val label = when {
            state.loading -> "Loading BIKEYARD…"
            state.connecting -> "Finish connecting in your browser"
            upload?.status == BikeyardUploadStatus.UPLOADED -> "Uploaded to BIKEYARD"
            upload?.status == BikeyardUploadStatus.DUPLICATE -> "Duplicate track — review in BIKEYARD"
            !state.connected -> "Connect BIKEYARD"
            upload?.status == BikeyardUploadStatus.QUEUED -> "Queued · waiting for network or retry"
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
        if (terminal) TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://yard.bike"))) }) { Text("Open BIKEYARD") }
        if (recording?.savedAtMs == null) Text("Save this ride before uploading.", style = MaterialTheme.typography.bodySmall)
    }
    if (confirmUpload && recording != null) AlertDialog(
        onDismissRequest = { confirmUpload = false }, title = { Text("Upload this ride?") },
        text = { Text("Send the whole processed ride to ${state.riderName} in ${state.environment.name.lowercase()}. Visibility: ${(upload?.visibility ?: state.visibility).label}. Change the default in Profile → BIKEYARD. A retry keeps the original queued file and settings.") },
        confirmButton = { TextButton(onClick = { viewModel.upload(recording); confirmUpload = false }) { Text("Upload") } },
        dismissButton = { TextButton(onClick = { confirmUpload = false }) { Text("Cancel") } },
    )
}
