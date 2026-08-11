package com.nakvali.core.fusion

import com.nakvali.fusion.CandidateDescent
import com.nakvali.fusion.CanonicalActivity
import com.nakvali.fusion.CanonicalTrackPoint
import com.nakvali.fusion.GeoBounds
import com.nakvali.fusion.LatLon
import com.nakvali.fusion.LiveTotals
import com.nakvali.fusion.RideAnalysis
import com.nakvali.fusion.RideProfile
import com.nakvali.fusion.RecordingReplay
import com.nakvali.fusion.SegmentBuildResult
import com.nakvali.fusion.SegmentDefinition
import com.nakvali.fusion.SegmentGateCenters
import com.nakvali.fusion.SegmentMatchResult
import com.nakvali.fusion.SegmentProposal
import com.nakvali.fusion.SelectionOverlap
import com.nakvali.fusion.algorithmVersion as ffiAlgorithmVersion
import com.nakvali.fusion.analyzeRecording as ffiAnalyzeRecording
import com.nakvali.fusion.buildSegment as ffiBuildSegment
import com.nakvali.fusion.buildSegmentContinuous as ffiBuildSegmentContinuous
import com.nakvali.fusion.buildSegmentContinuousWithGates as ffiBuildSegmentContinuousWithGates
import com.nakvali.fusion.finalizeRecording as ffiFinalizeRecording
import com.nakvali.fusion.liveTotalsFromRecording as ffiLiveTotalsFromRecording
import com.nakvali.fusion.matchSegment as ffiMatchSegment
import com.nakvali.fusion.nearestTrackPosition as ffiNearestTrackPosition
import com.nakvali.fusion.proposeDescents as ffiProposeDescents
import com.nakvali.fusion.proposeSegment as ffiProposeSegment
import com.nakvali.fusion.replayRecording as ffiReplayRecording
import com.nakvali.fusion.rideProfile as ffiRideProfile
import com.nakvali.fusion.segmentMatchVersion as ffiSegmentMatchVersion
import com.nakvali.fusion.segmentSearchBounds as ffiSegmentSearchBounds
import com.nakvali.fusion.selectionOverlap as ffiSelectionOverlap

/**
 * Thin facade over the Rust `fusion-core` crate (UniFFI bindings in
 * [com.nakvali.fusion]). All ride analysis math lives in Rust — never
 * reimplement timing/gate/airtime logic in Kotlin (architecture principle:
 * live and canonical results must not diverge).
 *
 * The underlying native library is loaded lazily on first use.
 */
object FusionCore {

    /**
     * Version tag of the canonical algorithms (e.g. `"gps-bounded-0.5"`).
     * Results are tagged with this value product-wide so they can be
     * recomputed on-device when algorithms improve.
     */
    val algorithmVersion: String by lazy { ffiAlgorithmVersion() }

    /**
     * Parses and analyzes a raw recording (`.jsonl.gz`) at [path].
     *
     * Blocking (file IO + number crunching over ~500 Hz IMU data) — call
     * from a background thread, e.g. `withContext(Dispatchers.IO)`.
     *
     * @param path absolute filesystem path to the recording file.
     * @throws com.nakvali.fusion.FusionException if the file cannot be read
     *   or contains no analyzable samples.
     */
    fun analyze(path: String): RideAnalysis = ffiAnalyzeRecording(path)

    /**
     * Produces the complete versioned post-ride artifact from one immutable
     * raw recording. Rust owns horizontal/vertical finalization and metrics;
     * Android may persist the returned value only as a rebuildable cache.
     */
    fun finalize(path: String): CanonicalActivity = ffiFinalizeRecording(path)

    /**
     * Distance and descent already recorded in the raw file at [path], for
     * restoring the live ride totals when an interrupted recording continues.
     *
     * Blocking (file IO) — call from a background thread. Cheaper than
     * [analyze]: no IMU airtime pass, the same accumulators.
     */
    fun liveTotals(path: String): LiveTotals = ffiLiveTotalsFromRecording(path)

    /**
     * Replays a raw recording through the exact live Rust pipeline and
     * returns raw/fused tracks for post-ride diagnostics.
     *
     * Blocking and intentionally separate from canonical [analyze]: this is
     * an engineering comparison surface, never the source of saved stats.
     */
    fun replay(path: String): RecordingReplay = ffiReplayRecording(path)

    /**
     * Version tag of the segment matching rules (e.g. `"gates-0.2"`), stored
     * on every attempt. Segment results computed by an older version are
     * recomputed rather than trusted.
     */
    val segmentMatchVersion: String by lazy { ffiSegmentMatchVersion() }

    /**
     * Position along [track] closest to [point], in continuous index units.
     *
     * Used when a gate marker is dragged on the map: the authored gate centre
     * stays where the rider dropped it, while the selection follows.
     */
    fun nearestTrackPosition(track: List<CanonicalTrackPoint>, point: LatLon): Double =
        ffiNearestTrackPosition(track, point)

