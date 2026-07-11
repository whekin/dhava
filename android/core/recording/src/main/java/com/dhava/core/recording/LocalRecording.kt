package com.dhava.core.recording

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    val uploaded: Boolean = false,
)
