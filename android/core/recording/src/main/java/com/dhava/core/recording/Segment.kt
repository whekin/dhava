package com.dhava.core.recording

import com.dhava.fusion.AttemptFlag
import com.dhava.fusion.AttemptQuality
import com.dhava.fusion.AttemptRejection
import com.dhava.fusion.CanonicalTrackPoint
import com.dhava.fusion.LatLon
import com.dhava.fusion.RejectedAttempt
import com.dhava.fusion.SegmentAttempt
import com.dhava.fusion.SegmentDefinition
import com.dhava.fusion.SegmentElevationPoint
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Segment persistence is deliberately split in two.
 *
 * [StoredSegment] is authored by the rider: it is durable input, never
 * recomputed and never silently changed. [SegmentResults] is pure derived
 * data — a cache keyed by the Rust algorithm version, the segment matching
 * version and the segment geometry version, rebuilt whenever any of those or
 * the underlying raw recording changes.
 */
@Serializable
data class StoredSegment(
    val id: String,
    val name: String,
    @SerialName("source_recording_id") val sourceRecordingId: String,
    @SerialName("geometry_version") val geometryVersion: Int,
    val centerline: List<StoredLatLon>,
    @SerialName("gate_half_width_m") val gateHalfWidthM: Double,
    @SerialName("corridor_m") val corridorM: Double,
    @SerialName("length_m") val lengthM: Double,
    /** Nullable/defaulted for segments authored before elevation profiles existed. */
    @SerialName("ascent_m") val ascentM: Double? = null,
    @SerialName("descent_m") val descentM: Double? = null,
    @SerialName("elevation_profile")
    val elevationProfile: List<StoredElevationPoint> = emptyList(),
    /**
     * False while the geometry is a single-ride draft. A draft is not
     * authoritative geometry and never corrects GPS.
     */
    val trusted: Boolean = false,
    @SerialName("created_at_ms") val createdAtMs: Long,
)

@Serializable
data class StoredLatLon(val lat: Double, val lon: Double)

@Serializable
data class StoredElevationPoint(
    @SerialName("distance_m") val distanceM: Double,
    @SerialName("altitude_m") val altitudeM: Double,
)

/** Per-recording match cache for one segment. */
@Serializable
data class SegmentResults(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("algorithm_version") val algorithmVersion: String,
    @SerialName("match_version") val matchVersion: String,
    @SerialName("geometry_version") val geometryVersion: Int,
    @SerialName("generated_at_ms") val generatedAtMs: Long,
    val rides: List<SegmentRideMatch> = emptyList(),
)

/**
 * What one recording contributed to one segment. Bound to the raw file by
 * size and modification time, exactly like [CanonicalActivityArtifact], so a
 * continued or repaired recording is re-matched instead of trusted.
 */
@Serializable
data class SegmentRideMatch(
    @SerialName("recording_id") val recordingId: String,
    @SerialName("source_size_bytes") val sourceSizeBytes: Long,
    @SerialName("source_last_modified_ms") val sourceLastModifiedMs: Long,
    val attempts: List<StoredAttempt> = emptyList(),
    val rejected: List<StoredRejection> = emptyList(),
)

@Serializable
data class StoredAttempt(
    @SerialName("recording_id") val recordingId: String,
    @SerialName("started_at_ms") val startedAtMs: Long,
    @SerialName("finished_at_ms") val finishedAtMs: Long,
    @SerialName("elapsed_ms") val elapsedMs: Long,
    @SerialName("uncertainty_ms") val uncertaintyMs: Long,
    @SerialName("section_id") val sectionId: Int,
    @SerialName("start_index") val startIndex: Int,
    @SerialName("end_index") val endIndex: Int,
    @SerialName("max_deviation_m") val maxDeviationM: Double,
    @SerialName("median_accuracy_m") val medianAccuracyM: Double? = null,
    val quality: StoredAttemptQuality,
    val flags: List<StoredAttemptFlag> = emptyList(),
    @SerialName("matched_geometry_version") val matchedGeometryVersion: Int,
    @SerialName("match_version") val matchVersion: String,
)

@Serializable
data class StoredRejection(
    @SerialName("recording_id") val recordingId: String,
    @SerialName("started_at_ms") val startedAtMs: Long,
    val reason: StoredRejectionReason,
    val detail: String,
)