    /**
     * Suggests the longest continuous descent of a finalized track as the
     * default start/finish selection for the segment editor.
     */
    fun proposeSegment(track: List<CanonicalTrackPoint>): SegmentProposal? =
        ffiProposeSegment(track)

    /**
     * Shortest segment developer mode may author, metres.
     *
     * The production floor lives in Rust and is the same one discovery uses.
     * A lower floor exists only to validate gate behaviour — entry and finish
     * haptics, live timing — on a stretch next to the house rather than on a
     * mountain, and Rust clamps whatever is asked for.
     */
    const val DEVELOPER_MIN_SEGMENT_LENGTH_M = 40.0

    /**
     * Builds a draft segment from a selection on one finalized track. Gate
     * widths and the corridor are derived in Rust from the source ride's own
     * horizontal accuracy.
     *
     * [minLengthM] lowers the minimum length for a developer-mode field test;
     * null uses the production floor. Rust clamps the request either way.
     *
     * @throws com.nakvali.fusion.SegmentException.InvalidSelection when the
     *   selection is too short, inverted, or crosses a pause/gap.
     */
    fun buildSegment(
        id: String,
        name: String,
        sourceRecordingId: String,
        track: List<CanonicalTrackPoint>,
        startIndex: Int,
        endIndex: Int,
        minLengthM: Double? = null,
    ): SegmentDefinition =
        ffiBuildSegment(id, name, sourceRecordingId, track, startIndex, endIndex, minLengthM)

    /**
     * Builds geometry v2 with start and finish at continuous positions along
     * the finalized polyline. Rust owns endpoint and timestamp interpolation.
     */
    fun buildSegmentContinuous(
        id: String,
        name: String,
        sourceRecordingId: String,
        track: List<CanonicalTrackPoint>,
        startPosition: Double,
        endPosition: Double,
        minLengthM: Double? = null,
    ): SegmentBuildResult = ffiBuildSegmentContinuous(
        id,
        name,
        sourceRecordingId,
        track,
        startPosition,
        endPosition,
        minLengthM,
    )

    /**
     * Builds geometry v3 while keeping authored timing gates independent from
     * the selected centerline endpoints. The centers are never snapped in
     * Kotlin; Rust validates and persists the exact map coordinates.
     */
    fun buildSegmentContinuousWithGates(
        id: String,
        name: String,
        sourceRecordingId: String,
        track: List<CanonicalTrackPoint>,
        startPosition: Double,
        endPosition: Double,
        startGateCenter: com.nakvali.fusion.LatLon,
        finishGateCenter: com.nakvali.fusion.LatLon,
        minLengthM: Double? = null,
    ): SegmentBuildResult = ffiBuildSegmentContinuousWithGates(
        id,
        name,
        sourceRecordingId,
        track,
        startPosition,
        endPosition,
        SegmentGateCenters(start = startGateCenter, finish = finishGateCenter),
        minLengthM,
    )

    /**
     * Elevation, gradient and pause structure of one finalized track, sampled
     * for the editor's chart. Every sample carries the continuous track
     * position it came from, so the editor maps a point on the chart back to a
     * gate without doing geometry of its own.
     */
    fun rideProfile(track: List<CanonicalTrackPoint>): RideProfile = ffiRideProfile(track)

    /**
     * Every descent in one ride worth offering as a ready-made selection,
     * longest first. Pauses, recording gaps and motorized evidence end a
     * candidate; a held stationary position and short trail link do not.
     */
    fun proposeDescents(track: List<CanonicalTrackPoint>): List<CandidateDescent> =
        ffiProposeDescents(track)

    /**
     * The existing segment a selection would duplicate, if any. Advisory only:
     * it warns a rider about to author the same trail twice and never merges
     * definitions or changes how attempts are timed.
     */
    fun selectionOverlap(
        existing: List<SegmentDefinition>,
        track: List<CanonicalTrackPoint>,
        startPosition: Double,
        endPosition: Double,
    ): SelectionOverlap? =
        ffiSelectionOverlap(existing, track, startPosition, endPosition)

    /** Corridor-padded bounds of a segment, for cheap candidate prefiltering. */
    fun segmentSearchBounds(definition: SegmentDefinition): GeoBounds? =
        ffiSegmentSearchBounds(definition)

    /**
     * Finds every attempt of [definition] in one recording's finalized track,
     * plus the gate pairs that were rejected and why. Gate timing, corridor,
     * coverage and uncertainty rules live in Rust only.
     */
    fun matchSegment(
        definition: SegmentDefinition,
        recordingId: String,
        track: List<CanonicalTrackPoint>,
    ): SegmentMatchResult = ffiMatchSegment(definition, recordingId, track)
}
