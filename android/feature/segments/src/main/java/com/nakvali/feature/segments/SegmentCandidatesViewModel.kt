package com.nakvali.feature.segments

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nakvali.core.fusion.FusionCore
import com.nakvali.core.map.SegmentLibraryCamera
import com.nakvali.core.map.SegmentMapPoint
import com.nakvali.core.recording.CanonicalPoint
import com.nakvali.core.recording.LocalRecording
import com.nakvali.core.recording.RecordingRepository
import com.nakvali.core.recording.toCanonicalTrack
import com.nakvali.core.recording.toDefinition
import com.nakvali.fusion.CandidateDescent
import com.nakvali.fusion.CanonicalTrackPoint
import com.nakvali.fusion.SegmentDefinition
import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface SegmentCandidatesState {
    data class Scanning(val scannedRides: Int, val totalRides: Int) : SegmentCandidatesState

    data class Ready(
        val candidates: List<SegmentCandidate>,
        val selectedId: String?,
        val scannedRides: Int,
        val coveredCount: Int,
        val qualityFilteredCount: Int,
        val unavailableRideCount: Int,
    ) : SegmentCandidatesState {
        val selected: SegmentCandidate?
            get() = candidates.firstOrNull { it.id == selectedId }
    }
}

/** One downhill trail proposal, represented by the best local seed ride found for it. */
data class SegmentCandidate(
    val id: String,
    val recordingId: String,
    val sourceTitle: String,
    val sourceStartedAtMs: Long,
    val startPosition: Double,
    val endPosition: Double,
    val lengthM: Double,
    val descentM: Double?,
    val gradientPercent: Double?,
    val p90AccuracyM: Double?,
    val supportCount: Int,
    val points: List<SegmentMapPoint>,
)

/**
 * Builds a local, derived view of every downhill candidate in every ride.
 *
 * Rust owns descent boundaries, directed overlap and temporary segment
 * geometry. This ViewModel only schedules cached canonical artifacts, applies
 * the product's visibility thresholds and retains the best seed per cluster.
 */
class SegmentCandidatesViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = RecordingRepository.getInstance(application)
    private val _state = MutableStateFlow<SegmentCandidatesState>(
        SegmentCandidatesState.Scanning(scannedRides = 0, totalRides = 0),
    )
    val state: StateFlow<SegmentCandidatesState> = _state.asStateFlow()

    var retainedCamera: SegmentLibraryCamera? = null
        private set

    init {
        scan()
        // The covered filter compares against the segments that existed when
        // the scan ran. Authoring one and coming back used to leave it sitting
        // in the list as a fresh proposal, so the scan is repeated whenever the
        // local segments change. `drop(1)` skips the snapshot the scan above
        // already used.
        viewModelScope.launch {
            repository.segments
                .map { segments -> segments.size }
                .distinctUntilChanged()
                .drop(1)
                .collect { scan() }
        }
    }

    fun scan() {
        viewModelScope.launch {
            repository.awaitReady()
            val recordings = repository.recordings.value
                .filter { it.endedAtMs > it.startedAtMs && !it.recoveryFailed }
                .sortedByDescending(LocalRecording::startedAtMs)
            val existing = repository.segments.value.map { it.toDefinition() }
            _state.value = SegmentCandidatesState.Scanning(0, recordings.size)

            val seeds = mutableListOf<CandidateSeed>()
            var covered = 0
            var qualityFiltered = 0
            var unavailable = 0
            recordings.forEachIndexed { index, recording ->
                val artifact = repository.canonicalActivity(recording.id)
                val finalized = artifact?.finalizedTrack.orEmpty()
                if (finalized.size < 2) {
                    unavailable += 1
                } else {
                    val fusionTrack = withContext(Dispatchers.Default) {
                        finalized.toCanonicalTrack()
                    }
                    val proposals = withContext(Dispatchers.Default) {
                        runCatching { FusionCore.proposeDescents(fusionTrack) }
                            .getOrDefault(emptyList())
                    }
                    proposals.forEachIndexed proposalLoop@{ candidateIndex, proposal ->
                        val selectedPoints = finalized.selectionPoints(
                            proposal.startPosition,
                            proposal.endPosition,
                        )
                        val p90AccuracyM = selectedPoints.p90AccuracyM()
                        if (p90AccuracyM != null && p90AccuracyM > MAX_VISIBLE_P90_ACCURACY_M) {
                            qualityFiltered += 1
                            return@proposalLoop
                        }
                        val overlap = withContext(Dispatchers.Default) {
                            runCatching {
                                FusionCore.selectionOverlap(
                                    existing = existing,
                                    track = fusionTrack,
                                    startPosition = proposal.startPosition,
                                    endPosition = proposal.endPosition,
                                )
                            }.getOrNull()
                        }
                        if (overlap != null && overlap.coverage >= COVERED_OVERLAP_FRACTION) {
                            covered += 1
                            return@proposalLoop
                        }
                        val definition = withContext(Dispatchers.Default) {
                            runCatching {
                                FusionCore.buildSegmentContinuous(
                                    id = "candidate-${recording.id}-$candidateIndex",
                                    name = "candidate",
                                    sourceRecordingId = recording.id,
                                    track = fusionTrack,
                                    startPosition = proposal.startPosition,
                                    endPosition = proposal.endPosition,
                                ).definition
                            }.getOrNull()
                        } ?: return@proposalLoop
                        seeds += CandidateSeed(
                            recording = recording,
                            proposal = proposal,
                            p90AccuracyM = p90AccuracyM,
                            selectedPoints = selectedPoints,
                            displayPoints = finalized.toContinuousMapSelection(
                                proposal.startPosition,
                                proposal.endPosition,
                            ),
                            fusionTrack = fusionTrack,
                            definition = definition,
                        )
                    }
                }
                _state.value = SegmentCandidatesState.Scanning(index + 1, recordings.size)
            }

            val candidates = withContext(Dispatchers.Default) {
                clusterSeeds(seeds).map(CandidateCluster::toUi).sortedWith(candidateOrder)
            }
            _state.value = SegmentCandidatesState.Ready(
                candidates = candidates,
                selectedId = candidates.firstOrNull()?.id,
                scannedRides = recordings.size,
                coveredCount = covered,
                qualityFilteredCount = qualityFiltered,
                unavailableRideCount = unavailable,
            )
        }
    }

    fun select(id: String?) {
        val ready = _state.value as? SegmentCandidatesState.Ready ?: return
        _state.value = ready.copy(selectedId = id)
    }

    fun onCameraSettled(camera: SegmentLibraryCamera) {
        retainedCamera = camera
    }
}

