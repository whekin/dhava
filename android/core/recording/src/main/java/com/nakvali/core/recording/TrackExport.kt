package com.nakvali.core.recording

import com.nakvali.fusion.RideRun

/**
 * One exported sample, in whichever format the rider picked.
 *
 * [sectionId] is writing granularity: a new one starts a fresh `<trkseg>`, so
 * every real gap in the recording stays a gap. [runId] is riding granularity:
 * a new one means a shuttle or a manual pause came between, which is what a lap
 * means and the only thing an odometer must refuse to cross.
 */
data class TrackExportPoint(
    val timestampMs: Long,
    val lat: Double,
    val lon: Double,
    val altitudeM: Double? = null,
    val sectionId: Int = 0,
    val runId: Int = 0,
    val odometerM: Double = 0.0,
)

object TrackExport {
    /**
     * Uses Rust's labels, runs and odometer; never reclassifies motion, decides
     * a run boundary, or changes source timestamps.
     *
     * [odometerM] is the running ride distance per input point and [runs] the
     * riding runs, both from `rideWithin`/`rideRuns` over this same track — so
     * the run a rider taps and the lap that gets written are one descent. [kept]
     * is the index range the rider's trim leaves, from `BoundedRide`, rather
     * than a timestamp test repeated here with its own idea of the boundary.
     */
    fun processedPoints(
        points: List<CanonicalPoint>,
        excludeTransport: Boolean = false,
        odometerM: List<Double> = emptyList(),
        runs: List<RideRun> = emptyList(),
        kept: IntRange? = null,
    ): List<TrackExportPoint> {
        val result = ArrayList<TrackExportPoint>(points.size)
        var previous: CanonicalPoint? = null
        var section = -1
        var run = -1
        var lap: String? = null
        var cursor = 0
        for ((index, point) in points.withIndex()) {
            if (kept != null && index !in kept) {
                previous = null
                continue
            }
            if (excludeTransport && point.activityState == CanonicalActivityState.LIKELY_MOTORIZED) {
                previous = null
                continue
            }
            while (cursor < runs.size && index >= runs[cursor].toIndex.toInt()) cursor++
            // Whatever falls between two runs — a shuttle the rider chose to
            // keep — is its own lap rather than an extension of either descent.
            val here = if (cursor < runs.size && index >= runs[cursor].fromIndex.toInt()) {
                "run$cursor"
            } else {
                "between$cursor"
            }
            val prior = previous
            // Preserve real gaps even within one manual recording section.
            if (prior == null || prior.sectionId != point.sectionId ||
                point.timestampMs - prior.timestampMs !in 1L..3_000L
            ) {
                section++
            }
            if (here != lap) {
                run++
                lap = here
            }
            result.add(TrackExportPoint(
                timestampMs = point.timestampMs,
                lat = point.lat,
                lon = point.lon,
                altitudeM = point.altitudeM,
                sectionId = section,
                runId = run,
                odometerM = odometerM.getOrElse(index) { 0.0 },
            ))
            previous = point
        }
        return result
    }

    /**
     * Narrow an export to one riding run.
     *
     * Selected by the run's own start time, never by its position: a transport
     * edit or a trim renumbers every run, and an ordinal would quietly hand back
     * a different descent. The odometer is rebased so the file starts at zero —
     * TCX states an absolute distance per trackpoint, and a run lifted out of
     * the middle of a day would otherwise open at several kilometres.
     */
    fun singleRun(points: List<TrackExportPoint>, run: RideRun): List<TrackExportPoint> {
        val kept = points.filter { it.timestampMs in run.startedAtMs..run.endedAtMs }
        val origin = kept.firstOrNull()?.odometerM ?: 0.0
        return kept.map { it.copy(runId = 0, odometerM = it.odometerM - origin) }
    }

    internal fun escape(value: String): String = value
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}

/** The riding runs of this activity, under whatever trim the rider applied. */
fun CanonicalActivityArtifact.ridingRuns(): List<RideRun> =
    com.nakvali.fusion.rideRuns(finalizedTrack.toCanonicalTrack(), rideBounds?.toFusion())

/**
 * Build the export samples for this activity. [runs] comes from [ridingRuns] on
 * this same artifact; passing the list the picker showed guarantees the file's
 * laps are the runs the rider was looking at.
 */
fun CanonicalActivityArtifact.exportPoints(
    excludeTransport: Boolean,
    runs: List<RideRun>,
): List<TrackExportPoint> {
    val bounded = com.nakvali.fusion.rideWithin(
        finalizedTrack.toCanonicalTrack(), rideBounds?.toFusion(),
        analysis.startedAtMs, analysis.endedAtMs,
    )
    return TrackExport.processedPoints(
        points = finalizedTrack,
        excludeTransport = excludeTransport,
        odometerM = bounded.odometerM,
        runs = runs,
        kept = rideBounds?.let { bounded.keptFrom.toInt() until bounded.keptTo.toInt() },
    )
}
