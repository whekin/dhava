package com.dhava.core.recording

/** Live recording state exposed to the UI via [RecordingRepository.state]. */
sealed interface RecordingState {

    data object Idle : RecordingState

    data class Recording(
        val startedAtMs: Long,
        val elapsedMs: Long,
        val lastSpeedMps: Float?,
        val lastAccuracyM: Float?,
        val stationary: Boolean,
        val liveTrack: List<LiveTrackPoint>,
        val gpsCount: Int,
        val imuCount: Int,
        val baroCount: Int,
    ) : RecordingState

    data class Finished(val summary: RecordingSummary) : RecordingState
}

/** A display-rate point from Rust live fusion, never a raw GPS fix. */
data class LiveTrackPoint(
    val timestampMs: Long,
    val lat: Double,
    val lon: Double,
    val speedMps: Double,
)

/** Summary of a just-finished recording. */
data class RecordingSummary(
    val id: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val sizeBytes: Long,
    val gpsCount: Int,
    val imuCount: Int,
    val baroCount: Int,
)

/**
 * Transient per-recording upload progress, keyed by recording id. Persistent
 * outcomes (pending / uploaded / failed) live in [LocalRecording.status];
 * this flow only carries the in-flight detail on top of `pending_upload`.
 */
sealed interface UploadState {
    /** The worker is actively pushing bytes right now. */
    data object Uploading : UploadState

    /** Last attempt failed; WorkManager will retry with backoff. */
    data class Retrying(val message: String) : UploadState
}
