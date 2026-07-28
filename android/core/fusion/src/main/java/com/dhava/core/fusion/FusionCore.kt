package com.dhava.core.fusion

import com.dhava.fusion.CanonicalActivity
import com.dhava.fusion.CanonicalTrackPoint
import com.dhava.fusion.GeoBounds
import com.dhava.fusion.RideAnalysis
import com.dhava.fusion.RecordingReplay
import com.dhava.fusion.SegmentDefinition
import com.dhava.fusion.SegmentMatchResult
import com.dhava.fusion.SegmentProposal
import com.dhava.fusion.algorithmVersion as ffiAlgorithmVersion
import com.dhava.fusion.analyzeRecording as ffiAnalyzeRecording
import com.dhava.fusion.buildSegment as ffiBuildSegment
import com.dhava.fusion.finalizeRecording as ffiFinalizeRecording
import com.dhava.fusion.matchSegment as ffiMatchSegment
import com.dhava.fusion.proposeSegment as ffiProposeSegment
import com.dhava.fusion.replayRecording as ffiReplayRecording
import com.dhava.fusion.segmentMatchVersion as ffiSegmentMatchVersion
import com.dhava.fusion.segmentSearchBounds as ffiSegmentSearchBounds

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
