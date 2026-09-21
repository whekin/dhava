package com.nakvali.feature.activity

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nakvali.core.recording.CanonicalRideTotals
import com.nakvali.core.recording.LocalRecording
import com.nakvali.core.recording.StravaConnectionState
import com.nakvali.core.recording.StravaExportStatus
import com.nakvali.fusion.RideRun
import com.nakvali.core.ui.NakvaliSizes
import com.nakvali.core.ui.NakvaliSpacing
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ActivityExportButton(
    runs: List<RideRun>,
    rawGpsAvailable: Boolean,
    processedAvailable: Boolean,
    processedLoading: Boolean,
    rawRecordingAvailable: Boolean,
    healthLogAvailable: Boolean,
    stravaConnection: StravaConnectionState,
    recording: LocalRecording?,
    ride: CanonicalRideTotals?,
    exportState: ActivityExportState,
    initiallyExpanded: Boolean = false,
    onExport: (ActivityExportKind, ExportDestination, ActivityExportScope) -> Unit,
    onConnectStrava: () -> Unit,
    onExportStrava: () -> Unit,
    onRetryStrava: () -> Unit,
    onViewStrava: (Long) -> Unit,
) {
    var expanded by rememberSaveable(recording?.id) { mutableStateOf(initiallyExpanded) }
    IconButton(
        onClick = { expanded = true },
        enabled = processedAvailable || processedLoading || rawGpsAvailable || rawRecordingAvailable || healthLogAvailable,
    ) { Icon(Icons.Filled.Share, contentDescription = "Export") }
    // Once the file has been handed to the system picker or the share chooser,
    // this sheet has done its job: the rider's attention is in the other app,
    // and coming back to a sheet still asking what to export is asking a
    // question that was already answered. The result arrives as a toast.
    LaunchedEffect(exportState.prepared) {
        if (exportState.prepared != null) expanded = false
    }
    if (!expanded) return
    ModalBottomSheet(
        onDismissRequest = { expanded = false },
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        ActivityExportPanel(
            runs, rawGpsAvailable, processedAvailable, processedLoading, rawRecordingAvailable,
            healthLogAvailable, stravaConnection, recording, ride, exportState,
            onClose = { expanded = false }, onExport = onExport,
            onConnectStrava = onConnectStrava, onExportStrava = onExportStrava,
            onRetryStrava = onRetryStrava, onViewStrava = onViewStrava,
        )
    }
}

