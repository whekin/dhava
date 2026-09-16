package com.nakvali.feature.activity

import com.nakvali.core.recording.StoredRideBounds
import com.nakvali.core.ui.ProfileSample
import com.nakvali.core.ui.RideProfileUi
import com.nakvali.fusion.CanonicalTrackPoint
import com.nakvali.fusion.RideProfile
import kotlin.math.roundToInt

/**
 * Draft trim, as elapsed HH:MM:SS from the start of the recording.
 *
 * Like a transport draft it keeps [original] so a boundary the rider never
 * touched round-trips its exact millisecond instead of being re-quantized to a
 * whole second every time the editor is opened.
 */
internal data class TrimDraft(
    val start: String,
    val finish: String,
    val original: StoredRideBounds? = null,
) {
    fun bounds(originMs: Long): StoredRideBounds {
        val first =
            if (original != null && start == elapsedClock(original.startedAtMs - originMs)) {
                original.startedAtMs
            } else originMs + parseElapsedClock(start)
        val last =
            if (original != null && finish == elapsedClock(original.endedAtMs - originMs)) {
                original.endedAtMs
            } else originMs + parseElapsedClock(finish)
        return StoredRideBounds(first, last)
    }
}

internal data class TrimEditorState(
    val originMs: Long,
    val endedAtMs: Long,
    val track: List<CanonicalTrackPoint>,
    /**
     * The chart the boundaries are dragged on. Its sample positions are indices
     * into [track], which is what makes a point on the chart a timestamp.
     */
    val profile: RideProfileUi,
    val draft: TrimDraft,
    /** True while the whole recording is the ride, i.e. nothing is trimmed. */
    val useWholeActivity: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)

/**
 * Position on the profile chart for a recording timestamp, in track indices.
 *
 * The track is chronological, so this is a binary search; the nearer of the two
 * neighbours wins, because a boundary the rider dragged is already only as
 * precise as the fix rate.
 */
internal fun List<CanonicalTrackPoint>.positionAt(timestampMs: Long): Double {
    if (isEmpty()) return 0.0
    var low = 0
    var high = lastIndex
    while (low < high) {
        val middle = (low + high) / 2
        if (this[middle].timestampMs < timestampMs) low = middle + 1 else high = middle
    }
    val after = low
    val before = (low - 1).coerceAtLeast(0)
    val closer = if (
        timestampMs - this[before].timestampMs <= this[after].timestampMs - timestampMs
    ) {
        before
    } else {
        after
    }
    return closer.toDouble()
}

/** The recording timestamp a position on the profile chart points at. */
internal fun List<CanonicalTrackPoint>.timestampAt(position: Double): Long {
    if (isEmpty()) return 0L
    return this[position.roundToInt().coerceIn(0, lastIndex)].timestampMs
}

internal fun StoredRideBounds.draft(origin: Long) = TrimDraft(
    elapsedClock(startedAtMs - origin), elapsedClock(endedAtMs - origin), this,
)

/**
 * The Rust profile as the shared chart wants it. `lastPosition` comes from the
 * track rather than from the sampled profile, because the chart's axis is the
 * whole ride and the samples are only a stride across it.
 */
internal fun RideProfile?.toUi(trackSize: Int): RideProfileUi = RideProfileUi(
    samples = this?.points.orEmpty().map { point ->
        ProfileSample(
            position = point.position,
            distanceM = point.distanceM,
            altitudeM = point.altitudeM,
            gradientPercent = point.gradientPercent,
            continues = point.continues,
        )
    },
    lengthM = this?.lengthM ?: 0.0,
    minAltitudeM = this?.minAltitudeM,
    maxAltitudeM = this?.maxAltitudeM,
    lastPosition = (trackSize - 1).coerceAtLeast(0).toDouble(),
)
