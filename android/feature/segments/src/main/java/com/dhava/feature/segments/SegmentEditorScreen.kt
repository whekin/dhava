package com.dhava.feature.segments

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dhava.core.map.SegmentMap
import com.dhava.core.map.SegmentMapCameraRequest
import com.dhava.core.map.SegmentMapCameraTarget
import com.dhava.core.map.SegmentMapGate
import com.dhava.core.map.SegmentMapPoint
import com.dhava.core.recording.CanonicalPoint
import com.dhava.core.ui.DhavaDivider
import com.dhava.core.ui.DhavaMetric
import com.dhava.core.ui.DhavaPanel
import com.dhava.core.ui.DhavaSectionLabel
import com.dhava.core.ui.DhavaSizes
import com.dhava.core.ui.DhavaSpacing
import com.dhava.core.ui.DhavaTextField
import com.dhava.core.ui.rememberSheetFlingBoundary
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Picks a segment's start and finish along one ride's finalized track.
 *
 * The instrument is the ride's own elevation profile: for a downhill-first app
 * the question "does this actually go down" has to be answerable while
 * trimming, not after saving. Both gates are dragged directly on that profile,
 * and every number below it is Rust's own judgement of the current selection.
 */
@Composable
fun SegmentEditorScreen(
    source: SegmentEditorSource,
    onBack: () -> Unit,
    onCreated: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SegmentEditorViewModel = viewModel(
        key = "segment-editor-${source.javaClass.simpleName}-${source.id}",
        factory = SegmentEditorViewModel.factory(source),
    ),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    when (val current = state) {
        SegmentEditorState.Loading -> Column(modifier = modifier.fillMaxSize()) {
            SegmentEditorHeader(onBack)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
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
            onGateCenterChange = viewModel::setGateCenter,
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
    onGateCenterChange: (SelectionHandle, com.dhava.fusion.LatLon) -> Unit,
    onNameChange: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lastPosition = state.profile.lastPosition
    var domain by remember(state.track.size) {
        mutableStateOf(focusedDomain(state.startPosition, state.endPosition, lastPosition))
    }
    var activeHandle by remember { mutableStateOf<SelectionHandle?>(null) }
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
    val wholeRide = domain.start <= 0.0 && domain.end >= lastPosition
    val flingBoundary = rememberSheetFlingBoundary()
    val sheetState = rememberStandardBottomSheetState(
        initialValue = SheetValue.PartiallyExpanded,
        skipHiddenState = true,
    )
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        modifier = modifier.fillMaxSize().imePadding(),
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
                    .fillMaxHeight(0.88f),
            ) {
                Column(modifier = Modifier.padding(horizontal = DhavaSpacing.screen)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        DhavaSectionLabel(if (wholeRide) "Full ride" else "Selected range")
                        IconButton(
                            onClick = {
                                domain = if (wholeRide) {
                                    focusedDomain(
                                        state.startPosition,
                                        state.endPosition,
                                        lastPosition,
                                    )
                                } else {
                                    ProfileDomain(0.0, lastPosition)
                                }
                                cameraRequest = SegmentMapCameraRequest(
                                    target = if (wholeRide) {
                                        SegmentMapCameraTarget.SEGMENT
                                    } else {
                                        SegmentMapCameraTarget.FULL_RIDE
                                    },
                                    token = cameraRequest.token + 1,
                                )
                            },
                        ) {
                            Icon(
                                imageVector = if (wholeRide) {
                                    Icons.Outlined.ZoomInMap
                                } else {
                                    Icons.Outlined.ZoomOutMap
                                },
                                contentDescription = if (wholeRide) {
                                    "Focus on selection"
                                } else {
                                    "Show full ride"
                                },
                            )
                        }
                    }
                    SegmentProfileTrimmer(
                        profile = state.profile,
                        candidates = state.candidates,
                        startPosition = state.startPosition,
                        endPosition = state.endPosition,
                        domain = domain,
                        onSelectionChange = onSelectionChange,
                        onDomainChange = { domain = it },
                        onCandidatePicked = { candidate ->
                            onSelectionChange(candidate.startPosition, candidate.endPosition)
                            domain = focusedDomain(
                                candidate.startPosition,
                                candidate.endPosition,
                                lastPosition,
                            )
                        },
                        onActiveHandleChange = { activeHandle = it },
                        height = SegmentProfileHeight,
                    )
                    TrimmerStatus(state = state, valid = valid, wholeRide = wholeRide)
                    Spacer(Modifier.height(DhavaSpacing.small))
                }
                DhavaDivider(Modifier.padding(top = DhavaSpacing.small))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .nestedScroll(flingBoundary)
                        .verticalScroll(rememberScrollState())
                        .padding(
                            start = DhavaSpacing.screen,
                            end = DhavaSpacing.screen,
                            top = DhavaSpacing.large,
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
                    SelectionHandle.START -> state.startGateCenter.toMapPoint()
                    SelectionHandle.FINISH -> state.finishGateCenter.toMapPoint()
                    null -> null
                },
                trackingBottomInset = SegmentEditorSheetPeekHeight,
                startGate = state.startGateCenter.toMapPoint(),
                finishGate = state.finishGateCenter.toMapPoint(),
                onGateDrag = { gate, point ->
                    onGateCenterChange(
                        if (gate == SegmentMapGate.START) {
                            SelectionHandle.START
                        } else {
                            SelectionHandle.FINISH
                        },
                        com.dhava.fusion.LatLon(point.lat, point.lon),
                    )
                },
                onGateDragStateChanged = { gate ->
                    activeHandle = when (gate) {
                        SegmentMapGate.START -> SelectionHandle.START
                        SegmentMapGate.FINISH -> SelectionHandle.FINISH
                        null -> null
                    }
                },
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

/**
 * One line under the chart. A rejected selection and a duplicate warning are
 * different things and are never collapsed into one treatment: the first blocks
 * saving, the second is advice the rider may ignore.
 */
@Composable
private fun TrimmerStatus(
    state: SegmentEditorState.Editing,
    valid: SelectionPreview.Valid?,
    wholeRide: Boolean,
) {
    val invalid = state.preview as? SelectionPreview.Invalid
    val text: String
    val color = when {
        invalid != null -> {
            text = invalid.message
            MaterialTheme.colorScheme.error
        }

        state.duplicateOf != null -> {
            text = "Already covered by “${state.duplicateOf}” — saving still creates a new segment"
            MaterialTheme.colorScheme.tertiary
        }

        valid != null -> {
            text = listOfNotNull(
                SegmentFormat.length(valid.lengthM),
                SegmentFormat.gradient(valid.gradientPercent),
                SegmentFormat.elapsed(valid.durationMs).takeUnless { state.importedGpx },
            ).joinToString(" · ")
            MaterialTheme.colorScheme.onSurfaceVariant
        }

        else -> {
            text = if (wholeRide) "Pinch the chart to zoom in" else "Drag a marker to trim"
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
    )
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
                        value = if (state.importedGpx) {
                            state.track.size.toString()
                        } else {
                            SegmentFormat.elapsed(preview.durationMs)
                        },
                        label = if (state.importedGpx) "GPX points" else "This pass",
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
                Row(modifier = Modifier.fillMaxWidth()) {
                    DhavaMetric(
                        value = SegmentFormat.gradient(preview.gradientPercent) ?: "—",
                        label = "Average gradient",
                        modifier = Modifier.weight(1f),
                    )
                    DhavaMetric(
                        value = if (state.importedGpx) "GPX" else state.candidates.size.toString(),
                        label = if (state.importedGpx) "Source" else "Descents found",
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
        if (state.duplicateOf != null) {
            Spacer(Modifier.height(DhavaSpacing.medium))
            DhavaPanel(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                Text(
                    text = "This selection covers “${state.duplicateOf}”, which you already " +
                        "have. Nakvali never merges segments, so saving this creates a second " +
                        "one with its own results.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(DhavaSpacing.large),
                )
            }
        }
        valid?.let { preview ->
            Spacer(Modifier.height(DhavaSpacing.medium))
            DhavaPanel(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Gates ${preview.gateWidthM.toInt()} m wide, corridor " +
                        "${preview.corridorM.toInt()} m — " +
                        (if (state.importedGpx) {
                            "a conservative initial margin because imported GPX has no " +
                                "trustworthy accuracy. "
                        } else {
                            "derived from this ride's own GPS accuracy estimate. "
                        }) +
                        "Drag either gate directly on the map for exact " +
                        "placement. The first segment stays a draft: it times runs but is " +
                        "not treated as ground truth.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(DhavaSpacing.large),
                )
            }
        }
        Spacer(Modifier.height(DhavaSpacing.large))
        DhavaTextField(
            value = state.name,
            onValueChange = onNameChange,
            label = "Segment name",
            placeholder = "Name this trail",
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
private val SegmentProfileHeight = 132.dp
private val SegmentEditorSheetPeekHeight = 288.dp

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

private fun com.dhava.fusion.LatLon.toMapPoint() = SegmentMapPoint(lat = lat, lon = lon)
