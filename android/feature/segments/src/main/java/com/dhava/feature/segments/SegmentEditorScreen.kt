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
import androidx.compose.material.icons.outlined.ZoomInMap
import androidx.compose.material.icons.outlined.ZoomOutMap
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableDoubleStateOf
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
import com.dhava.core.map.SegmentMapCameraRequest
import com.dhava.core.map.SegmentMapCameraTarget
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
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow

/**
 * Picks a segment's start and finish along one ride's finalized track.
 *
 * The two handles move continuously along recorded track edges rather than
 * free map coordinates, and every number below the map is Rust's own
 * judgement of the current selection.
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
    onSelectionChange: (Double, Double) -> Unit,
    onNameChange: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val initialSliderWindow = remember(state.track.size) {
        focusedSliderWindow(
            startPosition = state.startPosition.toFloat(),
            endPosition = state.endPosition.toFloat(),
            lastIndex = state.track.lastIndex,
        )
    }
    var sliderWindowStart by rememberSaveable(state.track.size) {
        mutableStateOf(initialSliderWindow.start)
    }
    var sliderWindowEnd by rememberSaveable(state.track.size) {
        mutableStateOf(initialSliderWindow.endInclusive)
    }
    var focusAfterDrag by remember { mutableStateOf(false) }
    var activeHandle by remember { mutableStateOf<SelectionHandle?>(null) }
    var mapZoom by remember { mutableDoubleStateOf(0.0) }
    var cameraRequest by remember {
        mutableStateOf(
            SegmentMapCameraRequest(
                target = SegmentMapCameraTarget.SEGMENT,
                token = 0,
            ),
        )
    }
    val sections = remember(state.track) { state.track.toMapSections() }
    val selection = remember(state.track, state.startPosition, state.endPosition) {
        state.track.toContinuousMapSelection(
            startPosition = state.startPosition,
            endPosition = state.endPosition,
        )
    }
    val valid = state.preview as? SelectionPreview.Valid
    val fullSliderRange =
        sliderWindowStart == 0f && sliderWindowEnd == state.track.lastIndex.toFloat()
    val sheetState = rememberStandardBottomSheetState(
        initialValue = SheetValue.PartiallyExpanded,
        skipHiddenState = true,
    )
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)

    // Wait until the rider has stopped dragging, then spend the available
    // slider width on the selected interval. Re-keying on the selection makes
    // the delay restart while a handle is still moving.
    LaunchedEffect(focusAfterDrag, state.startPosition, state.endPosition) {
        if (!focusAfterDrag) return@LaunchedEffect
        delay(SLIDER_FOCUS_DELAY_MS)
        val focused = focusedSliderWindow(
            startPosition = state.startPosition.toFloat(),
            endPosition = state.endPosition.toFloat(),
            lastIndex = state.track.lastIndex,
        )
        sliderWindowStart = focused.start
        sliderWindowEnd = focused.endInclusive
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
                    mapZoom = mapZoom,
                    onToggleSliderRange = {
                        focusAfterDrag = false
                        if (fullSliderRange) {
                            val focused = focusedSliderWindow(
                                startPosition = state.startPosition.toFloat(),
                                endPosition = state.endPosition.toFloat(),
                                lastIndex = state.track.lastIndex,
                            )
                            sliderWindowStart = focused.start
                            sliderWindowEnd = focused.endInclusive
                            cameraRequest = SegmentMapCameraRequest(
                                target = SegmentMapCameraTarget.SEGMENT,
                                token = cameraRequest.token + 1,
                            )
                        } else {
                            sliderWindowStart = 0f
                            sliderWindowEnd = state.track.lastIndex.toFloat()
                            cameraRequest = SegmentMapCameraRequest(
                                target = SegmentMapCameraTarget.FULL_RIDE,
                                token = cameraRequest.token + 1,
                            )
                        }
                    },
                    onSelectionChange = { start, end ->
                        focusAfterDrag = false
                        onSelectionChange(start, end)
                    },
                    onSelectionFinished = { focusAfterDrag = true },
                    onActiveHandleChange = { activeHandle = it },
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
                        valid = valid,
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
                focusOnSegment = true,
                cameraRequest = cameraRequest,
                trackedPoint = when (activeHandle) {
                    SelectionHandle.START -> selection.firstOrNull()
                    SelectionHandle.FINISH -> selection.lastOrNull()
                    null -> null
                },
                trackingBottomInset = SegmentEditorSheetPeekHeight,
                onZoomChanged = { mapZoom = it },
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
    sliderWindowStart: Float,
    sliderWindowEnd: Float,
    mapZoom: Double,
    onToggleSliderRange: () -> Unit,
    onSelectionChange: (Double, Double) -> Unit,
    onSelectionFinished: () -> Unit,
    onActiveHandleChange: (SelectionHandle?) -> Unit,
) {
    val startInteractionSource = remember { MutableInteractionSource() }
    val finishInteractionSource = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current
    var precisionHandle by remember { mutableStateOf<SelectionHandle?>(null) }
    var engagedHandle by remember { mutableStateOf<SelectionHandle?>(null) }
    var scaledHandle by remember { mutableStateOf<SelectionHandle?>(null) }
    var scaledLastRawValue by remember { mutableStateOf<Float?>(null) }
    var scaledValue by remember { mutableFloatStateOf(0f) }
    var scaledSensitivity by remember { mutableFloatStateOf(1f) }
    val mapSensitivity = dragSensitivityForMapZoom(mapZoom)

    PrecisionInteractionEffect(
        interactionSource = startInteractionSource,
        handle = SelectionHandle.START,
        onHandleEngaged = {
            engagedHandle = it
            onActiveHandleChange(it)
        },
        onHandleReleased = {
            if (engagedHandle == it) engagedHandle = null
            onActiveHandleChange(null)
        },
        onPrecisionStarted = { handle ->
            precisionHandle = handle
            scaledHandle = null
            scaledLastRawValue = null
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        },
        onPrecisionEnded = {
            if (precisionHandle == SelectionHandle.START) {
                precisionHandle = null
                scaledHandle = null
                scaledLastRawValue = null
            }
        },
    )
    PrecisionInteractionEffect(
        interactionSource = finishInteractionSource,
        handle = SelectionHandle.FINISH,
        onHandleEngaged = {
            engagedHandle = it
            onActiveHandleChange(it)
        },
        onHandleReleased = {
            if (engagedHandle == it) engagedHandle = null
            onActiveHandleChange(null)
        },
        onPrecisionStarted = { handle ->
            precisionHandle = handle
            scaledHandle = null
            scaledLastRawValue = null
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        },
        onPrecisionEnded = {
            if (precisionHandle == SelectionHandle.FINISH) {
                precisionHandle = null
                scaledHandle = null
                scaledLastRawValue = null
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
            DhavaSectionLabel(if (fullSliderRange) "Full ride" else "Selected range")
            IconButton(onClick = onToggleSliderRange) {
                Icon(
                    imageVector = if (fullSliderRange) {
                        Icons.Outlined.ZoomInMap
                    } else {
                        Icons.Outlined.ZoomOutMap
                    },
                    contentDescription = if (fullSliderRange) {
                        "Focus on selection"
                    } else {
                        "Show full ride"
                    },
                )
            }
        }
        RangeSlider(
            value = state.startPosition.toFloat()..state.endPosition.toFloat(),
            onValueChange = { range ->
                val inferredHandle = if (
                    abs(range.start - state.startPosition) >=
                    abs(range.endInclusive - state.endPosition)
                ) {
                    SelectionHandle.START
                } else {
                    SelectionHandle.FINISH
                }
                val handle = precisionHandle ?: engagedHandle ?: inferredHandle
                val precise = precisionHandle == handle
                val sensitivity = mapSensitivity * if (precise) {
                    PRECISION_SENSITIVITY
                } else {
                    1f
                }
                val proposedValue = when (handle) {
                    SelectionHandle.START -> range.start
                    SelectionHandle.FINISH -> range.endInclusive
                }
                val selectedValue = if (sensitivity < 0.999f) {
                    if (
                        scaledHandle != handle ||
                        scaledLastRawValue == null ||
                        abs(scaledSensitivity - sensitivity) > 0.001f
                    ) {
                        scaledHandle = handle
                        scaledLastRawValue = proposedValue
                        scaledSensitivity = sensitivity
                        scaledValue = when (handle) {
                            SelectionHandle.START -> state.startPosition.toFloat()
                            SelectionHandle.FINISH -> state.endPosition.toFloat()
                        }
                        return@RangeSlider
                    }
                    scaledValue = scaledMovementValue(
                        currentValue = scaledValue,
                        proposedValue = proposedValue,
                        previousProposedValue = checkNotNull(scaledLastRawValue),
                        sensitivity = sensitivity,
                    )
                    scaledLastRawValue = proposedValue
                    scaledValue
                } else {
                    scaledHandle = null
                    scaledLastRawValue = null
                    proposedValue
                }
                val start = when (handle) {
                    SelectionHandle.START -> selectedValue.coerceIn(
                            sliderWindowStart,
                            (state.endPosition - MIN_SELECTION_POSITION_GAP).toFloat(),
                        ).toDouble()
                    SelectionHandle.FINISH -> state.startPosition
                }
                val end = when (handle) {
                    SelectionHandle.START -> state.endPosition
                    SelectionHandle.FINISH -> selectedValue.coerceIn(
                            (state.startPosition + MIN_SELECTION_POSITION_GAP).toFloat(),
                            sliderWindowEnd,
                        ).toDouble()
                }
                onSelectionChange(start, end)
            },
            onValueChangeFinished = onSelectionFinished,
            valueRange = sliderWindowStart..sliderWindowEnd,
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
                precisionHandle != null && mapSensitivity < 0.999f ->
                    "Precision · 10× + map zoom"
                precisionHandle != null -> "Precision · 10× slower"
                mapSensitivity < 0.999f -> "Map zoom · finer movement"
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
    onHandleEngaged: (SelectionHandle) -> Unit,
    onHandleReleased: (SelectionHandle) -> Unit,
    onPrecisionStarted: (SelectionHandle) -> Unit,
    onPrecisionEnded: (SelectionHandle) -> Unit,
) {
    val currentOnHandleEngaged by rememberUpdatedState(onHandleEngaged)
    val currentOnHandleReleased by rememberUpdatedState(onHandleReleased)
    val currentOnPrecisionStarted by rememberUpdatedState(onPrecisionStarted)
    val currentOnPrecisionEnded by rememberUpdatedState(onPrecisionEnded)

    LaunchedEffect(interactionSource, handle) {
        var holdJob: Job? = null
        var cancelCleanupJob: Job? = null
        var precisionStarted = false
        var dragging = false
        var engaged = false
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    holdJob?.cancel()
                    cancelCleanupJob?.cancel()
                    precisionStarted = false
                    dragging = false
                    engaged = true
                    currentOnHandleEngaged(handle)
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
                    if (engaged) {
                        currentOnHandleReleased(handle)
                        engaged = false
                    }
                    dragging = false
                }

                is PressInteraction.Cancel -> {
                    holdJob?.cancel()
                    if (!dragging) {
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
                            if (!dragging && engaged) {
                                currentOnHandleReleased(handle)
                                engaged = false
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
                    if (engaged) {
                        currentOnHandleReleased(handle)
                        engaged = false
                    }
                }
            }
        }
    }
}

@Composable
private fun SegmentEditorDetails(
    state: SegmentEditorState.Editing,
    valid: SelectionPreview.Valid?,
    onNameChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    Column {
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
private const val SLIDER_FOCUS_PADDING_FRACTION = 0.08f
private const val MIN_SLIDER_FOCUS_PADDING_POINTS = 10
private const val PRECISION_HOLD_DELAY_MS = 700L
private const val PRECISION_DRAG_HANDOFF_MS = 80L
private const val PRECISION_SENSITIVITY = 0.1f
private const val MAP_FINE_ADJUST_ZOOM = 16.0
private const val MIN_MAP_DRAG_SENSITIVITY = 0.05f
private const val MIN_SELECTION_POSITION_GAP = 0.001
private val SegmentEditorSheetPeekHeight = 152.dp

/**
 * Gives the selected interval almost the full slider width while retaining a
 * small grab area beyond each handle. Values are continuous positions along
 * canonical edges, so focusing changes touch sensitivity without quantizing
 * the authored gate.
 */
