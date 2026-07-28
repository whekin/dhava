package com.dhava.feature.segments

import android.widget.Toast
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.ZoomOutMap
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dhava.core.map.SegmentMap
import com.dhava.core.map.SegmentMapPoint
import com.dhava.core.recording.CanonicalPoint
import com.dhava.core.ui.DhavaMetric
import com.dhava.core.ui.DhavaPanel
import com.dhava.core.ui.DhavaSectionLabel
import com.dhava.core.ui.DhavaSizes
import com.dhava.core.ui.DhavaSpacing
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Picks a segment's start and finish along one ride's finalized track.
 *
 * The two handles move along recorded track indexes rather than free map
 * points, and every number below the map is Rust's own judgement of the
 * current selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SegmentEditorScreen(
    recordingId: String,
    onBack: () -> Unit,
    onCreated: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SegmentEditorViewModel = viewModel(
        key = "segment-editor-$recordingId",
        factory = SegmentEditorViewModel.factory(recordingId),
    ),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    when (val current = state) {
        SegmentEditorState.Loading -> Column(modifier = modifier.fillMaxSize()) {
            SegmentEditorHeader(onBack)
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        is SegmentEditorState.Unavailable -> Column(modifier = modifier.fillMaxSize()) {
            SegmentEditorHeader(onBack)
            Text(
                text = current.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(DhavaSpacing.screen),
            )
        }

        is SegmentEditorState.Editing -> EditorBody(
            state = current,
            onBack = onBack,
            onSelectionChange = viewModel::setSelection,
            onNudgeSelection = viewModel::nudgeSelection,
            onNameChange = viewModel::setName,
            onSave = {
                viewModel.save(
                    onSaved = onCreated,
                    onError = { message ->
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    },
                )
            },
            modifier = modifier,
        )
    }
}

@Composable
private fun SegmentEditorHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DhavaSpacing.small, vertical = DhavaSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Text(
            text = "Create segment",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorBody(
    state: SegmentEditorState.Editing,
    onBack: () -> Unit,
    onSelectionChange: (Int, Int) -> Unit,
    onNudgeSelection: (SelectionHandle, Int) -> Unit,
    onNameChange: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var activeHandle by rememberSaveable { mutableStateOf(SelectionHandle.START) }
    var sliderWindowStart by rememberSaveable(state.track.size) { mutableStateOf(0) }
    var sliderWindowEnd by rememberSaveable(state.track.size) {
        mutableStateOf(state.track.lastIndex)
    }
    var focusAfterDrag by remember { mutableStateOf(false) }
    val sections = remember(state.track) { state.track.toMapSections() }
    val selection = remember(state.track, state.startIndex, state.endIndex) {
        state.track
            .subList(
                state.startIndex.coerceAtMost(state.endIndex),
                state.endIndex.coerceAtLeast(state.startIndex) + 1,
            )
            .map { SegmentMapPoint(it.lat, it.lon) }
    }
    val valid = state.preview as? SelectionPreview.Valid
    val fullSliderRange = sliderWindowStart == 0 && sliderWindowEnd == state.track.lastIndex
    val sheetState = rememberStandardBottomSheetState(
        initialValue = SheetValue.PartiallyExpanded,
        skipHiddenState = true,
    )
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)

    // Wait until the rider has stopped dragging, then spend the available
    // slider width on the selected interval. Re-keying on the selection makes
    // the delay restart while a handle is still moving.
    LaunchedEffect(focusAfterDrag, state.startIndex, state.endIndex) {
        if (!focusAfterDrag) return@LaunchedEffect
        delay(SLIDER_FOCUS_DELAY_MS)
        val focused = focusedSliderWindow(
            startIndex = state.startIndex,
            endIndex = state.endIndex,
            lastIndex = state.track.lastIndex,
        )
        sliderWindowStart = focused.first
        sliderWindowEnd = focused.last
        focusAfterDrag = false
    }

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        modifier = modifier.fillMaxSize(),
        sheetPeekHeight = SegmentEditorSheetPeekHeight,
        sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        sheetContainerColor = MaterialTheme.colorScheme.surface,
        sheetContentColor = MaterialTheme.colorScheme.onSurface,
        sheetTonalElevation = 0.dp,
        sheetShadowElevation = 8.dp,
        sheetDragHandle = {
            BottomSheetDefaults.DragHandle(color = MaterialTheme.colorScheme.outline)
        },
        sheetContent = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(),
            ) {
                SegmentSelectionSlider(
                    state = state,
                    fullSliderRange = fullSliderRange,
                    sliderWindowStart = sliderWindowStart,
                    sliderWindowEnd = sliderWindowEnd,
                    onShowFullRide = {
                        focusAfterDrag = false
                        sliderWindowStart = 0
                        sliderWindowEnd = state.track.lastIndex
                    },
                    onHandlePressed = { handle ->
                        focusAfterDrag = false
                        activeHandle = handle
                    },
                    onSelectionChange = { start, end, handle ->
                        activeHandle = handle
                        onSelectionChange(start, end)
                    },
                    onSelectionFinished = { focusAfterDrag = true },
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(
                            start = DhavaSpacing.screen,
                            end = DhavaSpacing.screen,
                            bottom = DhavaSpacing.screen,
                        )
                        .navigationBarsPadding(),
                ) {
                    SegmentEditorDetails(
                        state = state,
                        activeHandle = activeHandle,
                        valid = valid,
                        onActiveHandleChange = { activeHandle = it },
                        onNudgeSelection = onNudgeSelection,
                        onNameChange = onNameChange,
                        onSave = onSave,
                    )
                }
            }
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            SegmentMap(
                sections = sections,
                segment = selection,
                focusOnSegment = false,
                modifier = Modifier.fillMaxSize(),
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(DhavaSpacing.medium),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                shadowElevation = 8.dp,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Text(
                        text = "Create segment",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(end = DhavaSpacing.large),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SegmentSelectionSlider(
    state: SegmentEditorState.Editing,
    fullSliderRange: Boolean,
    sliderWindowStart: Int,
    sliderWindowEnd: Int,
    onShowFullRide: () -> Unit,
    onHandlePressed: (SelectionHandle) -> Unit,
    onSelectionChange: (Int, Int, SelectionHandle) -> Unit,
    onSelectionFinished: () -> Unit,
) {
    val startInteractionSource = remember { MutableInteractionSource() }
    val finishInteractionSource = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current
    var precisionHandle by remember { mutableStateOf<SelectionHandle?>(null) }
    var precisionLastRawValue by remember { mutableStateOf<Float?>(null) }
    var precisionValue by remember { mutableFloatStateOf(0f) }

    PrecisionInteractionEffect(
        interactionSource = startInteractionSource,
        handle = SelectionHandle.START,
        onPressed = onHandlePressed,
        onPrecisionStarted = { handle ->
            precisionHandle = handle
            precisionLastRawValue = null
            precisionValue = state.startIndex.toFloat()
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        },
        onPrecisionEnded = {
            if (precisionHandle == SelectionHandle.START) {
                precisionHandle = null
                precisionLastRawValue = null
            }
        },
    )
    PrecisionInteractionEffect(
        interactionSource = finishInteractionSource,
        handle = SelectionHandle.FINISH,
        onPressed = onHandlePressed,
        onPrecisionStarted = { handle ->
            precisionHandle = handle
            precisionLastRawValue = null
            precisionValue = state.endIndex.toFloat()
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        },
        onPrecisionEnded = {
            if (precisionHandle == SelectionHandle.FINISH) {
                precisionHandle = null
                precisionLastRawValue = null
            }
        },
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DhavaSpacing.screen),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            DhavaSectionLabel(if (fullSliderRange) "Start and finish" else "Precise range")
            if (!fullSliderRange) {
                IconButton(onClick = onShowFullRide) {
                    Icon(
                        imageVector = Icons.Outlined.ZoomOutMap,
                        contentDescription = "Show full ride",
                    )
                }
            }
        }
        RangeSlider(
            value = state.startIndex.toFloat()..state.endIndex.toFloat(),
            onValueChange = { range ->
                val inferredHandle = if (
                    abs(range.start - state.startIndex) >=
                    abs(range.endInclusive - state.endIndex)
                ) {
                    SelectionHandle.START
                } else {
                    SelectionHandle.FINISH
                }
                val handle = precisionHandle ?: inferredHandle
                val precise = precisionHandle == handle
                val proposedValue = when (handle) {
                    SelectionHandle.START -> range.start
                    SelectionHandle.FINISH -> range.endInclusive
                }
                if (precise && precisionLastRawValue == null) {
                    precisionLastRawValue = proposedValue
                    return@RangeSlider
                }
                if (precise) {
                    precisionValue = scaledPrecisionValue(
                        currentValue = precisionValue,
                        proposedValue = proposedValue,
                        previousProposedValue = checkNotNull(precisionLastRawValue),
                    )
                    precisionLastRawValue = proposedValue
                }
                val start = when (handle) {
                    SelectionHandle.START -> (
                        if (precise) precisionValue else range.start
                        ).roundToInt()
                        .coerceIn(sliderWindowStart, state.endIndex - 1)
                    SelectionHandle.FINISH -> state.startIndex
                }
                val end = when (handle) {
                    SelectionHandle.START -> state.endIndex
                    SelectionHandle.FINISH -> (
                        if (precise) precisionValue else range.endInclusive
                        ).roundToInt()
                        .coerceIn(state.startIndex + 1, sliderWindowEnd)
                }
                onSelectionChange(start, end, handle)
            },
            onValueChangeFinished = onSelectionFinished,
            valueRange = sliderWindowStart.toFloat()..sliderWindowEnd.toFloat(),
            startInteractionSource = startInteractionSource,
            endInteractionSource = finishInteractionSource,
            startThumb = {
                PrecisionSliderThumb(
                    interactionSource = startInteractionSource,
                    active = precisionHandle == SelectionHandle.START,
                )
            },
            endThumb = {
                PrecisionSliderThumb(
                    interactionSource = finishInteractionSource,
                    active = precisionHandle == SelectionHandle.FINISH,
                )
            },
        )
        Text(
            text = when {
                precisionHandle != null -> "Precision · 5× slower"
                fullSliderRange -> "Hold a handle for precision"
                else -> "Focused · hold a handle for precision"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (precisionHandle != null) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(Modifier.height(DhavaSpacing.small))
    }
}

@Composable
private fun PrecisionSliderThumb(
    interactionSource: MutableInteractionSource,
    active: Boolean,
) {
    Box(
        modifier = Modifier.size(40.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (active) {
            Surface(
                modifier = Modifier.size(34.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                content = {},
            )
        }
        SliderDefaults.Thumb(
            interactionSource = interactionSource,
            thumbSize = DpSize(
                width = if (active) 10.dp else 6.dp,
                height = 40.dp,
            ),
        )
    }
}

@Composable
private fun PrecisionInteractionEffect(
    interactionSource: MutableInteractionSource,
    handle: SelectionHandle,
    onPressed: (SelectionHandle) -> Unit,
    onPrecisionStarted: (SelectionHandle) -> Unit,
    onPrecisionEnded: (SelectionHandle) -> Unit,
) {
    val currentOnPressed by rememberUpdatedState(onPressed)
    val currentOnPrecisionStarted by rememberUpdatedState(onPrecisionStarted)
    val currentOnPrecisionEnded by rememberUpdatedState(onPrecisionEnded)

    LaunchedEffect(interactionSource, handle) {
        var holdJob: Job? = null
        var cancelCleanupJob: Job? = null
        var precisionStarted = false
        var dragging = false
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    currentOnPressed(handle)
                    holdJob?.cancel()
                    cancelCleanupJob?.cancel()
                    precisionStarted = false
                    dragging = false
                    holdJob = launch {
                        delay(PRECISION_HOLD_DELAY_MS)
                        precisionStarted = true
                        currentOnPrecisionStarted(handle)
                    }
                }

                is DragInteraction.Start -> {
                    dragging = true
                    cancelCleanupJob?.cancel()
                    if (!precisionStarted) holdJob?.cancel()
                }

                is PressInteraction.Release -> {
                    holdJob?.cancel()
                    if (precisionStarted) {
                        currentOnPrecisionEnded(handle)
                        precisionStarted = false
                    }
                    dragging = false
                }

                is PressInteraction.Cancel -> {
                    holdJob?.cancel()
                    if (precisionStarted && !dragging) {
                        cancelCleanupJob?.cancel()
                        cancelCleanupJob = launch {
                            // RangeSlider cancels the press immediately before
                            // emitting Drag.Start. A short grace period keeps
                            // precision armed across that handoff, but clears
                            // it for a vertical gesture or cancellation.
                            delay(PRECISION_DRAG_HANDOFF_MS)
                            if (!dragging && precisionStarted) {
                                currentOnPrecisionEnded(handle)
                                precisionStarted = false
                            }
                        }
                    }
                }

                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    dragging = false
                    cancelCleanupJob?.cancel()
                    holdJob?.cancel()
                    if (precisionStarted) {
                        currentOnPrecisionEnded(handle)
                        precisionStarted = false
                    }
                }
            }
        }
    }
}

@Composable
private fun SegmentEditorDetails(
    state: SegmentEditorState.Editing,
    activeHandle: SelectionHandle,
    valid: SelectionPreview.Valid?,
    onActiveHandleChange: (SelectionHandle) -> Unit,
    onNudgeSelection: (SelectionHandle, Int) -> Unit,
    onNameChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    Column {
        Row(
            horizontalArrangement = Arrangement.spacedBy(DhavaSpacing.small),
        ) {
            FilterChip(
                selected = activeHandle == SelectionHandle.START,
                onClick = { onActiveHandleChange(SelectionHandle.START) },
                label = { Text("Start") },
            )
            FilterChip(
                selected = activeHandle == SelectionHandle.FINISH,
                onClick = { onActiveHandleChange(SelectionHandle.FINISH) },
                label = { Text("Finish") },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { onNudgeSelection(activeHandle, -1) },
                enabled = when (activeHandle) {
                    SelectionHandle.START -> state.startIndex > 0
                    SelectionHandle.FINISH -> state.endIndex > state.startIndex + 1
                },
            ) {
                Icon(
                    imageVector = Icons.Filled.Remove,
                    contentDescription = "Move ${activeHandle.label()} one point earlier",
                )
            }
            Text(
                text = "Fine adjust ${activeHandle.label()} · one 5 Hz point",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { onNudgeSelection(activeHandle, 1) },
                enabled = when (activeHandle) {
                    SelectionHandle.START -> state.startIndex < state.endIndex - 1
                    SelectionHandle.FINISH -> state.endIndex < state.track.lastIndex
                },
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Move ${activeHandle.label()} one point later",
                )
            }
        }
        when (val preview = state.preview) {
            is SelectionPreview.Valid -> Column(
                verticalArrangement = Arrangement.spacedBy(DhavaSpacing.medium),
            ) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    DhavaMetric(
                        value = SegmentFormat.length(preview.lengthM),
                        label = "Length",
                        modifier = Modifier.weight(1f),
                    )
                    DhavaMetric(
                        value = SegmentFormat.elapsed(preview.durationMs),
                        label = "This pass",
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    DhavaMetric(
                        value = SegmentFormat.descent(preview.descentM) ?: "—",
                        label = "Descent",
                        modifier = Modifier.weight(1f),
                    )
                    DhavaMetric(
                        value = SegmentFormat.ascent(preview.ascentM) ?: "—",
                        label = "Climb",
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            is SelectionPreview.Invalid -> Text(
                text = preview.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        valid?.let { preview ->
            Spacer(Modifier.height(DhavaSpacing.medium))
            DhavaPanel(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Gates ${preview.gateWidthM.toInt()} m wide, corridor " +
                        "${preview.corridorM.toInt()} m — derived from this ride's own GPS " +
                        "accuracy. The first segment stays a draft: it times runs but is " +
                        "not treated as ground truth.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(DhavaSpacing.large),
                )
            }
        }
        Spacer(Modifier.height(DhavaSpacing.large))
        OutlinedTextField(
            value = state.name,
            onValueChange = onNameChange,
            label = { Text("Segment name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(DhavaSpacing.large))
        Button(
            onClick = onSave,
            enabled = valid != null && state.name.isNotBlank() && !state.saving,
            modifier = Modifier
                .fillMaxWidth()
                .height(DhavaSizes.primaryActionHeight),
        ) {
            Text(if (state.saving) "Saving…" else "Create segment")
        }
    }
}

/**
 * Splits the ride into drawable sections at manual pause boundaries and
 * recording gaps, so the context line never bridges a pause.
 */
private fun List<CanonicalPoint>.toMapSections(): List<List<SegmentMapPoint>> {
    val sections = mutableListOf<List<SegmentMapPoint>>()
    var current = mutableListOf<SegmentMapPoint>()
    forEachIndexed { index, point ->
        val continues = index == 0 ||
            (
                this[index - 1].sectionId == point.sectionId &&
                    point.timestampMs - this[index - 1].timestampMs in 1..MAX_SECTION_GAP_MS
                )
        if (!continues) {
            if (current.size >= 2) sections += current
            current = mutableListOf()
        }
        current += SegmentMapPoint(point.lat, point.lon)
    }
    if (current.size >= 2) sections += current
    return sections
}

private const val MAX_SECTION_GAP_MS = 3_000L
private const val SLIDER_FOCUS_DELAY_MS = 800L
private const val SLIDER_FOCUS_PADDING_FRACTION = 0.08
private const val MIN_SLIDER_FOCUS_PADDING_POINTS = 10
private const val PRECISION_HOLD_DELAY_MS = 700L
private const val PRECISION_DRAG_HANDOFF_MS = 80L
private const val PRECISION_SENSITIVITY = 0.2f
private val SegmentEditorSheetPeekHeight = 196.dp

private fun SelectionHandle.label(): String = when (this) {
    SelectionHandle.START -> "start"
    SelectionHandle.FINISH -> "finish"
}

/**
 * Gives the selected interval almost the full slider width while retaining a
 * small grab area beyond each handle. The unit stays a canonical track index;
 * at 5 Hz this provides roughly point-level touch precision independently of
 * the length of the full recording.
 */
internal fun focusedSliderWindow(
    startIndex: Int,
    endIndex: Int,
    lastIndex: Int,
): IntRange {
    if (lastIndex <= 0) return 0..0
    val start = startIndex.coerceIn(0, lastIndex)
    val end = endIndex.coerceIn(start, lastIndex)
    val span = (end - start).coerceAtLeast(1)
    val padding = max(
        MIN_SLIDER_FOCUS_PADDING_POINTS,
        ceil(span * SLIDER_FOCUS_PADDING_FRACTION).toInt(),
    )
    return (start - padding).coerceAtLeast(0)..(end + padding).coerceAtMost(lastIndex)
}

/** Scales finger movement without changing the handle's value when precision begins. */
internal fun scaledPrecisionValue(
    currentValue: Float,
    proposedValue: Float,
    previousProposedValue: Float,
): Float = currentValue + (proposedValue - previousProposedValue) * PRECISION_SENSITIVITY
