package com.nakvali.core.recording

/** Live recording state exposed to the UI via [RecordingRepository.state]. */
sealed interface RecordingState {

    data object Idle : RecordingState

    data class Preparing(
        val elapsedMs: Long,
        val gpsReady: Boolean,
        val imuReady: Boolean,
        val lastAccuracyM: Float?,
    ) : RecordingState

    data class Recording(
        val startedAtMs: Long,
        val elapsedMs: Long,
        val lastSpeedMps: Float?,
        val lastAccuracyM: Float?,
        /** Provisional ride distance from live Rust fusion, metres. */
        val distanceM: Double = 0.0,
        /** Provisional accumulated descent from live Rust fusion, metres. */
        val descentM: Double = 0.0,
        val stationary: Boolean,
        val liveTrack: List<LiveTrackPoint>,
        val gpsCount: Int,
        val imuCount: Int,
        val baroCount: Int,
        val paused: Boolean = false,
        /**
         * Reduced sampling because live fusion reports a vehicle. A power
         * decision only — the ride's transport spans are classified after
         * Finish from the raw file.
         */
        val powerSaving: Boolean = false,
        /** Segment the rider is inside right now, if any. */
        val activeSegment: ActiveSegmentRun? = null,
        /** Runs completed during this ride, newest first. */
        val segmentRuns: List<LiveSegmentRun> = emptyList(),
    ) : RecordingState

    data class Finished(val summary: RecordingSummary) : RecordingState
}

/** A segment run in progress, timed from the start-gate crossing. */
data class ActiveSegmentRun(
    val segmentId: String,
    val name: String,
    val startedAtMs: Long,
)

/**
 * A segment run completed during this ride.
 *
 * Provisional by construction: live timing is causal, while the canonical
 * result runs the bounded post-pass after Finish and replaces this. Screens
 * must say so rather than presenting these as final times.
 */
data class LiveSegmentRun(
    val segmentId: String,
    val name: String,
    val finishedAtMs: Long,
    val elapsedMs: Long,
    /** Negative when this run beat the record; null when there was none. */
    val deltaMs: Long?,
    val personalRecord: Boolean,
)

/** A display-rate point from Rust live fusion, never a raw GPS fix. */
data class LiveTrackPoint(
    val timestampMs: Long,
    val lat: Double,
    val lon: Double,
    val speedMps: Double,
    val stationary: Boolean = false,
    val sectionId: Int = 0,
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
