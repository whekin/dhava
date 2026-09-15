package com.nakvali.core.recording

import com.nakvali.fusion.BoundedRide
import com.nakvali.fusion.RideBounds
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The span the rider calls the ride, as absolute recording timestamps.
 *
 * Null in LocalRecording means the whole recording is the ride. Like a transport
 * override this is an annotation, never a truncation: the raw file and the
 * finalized track keep every sample, so a trim can always be widened or removed.
 */
@Serializable
data class StoredRideBounds(
    @SerialName("started_at_ms") val startedAtMs: Long,
    @SerialName("ended_at_ms") val endedAtMs: Long,
)

internal fun StoredRideBounds.toFusion() = RideBounds(startedAtMs, endedAtMs)
internal fun RideBounds.toStored() = StoredRideBounds(startedAtMs, endedAtMs)

/**
 * Replace the headline totals with the ones the bounds produce.
 *
 * The track itself is untouched on purpose. Every index correspondence in the
 * app — profile positions, segment attempt slices, the odometer — assumes the
 * finalized track keeps its length, exactly as a transport correction does.
 */
internal fun CanonicalActivityArtifact.withRideBounds(
    bounds: StoredRideBounds,
    bounded: BoundedRide,
) = copy(
    rideBounds = bounds,
    ride = bounded.ride.let {
        CanonicalRideTotals(it.distanceM, it.movingTimeS, it.ascentM, it.descentM,
            it.maxSpeedMps, it.avgMovingSpeedMps, it.transportDistanceM, it.transportTimeS)
    },
)

/**
 * Layer the rider's annotations onto the automatic artifact, in Rust.
 *
 * Transport comes first because it decides what each point *is*; bounds then
 * decide which points *count*. The two never fight — relabelling and accounting
 * are independent — but fixing the order here keeps one answer for both.
 */
internal fun CanonicalActivityArtifact.applyCorrections(
    episodes: List<StoredTransportEpisode>?,
    bounds: StoredRideBounds?,
): CanonicalActivityArtifact {
    val corrected = episodes?.let {
        withTransportCorrection(com.nakvali.fusion.correctTransport(
            finalizedTrack.toCanonicalTrack(), it.map { episode -> episode.toFusion() },
            analysis.startedAtMs, analysis.endedAtMs, elevationSource,
        ))
    } ?: this
    return bounds?.let {
        corrected.withRideBounds(it, com.nakvali.fusion.rideWithin(
            corrected.finalizedTrack.toCanonicalTrack(), it.toFusion(),
            analysis.startedAtMs, analysis.endedAtMs, corrected.elevationSource,
        ))
    } ?: corrected
}

/**
 * Identifies one corrected projection. Both annotations share a single cached
 * slot, so both belong in the key — a key that tracked only one of them would
 * keep serving the old projection after the other was edited.
 */
internal data class CorrectionCacheKey(
    val id: String, val sourceSize: Long, val sourceModified: Long, val algorithm: String,
    val episodes: List<StoredTransportEpisode>?, val bounds: StoredRideBounds?,
)
