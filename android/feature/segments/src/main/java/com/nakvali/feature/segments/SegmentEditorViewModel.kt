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
        /** Rust-derived preview of the current selection, or its rejection. */
        val preview: SelectionPreview,
        /** The existing segment this selection would duplicate, if any. */
        val duplicateOf: String? = null,
        val saving: Boolean = false,
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

    data class Ride(override val id: String) : SegmentEditorSource
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
            val start = candidates.firstOrNull()?.startPosition
                ?: fallback?.startIndex?.toDouble()
                ?: 0.0
            val end = candidates.firstOrNull()?.endPosition
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
        val updated = when (handle) {
            SelectionHandle.START -> editing.copy(startGateCenter = center)
            SelectionHandle.FINISH -> editing.copy(finishGateCenter = center)
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
                        startPosition = editing.startPosition,
                        endPosition = editing.endPosition,
                        startGateCenter = editing.startGateCenter,
                        finishGateCenter = editing.finishGateCenter,
                    )
                    is SegmentEditorSource.ImportedGpx -> repository.createSegmentFromImportedTrace(
                        traceId = source.id,
                        name = editing.name,
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

    private fun defaultName(rideTitle: String): String = "$rideTitle segment"

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

        fun factory(source: SegmentEditorSource): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    ?: error("APPLICATION_KEY missing from ViewModel CreationExtras")
                SegmentEditorViewModel(application as Application, source)
            }
        }
    }
}
