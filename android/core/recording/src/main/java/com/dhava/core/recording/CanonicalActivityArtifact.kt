package com.dhava.core.recording

import com.dhava.fusion.AirtimeWindow
import com.dhava.fusion.CanonicalActivity
import com.dhava.fusion.DiagnosticTrackPoint
import com.dhava.fusion.ElevationSource
import com.dhava.fusion.QualitySummary
import com.dhava.fusion.RecordingReplay
import com.dhava.fusion.RideAnalysis
import com.dhava.fusion.TrackPoint
import kotlinx.serialization.Serializable

/**
 * Rebuildable, versioned result derived from one immutable raw recording.
 *
 * This is an on-device cache, never the source of truth. [sourceSizeBytes]
 * and [sourceLastModifiedMs] bind it to the raw `.jsonl.gz`; the store also
 * requires the current Rust [algorithmVersion] before reusing it.
 */
@Serializable
data class CanonicalActivityArtifact(
    val schemaVersion: Int,
    val algorithmVersion: String,
    val sourceSizeBytes: Long,
    val sourceLastModifiedMs: Long,
    val generatedAtMs: Long,
    val analysis: CanonicalAnalysis,
    val rawTrack: List<CanonicalPoint>,
    val finalizedTrack: List<CanonicalPoint>,
    /**
     * Rust-derived signal quality. Nullable only for decode compatibility;
     * the schema bump makes the store rebuild artifacts that predate it.
     */
    val quality: CanonicalQuality? = null,
)

@Serializable
enum class CanonicalElevationSource { BAROMETRIC, GPS_INTERPOLATED, NONE }

/** Mirror of Rust's `QualitySummary`; heuristic v0, for UI display only. */
@Serializable
data class CanonicalQuality(
    val elevationSource: CanonicalElevationSource,
    val baroSampleCount: Int,
    val gpsFixCount: Int,
    val gpsAcceptedCount: Int,
    val medianAccuracyM: Double? = null,
    val p90AccuracyM: Double? = null,
    val gpsGapCount: Int,
    val longestGapS: Double,
    val elevationUncertaintyM: Double? = null,
)

@Serializable
data class CanonicalPoint(
    val timestampMs: Long,
    val lat: Double,
    val lon: Double,
    val altitudeM: Double? = null,
    val accuracyM: Double? = null,
    val speedMps: Double? = null,
    val stationary: Boolean? = null,
    val sectionId: Int,
)

@Serializable
data class CanonicalAnalysis(
    val startedAtMs: Long,
    val endedAtMs: Long,
    val movingTimeS: Double,
    val distanceM: Double,
    val ascentM: Double,
    val descentM: Double,
    val maxSpeedMps: Double,
    val avgMovingSpeedMps: Double,
    val airtimeTotalMs: Long,
    val airtimeWindows: List<CanonicalAirtimeWindow>,
    val track: List<CanonicalAnalysisTrackPoint>,
    val gpsCount: Int,
    val imuCount: Int,
    val algorithmVersion: String,
)

@Serializable
data class CanonicalAirtimeWindow(
    val startMs: Long,
    val durationMs: Long,
    val landingPeakG: Double,
)

@Serializable
data class CanonicalAnalysisTrackPoint(
    val timestampMs: Long,
    val lat: Double,
    val lon: Double,
    val altitudeM: Double? = null,
    val speedMps: Double? = null,
)

internal data class CanonicalArtifactPayload(
    val algorithmVersion: String,
    val analysis: CanonicalAnalysis,
    val rawTrack: List<CanonicalPoint>,
    val finalizedTrack: List<CanonicalPoint>,
    val quality: CanonicalQuality,
)

internal fun CanonicalActivity.toArtifactPayload(): CanonicalArtifactPayload =
    CanonicalArtifactPayload(
        algorithmVersion = algorithmVersion,
        analysis = analysis.toCanonicalAnalysis(),
        rawTrack = rawTrack.map { point ->
            CanonicalPoint(
                timestampMs = point.timestampMs,
                lat = point.lat,
                lon = point.lon,
                altitudeM = point.altitudeM,
                accuracyM = point.accuracyM,
                speedMps = point.speedMps,
                stationary = point.stationary,
                sectionId = point.sectionId,
            )
        },
        finalizedTrack = finalizedTrack.map { point ->
            CanonicalPoint(
                timestampMs = point.timestampMs,
                lat = point.lat,
                lon = point.lon,
                altitudeM = point.altitudeM,
                accuracyM = point.accuracyM,
                speedMps = point.speedMps,
                stationary = point.stationary,
                sectionId = point.sectionId,
            )
        },
        quality = quality.toCanonicalQuality(),
    )

