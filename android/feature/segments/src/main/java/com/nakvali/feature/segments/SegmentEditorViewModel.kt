package com.nakvali.feature.segments

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nakvali.core.fusion.FusionCore
import com.nakvali.core.recording.CanonicalPoint
import com.nakvali.core.recording.RecordingRepository
import com.nakvali.core.recording.SegmentDifficulty
import com.nakvali.core.recording.normalizeExternalTrailUrl
import com.nakvali.core.recording.toCanonicalTrack
import com.nakvali.core.recording.toDefinition
import com.nakvali.fusion.CanonicalTrackPoint
import com.nakvali.fusion.LatLon
import com.nakvali.fusion.SegmentDefinition
import com.nakvali.fusion.SegmentException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Loading/authoring state of the segment editor. */
sealed interface SegmentEditorState {
    data object Loading : SegmentEditorState

    /** The ride has no finalized track to author a segment from. */
    data class Unavailable(val message: String) : SegmentEditorState

    data class Editing(
        val sourceTitle: String,
        val importedGpx: Boolean,
        /** Finalized track points backing the continuous selection. */
        val track: List<CanonicalPoint>,
        /** Elevation and gradient story of the whole ride, authored in Rust. */
        val profile: RideProfileUi,
        /** Descents worth offering, longest first. */
        val candidates: List<CandidateSpan>,
        val startPosition: Double,
        val endPosition: Double,
        /** Timing gates are authored coordinates, not aliases for line ends. */
        val startGateCenter: LatLon,
        val finishGateCenter: LatLon,
        val name: String,
        val difficulty: SegmentDifficulty? = null,
        val externalUrl: String = "",
        /** Rust-derived preview of the current selection, or its rejection. */
        val preview: SelectionPreview,
        /** The existing segment this selection would duplicate, if any. */
        val duplicateOf: String? = null,
        val saving: Boolean = false,
        /** Whether a finished gate move can still be taken back. */
        val canUndo: Boolean = false,
    ) : SegmentEditorState
}

/** What Rust says about the current selection. */
sealed interface SelectionPreview {
    data class Valid(
        val lengthM: Double,
        val ascentM: Double?,
        val descentM: Double?,
        val gateWidthM: Double,
        val corridorM: Double,
        val durationMs: Long,
    ) : SelectionPreview {
        /**
         * Mean gradient of the selection, percent; negative is descending.
         *
         * Derived from the accumulated climb and descent Rust already reports,
         * so it agrees with the numbers next to it rather than being a second,
         * differently-rounded answer.
         */
        val gradientPercent: Double?
            get() {
                val descent = descentM ?: return null
                if (lengthM <= 0.0) return null
                return -(descent - (ascentM ?: 0.0)) / lengthM * 100.0
            }
    }

    data class Invalid(val message: String) : SelectionPreview
}

enum class SelectionHandle { START, FINISH }

sealed interface SegmentEditorSource {
    val id: String

    data class Ride(
        override val id: String,
        val initialStartPosition: Double? = null,
        val initialEndPosition: Double? = null,
    ) : SegmentEditorSource
    data class ImportedGpx(override val id: String) : SegmentEditorSource
}

/**
 * Authoring one segment from a ride or preserved GPX seed trace.
 *
 * Selection positions trim the reference centerline. Gate centers are separate
 * authored coordinates and may be dragged freely on the map. Rust owns both
 * interpolation and the final gate/matching geometry.
 */
