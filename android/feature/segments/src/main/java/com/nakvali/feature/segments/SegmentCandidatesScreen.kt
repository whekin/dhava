package com.nakvali.feature.segments

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.ZoomOutMap
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nakvali.core.map.SegmentLibraryCamera
import com.nakvali.core.map.SegmentLibraryCameraAction
import com.nakvali.core.map.SegmentLibraryCameraRequest
import com.nakvali.core.map.SegmentLibraryLine
import com.nakvali.core.map.SegmentLibraryMap
import com.nakvali.core.ui.NakvaliDivider
import com.nakvali.core.ui.NakvaliEmptyState
import com.nakvali.core.ui.NakvaliPanel
import com.nakvali.core.ui.NakvaliScreenHeader
import com.nakvali.core.ui.NakvaliSectionLabel
import com.nakvali.core.ui.NakvaliSpacing
import java.text.DateFormat
import java.util.Date

private val CandidatesSheetPeekHeight = 132.dp

/**
 * One derived, local index of downhill opportunities across all saved rides.
 *
 * Nothing is persisted here: choosing a line opens the normal segment editor,
 * and only that explicit review can create a segment.
 */
@Composable
fun SegmentCandidatesScreen(
    onBack: () -> Unit,
    onReviewCandidate: (recordingId: String, startPosition: Double, endPosition: Double) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SegmentCandidatesViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    when (val current = state) {
        is SegmentCandidatesState.Scanning -> CandidateScanProgress(
            state = current,
            onBack = onBack,
            modifier = modifier,
        )

        is SegmentCandidatesState.Ready -> if (current.candidates.isEmpty()) {
            CandidateEmptyState(
                state = current,
                onBack = onBack,
                onRetry = viewModel::scan,
                modifier = modifier,
            )
        } else {
            CandidateMap(
                state = current,
                onBack = onBack,
                onSelect = viewModel::select,
                onReviewCandidate = onReviewCandidate,
                retainedCamera = viewModel.retainedCamera,
                onCameraSettled = viewModel::onCameraSettled,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun CandidateScanProgress(
    state: SegmentCandidatesState.Scanning,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        CandidatePageHeader(onBack = onBack)
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(NakvaliSpacing.large))
                Text(
                    text = if (state.totalRides == 0) {
                        "Looking for local rides…"
                    } else {
                        "Scanning ${state.scannedRides} of ${state.totalRides} rides"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CandidateEmptyState(
    state: SegmentCandidatesState.Ready,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        CandidatePageHeader(onBack = onBack)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(NakvaliSpacing.screen),
            contentAlignment = Alignment.TopCenter,
        ) {
            NakvaliPanel(Modifier.fillMaxWidth()) {
                NakvaliEmptyState(
                    title = "No new descents found",
                    description = candidateEmptyExplanation(state),
                    icon = Icons.Filled.Timer,
                    action = {
                        TextButton(onClick = onRetry) {
                            Icon(Icons.Outlined.Refresh, contentDescription = null)
                            Text("Scan again")
                        }
                    },
                )
            }
        }
    }
}

private fun candidateEmptyExplanation(state: SegmentCandidatesState.Ready): String = when {
    state.scannedRides == 0 -> "Record a downhill ride first. Candidates stay available offline."
    state.coveredCount > 0 -> "Your downhill candidates are already covered by existing segments."
    state.qualityFilteredCount > 0 ->
        "The found descents had weak GPS quality. A cleaner ride can make them reviewable."
    state.unavailableRideCount == state.scannedRides ->
        "These rides do not have a finalized track available for segment authoring."
    else -> "No ride contained a downhill span long enough to propose as a segment."
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CandidateMap(
    state: SegmentCandidatesState.Ready,
    onBack: () -> Unit,
    onSelect: (String?) -> Unit,
    onReviewCandidate: (String, Double, Double) -> Unit,
    retainedCamera: SegmentLibraryCamera?,
    onCameraSettled: (SegmentLibraryCamera) -> Unit,
    modifier: Modifier = Modifier,
) {
    val initialCamera = remember { retainedCamera }
    val lineColor = MaterialTheme.colorScheme.primary.cssHex()
    val casingColor = MaterialTheme.colorScheme.scrim.cssHex()
    var cameraRequest by remember { mutableStateOf<SegmentLibraryCameraRequest?>(null) }
    val lines = remember(state.candidates, lineColor, casingColor) {
        state.candidates.map {
            SegmentLibraryLine(
                id = it.id,
                points = it.points,
                lineColor = lineColor,
                casingColor = casingColor,
            )
        }
    }
    val sheetState = rememberStandardBottomSheetState(
        initialValue = SheetValue.PartiallyExpanded,
        skipHiddenState = true,
    )
    val scaffoldState = rememberBottomSheetScaffoldState(sheetState)

    fun requestCamera(action: SegmentLibraryCameraAction) {
        cameraRequest = SegmentLibraryCameraRequest(
            action = action,
            token = (cameraRequest?.token ?: 0) + 1,
        )
    }

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        modifier = modifier.fillMaxSize(),
        sheetPeekHeight = CandidatesSheetPeekHeight,
        sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        sheetContainerColor = MaterialTheme.colorScheme.surface,
        sheetContentColor = MaterialTheme.colorScheme.onSurface,
        sheetTonalElevation = 0.dp,
        sheetShadowElevation = 8.dp,
        sheetDragHandle = {
            BottomSheetDefaults.DragHandle(color = MaterialTheme.colorScheme.outline)
        },
        sheetContent = {
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.72f)) {
                CandidateSheetHeader(
                    state = state,
                    onReviewCandidate = onReviewCandidate,
                )
                NakvaliDivider()
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(
                        start = NakvaliSpacing.screen,
                        end = NakvaliSpacing.screen,
                        top = NakvaliSpacing.medium,
                        bottom = NakvaliSpacing.xxLarge,
                    ),
                    verticalArrangement = Arrangement.spacedBy(NakvaliSpacing.medium),
                ) {
                    items(state.candidates, key = SegmentCandidate::id) { candidate ->
                        CandidateCard(
                            candidate = candidate,
                            selected = candidate.id == state.selectedId,
                            onSelect = {
                                onSelect(candidate.id)
                                requestCamera(SegmentLibraryCameraAction.FitSegment(candidate.id))
                            },
                            onReview = {
                                onReviewCandidate(
                                    candidate.recordingId,
                                    candidate.startPosition,
                                    candidate.endPosition,
                                )
                            },
                        )
                    }
                }
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            SegmentLibraryMap(
                lines = lines,
                selectedId = state.selectedId,
                onSelect = onSelect,
                onCameraSettled = onCameraSettled,
                initialCamera = initialCamera,
                cameraRequest = cameraRequest,
                bottomInset = CandidatesSheetPeekHeight,
                modifier = Modifier.fillMaxSize(),
            )
            CandidateMapControls(
                onBack = onBack,
                onFitAll = { requestCamera(SegmentLibraryCameraAction.FitAll) },
            )
        }
    }
}

@Composable
private fun CandidateMapControls(onBack: () -> Unit, onFitAll: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(NakvaliSpacing.medium),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            shadowElevation = 8.dp,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text(
                    text = "Find descents",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(end = NakvaliSpacing.large),
                )
            }
        }
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            shadowElevation = 8.dp,
        ) {
            IconButton(onClick = onFitAll) {
                Icon(Icons.Outlined.ZoomOutMap, contentDescription = "Fit all candidates")
            }
        }
    }
}