@Serializable
enum class StoredAttemptQuality {
    @SerialName("good") GOOD,

    @SerialName("uncertain") UNCERTAIN,
}

@Serializable
enum class StoredAttemptFlag {
    @SerialName("defining_ride") DEFINING_RIDE,

    @SerialName("low_gps_quality") LOW_GPS_QUALITY,

    @SerialName("likely_motorized") LIKELY_MOTORIZED,

    @SerialName("high_uncertainty") HIGH_UNCERTAINTY,
}

@Serializable
enum class StoredRejectionReason {
    @SerialName("no_finish") NO_FINISH,

    @SerialName("paused_inside") PAUSED_INSIDE,

    @SerialName("gap_inside") GAP_INSIDE,

    @SerialName("off_corridor") OFF_CORRIDOR,

    @SerialName("backtracked") BACKTRACKED,

    @SerialName("incomplete") INCOMPLETE,
}

/**
 * Cached geographic extent of one recording's raw GPS fixes, used to skip
 * recordings that cannot possibly touch a segment before their canonical
 * artifact is built or decompressed. A null [bounds] means the recording has
 * no usable GPS at all.
 */
@Serializable
data class TrackBoundsEntry(
    @SerialName("recording_id") val recordingId: String,
    @SerialName("source_size_bytes") val sourceSizeBytes: Long,
    @SerialName("source_last_modified_ms") val sourceLastModifiedMs: Long,
    val bounds: StoredBounds? = null,
)

@Serializable
data class StoredBounds(
    @SerialName("min_lat") val minLat: Double,
    @SerialName("min_lon") val minLon: Double,
    @SerialName("max_lat") val maxLat: Double,
    @SerialName("max_lon") val maxLon: Double,
) {
    fun intersects(other: StoredBounds): Boolean =
        minLat <= other.maxLat &&
            maxLat >= other.minLat &&
            minLon <= other.maxLon &&
            maxLon >= other.minLon
}

fun StoredSegment.toDefinition(): SegmentDefinition = SegmentDefinition(
    id = id,
    name = name,
    sourceRecordingId = sourceRecordingId,
    geometryVersion = geometryVersion,
    centerline = centerline.map { LatLon(it.lat, it.lon) },
    gateHalfWidthM = gateHalfWidthM,
    corridorM = corridorM,
    lengthM = lengthM,
    ascentM = ascentM,
    descentM = descentM,
    elevationProfile = elevationProfile.map {
        SegmentElevationPoint(
            distanceM = it.distanceM,
            altitudeM = it.altitudeM,
        )
    },
    trusted = trusted,
)

fun SegmentDefinition.toStored(createdAtMs: Long): StoredSegment = StoredSegment(
    id = id,
    name = name,
    sourceRecordingId = sourceRecordingId,
    geometryVersion = geometryVersion,
    centerline = centerline.map { StoredLatLon(it.lat, it.lon) },
    gateHalfWidthM = gateHalfWidthM,
    corridorM = corridorM,
    lengthM = lengthM,
    ascentM = ascentM,
    descentM = descentM,
    elevationProfile = elevationProfile.map {
        StoredElevationPoint(
            distanceM = it.distanceM,
            altitudeM = it.altitudeM,
        )
    },
    trusted = trusted,
    createdAtMs = createdAtMs,
)

internal fun SegmentAttempt.toStored(): StoredAttempt = StoredAttempt(
    recordingId = recordingId,
    startedAtMs = startedAtMs,
    finishedAtMs = finishedAtMs,
    elapsedMs = elapsedMs,
    uncertaintyMs = uncertaintyMs,
    sectionId = sectionId,
    startIndex = startIndex,
    endIndex = endIndex,
    maxDeviationM = maxDeviationM,
    medianAccuracyM = medianAccuracyM,
    quality = when (quality) {
        AttemptQuality.GOOD -> StoredAttemptQuality.GOOD
        AttemptQuality.UNCERTAIN -> StoredAttemptQuality.UNCERTAIN
    },
    flags = flags.map { flag ->
        when (flag) {
            AttemptFlag.DEFINING_RIDE -> StoredAttemptFlag.DEFINING_RIDE
            AttemptFlag.LOW_GPS_QUALITY -> StoredAttemptFlag.LOW_GPS_QUALITY
            AttemptFlag.LIKELY_MOTORIZED -> StoredAttemptFlag.LIKELY_MOTORIZED
            AttemptFlag.HIGH_UNCERTAINTY -> StoredAttemptFlag.HIGH_UNCERTAINTY
        }
    },
    matchedGeometryVersion = matchedGeometryVersion,
    matchVersion = matchVersion,
)

