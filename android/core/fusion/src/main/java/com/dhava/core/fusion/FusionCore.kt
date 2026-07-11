package com.dhava.core.fusion

import com.dhava.fusion.RideAnalysis
import com.dhava.fusion.algorithmVersion as ffiAlgorithmVersion
import com.dhava.fusion.analyzeRecording as ffiAnalyzeRecording

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
     * Version tag of the analysis algorithms (e.g. `"gps-naive-0.1"`).
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
}
