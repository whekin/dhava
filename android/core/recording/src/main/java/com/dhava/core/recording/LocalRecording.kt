package com.dhava.core.recording

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Lifecycle of an on-device recording:
 * file finalized → user saved (metadata attached, upload queued) → uploaded.
 *
 * The status is persisted so it survives process death: a [RECORDED] entry
 * that never got saved reappears in the list with a "Finish saving"
 * affordance; a [PENDING_UPLOAD] one is (re-)picked up by WorkManager.
 */
@Serializable
enum class RecordingStatus {
    /** File is finalized on disk but the user has not saved it yet. */
    @SerialName("recorded") RECORDED,

    /** Saved with metadata; upload is queued/retrying in WorkManager. */
    @SerialName("pending_upload") PENDING_UPLOAD,

    /** Fully uploaded (create + raw + finish all succeeded). */
    @SerialName("uploaded") UPLOADED,

    /** Upload retries exhausted; waits for a manual retry. */
    @SerialName("failed") FAILED,
}

/**
 * One entry of the on-device recording index (`recordings.json`).
 *
 * A flat JSON file is deliberately primitive: Phase 1 only needs a handful of
 * recordings per device. Migrate to Room once we need queries, sync state
 * machines, or per-segment metadata.
 */
@Serializable
data class LocalRecording(
    val id: String,
    @SerialName("started_at_ms") val startedAtMs: Long,
    @SerialName("ended_at_ms") val endedAtMs: Long,
    @SerialName("size_bytes") val sizeBytes: Long,
    val status: RecordingStatus = RecordingStatus.RECORDED,
    // Save-time metadata, attached by the save sheet.
    val title: String? = null,
    val description: String? = null,
    @SerialName("bike_id") val bikeId: String? = null,
    @SerialName("bike_name") val bikeName: String? = null,
    @SerialName("bike_type") val bikeType: BikeType? = null,
    @SerialName("saved_at_ms") val savedAtMs: Long? = null,
    /**
     * Server-assigned activity id, persisted as soon as `create` succeeds so
     * a retried upload skips create and reuses it (idempotency across
     * WorkManager retries: create runs at most once per recording).
     */
    @SerialName("server_id") val serverId: String? = null,
)
