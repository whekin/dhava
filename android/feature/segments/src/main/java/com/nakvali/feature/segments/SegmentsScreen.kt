package com.nakvali.feature.segments

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.outlined.ZoomOutMap
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nakvali.core.map.SegmentLibraryCamera
import com.nakvali.core.map.SegmentLibraryCameraAction
import com.nakvali.core.map.SegmentLibraryCameraRequest
import com.nakvali.core.map.SegmentLibraryLine
import com.nakvali.core.map.SegmentLibraryMap
import com.nakvali.core.map.SegmentMapPoint
import com.nakvali.core.map.currentLocationFix
import com.nakvali.core.recording.SegmentSourceKind
import com.nakvali.core.ui.NakvaliDivider
import com.nakvali.core.ui.NakvaliEmptyState
import com.nakvali.core.ui.NakvaliPanel
import com.nakvali.core.ui.NakvaliScreenHeader
import com.nakvali.core.ui.NakvaliSectionLabel
import com.nakvali.core.ui.NakvaliSpacing
import com.nakvali.core.ui.NakvaliStatusPill
import kotlinx.coroutines.launch

/**
 * Just the pinned header, deliberately not a sliced-off first card: a card cut
 * in half reads as a clipped layout rather than as an invitation to scroll.
 */
private val LibrarySheetPeekHeight = 116.dp
private const val MY_LOCATION_ZOOM = 15.0

/**
 * The segment library: one map of every local segment, with the list inside a
 * persistent sheet.
 *
 * The map leads because a segment's first question is "where is it", which no
 * list can answer. Nothing on the map is emphasised until the rider taps a
 * line; opening a segment stays a separate, explicit action so browsing the map
 * never navigates away by accident.
 */
