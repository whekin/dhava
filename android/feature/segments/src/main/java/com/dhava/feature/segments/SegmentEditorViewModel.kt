package com.dhava.feature.segments

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dhava.core.fusion.FusionCore
import com.dhava.core.recording.CanonicalPoint
import com.dhava.core.recording.RecordingRepository
import com.dhava.core.recording.toCanonicalTrack
import com.dhava.core.recording.toDefinition
import com.dhava.fusion.CanonicalTrackPoint
import com.dhava.fusion.SegmentDefinition
import com.dhava.fusion.SegmentException
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
        val rideTitle: String,
        /** Finalized track points backing the continuous selection. */
        val track: List<CanonicalPoint>,
        /** Elevation and gradient story of the whole ride, authored in Rust. */
        val profile: RideProfileUi,
        /** Descents worth offering, longest first. */
        val candidates: List<CandidateSpan>,
        val startPosition: Double,
        val endPosition: Double,
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

/**
 * Authoring one segment from one ride.
 *
 * A selection position may lie anywhere on an edge of the finalized track. It
 * is not a free map coordinate, so it cannot silently jump to a different pass.
 * Rust owns interpolation, the elevation profile, the candidate descents and
 * every geometry judgement, so what the editor draws is exactly what gets
 * persisted.
 */
class SegmentEditorViewModel(
    application: Application,
    private val recordingId: String,
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
            val recording = repository.recording(recordingId)
            val artifact = withContext(Dispatchers.IO) {
                repository.canonicalActivity(recordingId)
            }
            val track = artifact?.finalizedTrack.orEmpty()
            if (track.size < 2) {
                _state.value = SegmentEditorState.Unavailable(
                    "This ride has no finalized track yet, so a segment cannot be timed on it.",
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
            val title = repository.recordings.value
                .firstOrNull { it.id == recordingId }
                ?.title
                ?.takeIf { it.isNotBlank() }
                ?: "Ride"
            _state.value = SegmentEditorState.Editing(
                rideTitle = title,
                track = track,
                profile = profile,
                candidates = candidates,
                startPosition = start,
                endPosition = end,
                name = defaultName(title),
                preview = withContext(Dispatchers.Default) { preview(start, end) },
                duplicateOf = withContext(Dispatchers.Default) { duplicateOf(start, end) },
            )
            // The ride entry may be renamed while the editor is open; the
            // selection itself does not depend on it, so nothing else re-runs.
            recording.collect { entry ->
                val editing = _state.value as? SegmentEditorState.Editing ?: return@collect
                val updated = entry?.title?.takeIf { it.isNotBlank() } ?: editing.rideTitle
                if (updated != editing.rideTitle) {
                    _state.value = editing.copy(rideTitle = updated)
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
        // The gates follow the drag immediately; the Rust verdict on a
        // multi-thousand-point selection is recomputed off the main thread and
        // superseded by the next drag position.
        _state.value = editing.copy(startPosition = start, endPosition = end)
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            val computed = withContext(Dispatchers.Default) { preview(start, end) }
            val duplicate = withContext(Dispatchers.Default) { duplicateOf(start, end) }
            val current = _state.value as? SegmentEditorState.Editing ?: return@launch
            if (current.startPosition == start && current.endPosition == end) {
                _state.value = current.copy(preview = computed, duplicateOf = duplicate)
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
                repository.createSegment(
                    recordingId = recordingId,
                    name = editing.name,
                    startPosition = editing.startPosition,
                    endPosition = editing.endPosition,
                )
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
    ): SelectionPreview = runCatching {
        FusionCore.buildSegmentContinuous(
            id = PREVIEW_ID,
            name = "preview",
            sourceRecordingId = recordingId,
            track = fusionTrack,
            startPosition = startPosition,
            endPosition = endPosition,
        )
    }.fold(
        onSuccess = { result ->
            val definition = result.definition
            SelectionPreview.Valid(
                lengthM = definition.lengthM,
                ascentM = definition.ascentM,
                descentM = definition.descentM,
                gateWidthM = definition.gateHalfWidthM * 2.0,
                corridorM = definition.corridorM,
                durationMs = result.finishedAtMs - result.startedAtMs,
            )
        },
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

    companion object {
        private const val PREVIEW_ID = "preview"

        fun factory(recordingId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    ?: error("APPLICATION_KEY missing from ViewModel CreationExtras")
                SegmentEditorViewModel(application as Application, recordingId)
            }
        }
    }
}