private fun QualitySummary.toCanonicalQuality(): CanonicalQuality = CanonicalQuality(
    elevationSource = when (elevationSource) {
        ElevationSource.BAROMETRIC -> CanonicalElevationSource.BAROMETRIC
        ElevationSource.GPS_INTERPOLATED -> CanonicalElevationSource.GPS_INTERPOLATED
        ElevationSource.NONE -> CanonicalElevationSource.NONE
    },
    baroSampleCount = baroSampleCount.toInt(),
    gpsFixCount = gpsFixCount.toInt(),
    gpsAcceptedCount = gpsAcceptedCount.toInt(),
    medianAccuracyM = medianAccuracyM,
    p90AccuracyM = p90AccuracyM,
    gpsGapCount = gpsGapCount.toInt(),
    longestGapS = longestGapS,
    elevationUncertaintyM = elevationUncertaintyM,
)

private fun RideAnalysis.toCanonicalAnalysis(): CanonicalAnalysis = CanonicalAnalysis(
    startedAtMs = startedAtMs,
    endedAtMs = endedAtMs,
    movingTimeS = movingTimeS,
    distanceM = distanceM,
    ascentM = ascentM,
    descentM = descentM,
    maxSpeedMps = maxSpeedMps,
    avgMovingSpeedMps = avgMovingSpeedMps,
    airtimeTotalMs = airtimeTotalMs,
    airtimeWindows = airtimeWindows.map { window ->
        CanonicalAirtimeWindow(
            startMs = window.startMs,
            durationMs = window.durationMs,
            landingPeakG = window.landingPeakG,
        )
    },
    track = track.map { point ->
        CanonicalAnalysisTrackPoint(
            timestampMs = point.timestampMs,
            lat = point.lat,
            lon = point.lon,
            altitudeM = point.altitudeM,
            speedMps = point.speedMps,
        )
    },
    gpsCount = gpsCount.toInt(),
    imuCount = imuCount.toInt(),
    algorithmVersion = algorithmVersion,
)

fun CanonicalActivityArtifact.toRideAnalysis(): RideAnalysis = RideAnalysis(
    startedAtMs = analysis.startedAtMs,
    endedAtMs = analysis.endedAtMs,
    movingTimeS = analysis.movingTimeS,
    distanceM = analysis.distanceM,
    ascentM = analysis.ascentM,
    descentM = analysis.descentM,
    maxSpeedMps = analysis.maxSpeedMps,
    avgMovingSpeedMps = analysis.avgMovingSpeedMps,
    airtimeTotalMs = analysis.airtimeTotalMs,
    airtimeWindows = analysis.airtimeWindows.map { window ->
        AirtimeWindow(window.startMs, window.durationMs, window.landingPeakG)
    },
    track = analysis.track.map { point ->
        TrackPoint(
            point.timestampMs,
            point.lat,
            point.lon,
            point.altitudeM,
            point.speedMps,
        )
    },
    gpsCount = analysis.gpsCount.toUInt(),
    imuCount = analysis.imuCount.toUInt(),
    algorithmVersion = analysis.algorithmVersion,
)

fun CanonicalActivityArtifact.toRecordingReplay(): RecordingReplay {
    fun CanonicalPoint.diagnostic(): DiagnosticTrackPoint = DiagnosticTrackPoint(
        timestampMs = timestampMs,
        lat = lat,
        lon = lon,
        accuracyM = accuracyM,
        stationary = stationary,
        sectionId = sectionId,
    )
    val finalized = finalizedTrack.map { it.diagnostic() }
    return RecordingReplay(
        rawTrack = rawTrack.map { it.diagnostic() },
        fusedTrack = emptyList(),
        finalizedTrack = finalized,
    )
}

fun CanonicalActivityArtifact.rawGpsPoints(): List<RecordLine.Gps> = rawTrack.map { point ->
    RecordLine.Gps(
        timestampMs = point.timestampMs,
        lat = point.lat,
        lon = point.lon,
        altitudeM = point.altitudeM,
        accuracyM = point.accuracyM,
        speedMps = point.speedMps,
    )
}
