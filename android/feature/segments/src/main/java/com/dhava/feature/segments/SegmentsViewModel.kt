package com.dhava.feature.segments

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dhava.core.map.SegmentLibraryCamera
import com.dhava.core.recording.RecordingRepository
import com.dhava.core.recording.SegmentResults
import com.dhava.core.recording.StoredAttempt
import com.dhava.core.recording.StoredAttemptQuality
import com.dhava.core.recording.StoredSegment
import com.dhava.core.recording.attempts
import com.dhava.core.recording.fastestUncountableAhead
import com.dhava.core.recording.latestAttempt
import com.dhava.core.recording.personalRecord
import com.dhava.core.recording.rejections
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One row of the segment list. */
data class SegmentSummary(
    val segment: StoredSegment,
    val attemptCount: Int,
    val uncertainCount: Int,
    /** Gate pairs that produced no time at all, with a reason each. */
    val notTimedCount: Int,
    /** Fastest countable run, or null while none exists. */
    val record: StoredAttempt?,
    val latest: StoredAttempt?,
    /** Fastest run that does not count, when it leads [record]. */
    val fastestNotCounted: StoredAttempt?,
)

sealed interface SegmentsState {
    data object Loading : SegmentsState

    data class Ready(
        val summaries: List<SegmentSummary>,
        val selectedId: String?,
    ) : SegmentsState {
        val selected: SegmentSummary?
            get() = summaries.firstOrNull { it.segment.id == selectedId }
    }
}

/**
 * The rider's map view of the library, kept for the life of the process.
 *
 * The library screen's ViewModel is cleared when its bottom-navigation entry is
 * popped, so retaining the camera there would silently reframe the map on every
 * tab switch. Panning to a trail is deliberate work; the app does not undo it.
 * This is intentionally not persisted to disk: a cold start legitimately starts
 * from "frame what I have". It becomes a per-riding-area value once areas
 * exist.
 */
internal object SegmentLibraryCameraStore {
    var camera: SegmentLibraryCamera? = null
}

/**
 * Segment library with lazily recomputed results.
 *
 * Matching runs on every emission of the segment or recording list, but the
 * matcher only recomputes rides whose raw fingerprint, the algorithm version,
 * the match version or the segment geometry changed — so the common case after
 * one new ride is one match, not a full rescan.
 */
class SegmentsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = RecordingRepository.getInstance(application)

    private val _selectedId = MutableStateFlow<String?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val summaries: StateFlow<List<SegmentSummary>?> = combine(
        repository.segments,
        repository.recordings,
    ) { segments, _ -> segments }
        .mapLatest {
            // Read the list again after the index is loaded: the flow's initial
            // pre-load emission is empty and would flash the empty state.
            repository.awaitReady()
            repository.segments.value.map { segment -> summary(segment) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val state: StateFlow<SegmentsState> = combine(
        summaries,
        _selectedId,
    ) { list, selectedId ->
        if (list == null) {
            SegmentsState.Loading
        } else {
            SegmentsState.Ready(
                summaries = list,
                // A segment deleted elsewhere must not stay selected, or the
                // sheet would keep offering to open something that is gone.
                selectedId = selectedId?.takeIf { id -> list.any { it.segment.id == id } },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SegmentsState.Loading)

    /** Selecting is not opening: it only highlights one segment on the map. */
    fun select(segmentId: String?) {
        _selectedId.value = segmentId
    }

    fun importGpx(onImported: (String) -> Unit, onError: (String) -> Unit, uri: Uri) {
        viewModelScope.launch {
            runCatching { repository.importGpx(uri) }.fold(
                onSuccess = { trace -> onImported(trace.id) },
                onFailure = { error ->
                    onError(error.message ?: "The selected file is not a readable GPX track")
                },
            )
        }
    }

    val retainedCamera: SegmentLibraryCamera?
        get() = SegmentLibraryCameraStore.camera

    fun onCameraSettled(camera: SegmentLibraryCamera) {
        SegmentLibraryCameraStore.camera = camera
    }

    private suspend fun summary(segment: StoredSegment): SegmentSummary {
        val results: SegmentResults? = repository.segmentResults(segment.id)
        val attempts = results?.attempts().orEmpty()
        val record = attempts.personalRecord()
        return SegmentSummary(
            segment = segment,
            attemptCount = attempts.size,
            uncertainCount = attempts.count { it.quality == StoredAttemptQuality.UNCERTAIN },
            notTimedCount = results?.rejections()?.size ?: 0,
            record = record,
            latest = attempts.latestAttempt(),
            fastestNotCounted = attempts.fastestUncountableAhead(record),
        )
    }
}

/** Detail state for one segment. */
sealed interface SegmentDetailState {
    data object Loading : SegmentDetailState

    /** The segment was deleted (here or elsewhere). */
    data object Gone : SegmentDetailState

    data class Ready(
        val segment: StoredSegment,
        val attempts: List<AttemptRow>,
        val notTimed: List<RejectionRow>,
        /** Fastest countable run, or null while none exists. */
        val record: StoredAttempt?,
        val latest: StoredAttempt?,
        /** Fastest run that does not count, when it leads [record]. */
        val fastestNotCounted: StoredAttempt?,
    ) : SegmentDetailState
}

/** An attempt plus the human-readable identity of the ride it came from. */
data class AttemptRow(
    val attempt: StoredAttempt,
    val rideTitle: String,
)

data class RejectionRow(
    val rideTitle: String,
    val startedAtMs: Long,
    val reason: String,
    val detail: String,
)

class SegmentDetailViewModel(
    application: Application,
    private val segmentId: String,
) : AndroidViewModel(application) {

    private val repository = RecordingRepository.getInstance(application)

    private val _state = MutableStateFlow<SegmentDetailState>(SegmentDetailState.Loading)
    val state: StateFlow<SegmentDetailState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val segment = repository.segment(segmentId)
            if (segment == null) {
                _state.value = SegmentDetailState.Gone
                return@launch
            }
            val results = repository.segmentResults(segmentId)
            val titles = repository.recordings.value.associate { recording ->
                recording.id to (recording.title?.takeIf { it.isNotBlank() } ?: "Untitled ride")
            }
            val attempts = results?.attempts().orEmpty()
            val record = attempts.personalRecord()
            _state.value = SegmentDetailState.Ready(
                segment = segment,
                attempts = attempts
                    .sortedBy { it.elapsedMs }
                    .map { attempt ->
                        AttemptRow(attempt, titles[attempt.recordingId] ?: "Deleted ride")
                    },
                notTimed = results?.rejections().orEmpty()
                    .sortedByDescending { it.startedAtMs }
                    .map { rejection ->
                        RejectionRow(
                            rideTitle = titles[rejection.recordingId] ?: "Deleted ride",
                            startedAtMs = rejection.startedAtMs,
                            reason = rejection.reason.label(),
                            detail = rejection.detail,
                        )
                    },
                record = record,
                latest = attempts.latestAttempt(),
                fastestNotCounted = attempts.fastestUncountableAhead(record),
            )
        }
    }

    fun rename(name: String) {
        viewModelScope.launch {
            repository.renameSegment(segmentId, name)
            refresh()
        }
    }

    fun delete() {
        viewModelScope.launch {
            repository.deleteSegment(segmentId)
            _state.value = SegmentDetailState.Gone
        }
    }
}
