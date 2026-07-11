package com.dhava.core.recording

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One line of the raw recording file, per `proto/raw-recording-format.md` v1.
 *
 * The `type` discriminator and every field name are a hard contract shared
 * with the serde types in `fusion/crates/fusion-core` — do not rename.
 */
@Serializable
sealed interface RecordLine {

    @Serializable
    @SerialName("meta")
    data class Meta(
        val version: Int = 1,
        @SerialName("activity_id") val activityId: String,
        val device: String,
        val os: String,
        @SerialName("app_version") val appVersion: String,
        @SerialName("started_at_ms") val startedAtMs: Long,
    ) : RecordLine

    @Serializable
    @SerialName("gps")
    data class Gps(
        @SerialName("timestamp_ms") val timestampMs: Long,
        val lat: Double,
        val lon: Double,
        @SerialName("altitude_m") val altitudeM: Double? = null,
        @SerialName("accuracy_m") val accuracyM: Double? = null,
        @SerialName("speed_mps") val speedMps: Double? = null,
        @SerialName("bearing_deg") val bearingDeg: Double? = null,
    ) : RecordLine

    @Serializable
    @SerialName("imu")
    data class Imu(
        @SerialName("timestamp_ms") val timestampMs: Long,
        /** m/s², raw (gravity included). */
        val accel: List<Float>,
        /** rad/s. */
        val gyro: List<Float>,
        /** µT; null when the device has no magnetometer. */
        val mag: List<Float>? = null,
    ) : RecordLine

    @Serializable
    @SerialName("baro")
    data class Baro(
        @SerialName("timestamp_ms") val timestampMs: Long,
        @SerialName("pressure_hpa") val pressureHpa: Float,
    ) : RecordLine
}

/** JSON codec for recording lines: `type` discriminator, optional fields omitted. */
internal val RecordLineJson = Json {
    classDiscriminator = "type"
    encodeDefaults = true
    explicitNulls = false
}
