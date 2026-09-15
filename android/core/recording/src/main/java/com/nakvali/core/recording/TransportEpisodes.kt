package com.nakvali.core.recording

import com.nakvali.fusion.TransportEpisode
import com.nakvali.fusion.TransportCorrection
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Null in LocalRecording means automatic; an empty list means no transport. */
@Serializable
data class StoredTransportEpisode(
    @SerialName("started_at_ms") val startedAtMs: Long,
    @SerialName("ended_at_ms") val endedAtMs: Long,
)

internal fun StoredTransportEpisode.toFusion() = TransportEpisode(startedAtMs, endedAtMs)
internal fun TransportEpisode.toStored() = StoredTransportEpisode(startedAtMs, endedAtMs)

internal fun CanonicalActivityArtifact.withTransportCorrection(correction: TransportCorrection) = copy(
    transportEpisodes = correction.episodes.map { it.toStored() },
    finalizedTrack = correction.track.map { point ->
        CanonicalPoint(
            timestampMs = point.timestampMs, lat = point.lat, lon = point.lon,
            altitudeM = point.altitudeM, accuracyM = point.accuracyM, speedMps = point.speedMps,
            stationary = point.stationary, sectionId = point.sectionId,
            activityState = point.activityState.toCanonicalActivityState(),
            activityConfidence = point.activityConfidence,
        )
    },
    ride = correction.ride.let {
        CanonicalRideTotals(it.distanceM, it.movingTimeS, it.ascentM, it.descentM,
            it.maxSpeedMps, it.avgMovingSpeedMps, it.transportDistanceM, it.transportTimeS)
    },
)