@Composable
private fun CandidateSheetHeader(
    state: SegmentCandidatesState.Ready,
    onReviewCandidate: (String, Double, Double) -> Unit,
) {
    val selected = state.selected
    Row(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = NakvaliSpacing.screen, vertical = NakvaliSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            NakvaliSectionLabel(if (selected == null) "Downhill candidates" else "Selected descent")
            Spacer(Modifier.height(NakvaliSpacing.xSmall))
            Text(
                text = selected?.sourceTitle ?: "${state.candidates.size} places to review",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = selected?.headline() ?: candidateScanSummary(state),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected != null) {
            FilledTonalButton(
                onClick = {
                    onReviewCandidate(
                        selected.recordingId,
                        selected.startPosition,
                        selected.endPosition,
                    )
                },
            ) {
                Text("Review")
            }
        }
    }
}

private fun candidateScanSummary(state: SegmentCandidatesState.Ready): String = buildList {
    add("from ${state.scannedRides} rides")
    if (state.coveredCount > 0) add("${state.coveredCount} covered hidden")
    if (state.qualityFilteredCount > 0) add("${state.qualityFilteredCount} weak GPS hidden")
}.joinToString(" · ")

@Composable
private fun CandidateCard(
    candidate: SegmentCandidate,
    selected: Boolean,
    onSelect: () -> Unit,
    onReview: () -> Unit,
) {
    NakvaliPanel(
        modifier = Modifier.fillMaxWidth()
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
        Column(Modifier.padding(NakvaliSpacing.large)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = candidate.sourceTitle,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                            .format(Date(candidate.sourceStartedAtMs)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onReview) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Review candidate",
                    )
                }
            }
            Spacer(Modifier.height(NakvaliSpacing.medium))
            Text(
                text = candidate.headline(),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(NakvaliSpacing.xSmall))
            Text(
                text = buildList {
                    add(if (candidate.supportCount == 1) "1 pass" else "${candidate.supportCount} passes")
                    candidate.p90AccuracyM?.let { add("GPS p90 ${it.toInt()} m") }
                }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun SegmentCandidate.headline(): String = listOfNotNull(
    SegmentFormat.length(lengthM),
    SegmentFormat.descent(descentM),
    SegmentFormat.gradient(gradientPercent),
).joinToString(" · ")

@Composable
private fun CandidatePageHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = NakvaliSpacing.medium, vertical = NakvaliSpacing.large),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        NakvaliScreenHeader(
            eyebrow = "Trail library",
            title = "Find descents",
            modifier = Modifier.padding(start = NakvaliSpacing.small),
        )
    }
}