@Composable
fun SegmentsScreen(
    onOpenSegment: (String) -> Unit,
    onEditImportedTrace: (String) -> Unit,
    onCreateSegment: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SegmentsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        viewModel.importGpx(
            uri = uri,
            onImported = onEditImportedTrace,
            onError = { message -> Toast.makeText(context, message, Toast.LENGTH_LONG).show() },
        )
    }
    val launchImport = {
        importLauncher.launch(
            arrayOf("application/gpx+xml", "application/xml", "text/xml", "text/plain"),
        )
    }

    when (val current = state) {
        SegmentsState.Loading -> Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }

        is SegmentsState.Ready -> if (current.summaries.isEmpty()) {
            // With nothing authored there is nothing to place, and a map of
            // empty terrain would answer no question the rider has.
            Column(
                modifier = modifier.fillMaxSize(),
            ) {
                NakvaliScreenHeader(
                    eyebrow = "Trail library",
                    title = "Segments",
                    modifier = Modifier.padding(
                        start = NakvaliSpacing.screen,
                        end = NakvaliSpacing.screen,
                        top = NakvaliSpacing.xLarge,
                        bottom = NakvaliSpacing.large,
                    ),
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(NakvaliSpacing.screen),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    NakvaliPanel(Modifier.fillMaxWidth()) {
                        NakvaliEmptyState(
                            title = "No segments yet",
                            description = "Find downhill candidates across your saved rides or import a GPX trail.",
                            icon = Icons.Filled.Timer,
                            action = {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    FilledTonalButton(onClick = onCreateSegment) {
                                        Text("Find descents")
                                    }
                                    TextButton(onClick = launchImport) {
                                        Icon(Icons.Filled.Add, contentDescription = null)
                                        Spacer(Modifier.width(NakvaliSpacing.small))
                                        Text("Import GPX")
                                    }
                                }
                            },
                        )
                    }
                }
            }
        } else {
            SegmentLibrary(
                state = current,
                onSelect = viewModel::select,
                onOpenSegment = onOpenSegment,
                onCreateSegment = onCreateSegment,
                onImportGpx = launchImport,
                retainedCamera = viewModel.retainedCamera,
                onCameraSettled = viewModel::onCameraSettled,
                modifier = modifier,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SegmentLibrary(
    state: SegmentsState.Ready,
    onSelect: (String?) -> Unit,
    onOpenSegment: (String) -> Unit,
    onCreateSegment: () -> Unit,
    onImportGpx: () -> Unit,
    retainedCamera: SegmentLibraryCamera?,
    onCameraSettled: (SegmentLibraryCamera) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Read once per entry into the screen: a retained camera is a starting
    // point, not a target the map keeps snapping back to.
    val initialCamera = remember { retainedCamera }
    val unratedColor = MaterialTheme.colorScheme.primary
    val defaultCasing = MaterialTheme.colorScheme.scrim
    val blackCasing = MaterialTheme.colorScheme.outline
    var cameraRequest by remember { mutableStateOf<SegmentLibraryCameraRequest?>(null) }
    val lines = remember(state.summaries) {
        state.summaries.map { summary ->
            SegmentLibraryLine(
                id = summary.segment.id,
                points = summary.segment.centerline.map { SegmentMapPoint(it.lat, it.lon) },
                lineColor = (summary.segment.difficulty?.color ?: unratedColor).cssHex(),
                casingColor = if (
                    summary.segment.difficulty == com.nakvali.core.recording.SegmentDifficulty.BLACK ||
                    summary.segment.difficulty == com.nakvali.core.recording.SegmentDifficulty.DOUBLE_BLACK
                ) {
                    blackCasing.cssHex()
                } else {
                    defaultCasing.cssHex()
                },
            )
        }
    }
    val sheetState = rememberStandardBottomSheetState(
        initialValue = SheetValue.PartiallyExpanded,
        skipHiddenState = true,
    )
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)
    var addMenuExpanded by remember { mutableStateOf(false) }

    fun requestCamera(action: SegmentLibraryCameraAction) {
        cameraRequest = SegmentLibraryCameraRequest(
            action = action,
            token = (cameraRequest?.token ?: 0) + 1,
        )
    }

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        modifier = modifier.fillMaxSize(),
        sheetPeekHeight = LibrarySheetPeekHeight,
        sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        sheetContainerColor = MaterialTheme.colorScheme.surface,
        sheetContentColor = MaterialTheme.colorScheme.onSurface,
        sheetTonalElevation = 0.dp,
        sheetShadowElevation = 8.dp,
        sheetDragHandle = {
            BottomSheetDefaults.DragHandle(color = MaterialTheme.colorScheme.outline)
        },
        sheetContent = {
            // Deliberately short of full height: the map is the point of this
            // screen, and a sheet that swallows it also hides the top controls.
            Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.72f)) {
                LibrarySheetHeader(
                    state = state,
                    onOpenSegment = onOpenSegment,
                )
                NakvaliDivider()
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        ,
                    contentPadding = PaddingValues(
                        start = NakvaliSpacing.screen,
                        end = NakvaliSpacing.screen,
                        top = NakvaliSpacing.medium,
                        bottom = NakvaliSpacing.xxLarge,
                    ),
                    verticalArrangement = Arrangement.spacedBy(NakvaliSpacing.medium),
                ) {
                    items(state.summaries, key = { it.segment.id }) { summary ->
                        SegmentCard(
                            summary = summary,
                            selected = summary.segment.id == state.selectedId,
                            onSelect = {
                                onSelect(summary.segment.id)
                                // Picking from the list is deliberate, so the
                                // map may move; a tap on the map itself never
                                // reframes anything.
                                requestCamera(
                                    SegmentLibraryCameraAction.FitSegment(summary.segment.id),
                                )
                            },
                            onOpen = { onOpenSegment(summary.segment.id) },
                        )
                    }
                }
            }
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            SegmentLibraryMap(
                lines = lines,
                selectedId = state.selectedId,
                onSelect = onSelect,
                onCameraSettled = onCameraSettled,
                initialCamera = initialCamera,
                cameraRequest = cameraRequest,
                bottomInset = LibrarySheetPeekHeight,
                modifier = Modifier.fillMaxSize(),
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(NakvaliSpacing.medium),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                shadowElevation = 8.dp,
            ) {
                Column {
                    IconButton(onClick = { addMenuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "Add segment",
                        )
                    }
                    DropdownMenu(
                        expanded = addMenuExpanded,
                        onDismissRequest = { addMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Find descents") },
                            onClick = {
                                addMenuExpanded = false
                                onCreateSegment()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Import GPX") },
                            onClick = {
                                addMenuExpanded = false
                                onImportGpx()
                            },
                        )
                    }
                    IconButton(
                        onClick = { requestCamera(SegmentLibraryCameraAction.FitAll) },
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ZoomOutMap,
                            contentDescription = "Fit area",
                        )
                    }
                    IconButton(
                        onClick = {
                            scope.launch {
                                val fix = currentLocationFix(context)
                                if (fix == null) {
                                    Toast.makeText(
                                        context,
                                        "No location yet — check the location permission",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                } else {
                                    requestCamera(
                                        SegmentLibraryCameraAction.Center(
                                            lat = fix.lat,
                                            lon = fix.lon,
                                            zoom = MY_LOCATION_ZOOM,
                                        ),
                                    )
                                }
                            }
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MyLocation,
                            contentDescription = "My location",
                        )
                    }
                }
            }
        }
    }
}


