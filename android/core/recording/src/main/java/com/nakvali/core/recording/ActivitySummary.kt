package com.nakvali.core.recording

import android.util.AtomicFile
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * What the activity list shows for one ride without opening its canonical
 * artifact: the headline totals and a thumbnail-sized track.
 *
 * Every number is copied from the corrected canonical artifact (Rust totals,
 * Rust runs, Rust transport labels); nothing here is recomputed. The preview
 * polyline is display geometry only — thinned for a thumbnail, never used for
 * timing, distance or matching.
 */
@Serializable
data class ActivitySummary(
    /** Identifies the artifact and corrections this was taken from. */
    val key: String,
    val distanceM: Double,
    val movingTimeS: Double,
    val descentM: Double,
    val ascentM: Double,
    val maxSpeedMps: Double,
    val runCount: Int,
    val preview: List<ActivityPreviewPoint>,
    /**
     * Phone-sensor airtime candidates, exactly the set the activity screen
     * lists as "possible airtime" — not verified jumps.
     */
    val airtimeCount: Int = 0,
    val airtimeTotalMs: Long = 0,
    val airtimeLongestMs: Long = 0,
    /** Candidates long enough to count as jumps, by Rust's `is_likely_jump`. */
    val jumpCount: Int = 0,
    /** Summed airtime of those jumps only. */
    val jumpAirtimeMs: Long = 0,
    /** Where each candidate falls in the recording, for the card's timeline. */
    val airtimeMarks: List<ActivityAirtimeMark> = emptyList(),
)

/** One airtime candidate on a 0..1 recording timeline. */
@Serializable
data class ActivityAirtimeMark(val at: Float, val durationMs: Int, val jump: Boolean = false)

/**
 * One thumbnail vertex. [riding] is false across a vehicle transfer, drawn
 * muted; [breakBefore] marks a real recording gap, which is never bridged.
 */
@Serializable
data class ActivityPreviewPoint(
    val lat: Double,
    val lon: Double,
    val riding: Boolean = true,
    val breakBefore: Boolean = false,
)

internal fun summaryKey(artifact: CanonicalActivityArtifact, entry: LocalRecording?): String = listOf(
    SUMMARY_SCHEMA,
    artifact.algorithmVersion,
    artifact.sourceSizeBytes,
    artifact.sourceLastModifiedMs,
    entry?.transportRevision ?: 0,
    entry?.transportEpisodes?.hashCode() ?: 0,
    entry?.rideBounds?.let { "${it.startedAtMs}-${it.endedAtMs}" } ?: "whole",
).joinToString("|")

/** Builds the list summary from an artifact that already carries the rider's corrections. */
internal fun CanonicalActivityArtifact.toActivitySummary(key: String): ActivitySummary {
    val totals = ride
    val kept = rideBounds?.let {
        runCatching {
            com.nakvali.fusion.rideWithin(
                finalizedTrack.toCanonicalTrack(), it.toFusion(),
                analysis.startedAtMs, analysis.endedAtMs, elevationSource,
            ).let { bounded -> bounded.keptFrom.toInt() until bounded.keptTo.toInt() }
        }.getOrNull()
    }
    val track = finalizedTrack.ifEmpty { rawTrack }
    val points = track.withIndex()
        .filter { (index, _) -> kept == null || index in kept }
        .map { it.value }
    return ActivitySummary(
        key = key,
        distanceM = totals?.distanceM ?: analysis.distanceM,
        movingTimeS = totals?.movingTimeS ?: analysis.movingTimeS,
        descentM = totals?.descentM ?: analysis.descentM,
        ascentM = totals?.ascentM ?: analysis.ascentM,
        maxSpeedMps = totals?.maxSpeedMps ?: analysis.maxSpeedMps,
        runCount = runCatching { ridingRuns().size }.getOrDefault(0),
        preview = ActivityPreviewGeometry.thin(points),
        airtimeCount = analysis.airtimeWindows.size,
        airtimeTotalMs = analysis.airtimeTotalMs,
        airtimeLongestMs = analysis.airtimeWindows.maxOfOrNull { it.durationMs } ?: 0L,
        jumpCount = analysis.airtimeWindows.count { com.nakvali.fusion.isLikelyJump(it.durationMs) },
        jumpAirtimeMs = analysis.airtimeWindows
            .filter { com.nakvali.fusion.isLikelyJump(it.durationMs) }
            .sumOf { it.durationMs },
        airtimeMarks = airtimeMarks(),
    )
}

private fun CanonicalActivityArtifact.airtimeMarks(): List<ActivityAirtimeMark> {
    val span = (analysis.endedAtMs - analysis.startedAtMs).takeIf { it > 0 } ?: return emptyList()
    return analysis.airtimeWindows
        // A thumbnail timeline cannot show more; the longest ones matter most.
        .sortedByDescending { it.durationMs }
        .take(MAX_AIRTIME_MARKS)
        .map { window ->
            ActivityAirtimeMark(
                at = ((window.startMs - analysis.startedAtMs).toDouble() / span).toFloat().coerceIn(0f, 1f),
                durationMs = window.durationMs.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
                jump = com.nakvali.fusion.isLikelyJump(window.durationMs),
            )
        }
        .sortedBy { it.at }
}