@Composable
private fun ActivityExportPanel(
    runs: List<RideRun>,
    rawGpsAvailable: Boolean,
    processedAvailable: Boolean,
    processedLoading: Boolean,
    rawRecordingAvailable: Boolean,
    healthLogAvailable: Boolean,
    stravaConnection: StravaConnectionState,
    recording: LocalRecording?,
    ride: CanonicalRideTotals?,
    exportState: ActivityExportState,
    onClose: () -> Unit,
    onExport: (ActivityExportKind, ExportDestination, ActivityExportScope) -> Unit,
    onConnectStrava: () -> Unit,
    onExportStrava: () -> Unit,
    onRetryStrava: () -> Unit,
    onViewStrava: (Long) -> Unit,
) {
    var kind by rememberSaveable(recording?.id) { mutableStateOf(ActivityExportKind.RIDING_ONLY_TCX) }
    // Held by start time, not ordinal: a transport edit or a trim renumbers runs.
    var chosenRunStartMs by rememberSaveable(recording?.id) { mutableStateOf<Long?>(null) }
    val scope: ActivityExportScope = chosenRunStartMs
        ?.takeIf { start -> runs.any { it.startedAtMs == start } }
        ?.let { ActivityExportScope.OneRun(it) }
        ?: ActivityExportScope.WholeActivity
    var diagnosticsExpanded by rememberSaveable { mutableStateOf(false) }
    val isTrack = kind.isProcessedTrack
    val ready = when {
        kind.isProcessedTrack -> processedAvailable
        kind == ActivityExportKind.RAW_GPS -> rawGpsAvailable
        kind == ActivityExportKind.RAW_RECORDING -> rawRecordingAvailable
        else -> healthLogAvailable
    }
    val busy = exportState.busy || exportState.prepared != null
    val transportSource = if (recording?.transportEpisodes != null) "marked" else "detected"
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.85f
    Column(Modifier.fillMaxWidth().heightIn(max = maxHeight)) {
        Row(
            Modifier.fillMaxWidth().padding(start = NakvaliSpacing.xLarge, end = NakvaliSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Export activity", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close export") }
        }
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                .padding(horizontal = NakvaliSpacing.xLarge).selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(NakvaliSpacing.small),
        ) {
            recording?.title?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FileChoice(
                title = "TCX track",
                description = "States riding distance and one lap per run",
                selected = isTrack && kind.isTcx,
                enabled = !busy && (processedAvailable || processedLoading),
                onClick = { kind = ActivityExportKind.processedTrack(tcx = true, excludeTransport = kind.excludesTransport) },
            )
            FileChoice(
                title = "GPX track",
                description = "Widest support · readers add the distance across removed transport",
                selected = isTrack && !kind.isTcx,
                enabled = !busy && (processedAvailable || processedLoading),
                onClick = { kind = ActivityExportKind.processedTrack(tcx = false, excludeTransport = kind.excludesTransport) },
            )
            if (isTrack && runs.size > 1) {
                HorizontalDivider(Modifier.padding(vertical = NakvaliSpacing.small))
                FileChoice(
                    title = "Whole activity",
                    description = "Every run of the day in one file",
                    selected = scope is ActivityExportScope.WholeActivity,
                    enabled = !busy && processedAvailable,
                    onClick = { chosenRunStartMs = null },
                )
                runs.forEach { run ->
                    RunChoice(
                        run = run,
                        selected = (scope as? ActivityExportScope.OneRun)?.startedAtMs == run.startedAtMs,
                        enabled = !busy && processedAvailable,
                        onClick = { chosenRunStartMs = run.startedAtMs },
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = NakvaliSpacing.small))
            }
            if (isTrack) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = NakvaliSpacing.medium)) {
                        Text("Exclude transport", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (ride == null) "Remove transport sections" else if (ride.transportDistanceM > 0) {
                                String.format(Locale.US, "%.1f km · %d min of $transportSource transport",
                                    ride.transportDistanceM / 1000, (ride.transportTimeS / 60).toInt())
                            } else "No transport selected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = kind.excludesTransport,
                        onCheckedChange = { kind = ActivityExportKind.processedTrack(tcx = kind.isTcx, excludeTransport = it) },
                        enabled = !busy && processedAvailable,
                        modifier = Modifier.semantics { contentDescription = "Exclude transport" },
                    )
                }
                Text(
                    when {
                        processedLoading -> "Preparing track… You can still export original files below."
                        !processedAvailable -> "Processed track unavailable. Original files are available below."
                        !kind.excludesTransport -> "Includes the full recorded day. Your original recording is kept."
                        ride != null && kind.isTcx ->
                            String.format(Locale.US, "%.1f km of riding · the file states this distance", ride.distanceM / 1000)
                        ride != null ->
                            String.format(Locale.US, "%.1f km of riding · readers may report more", ride.distanceM / 1000)
                        else -> "Original recording kept. Times and recording gaps are preserved."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(Modifier.padding(vertical = NakvaliSpacing.small))
            BikeyardExportAction(recording, processedAvailable && !busy)
            HorizontalDivider(Modifier.padding(vertical = NakvaliSpacing.small))
            TextButton(
                onClick = { diagnosticsExpanded = !diagnosticsExpanded },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = NakvaliSpacing.small),
            ) {
                Text("Original files & diagnostics", Modifier.weight(1f))
                Icon(if (diagnosticsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (diagnosticsExpanded) "Collapse diagnostics" else "Expand diagnostics")
            }
            if (diagnosticsExpanded) {
                FileChoice("Original GPS", "GPX · recorded fixes, including transport",
                    kind == ActivityExportKind.RAW_GPS, !busy && rawGpsAvailable) { kind = ActivityExportKind.RAW_GPS }
                FileChoice("Sensor recording", "GPS, motion and pressure · .jsonl.gz",
                    kind == ActivityExportKind.RAW_RECORDING, !busy && rawRecordingAvailable) { kind = ActivityExportKind.RAW_RECORDING }
                FileChoice("Recording health", if (healthLogAvailable) "Logs for investigating recording problems · .jsonl" else "No health log for this activity",
                    kind == ActivityExportKind.HEALTH_LOG, !busy && healthLogAvailable) { kind = ActivityExportKind.HEALTH_LOG }
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = NakvaliSpacing.xLarge, vertical = NakvaliSpacing.large),
            verticalArrangement = Arrangement.spacedBy(NakvaliSpacing.small),
        ) {
            val feedback = exportState.error ?: exportState.message
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (feedback != null) Text(
                feedback,
                style = MaterialTheme.typography.bodySmall,
                color = if (exportState.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (!isTrack) Text(
                when (kind) {
                    ActivityExportKind.RAW_GPS -> "Selected: original GPS (.gpx)"
                    ActivityExportKind.RAW_RECORDING -> "Selected: sensor recording (.jsonl.gz)"
                    else -> "Selected: recording health (.jsonl)"
                }, style = MaterialTheme.typography.labelMedium,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.medium)) {
                Button(
                    onClick = { onExport(kind, ExportDestination.SAVE, scope) }, enabled = ready && !busy,
                    modifier = Modifier.weight(1f).heightIn(min = NakvaliSizes.primaryActionHeight),
                ) {
                    Icon(Icons.Outlined.SaveAlt, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(NakvaliSpacing.small))
                    Text("Save file")
                }
                OutlinedButton(
                    onClick = { onExport(kind, ExportDestination.SHARE, scope) }, enabled = ready && !busy,
                    modifier = Modifier.weight(1f).heightIn(min = NakvaliSizes.primaryActionHeight),
                ) {
                    Icon(Icons.Default.Share, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(NakvaliSpacing.small))
                    Text("Share")
                }
            }
        }
    }
}