class SegmentEditorViewModel(
    application: Application,
    private val source: SegmentEditorSource,
) : AndroidViewModel(application) {

    private val repository = RecordingRepository.getInstance(application)

    private val _state = MutableStateFlow<SegmentEditorState>(SegmentEditorState.Loading)
    val state: StateFlow<SegmentEditorState> = _state.asStateFlow()

    /** Cached Rust-side track so a drag does not re-convert 5 Hz points. */
    @Volatile
    private var fusionTrack: List<CanonicalTrackPoint> = emptyList()

    /** Segments that already exist, for the duplicate warning. */
    @Volatile
    private var existing: List<SegmentDefinition> = emptyList()

    private var previewJob: kotlinx.coroutines.Job? = null

    /**
     * Selections as they were before each gate move.
     *
     * Pushed when a handle is grabbed rather than when it is released: the
     * state at the start of a gesture is what the rider wants back, and a drag
     * emits hundreds of intermediate positions that must never each become a
     * step. Bounded — this is an undo button, not a document history.
     */
    private val history = ArrayDeque<GateSelection>()

    init {
        viewModelScope.launch {
            val loadedSource = when (source) {
                is SegmentEditorSource.Ride -> {
                    val artifact = withContext(Dispatchers.IO) {
                        repository.canonicalActivity(source.id)
                    }
                    val title = repository.recordings.value
                        .firstOrNull { it.id == source.id }
                        ?.title
                        ?.takeIf { it.isNotBlank() }
                        ?: "Ride"
                    LoadedEditorSource(title, artifact?.finalizedTrack.orEmpty())
                }
                is SegmentEditorSource.ImportedGpx -> {
                    val trace = repository.importedTrace(source.id)
                    LoadedEditorSource(trace?.displayName ?: "Imported GPX", trace?.points.orEmpty())
                }
            }
            val track = loadedSource.track
            if (track.size < 2) {
                _state.value = SegmentEditorState.Unavailable(
                    if (source is SegmentEditorSource.ImportedGpx) {
                        "This GPX has no continuous track with at least two points."
                    } else {
                        "This ride has no finalized track yet, so a segment cannot be timed on it."
                    },
                )
                return@launch
            }
            repository.awaitReady()
            existing = repository.segments.value.map { it.toDefinition() }
            fusionTrack = withContext(Dispatchers.Default) { track.toCanonicalTrack() }
            val profile = withContext(Dispatchers.Default) { rideProfile() }
            val candidates = withContext(Dispatchers.Default) { candidates() }
            // The default selection is the longest candidate descent. Only when
            // no descent clears the candidate filters does this fall back to the
            // older single proposal, so a short or messy ride still opens with
            // something selected rather than with the whole ride.
            val fallback = withContext(Dispatchers.Default) {
                runCatching { FusionCore.proposeSegment(fusionTrack) }.getOrNull()
            }
            val requested = (source as? SegmentEditorSource.Ride)
                ?.let { ride ->
                    ride.initialStartPosition?.let { start ->
                        ride.initialEndPosition?.let { end ->
                            (start to end).takeIf {
                                start.isFinite() && end.isFinite() &&
                                    start >= 0.0 && end <= track.lastIndex.toDouble() && start < end
                            }
                        }
                    }
                }
            val start = requested?.first
                ?: candidates.firstOrNull()?.startPosition
                ?: fallback?.startIndex?.toDouble()
                ?: 0.0
            val end = requested?.second
                ?: candidates.firstOrNull()?.endPosition
                ?: fallback?.endIndex?.toDouble()
                ?: track.lastIndex.toDouble()
            val title = loadedSource.title
            val initialResult = withContext(Dispatchers.Default) {
                runCatching {
                    FusionCore.buildSegmentContinuous(
                        id = PREVIEW_ID,
                        name = "preview",
                        sourceRecordingId = sourceId(),
                        track = fusionTrack,
                        startPosition = start,
                        endPosition = end,
                        // The preview has to answer with the same floor the
                        // save will apply. Asking Rust for the production one
                        // here made developer mode unreachable: the editor
                        // rejected a short selection long before the lowered
                        // floor on the save path could be used.
                        minLengthM = repository.developerSegmentFloorM(),
                    )
                }
            }
            val initial = initialResult.getOrElse { error ->
                _state.value = SegmentEditorState.Unavailable(
                    (error as? SegmentException.InvalidSelection)?.msg
                        ?.replaceFirstChar { it.uppercase() }
                        ?: "This source cannot become a segment",
                )
                return@launch
            }
            _state.value = SegmentEditorState.Editing(
                sourceTitle = title,
                importedGpx = source is SegmentEditorSource.ImportedGpx,
                track = track,
                profile = profile,
                candidates = candidates,
                startPosition = start,
                endPosition = end,
                startGateCenter = initial.definition.startGateCenter,
                finishGateCenter = initial.definition.finishGateCenter,
                name = defaultName(title),
                preview = initial.toPreview(),
                duplicateOf = withContext(Dispatchers.Default) { duplicateOf(start, end) },
            )
            // The ride entry may be renamed while the editor is open; the
            // selection itself does not depend on it, so nothing else re-runs.
            if (source is SegmentEditorSource.Ride) {
                repository.recording(source.id).collect { entry ->
                    val editing = _state.value as? SegmentEditorState.Editing ?: return@collect
                    val updated = entry?.title?.takeIf { it.isNotBlank() } ?: editing.sourceTitle
                    if (updated != editing.sourceTitle) {
                        _state.value = editing.copy(sourceTitle = updated)
                    }
                }
            }
        }
    }

    /**
     * Records where the gates were before the gesture that is starting now.
     */
    fun beginGateGesture() {
        val editing = _state.value as? SegmentEditorState.Editing ?: return
        val snapshot = GateSelection(
            startPosition = editing.startPosition,
            endPosition = editing.endPosition,
            startGateCenter = editing.startGateCenter,
            finishGateCenter = editing.finishGateCenter,
        )
        if (history.lastOrNull() == snapshot) return
        history.addLast(snapshot)
        while (history.size > MAX_UNDO_STEPS) history.removeFirst()
        _state.value = editing.copy(canUndo = true)
    }

    /** Restores the gates to where they were before the last completed move. */
    fun undo() {
        val editing = _state.value as? SegmentEditorState.Editing ?: return
        val previous = history.removeLastOrNull() ?: return
        val restored = editing.copy(
            startPosition = previous.startPosition,
            endPosition = previous.endPosition,
            startGateCenter = previous.startGateCenter,
            finishGateCenter = previous.finishGateCenter,
            canUndo = history.isNotEmpty(),
        )
        _state.value = restored
        refreshPreview(restored)
    }

    private fun refreshPreview(editing: SegmentEditorState.Editing) {
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            val computed = withContext(Dispatchers.Default) {
                preview(
                    editing.startPosition,
                    editing.endPosition,
                    editing.startGateCenter,
                    editing.finishGateCenter,
                )
            }
            val current = _state.value as? SegmentEditorState.Editing ?: return@launch
            _state.value = current.copy(preview = computed)
        }
    }

    fun setSelection(startPosition: Double, endPosition: Double) {
        val editing = _state.value as? SegmentEditorState.Editing ?: return
        val lastPosition = editing.track.lastIndex.toDouble()
        val start = startPosition.coerceIn(0.0, lastPosition)
        val end = endPosition.coerceIn(0.0, lastPosition)
        if (start == editing.startPosition && end == editing.endPosition) return
        val startGate = if (start != editing.startPosition) gateAt(start) else editing.startGateCenter
        val finishGate = if (end != editing.endPosition) gateAt(end) else editing.finishGateCenter
        // The gates follow the drag immediately; the Rust verdict on a
        // multi-thousand-point selection is recomputed off the main thread and
        // superseded by the next drag position.
        _state.value = editing.copy(
            startPosition = start,
            endPosition = end,
            startGateCenter = startGate,
            finishGateCenter = finishGate,
        )
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            val computed = withContext(Dispatchers.Default) {
                preview(start, end, startGate, finishGate)
            }
            val duplicate = withContext(Dispatchers.Default) { duplicateOf(start, end) }
            val current = _state.value as? SegmentEditorState.Editing ?: return@launch
            if (
                current.startPosition == start && current.endPosition == end &&
                current.startGateCenter == startGate && current.finishGateCenter == finishGate
            ) {
                _state.value = current.copy(preview = computed, duplicateOf = duplicate)
            }
        }
    }

    fun setGateCenter(handle: SelectionHandle, center: LatLon) {
        val editing = _state.value as? SegmentEditorState.Editing ?: return
        // The authored gate centre stays exactly where the finger dropped it,
        // but the selection has to come along: the map line, the trimmer
        // handles and the preview all key off positions, and leaving them
        // behind made a dragged marker detach from its own segment.
        val position = FusionCore.nearestTrackPosition(
            editing.track.toCanonicalTrack(),
            center,
        ).coerceIn(0.0, editing.track.lastIndex.toDouble())
        val updated = when (handle) {
            SelectionHandle.START -> editing.copy(
                startGateCenter = center,
                startPosition = position.coerceAtMost(editing.endPosition),
            )
            SelectionHandle.FINISH -> editing.copy(
                finishGateCenter = center,
                endPosition = position.coerceAtLeast(editing.startPosition),
            )
        }
        _state.value = updated
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            val computed = withContext(Dispatchers.Default) {
                preview(
                    updated.startPosition,
                    updated.endPosition,
                    updated.startGateCenter,
                    updated.finishGateCenter,
                )
            }
            val current = _state.value as? SegmentEditorState.Editing ?: return@launch
            if (
                current.startGateCenter == updated.startGateCenter &&
                current.finishGateCenter == updated.finishGateCenter
            ) {
                _state.value = current.copy(preview = computed)
            }
        }
    }

    fun setName(name: String) {
        val editing = _state.value as? SegmentEditorState.Editing ?: return
        _state.value = editing.copy(name = name)
    }

    fun setDifficulty(difficulty: SegmentDifficulty?) {
        val editing = _state.value as? SegmentEditorState.Editing ?: return
        _state.value = editing.copy(difficulty = difficulty)
    }

    fun setExternalUrl(url: String) {
        val editing = _state.value as? SegmentEditorState.Editing ?: return
        _state.value = editing.copy(externalUrl = url)
    }

    /** Persists the selection; [onSaved] receives the new segment id. */
    fun save(onSaved: (String) -> Unit, onError: (String) -> Unit) {
        val editing = _state.value as? SegmentEditorState.Editing ?: return
        if (editing.saving) return
        _state.value = editing.copy(saving = true)
        viewModelScope.launch {
            val result = runCatching {
                when (source) {
                    is SegmentEditorSource.Ride -> repository.createSegment(
                        recordingId = source.id,
                        name = editing.name,
                        difficulty = editing.difficulty,
                        externalUrl = editing.externalUrl,
                        startPosition = editing.startPosition,
                        endPosition = editing.endPosition,
                        startGateCenter = editing.startGateCenter,
                        finishGateCenter = editing.finishGateCenter,
                    )
                    is SegmentEditorSource.ImportedGpx -> repository.createSegmentFromImportedTrace(
                        traceId = source.id,
                        name = editing.name,
                        difficulty = editing.difficulty,
                        externalUrl = editing.externalUrl,
                        startPosition = editing.startPosition,
                        endPosition = editing.endPosition,
                        startGateCenter = editing.startGateCenter,
                        finishGateCenter = editing.finishGateCenter,
                    )
                }
            }
            val current = _state.value as? SegmentEditorState.Editing
            if (current != null) _state.value = current.copy(saving = false)
            result.fold(
                onSuccess = { segment -> onSaved(segment.id) },
                onFailure = { error ->
                    onError(error.message ?: "The selection could not be saved")
                },
            )
        }
    }

    private fun rideProfile(): RideProfileUi {
        val profile = runCatching { FusionCore.rideProfile(fusionTrack) }.getOrNull()
        return RideProfileUi(
            samples = profile?.points.orEmpty().map { point ->
                ProfileSample(
                    position = point.position,
                    distanceM = point.distanceM,
                    altitudeM = point.altitudeM,
                    gradientPercent = point.gradientPercent,
                    continues = point.continues,
                )
            },
            lengthM = profile?.lengthM ?: 0.0,
            minAltitudeM = profile?.minAltitudeM,
            maxAltitudeM = profile?.maxAltitudeM,
            lastPosition = fusionTrack.lastIndex.toDouble().coerceAtLeast(0.0),
        )
    }

    private fun candidates(): List<CandidateSpan> =
        runCatching { FusionCore.proposeDescents(fusionTrack) }
            .getOrDefault(emptyList())
            .map { candidate ->
                CandidateSpan(
                    startPosition = candidate.startPosition,
                    endPosition = candidate.endPosition,
                    lengthM = candidate.lengthM,
                    descentM = candidate.descentM,
                    gradientPercent = candidate.gradientPercent,
                    // Marked, never hidden: hiding a candidate that already
                    // exists would tell the rider nothing was found here and
                    // send them off to draw the same trail by hand.
                    existingSegmentName = duplicateOf(
                        candidate.startPosition,
                        candidate.endPosition,
                    ),
                )
            }

    private fun duplicateOf(startPosition: Double, endPosition: Double): String? {
        if (existing.isEmpty()) return null
        return runCatching {
            FusionCore.selectionOverlap(existing, fusionTrack, startPosition, endPosition)
        }.getOrNull()?.segmentName
    }

    private fun preview(
        startPosition: Double,
        endPosition: Double,
        startGateCenter: LatLon,
        finishGateCenter: LatLon,
    ): SelectionPreview = runCatching {
        FusionCore.buildSegmentContinuousWithGates(
            id = PREVIEW_ID,
            name = "preview",
            sourceRecordingId = sourceId(),
            track = fusionTrack,
            startPosition = startPosition,
            endPosition = endPosition,
            startGateCenter = startGateCenter,
            finishGateCenter = finishGateCenter,
            minLengthM = repository.developerSegmentFloorM(),
        )
    }.fold(
        onSuccess = { result -> result.toPreview() },
        onFailure = { error ->
            // Read the typed reason rather than the exception's own text: UniFFI
            // renders that as `msg=…`, which is a binding detail and not
            // something to show a rider.
            SelectionPreview.Invalid(
                (error as? SegmentException.InvalidSelection)?.msg
                    ?.replaceFirstChar { it.uppercase() }
                    ?: "This selection cannot become a segment",
            )
        },
    )

    /**
     * Left empty on purpose.
     *
     * A prefilled name is a name the rider has to delete before typing their
     * own, and "<ride> segment" was never the name anyone wanted. The field
     * carries a hint instead, and saving requires a real one.
     */
    private fun defaultName(rideTitle: String): String = ""

    private fun sourceId(): String = when (source) {
        is SegmentEditorSource.Ride -> source.id
        is SegmentEditorSource.ImportedGpx -> "imported-gpx:${source.id}"
    }

    private fun gateAt(position: Double): LatLon {
        val lower = position.toInt().coerceIn(fusionTrack.indices)
        val upper = (lower + 1).coerceIn(fusionTrack.indices)
        val fraction = position - lower
        val from = fusionTrack[lower]
        val to = fusionTrack[upper]
        return LatLon(
            lat = from.lat + (to.lat - from.lat) * fraction,
            lon = from.lon + (to.lon - from.lon) * fraction,
        )
    }

    private fun com.nakvali.fusion.SegmentBuildResult.toPreview(): SelectionPreview.Valid {
        val definition = definition
        return SelectionPreview.Valid(
            lengthM = definition.lengthM,
            ascentM = definition.ascentM,
            descentM = definition.descentM,
            gateWidthM = definition.gateHalfWidthM * 2.0,
            corridorM = definition.corridorM,
            durationMs = finishedAtMs - startedAtMs,
        )
    }

    private data class LoadedEditorSource(
        val title: String,
        val track: List<CanonicalPoint>,
    )

    companion object {
        private const val PREVIEW_ID = "preview"

        fun externalUrlIsValid(value: String): Boolean =
            value.isBlank() || normalizeExternalTrailUrl(value) != null

        fun factory(source: SegmentEditorSource): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    ?: error("APPLICATION_KEY missing from ViewModel CreationExtras")
                SegmentEditorViewModel(application as Application, source)
            }
        }
    }
}

/** One undoable gate placement. */
private data class GateSelection(
    val startPosition: Double,
    val endPosition: Double,
    val startGateCenter: LatLon,
    val finishGateCenter: LatLon,
)

private const val MAX_UNDO_STEPS = 20
