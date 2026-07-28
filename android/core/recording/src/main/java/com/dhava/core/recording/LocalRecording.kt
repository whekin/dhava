package com.dhava.core.recording

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Lifecycle of an on-device recording:
 * recording started → file finalized → user saved (metadata attached,
 * upload queued) → uploaded.
 *
 * The status is persisted so it survives process death: a [RECORDING] entry
 * found at startup is repaired and recovered (or resumed by a restarted
 * service); a [RECORDED] entry that never got saved reappears in the list
 * with a "Finish saving" affordance; a [PENDING_UPLOAD] one is (re-)picked
 * up by WorkManager.
 */
@Serializable
enum class RecordingStatus {
    /**
     * Actively being written by the service. Persisted at Start so a hard
     * process kill can never make a recording invisible (2026-07 OnePlus
     * "o-kill" incident: the index entry used to be created only at Stop, so
     * a 13-minute ride survived on disk but never showed up in the app).
     */
    @SerialName("recording") RECORDING,

    /** File is finalized on disk but the user has not saved it yet. */
    @SerialName("recorded") RECORDED,

    /** Saved with metadata; upload is queued/retrying in WorkManager. */
    @SerialName("pending_upload") PENDING_UPLOAD,

    /** Fully uploaded (create + raw + finish all succeeded). */
    @SerialName("uploaded") UPLOADED,

    /** Upload retries exhausted; waits for a manual retry. */
    @SerialName("failed") FAILED,
}

@Serializable
enum class StravaExportStatus {
    @SerialName("queued") QUEUED,
    @SerialName("processing") PROCESSING,
    @SerialName("uploaded") UPLOADED,
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
    // Zero (and omitted from JSON) while the entry is still [RecordingStatus.RECORDING];
    // filled in at Stop or by crash recovery.
    @SerialName("ended_at_ms") val endedAtMs: Long = 0,
    @SerialName("size_bytes") val sizeBytes: Long = 0,
    val status: RecordingStatus = RecordingStatus.RECORDED,
    /**
     * True when this entry was rebuilt by crash recovery: the process died
     * mid-recording and the truncated file was repaired on the next launch
     * (see [RecordingRecovery]). Shown as "Recovered after crash" in the
     * list; omitted from JSON when false.
     */
    val recovered: Boolean = false,
    /**
     * Recovery found the raw file but could not decode one complete line.
     * The entry stays visible so the rider can keep/export the original bytes
     * instead of leaving an invisible orphan on disk.
     */
    @SerialName("recovery_failed") val recoveryFailed: Boolean = false,
    /**
     * Whether the interrupted tail may still be appended to. Null keeps old
     * recovered index entries backward-compatible (treated as allowed).
     * An explicit Finish writes false while preserving the recovery history.
     */
    @SerialName("continuation_allowed") val continuationAllowed: Boolean? = null,
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
    /** Independent one-tap Strava delivery state; never changes raw upload state. */
    @SerialName("strava_export_status") val stravaExportStatus: StravaExportStatus? = null,
    @SerialName("strava_upload_id") val stravaUploadId: Long? = null,
    @SerialName("strava_activity_id") val stravaActivityId: Long? = null,
    @SerialName("strava_error") val stravaError: String? = null,
)

/** An interrupted, unsaved recording that is safe to append to. */
fun LocalRecording.canContinueRecording(): Boolean =
    recovered &&
        !recoveryFailed &&
        continuationAllowed != false &&
        status == RecordingStatus.RECORDED &&
        savedAtMs == null

/** Whether an unsaved interruption still needs a decision on the Record screen. */
fun LocalRecording.needsRecoveryAttention(): Boolean =
    recovered &&
        status == RecordingStatus.RECORDED &&
        savedAtMs == null &&
        (recoveryFailed || continuationAllowed != false)

/**
 * Applies save-sheet/edit metadata to an entry. Blank title/description clear
 * the field; a null bike clears every bike field. Lifecycle fields (status,
 * savedAtMs, serverId, …) are deliberately untouched — editing metadata must
 * never change what happens to the recording.
 */
fun LocalRecording.withMetadata(title: String, description: String, bike: Bike?): LocalRecording =
    copy(
        title = title.trim().ifBlank { null },
        description = description.trim().ifBlank { null },
        bikeId = bike?.id,
        bikeName = bike?.name,
        bikeType = bike?.type,
    )
