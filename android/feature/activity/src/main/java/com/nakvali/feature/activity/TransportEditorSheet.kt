package com.nakvali.feature.activity

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.nakvali.core.recording.StoredTransportEpisode
import com.nakvali.core.ui.NakvaliSizes
import com.nakvali.core.ui.NakvaliSpacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TransportEditorSheet(
    state: TransportEditorState,
    onDraftsChanged: (List<TransportDraft>) -> Unit,
    onReset: () -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(0) }
    val index = selected.coerceIn(0, (state.drafts.size - 1).coerceAtLeast(0))
    val draft = state.drafts.getOrNull(index)
    val interval = draft?.let { runCatching { it.interval(state.originMs) }.getOrNull() }
    val preview = remember(state.track, interval) {
        if (interval == null) emptyList() else state.track
            .filter { it.timestampMs in interval.startedAtMs..interval.endedAtMs }
            .map { MapTrackPoint(it.lat, it.lon, it.sectionId, timestampMs = it.timestampMs, isTransportPreview = true) }
    }
    fun update(value: TransportDraft) {
        onDraftsChanged(state.drafts.mapIndexed { i, original -> if (i == index) value else original })
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.9f)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = NakvaliSpacing.xLarge), verticalAlignment = Alignment.CenterVertically) {
                Text("Transport episodes", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                IconButton(onClick = onDismiss, enabled = !state.busy) { Icon(Icons.Default.Close, "Close transport editor") }
            }
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = NakvaliSpacing.xLarge),
                verticalArrangement = Arrangement.spacedBy(NakvaliSpacing.medium),
            ) {
                Text(if (state.useAutomatic) "Automatic estimate · check boarding and unloading"
                    else "Manual boundaries · changes are saved with Apply",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.drafts.isEmpty()) Text("No transport episodes selected.")
                state.drafts.forEachIndexed { i, item ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).selectable(selected = i == index, enabled = !state.busy,
                            role = Role.RadioButton, onClick = { selected = i }), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = i == index, onClick = null)
                            val range = runCatching { item.interval(state.originMs) }.getOrNull()
                            Text("Transport ${i + 1} · ${range?.let { wallClock(it.startedAtMs) + "–" + wallClock(it.endedAtMs) } ?: "Edit times"}",
                                modifier = Modifier.padding(start = NakvaliSpacing.small), style = MaterialTheme.typography.bodyMedium)
                        }
                        IconButton(onClick = { onDraftsChanged(state.drafts.filterIndexed { j, _ -> j != i }) }, enabled = !state.busy) {
                            Icon(Icons.Outlined.Delete, "Remove transport ${i + 1}")
                        }
                    }
                }
                if (draft != null) {
                    if (preview.isNotEmpty()) TrackMap(
                        rawPoints = emptyList(), fusedPoints = preview, mode = TrackMode.Fusion,
                        rawColor = MaterialTheme.colorScheme.outline, fusedColor = MaterialTheme.colorScheme.primary,
                        overlayBottomPadding = 0.dp, modifier = Modifier.fillMaxWidth().height(200.dp),
                    )
                    Text("Boundaries are elapsed time (HH:MM:SS) from ${wallClock(state.originMs)}.",
                        style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.medium)) {
                        OutlinedTextField(value = draft.start, onValueChange = { update(draft.copy(start = it)) },
                            label = { Text("Start") }, singleLine = true, enabled = !state.busy, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = draft.finish, onValueChange = { update(draft.copy(finish = it)) },
                            label = { Text("Finish") }, singleLine = true, enabled = !state.busy, modifier = Modifier.weight(1f))
                    }
                    val duration = ((state.endedAtMs - state.originMs) / 1000).coerceAtLeast(1).toFloat()
                    if (interval != null && interval.startedAtMs < interval.endedAtMs) RangeSlider(
                        value = ((interval.startedAtMs - state.originMs) / 1000f).coerceIn(0f, duration)..
                            ((interval.endedAtMs - state.originMs) / 1000f).coerceIn(0f, duration),
                        valueRange = 0f..duration, enabled = !state.busy,
                        onValueChange = { update(draft.copy(start = elapsedClock(it.start.roundToLong() * 1000), finish = elapsedClock(it.endInclusive.roundToLong() * 1000))) },
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = {
                        val start = state.drafts.mapNotNull { runCatching { it.interval(state.originMs).endedAtMs }.getOrNull() }.maxOrNull()
                            ?.plus(1000)?.takeIf { it < state.endedAtMs } ?: state.originMs
                        val added = StoredTransportEpisode(start, (start + 300_000).coerceAtMost(state.endedAtMs))
                        selected = state.drafts.size
                        onDraftsChanged(state.drafts + added.draft(state.originMs))
                    }, enabled = !state.busy) { Text("Add transport") }
                    TextButton(onClick = { selected = 0; onReset() }, enabled = !state.busy) { Text("Use automatic") }
                }
                Text("Original sensor data is kept. Already uploaded activities are unchanged.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.fillMaxWidth().padding(NakvaliSpacing.xLarge), verticalArrangement = Arrangement.spacedBy(NakvaliSpacing.small)) {
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Button(onClick = onApply, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = NakvaliSizes.primaryActionHeight)) {
                    Text(if (state.busy) "Updating activity…" else "Apply to activity")
                }
            }
        }
    }
}

private fun wallClock(timestampMs: Long): String = DateTimeFormatter.ofPattern("HH:mm:ss")
    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(timestampMs))