internal fun RejectedAttempt.toStored(): StoredRejection = StoredRejection(
    recordingId = recordingId,
    startedAtMs = startedAtMs,
    reason = when (reason) {
        AttemptRejection.NO_FINISH -> StoredRejectionReason.NO_FINISH
        AttemptRejection.PAUSED_INSIDE -> StoredRejectionReason.PAUSED_INSIDE
        AttemptRejection.GAP_INSIDE -> StoredRejectionReason.GAP_INSIDE
        AttemptRejection.OFF_CORRIDOR -> StoredRejectionReason.OFF_CORRIDOR
        AttemptRejection.BACKTRACKED -> StoredRejectionReason.BACKTRACKED
        AttemptRejection.INCOMPLETE -> StoredRejectionReason.INCOMPLETE
    },
    detail = detail,
)

/** Converts persisted canonical points back into the Rust matching input. */
fun List<CanonicalPoint>.toCanonicalTrack(): List<CanonicalTrackPoint> = map { point ->
    CanonicalTrackPoint(
        timestampMs = point.timestampMs,
        lat = point.lat,
        lon = point.lon,
        altitudeM = point.altitudeM,
        accuracyM = point.accuracyM,
        speedMps = point.speedMps,
        stationary = point.stationary,
        sectionId = point.sectionId,
        activityState = point.activityState.toFusionActivityState(),
        activityConfidence = point.activityConfidence,
    )
}

/**
 * Geographic extent of raw GPS fixes, used as the segment candidate
 * prefilter. Raw fixes are a deliberately conservative superset of the
 * finalized geometry, so this can add candidates but never drop a real one.
 */
internal fun List<RecordLine.Gps>.gpsBoundsOrNull(): StoredBounds? {
    if (isEmpty()) return null
    var minLat = Double.MAX_VALUE
    var minLon = Double.MAX_VALUE
    var maxLat = -Double.MAX_VALUE
    var maxLon = -Double.MAX_VALUE
    forEach { fix ->
        minLat = minOf(minLat, fix.lat)
        minLon = minOf(minLon, fix.lon)
        maxLat = maxOf(maxLat, fix.lat)
        maxLon = maxOf(maxLon, fix.lon)
    }
    return StoredBounds(minLat, minLon, maxLat, maxLon)
}

/**
 * True when this attempt is measured well enough to stand as a result, so it
 * may set a personal record and later enter a leaderboard.
 *
 * Rust already folds every disqualifying observation — weak GPS, a margin that
 * is large next to the result, vehicle-like evidence, or being the ride that
 * drew the segment — into [StoredAttemptQuality], so countability is one
 * condition here rather than a second, drifting rule.
 */
val StoredAttempt.countable: Boolean
    get() = quality == StoredAttemptQuality.GOOD

fun List<StoredAttempt>.countable(): List<StoredAttempt> = filter { it.countable }

/**
 * The rider's personal record: the fastest countable attempt, or null.
 *
 * There is deliberately no fallback to an uncertain attempt. `3:20 ± 4.2 s` is
 * a range, not a result: presenting it as a PR would give the rider a number
 * they can never honestly beat — exactly the pain this product exists to
 * remove. A segment with no countable attempt has no PR, and the screens say
 * so instead of quietly showing the next-best thing.
 */
fun List<StoredAttempt>.personalRecord(): StoredAttempt? =
    countable().minByOrNull { it.elapsedMs }

/**
 * The fastest attempt that does not count, when it is faster than [record].
 *
 * Surfacing it is not optional: a list containing a run quicker than the PR
 * reads as a bug unless the screen says why that run does not count.
 */
fun List<StoredAttempt>.fastestUncountableAhead(record: StoredAttempt?): StoredAttempt? =
    filterNot { it.countable }
        .minByOrNull { it.elapsedMs }
        ?.takeIf { record == null || it.elapsedMs < record.elapsedMs }

fun List<StoredAttempt>.latestAttempt(): StoredAttempt? = maxByOrNull { it.startedAtMs }
