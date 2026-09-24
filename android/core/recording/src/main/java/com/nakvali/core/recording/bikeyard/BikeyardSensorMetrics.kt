package com.nakvali.core.recording.bikeyard

import com.nakvali.core.recording.TrackExportPoint
import com.nakvali.fusion.SensorMetricsEvidence
import com.nakvali.fusion.SensorTimeScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The manually reviewed pilot sends candidate timing only, never phone G or raw samples. */
@Serializable
internal data class BikeyardSensorMetricsDocument(
    val schema: String = "bikeyard.sensor-metrics",
    @SerialName("schema_version") val schemaVersion: Int = 1,
    val generator: Generator,
    @SerialName("generated_at") val generatedAt: Long,
    val sensor: Sensor,
    val events: List<Event>,
) {
    @Serializable
    data class Generator(
        val name: String = "nakvali",
        @SerialName("app_version") val appVersion: String? = null,
        @SerialName("algorithm_version") val algorithmVersion: String,
    )

    @Serializable
    data class Sensor(
        val type: String = "phone_accelerometer",
        @SerialName("sample_rate_hz") val sampleRateHz: Double? = null,
        val coverage: Double,
        val mounting: String,
    )

    @Serializable
    data class Event(
        val id: String,
        val kind: String = "airtime",
        val status: String = "candidate",
        @SerialName("start_ms") val startMs: Long,
        @SerialName("end_ms") val endMs: Long,
        val flags: List<String> = emptyList(),
    )
}

internal val bikeyardMetricsJson = Json { encodeDefaults = true; explicitNulls = false }

internal fun buildBikeyardMetricsDocument(
    evidence: SensorMetricsEvidence,
    mounting: BikeyardMounting,
    algorithmVersion: String,
    appVersion: String?,
    generatedAtMs: Long,
): BikeyardSensorMetricsDocument {
    val sampleRate = evidence.sampleRateHz
    require(evidence.coverage.isFinite() && evidence.coverage in 0.0..1.0)
    require(sampleRate == null || (sampleRate.isFinite() && sampleRate > 0 && sampleRate <= 10_000))
    require(evidence.events.size <= 2_000)
    require(evidence.events.isNotEmpty() || evidence.coverage >= 0.8) {
        "Sensor coverage is too low to report no airtime"
    }
    val events = evidence.events.mapIndexed { index, window ->
        require(window.durationMs in 50..10_000 && window.startMs > 0)
        BikeyardSensorMetricsDocument.Event(
            id = "a${index + 1}",
            startMs = window.startMs,
            endMs = Math.addExact(window.startMs, window.durationMs),
        )
    }
    return BikeyardSensorMetricsDocument(
        generator = BikeyardSensorMetricsDocument.Generator(
            appVersion = appVersion?.take(64),
            algorithmVersion = algorithmVersion.take(64),
        ),
        generatedAt = generatedAtMs,
        sensor = BikeyardSensorMetricsDocument.Sensor(
            sampleRateHz = sampleRate,
            coverage = evidence.coverage,
            mounting = mounting.wire,
        ),
        events = events,
    )
}

/** Reuse the exact TCX section/run breaks already chosen for this upload. */
internal fun List<TrackExportPoint>.sensorScopes(): List<SensorTimeScope> {
    val scopes = mutableListOf<SensorTimeScope>()
    var first: TrackExportPoint? = null
    var last: TrackExportPoint? = null
    fun flush() {
        val start = first
        val end = last
        if (start != null && end != null && end.timestampMs > start.timestampMs) {
            scopes += SensorTimeScope(start.timestampMs, end.timestampMs)
        }
    }
    for (point in this) {
        val previous = last
        if (previous != null && (previous.sectionId != point.sectionId ||
                previous.runId != point.runId || point.timestampMs - previous.timestampMs !in 1..3_000)) {
            flush()
            first = null
        }
        if (first == null) first = point
        last = point
    }
    flush()
    return scopes
}