internal fun focusedSliderWindow(
    startPosition: Float,
    endPosition: Float,
    lastIndex: Int,
): ClosedFloatingPointRange<Float> {
    if (lastIndex <= 0) return 0f..0f
    val start = startPosition.coerceIn(0f, lastIndex.toFloat())
    val end = endPosition.coerceIn(start, lastIndex.toFloat())
    val span = (end - start).coerceAtLeast(1f)
    val padding = max(
        MIN_SLIDER_FOCUS_PADDING_POINTS.toFloat(),
        ceil((span * SLIDER_FOCUS_PADDING_FRACTION).toDouble()).toFloat(),
    )
    return (start - padding).coerceAtLeast(0f)..
        (end + padding).coerceAtMost(lastIndex.toFloat())
}

/** Scales finger movement without changing the handle's value when scaling begins. */
internal fun scaledMovementValue(
    currentValue: Float,
    proposedValue: Float,
    previousProposedValue: Float,
    sensitivity: Float,
): Float = currentValue + (proposedValue - previousProposedValue) * sensitivity

/** Manual map zoom progressively lowers gate movement, while remaining continuous. */
internal fun dragSensitivityForMapZoom(zoom: Double): Float {
    if (!zoom.isFinite() || zoom <= MAP_FINE_ADJUST_ZOOM) return 1f
    return 2.0.pow(MAP_FINE_ADJUST_ZOOM - zoom)
        .toFloat()
        .coerceIn(MIN_MAP_DRAG_SENSITIVITY, 1f)
}

