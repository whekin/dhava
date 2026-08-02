package com.nakvali.feature.segments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import android.app.Application
import com.nakvali.core.map.SegmentMap
import com.nakvali.core.map.SegmentMapPoint
import com.nakvali.core.recording.StoredAttempt
import com.nakvali.core.recording.StoredSegment
import com.nakvali.core.recording.SegmentSourceKind
import com.nakvali.core.recording.countable
import com.nakvali.core.ui.NakvaliDivider
import com.nakvali.core.ui.NakvaliMetric
import com.nakvali.core.ui.NakvaliPanel
import com.nakvali.core.ui.NakvaliSectionLabel
import com.nakvali.core.ui.NakvaliSpacing
import com.nakvali.core.ui.NakvaliTextField
import com.nakvali.core.ui.NakvaliStatusPill
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val AttemptDateFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")

/** One segment: its geometry, every countable run and everything not counted. */
@Composable
fun SegmentDetailScreen(
    segmentId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SegmentDetailViewModel = viewModel(
        key = "segment-detail-$segmentId",
        factory = segmentDetailViewModelFactory(segmentId),
    ),
) {
    val state by viewModel.state.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }

    LaunchedEffect(state) {
        if (state is SegmentDetailState.Gone) onBack()
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NakvaliSpacing.small, vertical = NakvaliSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = (state as? SegmentDetailState.Ready)?.segment?.name ?: "Segment",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            SegmentOverflowMenu(
                enabled = state is SegmentDetailState.Ready,
                onRename = { renaming = true },
                onDelete = { confirmDelete = true },
            )
        }

        when (val current = state) {
            SegmentDetailState.Loading, SegmentDetailState.Gone -> Box(
                // weight() rather than fillMaxSize(): a full-height child inside
                // this column is measured against the whole screen, so it
                // overflows past the bottom and pushes its centre off-centre.
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(NakvaliSpacing.large))
                    Text(
                        text = "Matching your rides…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            is SegmentDetailState.Ready -> SegmentDetailBody(current)
        }
    }

    val ready = state as? SegmentDetailState.Ready
    if (renaming && ready != null) {
        RenameSegmentDialog(
            initialName = ready.segment.name,
            onConfirm = { name ->
                renaming = false
                viewModel.rename(name)
            },
            onDismiss = { renaming = false },
        )
    }
    if (confirmDelete && ready != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete segment?") },
            text = {
                Text(
                    "“${ready.segment.name}” and its results will be removed. Your rides and " +
                        "their raw recordings are not touched.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        viewModel.delete()
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SegmentDetailBody(state: SegmentDetailState.Ready) {
    val segment = state.segment
    val centerline = remember(segment) {
        segment.centerline.map { SegmentMapPoint(it.lat, it.lon) }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = NakvaliSpacing.xxLarge),
    ) {
        item {
            SegmentMap(
                sections = emptyList(),
                segment = centerline,
                startGate = (segment.startGateCenter ?: segment.centerline.firstOrNull())
                    ?.let { SegmentMapPoint(it.lat, it.lon) },
                finishGate = (segment.finishGateCenter ?: segment.centerline.lastOrNull())
                    ?.let { SegmentMapPoint(it.lat, it.lon) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp),
            )
        }
        item {
            Column(modifier = Modifier.padding(NakvaliSpacing.screen)) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    NakvaliMetric(
                        value = SegmentFormat.length(segment.lengthM),
                        label = "Length",
                        modifier = Modifier.weight(1f),
                    )
                    NakvaliMetric(
                        value = state.attempts.size.toString(),
                        label = "Runs",
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(NakvaliSpacing.medium))
                Row(modifier = Modifier.fillMaxWidth()) {
                    NakvaliMetric(
                        value = SegmentFormat.descent(segment.descentM) ?: "—",
                        label = "Descent",
                        modifier = Modifier.weight(1f),
                    )
                    NakvaliMetric(
                        value = SegmentFormat.ascent(segment.ascentM) ?: "—",
                        label = "Climb",
                        modifier = Modifier.weight(1f),
                    )
                }
                if (!segment.trusted) {
                    Spacer(Modifier.height(NakvaliSpacing.medium))
                    DraftNotice(segment)
                }
            }
        }
        if (segment.elevationProfile.size >= 2) {
            item {
                SegmentElevationProfile(
                    segment = segment,
                    modifier = Modifier.padding(horizontal = NakvaliSpacing.screen),
                )
            }
        }
        item {
            val record = state.record
            if (record == null) {
                NoRecordPanel(state)
            } else {
                ResultPanel(
                    label = "Personal record",
                    attempt = record,
                    segment = segment,
                    rideTitle = state.attempts
                        .firstOrNull { it.attempt === record }
                        ?.rideTitle
                        .orEmpty(),
                    footnote = state.fastestNotCounted?.let { faster ->
                        "${SegmentFormat.elapsed(faster.elapsedMs)} was faster but does not " +
                            "count, so it cannot be your record."
                    },
                )
            }
        }
        state.latest?.takeIf { it !== state.record }?.let { latest ->
            item {
                ResultPanel(
                    label = "Latest",
                    attempt = latest,
                    segment = segment,
                    rideTitle = state.attempts
                        .firstOrNull { it.attempt === latest }
                        ?.rideTitle
                        .orEmpty(),
                )
            }
        }
        if (state.attempts.isNotEmpty()) {
            item {
                NakvaliSectionLabel(
                    text = "All runs",
                    modifier = Modifier.padding(
                        start = NakvaliSpacing.screen,
                        end = NakvaliSpacing.screen,
                        top = NakvaliSpacing.large,
                        bottom = NakvaliSpacing.small,
                    ),
                )
            }
            items(state.attempts, key = { it.attempt.startedAtMs }) { row ->
                AttemptListRow(row)
            }
        }
        if (state.notTimed.isNotEmpty()) {
            item {
                NakvaliSectionLabel(
                    text = "Not timed",
                    modifier = Modifier.padding(
                        start = NakvaliSpacing.screen,
                        end = NakvaliSpacing.screen,
                        top = NakvaliSpacing.large,
                        bottom = NakvaliSpacing.small,
                    ),
                )
            }
            items(state.notTimed, key = { it.startedAtMs }) { row ->
                RejectionListRow(row)
            }
        }
        if (state.attempts.isEmpty() && state.notTimed.isEmpty()) {
            item {
                Text(
                    text = "No ride has crossed both gates of this segment yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = NakvaliSpacing.screen),
                )
            }
        }
    }
}

@Composable
private fun DraftNotice(segment: StoredSegment) {
    NakvaliPanel(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Column(modifier = Modifier.padding(NakvaliSpacing.large)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NakvaliStatusPill(text = "Draft")
                Spacer(Modifier.height(NakvaliSpacing.small))
            }
            Spacer(Modifier.height(NakvaliSpacing.small))
            Text(
                text = (if (segment.sourceKind == SegmentSourceKind.IMPORTED_GPX) {
                    "The geometry is an imported GPX seed, not a recorded attempt. It can " +
                        "be refined by later Nakvali rides, while its authored gates stay fixed. "
                } else {
                    "The geometry comes from a single ride, so it is not treated as ground " +
                        "truth and does not correct GPS. "
                }) +
                    "Runs are matched inside a " +
                    "${segment.corridorM.toInt()} m corridor with " +
                    "${(segment.gateHalfWidthM * 2).toInt()} m wide gates.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A segment with runs but no record.
 *
 * Falling back to the fastest uncertain run would read as a record the rider
 * can never honestly beat, so the panel says what is missing instead.
 */
@Composable
private fun NoRecordPanel(state: SegmentDetailState.Ready) {
    NakvaliPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NakvaliSpacing.screen, vertical = NakvaliSpacing.small),
    ) {
        Column(modifier = Modifier.padding(NakvaliSpacing.large)) {
            NakvaliSectionLabel("Personal record")
            Spacer(Modifier.height(NakvaliSpacing.small))
            Text(
                text = "—",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                text = when (state.attempts.size) {
                    0 -> "No run has been timed on this segment yet."
                    1 -> "The one timed run does not count yet — the row below says why."
                    else -> "None of the ${state.attempts.size} timed runs counts yet — each " +
                        "row below says why."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ResultPanel(
    label: String,
    attempt: StoredAttempt,
    segment: StoredSegment,
    rideTitle: String,
    footnote: String? = null,
) {
    NakvaliPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NakvaliSpacing.screen, vertical = NakvaliSpacing.small),
    ) {
        Column(modifier = Modifier.padding(NakvaliSpacing.large)) {
            NakvaliSectionLabel(label)
            Spacer(Modifier.height(NakvaliSpacing.small))
            // Time and margin are stacked rather than joined on one line: at
            // 360 dp a joined string wraps and orphans the unit.
            Text(
                text = SegmentFormat.elapsed(attempt.elapsedMs),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                text = SegmentFormat.uncertainty(attempt.uncertaintyMs),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = listOfNotNull(
                    rideTitle.takeIf { it.isNotBlank() },
                    formatAttemptTime(attempt.startedAtMs),
                    SegmentFormat.averageSpeed(segment.lengthM, attempt.elapsedMs),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AttemptFlags(attempt)
            if (footnote != null) {
                Spacer(Modifier.height(NakvaliSpacing.small))
                Text(
                    text = footnote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AttemptListRow(row: AttemptRow) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NakvaliSpacing.screen, vertical = NakvaliSpacing.medium),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = SegmentFormat.elapsed(row.attempt.elapsedMs),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = SegmentFormat.uncertainty(row.attempt.uncertaintyMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "${row.rideTitle} · ${formatAttemptTime(row.attempt.startedAtMs)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AttemptFlags(row.attempt)
        NakvaliDivider(Modifier.padding(top = NakvaliSpacing.medium))
    }
}

/**
 * The verdict on a run, then the reasons behind it.
 *
 * A run quicker than the record has to carry its own explanation: without one,
 * a list where the fastest row is not the PR reads as a defect rather than as
 * honest measurement.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun AttemptFlags(attempt: StoredAttempt) {
    if (attempt.countable && attempt.flags.isEmpty()) return
    Spacer(Modifier.height(NakvaliSpacing.small))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.small),
        verticalArrangement = Arrangement.spacedBy(NakvaliSpacing.small),
    ) {
        if (!attempt.countable) {
            NakvaliStatusPill(
                text = "Not counted",
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        attempt.flags.forEach { flag ->
            NakvaliStatusPill(text = flag.label())
        }
    }
}

@Composable
private fun RejectionListRow(row: RejectionRow) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NakvaliSpacing.screen, vertical = NakvaliSpacing.medium),
    ) {
        Text(
            text = row.reason,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = row.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "${row.rideTitle} · ${formatAttemptTime(row.startedAtMs)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        NakvaliDivider(Modifier.padding(top = NakvaliSpacing.medium))
    }
}

@Composable
private fun SegmentOverflowMenu(
    enabled: Boolean,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled) {
            Icon(Icons.Filled.MoreVert, contentDescription = "More actions")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Rename") },
                onClick = {
                    expanded = false
                    onRename()
                },
            )
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                onClick = {
                    expanded = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun RenameSegmentDialog(
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        // Edge-to-edge windows are not resized for the keyboard, so a centred
        // dialog would otherwise sit behind it.
        modifier = Modifier.imePadding(),
        onDismissRequest = onDismiss,
        title = { Text("Rename segment") },
        text = {
            NakvaliTextField(
                value = name,
                onValueChange = { name = it },
                label = "Name",
                keyboardActions = KeyboardActions(
                    onDone = { if (name.isNotBlank()) onConfirm(name) },
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

internal fun formatAttemptTime(timestampMs: Long): String =
    AttemptDateFormat.format(
        Instant.ofEpochMilli(timestampMs).atZone(ZoneId.systemDefault()),
    )

private fun segmentDetailViewModelFactory(segmentId: String): ViewModelProvider.Factory =
    viewModelFactory {
        initializer {
            val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                ?: error("APPLICATION_KEY missing from ViewModel CreationExtras")
            SegmentDetailViewModel(application as Application, segmentId)
        }
    }