/**
 * One riding run, offered as an export scope. A panel row rather than a
 * [FileChoice], because three lines of stats beside a radio button already clip
 * at large font scales.
 */
@Composable
private fun RunChoice(run: RideRun, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val label = "Run ${run.index + 1u}"
    val stats = String.format(
        Locale.US, "%.1f km · %d m down · %s",
        run.distanceM / 1000, run.descentM.toInt(), clock(run.movingTimeS.toLong()),
    )
    Row(
        Modifier.fillMaxWidth().heightIn(min = NakvaliSizes.secondaryControl)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = "$label, $stats" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.padding(start = NakvaliSpacing.medium, top = NakvaliSpacing.small, bottom = NakvaliSpacing.small)) {
            Text(label, style = MaterialTheme.typography.titleSmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stats, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun clock(totalSeconds: Long): String = String.format(
    Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60,
)

@Composable
private fun FileChoice(title: String, description: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = NakvaliSizes.secondaryControl)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.padding(start = NakvaliSpacing.medium, top = NakvaliSpacing.small, bottom = NakvaliSpacing.small)) {
            Text(title, style = MaterialTheme.typography.titleSmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StravaAction(
    connection: StravaConnectionState, recording: LocalRecording?, available: Boolean,
    onConnect: () -> Unit, onExport: () -> Unit, onRetry: () -> Unit, onView: (Long) -> Unit,
) {
    val status = recording?.stravaExportStatus
    val id = recording?.stravaActivityId
    val label: String
    val description: String
    val enabled: Boolean
    val action: () -> Unit
    when {
        status == StravaExportStatus.UPLOADED && id != null -> {
            label = "View on Strava"
            description = "Activity already uploaded"
            enabled = true
            action = { onView(id) }
        }
        status == StravaExportStatus.QUEUED -> {
            label = "Strava upload queued"
            description = "Will send when a network is available"
            enabled = false
            action = {}
        }
        status == StravaExportStatus.PROCESSING -> {
            label = "Sending to Strava…"
            description = "The upload is being processed"
            enabled = false
            action = {}
        }
        connection is StravaConnectionState.Connected -> {
            label = if (status == StravaExportStatus.FAILED) "Retry Strava upload" else "Send to Strava"
            description = recording?.stravaError
                ?: "Whole activity, transport excluded · a single run is file-only"
            enabled = available && recording != null
            action = if (status == StravaExportStatus.FAILED) onRetry else onExport
        }
        connection == StravaConnectionState.Loading || connection == StravaConnectionState.Connecting -> {
            label = "Connecting to Strava…"
            description = "You can save or share a file below"
            enabled = false
            action = {}
        }
        else -> {
            label = "Connect Strava"
            description = "Optional · save and share work without an account"
            enabled = true
            action = onConnect
        }
    }
    // A multi-line action is a row, not a capsule-shaped text button: the
    // button's shape would clip its first/last line at large font scales.
    Column(
        Modifier.fillMaxWidth().heightIn(min = NakvaliSizes.secondaryControl)
            .clickable(enabled = enabled, role = Role.Button, onClick = action)
            .padding(vertical = NakvaliSpacing.small),
        verticalArrangement = Arrangement.spacedBy(NakvaliSpacing.xSmall),
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall,
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
