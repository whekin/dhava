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
import com.nakvali.core.ui.ProfileDomain
import com.nakvali.core.ui.ProfileTrimmer
import com.nakvali.core.ui.SelectionHandle
import com.nakvali.core.ui.clampDomain
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Trims the walk to the trailhead off the front and the standing around off the
 * back.
 *
 * The instrument is the ride's own elevation profile with the two boundaries
 * living on it — the same one the segment editor trims with, promoted to
 * `:core:ui` so both features share it. It replaced a range slider here for the
 * reason it replaced one there: a slider shows the rider a bare axis, while
 * what they are actually looking for is a shape — the climb before the run, the
 * flat roll-out after it. The elapsed clocks stay below the chart as the exact,
 * screen-reader-operable input.
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
    val lastPosition = state.profile.lastPosition
    var domain by remember(state.profile) {
        mutableStateOf(ProfileDomain(0.0, lastPosition.coerceAtLeast(1.0)))
    }
    // The sheet and the chart both want a drag. While a boundary is held the
    // sheet stands down, so pulling a gate upward moves the gate and not the
    // whole editor.
    var activeHandle by remember { mutableStateOf<SelectionHandle?>(null) }
    val startPosition = state.track.positionAt(bounds?.startedAtMs ?: state.originMs)
    val endPosition = state.track.positionAt(bounds?.endedAtMs ?: state.endedAtMs)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
        sheetGesturesEnabled = activeHandle == null,
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.92f)) {
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
                if (state.profile.drawable) {
                    ProfileTrimmer(
                        profile = state.profile,
                        candidates = emptyList(),
                        startPosition = startPosition,
                        endPosition = endPosition,
                        domain = domain,
                        onSelectionChange = { start, end ->
                            if (state.busy) return@ProfileTrimmer
                            onDraftChanged(
                                state.draft.copy(
                                    start = elapsedClock(
                                        state.track.timestampAt(start) - state.originMs,
                                    ),
                                    finish = elapsedClock(
                                        state.track.timestampAt(end) - state.originMs,
                                    ),
                                ),
                            )
                        },
                        onDomainChange = { domain = clampDomain(it, lastPosition) },
                        onCandidatePicked = {},
                        onActiveHandleChange = { activeHandle = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Drag either boundary along the profile · pinch to zoom in",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
