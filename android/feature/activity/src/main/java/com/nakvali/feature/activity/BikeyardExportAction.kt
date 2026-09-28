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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.ui.Alignment
import com.nakvali.core.ui.NakvaliSecondaryButton
import com.nakvali.core.ui.NakvaliSectionLabel
import com.nakvali.core.ui.NakvaliSpacing
import com.nakvali.core.ui.NakvaliStatusPill
import com.nakvali.core.ui.NakvaliStatusTone
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
    fun refreshResult(recordingId: String) = repository.refreshRideResults(listOf(recordingId), force = true)
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
                    BikeyardMetricsStatus.NONE -> "Send jumps"
                    BikeyardMetricsStatus.QUEUED, BikeyardMetricsStatus.UPLOADING -> "Sending jumps…"
                    BikeyardMetricsStatus.UPLOADED -> "Resend jumps"
                    BikeyardMetricsStatus.FAILED -> "Retry jump sync"
                    BikeyardMetricsStatus.NEEDS_AUTH -> "Reconnect to sync jumps"
                    BikeyardMetricsStatus.CANCELLED -> "Send jumps manually"
                }
                TextButton(onClick = { confirmMetrics = true }, enabled = state.connected && !metricsWaiting) {
                    Text(metricsLabel)
                }
                if (upload.metricsStatus == BikeyardMetricsStatus.UPLOADED) {
                    Text("Jumps sent to BIKEYARD · revision ${upload.metricsRevision ?: 1}",
                        style = MaterialTheme.typography.bodySmall)
                }
                upload.metricsError?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                Text("Jump sync is available for private rides only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (terminal) TextButton(onClick = {
            val url = upload?.result?.url ?: state.profile?.profileUrl ?: "https://yard.bike"
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }) { Text(if (upload?.result?.url != null) "Open ride in BIKEYARD" else "Open BIKEYARD") }
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
        title = { Text("Send jumps to BIKEYARD?") },
        text = {
            Column {
                Text("Send jump and airtime event times and measured sensor coverage for this private " +
                    "ride. Air of 0.25 s or more counts as a jump. No GPS coordinates, raw sensors or " +
                    "phone G peaks are included.")
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
        }) { Text("Send jumps") } },
        dismissButton = { TextButton(onClick = { confirmMetrics = false }) { Text("Cancel") } },
    )
}

/**
 * BIKEYARD's processing of this ride, once uploaded: its page, the trails it
 * matched and any honours. Their matching, not Nakvali's segment timing — the
 * section says so, and it never replaces the ride's own segment runs.
 */
@Composable
internal fun BikeyardRideResults(recording: LocalRecording?, modifier: Modifier = Modifier) {
    if (LocalInspectionMode.current || recording == null) return
    val viewModel: BikeyardExportViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    val upload = state.uploadFor(recording.id)?.takeIf { it.status == BikeyardUploadStatus.UPLOADED } ?: return
    val context = LocalContext.current
    LaunchedEffect(recording.id, state.rideAccess, upload.result?.ready) {
        viewModel.refreshResult(recording.id)
    }
    val result = upload.result
    val url = result?.url ?: state.profile?.profileUrl
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(NakvaliSpacing.small)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                NakvaliSectionLabel("On BIKEYARD")
                Spacer(Modifier.height(NakvaliSpacing.xSmall))
                Text(
                    text = when {
                        result == null && state.rideAccess == BikeyardRideAccess.NONE ->
                            "Uploaded · allow ride results in Profile to see trails here"
                        result == null -> "Uploaded · reading results…"
                        !result.ready -> "BIKEYARD is matching trails…"
                        result.trails.isEmpty() -> "No BIKEYARD trails matched"
                        else -> honours(result) ?: "${result.trails.size} " +
                            if (result.trails.size == 1) "trail matched" else "trails matched"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            url?.let {
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
                }) {
                    Text("Open")
                    Spacer(Modifier.width(NakvaliSpacing.xSmall))
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }
        }
        result?.trails?.take(12)?.forEach { trail -> TrailRow(trail) }
        if (!result?.trails.isNullOrEmpty()) {
            Text(
                "Trail matching and honours by BIKEYARD",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun TrailRow(trail: BikeyardTrailResult) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.medium),
    ) {
        Text(
            trail.name.ifBlank { "Trail" },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        trail.achievement?.let { kind ->
            NakvaliStatusPill(
                text = when (kind) {
                    BikeyardAchievementKind.KOM -> "KOM"
                    BikeyardAchievementKind.PERSONAL_BEST -> trail.rank?.let { "PR #$it" } ?: "PR"
                    BikeyardAchievementKind.LOCAL_LEGEND -> "Local legend"
                },
                tone = if (kind == BikeyardAchievementKind.KOM) NakvaliStatusTone.Held else NakvaliStatusTone.Live,
            )
        }
        Text(
            text = if (trail.complete) formatTrailTime(trail.durationS) else "partial",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun honours(result: BikeyardRideResult): String? = listOfNotNull(
    result.kom.takeIf { it > 0 }?.let { "$it KOM" },
    result.medals.takeIf { it > 0 }?.let { "$it ${if (it == 1) "medal" else "medals"}" },
    result.localLegend.takeIf { it > 0 }?.let { "Local legend on $it" },
).takeIf { it.isNotEmpty() }?.joinToString(" · ")

private fun formatTrailTime(seconds: Int): String =
    String.format(java.util.Locale.US, "%d:%02d", seconds / 60, seconds % 60)
