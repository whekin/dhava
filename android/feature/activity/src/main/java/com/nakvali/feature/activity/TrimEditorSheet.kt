package com.nakvali.feature.activity

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.nakvali.core.ui.NakvaliSizes
import com.nakvali.core.ui.NakvaliSpacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Trims the walk to the trailhead off the front and the standing around off the
 * back. Deliberately the same instrument as the transport editor: two elapsed
 * clocks are the authoritative, screen-reader-operable input and the slider is
 * the fast path, so the two features are one thing to learn.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrimEditorSheet(
    state: TrimEditorState,
    onDraftChanged: (TrimDraft) -> Unit,
    onReset: () -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    val bounds = runCatching { state.draft.bounds(state.originMs) }.getOrNull()
    val kept = remember(state.track, bounds) {
        if (bounds == null) emptyList() else state.track
            .filter { it.timestampMs in bounds.startedAtMs..bounds.endedAtMs }
            .map { MapTrackPoint(it.lat, it.lon, it.sectionId, timestampMs = it.timestampMs) }
    }
    val dropped = remember(state.track, bounds) {
        if (bounds == null) emptyList() else state.track
            .filter { it.timestampMs !in bounds.startedAtMs..bounds.endedAtMs }
            .map { MapTrackPoint(it.lat, it.lon, it.sectionId, timestampMs = it.timestampMs) }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.9f)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = NakvaliSpacing.xLarge),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Trim start and finish", Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineSmall)
                IconButton(onClick = onDismiss, enabled = !state.busy) {
                    Icon(Icons.Default.Close, "Close trim editor")
                }
            }
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                    .padding(horizontal = NakvaliSpacing.xLarge),
                verticalArrangement = Arrangement.spacedBy(NakvaliSpacing.medium),
            ) {
                Text(
                    if (state.useWholeActivity) "The whole recording counts as the ride."
                    else "Only the kept span counts · changes are saved with Apply",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (kept.isNotEmpty()) TrackMap(
                    rawPoints = dropped, fusedPoints = kept, mode = TrackMode.Compare,
                    rawColor = MaterialTheme.colorScheme.outlineVariant,
                    fusedColor = MaterialTheme.colorScheme.primary,
                    overlayBottomPadding = 0.dp,
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                )
                Text("Boundaries are elapsed time (HH:MM:SS) from ${wallClock(state.originMs)}.",
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.medium)) {
                    OutlinedTextField(
                        value = state.draft.start,
                        onValueChange = { onDraftChanged(state.draft.copy(start = it)) },
                        label = { Text("Ride starts") }, singleLine = true,
                        enabled = !state.busy, modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = state.draft.finish,
                        onValueChange = { onDraftChanged(state.draft.copy(finish = it)) },
                        label = { Text("Ride ends") }, singleLine = true,
                        enabled = !state.busy, modifier = Modifier.weight(1f),
                    )
                }
                val duration = ((state.endedAtMs - state.originMs) / 1000).coerceAtLeast(1).toFloat()
                if (bounds != null && bounds.startedAtMs < bounds.endedAtMs) RangeSlider(
                    value = ((bounds.startedAtMs - state.originMs) / 1000f).coerceIn(0f, duration)..
                        ((bounds.endedAtMs - state.originMs) / 1000f).coerceIn(0f, duration),
                    valueRange = 0f..duration, enabled = !state.busy,
                    onValueChange = {
                        onDraftChanged(state.draft.copy(
                            start = elapsedClock(it.start.roundToLong() * 1000),
                            finish = elapsedClock(it.endInclusive.roundToLong() * 1000),
                        ))
                    },
                )
                if (bounds != null && !state.useWholeActivity) Text(
                    trimmedAway(state.originMs, state.endedAtMs, bounds.startedAtMs, bounds.endedAtMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onReset, enabled = !state.busy) { Text("Use whole activity") }
                Text(
                    "Original sensor data is kept, and segment times still count. " +
                        "Already uploaded activities are unchanged.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(
                Modifier.fillMaxWidth().padding(NakvaliSpacing.xLarge),
                verticalArrangement = Arrangement.spacedBy(NakvaliSpacing.small),
            ) {
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onClick = onApply, enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = NakvaliSizes.primaryActionHeight),
                ) { Text(if (state.busy) "Updating activity…" else "Apply to activity") }
            }
        }
    }
}

/** What the trim leaves out, because excluded time is stated and never hidden. */
private fun trimmedAway(originMs: Long, endedAtMs: Long, startsAtMs: Long, finishesAtMs: Long): String {
    val head = (startsAtMs - originMs).coerceAtLeast(0)
    val tail = (endedAtMs - finishesAtMs).coerceAtLeast(0)
    return String.format(
        Locale.US, "Leaves out %s at the start and %s at the finish.",
        elapsedClock(head), elapsedClock(tail),
    )
}

private fun wallClock(timestampMs: Long): String = DateTimeFormatter.ofPattern("HH:mm:ss")
    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(timestampMs))
