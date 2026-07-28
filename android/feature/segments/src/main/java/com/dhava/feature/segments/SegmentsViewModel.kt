package com.dhava.feature.segments

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dhava.core.recording.RecordingRepository
import com.dhava.core.recording.SegmentResults
import com.dhava.core.recording.StoredAttempt
import com.dhava.core.recording.StoredAttemptQuality
import com.dhava.core.recording.StoredSegment
import com.dhava.core.recording.attempts
import com.dhava.core.recording.bestAttempt
import com.dhava.core.recording.latestAttempt
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
    val rejectedCount: Int,
    val best: StoredAttempt?,
    val latest: StoredAttempt?,
)

sealed interface SegmentsState {
    data object Loading : SegmentsState
    data class Ready(val summaries: List<SegmentSummary>) : SegmentsState
}

/**
 * Segment list with lazily recomputed results.
 *
 * Matching runs on every emission of the segment or recording list, but the
 * matcher only recomputes rides whose raw fingerprint, the algorithm version,
 * the match version or the segment geometry changed — so the common case after
 * one new ride is one match, not a full rescan.
 */
class SegmentsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = RecordingRepository.getInstance(application)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<SegmentsState> = combine(
        repository.segments,
        repository.recordings,
    ) { segments, _ -> segments }
        .mapLatest {
            // Read the list again after the index is loaded: the flow's initial
            // pre-load emission is empty and would flash the empty state.
            repository.awaitReady()
            SegmentsState.Ready(repository.segments.value.map { segment -> summary(segment) })
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SegmentsState.Loading)

    private suspend fun summary(segment: StoredSegment): SegmentSummary {
        val results: SegmentResults? = repository.segmentResults(segment.id)
        val attempts = results?.attempts().orEmpty()
        return SegmentSummary(
            segment = segment,
            attemptCount = attempts.size,
            uncertainCount = attempts.count { it.quality == StoredAttemptQuality.UNCERTAIN },
            rejectedCount = results?.rejections()?.size ?: 0,
            best = attempts.bestAttempt(),
            latest = attempts.latestAttempt(),
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
        val rejected: List<RejectionRow>,
        val best: StoredAttempt?,
        val latest: StoredAttempt?,
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
            _state.value = SegmentDetailState.Ready(
                segment = segment,
                attempts = attempts
                    .sortedBy { it.elapsedMs }
                    .map { attempt ->
                        AttemptRow(attempt, titles[attempt.recordingId] ?: "Deleted ride")
                    },
                rejected = results?.rejections().orEmpty()
                    .sortedByDescending { it.startedAtMs }
                    .map { rejection ->
                        RejectionRow(
                            rideTitle = titles[rejection.recordingId] ?: "Deleted ride",
                            startedAtMs = rejection.startedAtMs,
                            reason = rejection.reason.label(),
                            detail = rejection.detail,
                        )
                    },
                best = attempts.bestAttempt(),
                latest = attempts.latestAttempt(),
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