private data class CandidateSeed(
    val recording: LocalRecording,
    val proposal: CandidateDescent,
    val p90AccuracyM: Double?,
    val selectedPoints: List<CanonicalPoint>,
    val displayPoints: List<SegmentMapPoint>,
    val fusionTrack: List<CanonicalTrackPoint>,
    val definition: SegmentDefinition,
) {
    val id: String
        get() = "${recording.id}:${proposal.startPosition}:${proposal.endPosition}"
}

private data class CandidateCluster(
    var representative: CandidateSeed,
    var passCount: Int,
)

private fun clusterSeeds(seeds: List<CandidateSeed>): List<CandidateCluster> {
    val clusters = mutableListOf<CandidateCluster>()
    seeds.sortedWith(seedOrder).forEach { seed ->
        val cluster = clusters.firstOrNull { existing -> sameTrail(existing.representative, seed) }
        if (cluster == null) {
            clusters += CandidateCluster(seed, passCount = 1)
        } else {
            // Three laps inside one long recording are three independent
            // pieces of support, not one merely because they share a file.
            cluster.passCount += 1
            if (seedOrder.compare(seed, cluster.representative) < 0) {
                cluster.representative = seed
            }
        }
    }
    return clusters
}

/** Both directed selections must cover each other, so partial/extended trails stay separate. */
private fun sameTrail(a: CandidateSeed, b: CandidateSeed): Boolean {
    val bOnA = runCatching {
        FusionCore.selectionOverlap(
            existing = listOf(a.definition),
            track = b.fusionTrack,
            startPosition = b.proposal.startPosition,
            endPosition = b.proposal.endPosition,
        )
    }.getOrNull()?.coverage ?: return false
    if (bOnA < CLUSTER_OVERLAP_FRACTION) return false
    val aOnB = runCatching {
        FusionCore.selectionOverlap(
            existing = listOf(b.definition),
            track = a.fusionTrack,
            startPosition = a.proposal.startPosition,
            endPosition = a.proposal.endPosition,
        )
    }.getOrNull()?.coverage ?: return false
    return aOnB >= CLUSTER_OVERLAP_FRACTION
}

private fun CandidateCluster.toUi(): SegmentCandidate {
    val seed = representative
    val title = seed.recording.title?.takeIf(String::isNotBlank) ?: "Ride"
    return SegmentCandidate(
        id = seed.id,
        recordingId = seed.recording.id,
        sourceTitle = title,
        sourceStartedAtMs = seed.recording.startedAtMs,
        startPosition = seed.proposal.startPosition,
        endPosition = seed.proposal.endPosition,
        lengthM = seed.proposal.lengthM,
        descentM = seed.proposal.descentM,
        gradientPercent = seed.proposal.gradientPercent,
        p90AccuracyM = seed.p90AccuracyM,
        supportCount = passCount,
        points = seed.displayPoints,
    )
}

private val seedOrder = compareBy<CandidateSeed>(
    { it.p90AccuracyM ?: Double.MAX_VALUE },
    { -it.proposal.descentM.orZero() },
    { -it.proposal.lengthM },
    { -it.recording.startedAtMs },
)

internal val candidateOrder = compareByDescending<SegmentCandidate> { it.supportCount }
    .thenBy { it.p90AccuracyM ?: Double.MAX_VALUE }
    .thenByDescending { it.descentM.orZero() }
    .thenByDescending { it.lengthM }

private fun List<CanonicalPoint>.selectionPoints(start: Double, end: Double): List<CanonicalPoint> {
    if (isEmpty() || !start.isFinite() || !end.isFinite() || start >= end) return emptyList()
    val first = floor(start).toInt().coerceIn(indices)
    val last = ceil(end).toInt().coerceIn(indices)
    if (last <= first) return emptyList()
    return subList(first, last + 1)
}

private fun List<CanonicalPoint>.p90AccuracyM(): Double? {
    val values = mapNotNull { it.accuracyM?.takeIf(Double::isFinite) }.sorted()
    if (values.isEmpty()) return null
    val index = ceil(values.lastIndex * 0.9).toInt().coerceIn(values.indices)
    return values[index]
}

private fun Double?.orZero(): Double = this ?: 0.0

private const val MAX_VISIBLE_P90_ACCURACY_M = 25.0
private const val COVERED_OVERLAP_FRACTION = 0.8
private const val CLUSTER_OVERLAP_FRACTION = 0.8
