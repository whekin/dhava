package com.dhava.core.fusion

import com.dhava.fusion.CanonicalActivity
import com.dhava.fusion.RideAnalysis
import com.dhava.fusion.RecordingReplay
import com.dhava.fusion.algorithmVersion as ffiAlgorithmVersion
import com.dhava.fusion.analyzeRecording as ffiAnalyzeRecording
import com.dhava.fusion.finalizeRecording as ffiFinalizeRecording
import com.dhava.fusion.replayRecording as ffiReplayRecording

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
     * Version tag of the canonical algorithms (e.g. `"gps-bounded-0.2"`).
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
}