/**
 * The pinned peek of the sheet: the identity and primary action of whatever the
 * rider is looking at, so the collapsed sheet is never just a metric dump.
 */
@Composable
private fun LibrarySheetHeader(
    state: SegmentsState.Ready,
    onOpenSegment: (String) -> Unit,
) {
    val selected = state.selected
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NakvaliSpacing.screen, vertical = NakvaliSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (selected == null) {
                NakvaliSectionLabel("Local segments")
                Spacer(Modifier.height(NakvaliSpacing.xSmall))
                Text(
                    text = "${state.summaries.size} on the map",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Tap a line to see its results",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                NakvaliSectionLabel("Selected")
                Spacer(Modifier.height(NakvaliSpacing.xSmall))
                Text(
                    text = selected.segment.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = selected.headline(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (selected != null) {
            FilledTonalButton(onClick = { onOpenSegment(selected.segment.id) }) {
                Text("Open")
            }
        }
    }
}

@Composable
private fun SegmentCard(
    summary: SegmentSummary,
    selected: Boolean,
    onSelect: () -> Unit,
    onOpen: () -> Unit,
) {
    val segment = summary.segment
    // Selection is stated in words by the sheet header; the card only needs a
    // quiet outline, not a saturated fill competing with the map.
    NakvaliPanel(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (selected) {
                    Modifier.border(
                        width = 1.5.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = MaterialTheme.shapes.large,
                    )
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onSelect),
    ) {
        Column(modifier = Modifier.padding(NakvaliSpacing.large)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = segment.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onOpen) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Open ${segment.name}",
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NakvaliSpacing.small),
            ) {
                if (!segment.trusted) {
                    NakvaliStatusPill(text = "Draft")
                }
                if (segment.sourceKind == SegmentSourceKind.IMPORTED_GPX) {
                    NakvaliStatusPill(text = "GPX seed")
                }
                segment.difficulty?.let { difficulty ->
                    DifficultyDot(difficulty)
                    Text(
                        text = difficulty.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = listOfNotNull(
                        SegmentFormat.length(segment.lengthM),
                        SegmentFormat.descent(segment.descentM),
                        SegmentFormat.ascent(segment.ascentM)
                            ?.takeUnless { segment.ascentM == 0.0 },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            NakvaliDivider(Modifier.padding(vertical = NakvaliSpacing.medium))
            val record = summary.record
            if (record == null) {
                Text(
                    text = "—",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "No countable run yet",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = SegmentFormat.elapsedWithUncertainty(
                        record.elapsedMs,
                        record.uncertaintyMs,
                    ),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "PR of ${summary.attemptCount} " +
                        if (summary.attemptCount == 1) "run" else "runs",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val notes = listOfNotNull(
                summary.fastestNotCounted?.let { faster ->
                    "${SegmentFormat.elapsed(faster.elapsedMs)} was faster but does not count"
                },
                summary.notTimedCount.takeIf { it > 0 }?.let { "$it not timed" },
            )
            if (notes.isNotEmpty()) {
                Spacer(Modifier.height(NakvaliSpacing.small))
                Text(
                    text = notes.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** One line under a selected segment's name: what it is, then how it went. */
private fun SegmentSummary.headline(): String = listOfNotNull(
    SegmentFormat.length(segment.lengthM),
    SegmentFormat.descent(segment.descentM),
    record?.let { "PR ${SegmentFormat.elapsed(it.elapsedMs)}" } ?: "no countable run",
).joinToString(" · ")