private const val MAX_AIRTIME_MARKS = 240

internal object ActivityPreviewGeometry {
    /** Enough for a card-width line to look drawn rather than faceted. */
    const val MAX_POINTS = 360

    fun thin(points: List<CanonicalPoint>): List<ActivityPreviewPoint> {
        if (points.isEmpty()) return emptyList()
        // Split where the line must not be joined: a recording gap, or a change
        // between riding and transport (drawn in different inks).
        val pieces = mutableListOf<MutableList<CanonicalPoint>>()
        var previous: CanonicalPoint? = null
        for (point in points) {
            val prior = previous
            val split = prior == null || prior.sectionId != point.sectionId ||
                point.timestampMs - prior.timestampMs > GAP_MS ||
                prior.isTransport() != point.isTransport()
            if (split) {
                // Keep a shared vertex at a riding/transport handover so the two
                // inks meet instead of leaving a hole.
                val handover = prior != null && prior.sectionId == point.sectionId &&
                    point.timestampMs - prior.timestampMs <= GAP_MS
                pieces += if (handover) mutableListOf(prior!!, point) else mutableListOf(point)
            } else {
                pieces.last() += point
            }
            previous = point
        }
        val latitude = points.sumOf { it.lat } / points.size
        val scaleX = cos(Math.toRadians(latitude))
        val minX = points.minOf { it.lon } * scaleX
        val maxX = points.maxOf { it.lon } * scaleX
        val minY = points.minOf { it.lat }
        val maxY = points.maxOf { it.lat }
        val diagonal = hypot(maxX - minX, maxY - minY).takeIf { it > 0 } ?: return listOf(
            ActivityPreviewPoint(points.first().lat, points.first().lon, !points.first().isTransport()),
        )
        var tolerance = diagonal / 700
        var result: List<ActivityPreviewPoint>
        do {
            result = pieces.flatMapIndexed { index, piece ->
                val kept = simplify(piece, tolerance, scaleX)
                val joined = index > 0 && pieces[index - 1].last() === piece.first()
                kept.mapIndexed { position, point ->
                    ActivityPreviewPoint(
                        lat = point.lat,
                        lon = point.lon,
                        riding = !(piece.getOrNull(1) ?: piece.first()).isTransport(),
                        breakBefore = position == 0 && index > 0 && !joined,
                    )
                }
            }
            tolerance *= 1.6
        } while (result.size > MAX_POINTS)
        return result
    }

    /** Iterative Douglas–Peucker on a local equirectangular plane. */
    private fun simplify(points: List<CanonicalPoint>, tolerance: Double, scaleX: Double): List<CanonicalPoint> {
        if (points.size <= 2) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.lastIndex] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to points.lastIndex)
        while (stack.isNotEmpty()) {
            val (from, to) = stack.removeLast()
            if (to - from < 2) continue
            val ax = points[from].lon * scaleX
            val ay = points[from].lat
            val bx = points[to].lon * scaleX
            val by = points[to].lat
            val length = hypot(bx - ax, by - ay)
            var worst = -1.0
            var worstIndex = -1
            for (index in from + 1 until to) {
                val px = points[index].lon * scaleX
                val py = points[index].lat
                val distance = if (length == 0.0) {
                    hypot(px - ax, py - ay)
                } else {
                    abs((bx - ax) * (ay - py) - (ax - px) * (by - ay)) / length
                }
                if (distance > worst) {
                    worst = distance
                    worstIndex = index
                }
            }
            if (worst > tolerance) {
                keep[worstIndex] = true
                stack.addLast(from to worstIndex)
                stack.addLast(worstIndex to to)
            }
        }
        return points.filterIndexed { index, _ -> keep[index] }
    }

    private fun CanonicalPoint.isTransport() = activityState == CanonicalActivityState.LIKELY_MOTORIZED

    private const val GAP_MS = 10_000L
}

/**
 * One small JSON file per ride, outside backups: every value is rebuildable
 * from the raw recording, so restoring a phone rebuilds rather than trusts it.
 */
internal class ActivitySummaryStore(private val directory: File) {
    private val json = Json { ignoreUnknownKeys = true }

    fun loadAll(): Map<String, ActivitySummary> {
        val files = directory.listFiles { file -> file.isFile && file.name.endsWith(SUFFIX) } ?: return emptyMap()
        return files.mapNotNull { file ->
            runCatching {
                file.name.removeSuffix(SUFFIX) to json.decodeFromString<ActivitySummary>(file.readText())
            }.getOrNull()
        }.toMap()
    }

    fun write(id: String, summary: ActivitySummary) {
        directory.mkdirs()
        val file = AtomicFile(file(id))
        val stream = file.startWrite()
        try {
            stream.write(json.encodeToString(summary).toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    fun delete(id: String) {
        AtomicFile(file(id)).delete()
    }

    private fun file(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9_-]+"))) { "Unexpected recording id" }
        return File(directory, "$id$SUFFIX")
    }

    private companion object {
        const val SUFFIX = ".summary.json"
    }
}

internal const val SUMMARY_SCHEMA = 3

/** True when a stored summary predates the current summary fields. */
internal fun ActivitySummary.isOutdated(): Boolean = key.substringBefore('|') != SUMMARY_SCHEMA.toString()