/**
 * Immediate display geometry for a continuous selection. Rust independently
 * owns and validates the persisted geometry; this only keeps the marker under
 * the rider's finger while the asynchronous preview is being rebuilt.
 */
internal fun List<CanonicalPoint>.toContinuousMapSelection(
    startPosition: Double,
    endPosition: Double,
): List<SegmentMapPoint> {
    if (isEmpty()) return emptyList()
    val lastPosition = lastIndex.toDouble()
    val start = startPosition.coerceIn(0.0, lastPosition)
    val end = endPosition.coerceIn(start, lastPosition)
    val result = ArrayList<SegmentMapPoint>(
        (ceil(end) - floor(start)).toInt().coerceAtLeast(0) + 2,
    )
    result += interpolateMapPoint(start)
    var index = floor(start).toInt() + 1
    while (index.toDouble() < end) {
        result += this[index].toMapPoint()
        index += 1
    }
    result += interpolateMapPoint(end)
    return result
}

private fun List<CanonicalPoint>.interpolateMapPoint(position: Double): SegmentMapPoint {
    val lower = floor(position).toInt().coerceIn(indices)
    val fraction = position - lower
    val from = this[lower]
    val to = getOrNull(lower + 1) ?: from
    return SegmentMapPoint(
        lat = from.lat + (to.lat - from.lat) * fraction,
        lon = from.lon + (to.lon - from.lon) * fraction,
    )
}

private fun CanonicalPoint.toMapPoint() = SegmentMapPoint(lat = lat, lon = lon)
