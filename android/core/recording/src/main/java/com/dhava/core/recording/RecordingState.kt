package com.dhava.core.recording

/** Live recording state exposed to the UI via [RecordingRepository.state]. */
sealed interface RecordingState {

    data object Idle : RecordingState

    data class Recording(
        val startedAtMs: Long,
        val elapsedMs: Long,
        val lastSpeedMps: Float?,
        val lastAccuracyM: Float?,
        val gpsCount: Int,
        val imuCount: Int,
        val baroCount: Int,
    ) : RecordingState

    data class Finished(val summary: RecordingSummary) : RecordingState
}

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

/** Transient per-recording upload progress, keyed by recording id. */
sealed interface UploadState {
    data object Uploading : UploadState
    data class Failed(val message: String) : UploadState
}
