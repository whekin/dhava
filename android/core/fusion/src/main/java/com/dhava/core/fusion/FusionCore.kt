package com.dhava.core.fusion

import com.dhava.fusion.CandidateDescent
import com.dhava.fusion.CanonicalActivity
import com.dhava.fusion.CanonicalTrackPoint
import com.dhava.fusion.GeoBounds
import com.dhava.fusion.RideAnalysis
import com.dhava.fusion.RideProfile
import com.dhava.fusion.RecordingReplay
import com.dhava.fusion.SegmentBuildResult
import com.dhava.fusion.SegmentDefinition
import com.dhava.fusion.SegmentGateCenters
import com.dhava.fusion.SegmentMatchResult
import com.dhava.fusion.SegmentProposal
import com.dhava.fusion.SelectionOverlap
import com.dhava.fusion.algorithmVersion as ffiAlgorithmVersion
import com.dhava.fusion.analyzeRecording as ffiAnalyzeRecording
import com.dhava.fusion.buildSegment as ffiBuildSegment
import com.dhava.fusion.buildSegmentContinuous as ffiBuildSegmentContinuous
import com.dhava.fusion.buildSegmentContinuousWithGates as ffiBuildSegmentContinuousWithGates
import com.dhava.fusion.finalizeRecording as ffiFinalizeRecording
import com.dhava.fusion.matchSegment as ffiMatchSegment
import com.dhava.fusion.proposeDescents as ffiProposeDescents
import com.dhava.fusion.proposeSegment as ffiProposeSegment
import com.dhava.fusion.replayRecording as ffiReplayRecording
import com.dhava.fusion.rideProfile as ffiRideProfile
import com.dhava.fusion.segmentMatchVersion as ffiSegmentMatchVersion
import com.dhava.fusion.segmentSearchBounds as ffiSegmentSearchBounds
import com.dhava.fusion.selectionOverlap as ffiSelectionOverlap

/**
 * Thin facade over the Rust `fusion-core` crate (UniFFI bindings in
 * [com.dhava.fusion]). All ride analysis math lives in Rust — never
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
     * @throws com.dhava.fusion.FusionException if the file cannot be read
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
     * Suggests the longest continuous descent of a finalized track as the
     * default start/finish selection for the segment editor.
     */
    fun proposeSegment(track: List<CanonicalTrackPoint>): SegmentProposal? =
        ffiProposeSegment(track)

    /**
     * Builds a draft segment from a selection on one finalized track. Gate
     * widths and the corridor are derived in Rust from the source ride's own
     * horizontal accuracy.
     *
     * @throws com.dhava.fusion.SegmentException.InvalidSelection when the
     *   selection is too short, inverted, or crosses a pause/gap.
     */
    fun buildSegment(
        id: String,
        name: String,
        sourceRecordingId: String,
        track: List<CanonicalTrackPoint>,
        startIndex: Int,
        endIndex: Int,
    ): SegmentDefinition =
        ffiBuildSegment(id, name, sourceRecordingId, track, startIndex, endIndex)

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
    ): SegmentBuildResult = ffiBuildSegmentContinuous(
        id,
        name,
        sourceRecordingId,
        track,
        startPosition,
        endPosition,
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
        startGateCenter: com.dhava.fusion.LatLon,
        finishGateCenter: com.dhava.fusion.LatLon,
    ): SegmentBuildResult = ffiBuildSegmentContinuousWithGates(
        id,
        name,
        sourceRecordingId,
        track,
        startPosition,
        endPosition,
        SegmentGateCenters(start = startGateCenter, finish = finishGateCenter),
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
     * longest first. Stops, pauses, recording gaps and motorized evidence end a
     * candidate; a short link inside one trail does not.
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
