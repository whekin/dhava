package com.nakvali.core.recording

import android.util.Log
import com.nakvali.core.fusion.FusionCore

/**
 * Incremental matching of one authored segment against local rides.
 *
 * Matching consumes canonical artifacts, never raw recordings: the finalized
 * 5 Hz track is the timing reference, and rebuilding it here would duplicate
 * work the artifact cache already did. Per-ride results are cached and reused
 * while the raw fingerprint, algorithm version, match version and segment
 * geometry version all stay the same, so opening a segment after one new ride
 * matches exactly that one ride.
 *
 * A padded-bounds prefilter runs before any canonical artifact is touched, and
 * takes its bounds from raw GPS lines only. Authoring one segment must not
 * trigger a full fusion pass over every ride ever recorded; bounds are cached
 * per raw fingerprint, so a growing ride history does not turn every segment
 * open into a full rescan either.
 */
internal class SegmentMatcher(
    private val store: SegmentStore,
    private val canonicalArtifact: suspend (String) -> CanonicalActivityArtifact?,
    private val rawFile: (String) -> java.io.File,
    private val algorithmVersion: () -> String,
    private val matchVersion: () -> String,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    /**
     * Bounds for the prefilter come from the raw GPS lines, not from the
     * canonical artifact: authoring one segment must not force a full fusion
     * pass over every ride ever recorded. Raw fixes are a conservative
     * superset of the finalized geometry (finalized samples stay inside a 6 m
     * corridor around accepted fixes, and the search bounds are already padded
     * by at least a 15 m segment corridor), so this can only ever add
     * candidates, never drop a real one.
     */
    private fun rawGpsBounds(recordingId: String): StoredBounds? =
        GpsTrackReader.read(rawFile(recordingId)).gpsBoundsOrNull()

    /**
     * Returns up-to-date results for [segment], recomputing only what changed.
     * [recordings] is the set of rides that may contribute; entries for rides
     * that are gone are dropped.
     */
    suspend fun results(
        segment: StoredSegment,
        recordings: List<LocalRecording>,
    ): SegmentResults {
        val algorithm = algorithmVersion()
        val match = matchVersion()
        val cached = store.loadResults(segment.id)?.takeIf { results ->
            results.schemaVersion == SegmentStore.RESULTS_SCHEMA_VERSION &&
                results.algorithmVersion == algorithm &&
                results.matchVersion == match &&
                results.geometryVersion == segment.geometryVersion
        }
        val cachedRides = cached?.rides?.associateBy { it.recordingId }.orEmpty()

        val definition = segment.toDefinition()
        val searchBounds = FusionCore.segmentSearchBounds(definition)?.let { bounds ->
            StoredBounds(bounds.minLat, bounds.minLon, bounds.maxLat, bounds.maxLon)
        }
        val boundsCache = store.loadTrackBounds().toMutableMap()
        var boundsChanged = false

        val rides = mutableListOf<SegmentRideMatch>()
        for (recording in recordings) {
            val file = rawFile(recording.id)
            if (!file.isFile) continue
            val sizeBytes = file.length()
            val lastModifiedMs = file.lastModified()

            val reusable = cachedRides[recording.id]?.takeIf {
                it.sourceSizeBytes == sizeBytes && it.sourceLastModifiedMs == lastModifiedMs
            }
            if (reusable != null) {
                rides += reusable
                continue
            }

            val empty = SegmentRideMatch(
                recordingId = recording.id,
                sourceSizeBytes = sizeBytes,
                sourceLastModifiedMs = lastModifiedMs,
            )
            val known = boundsCache[recording.id]?.takeIf {
                it.sourceSizeBytes == sizeBytes && it.sourceLastModifiedMs == lastModifiedMs
            }
            val bounds = if (known != null) {
                known.bounds
            } else {
                rawGpsBounds(recording.id).also { computed ->
                    boundsCache[recording.id] = TrackBoundsEntry(
                        recordingId = recording.id,
                        sourceSizeBytes = sizeBytes,
                        sourceLastModifiedMs = lastModifiedMs,
                        bounds = computed,
                    )
                    boundsChanged = true
                }
            }
            if (searchBounds != null && (bounds == null || !bounds.intersects(searchBounds))) {
                rides += empty
                continue
            }

            val artifact = canonicalArtifact(recording.id)
            if (artifact == null) {
                // A ride whose artifact cannot be built yet is simply not
                // matched; it is retried on the next open rather than cached
                // as "no attempts".
                continue
            }

            val result = runCatching {
                FusionCore.matchSegment(
                    definition,
                    recording.id,
                    artifact.finalizedTrack.toCanonicalTrack(),
                )
            }.onFailure { error ->
                Log.w(LOG_TAG, "segment match failed for ${recording.id}", error)
            }.getOrNull() ?: continue

            rides += empty.copy(
                attempts = result.attempts.map { it.toStored() },
                rejected = result.rejected.map { it.toStored() },
            )
        }

        // Keep only rides that still exist so the cache cannot grow forever,
        // even when this pass reused every surviving bound.
        val live = recordings.mapTo(mutableSetOf()) { it.id }
        val liveBounds = boundsCache.filterKeys { it in live }
        if (boundsChanged || liveBounds.size != boundsCache.size) {
            store.saveTrackBounds(liveBounds)
        }

        val results = SegmentResults(
            schemaVersion = SegmentStore.RESULTS_SCHEMA_VERSION,
            algorithmVersion = algorithm,
            matchVersion = match,
            geometryVersion = segment.geometryVersion,
            generatedAtMs = nowMs(),
            rides = rides.sortedBy { ride -> ride.recordingId },
        )
        // Count equality is not enough: deleting one ride and adding another
        // outside the search bounds keeps the same count but changes the
        // cache's identity set.
        val unchanged = cached?.rides == results.rides
        if (!unchanged) {
            store.saveResults(segment.id, results)
        }
        return results
    }

    private companion object {
        const val LOG_TAG = "SegmentMatcher"
    }
}

/** Every attempt of a segment across all matched rides. */
fun SegmentResults.attempts(): List<StoredAttempt> = rides.flatMap { it.attempts }

/** Every rejected gate pair of a segment across all matched rides. */
fun SegmentResults.rejections(): List<StoredRejection> = rides.flatMap { it.rejected }

/**
 * One run at one segment, as it appears on the ride that produced it.
 *
 * This is the ride's point of view rather than the segment's: a lap counts
 * once per pass, so a ride that rode the same trail three times contributes
 * three of these.
 */
data class RideSegmentRun(
    val segmentId: String,
    val segmentName: String,
    val attempt: StoredAttempt,
    /**
     * Place among every confirmed attempt the rider has on this segment, best
     * first. Null for an uncertain attempt, which is deliberately left out of
     * the ranking: a run the matcher is unsure about must not be able to claim
     * a personal best.
     */
    val place: Int?,
    /** How many confirmed attempts the place is out of. */
    val confirmedAttempts: Int,
    /**
     * Time behind the rider's fastest confirmed attempt. Zero when this run is
     * that attempt, null when it is not ranked.
     */
    val behindBestMs: Long?,
)

/**
 * Ranks one ride's attempts at [segment] against the rider's own history.
 *
 * Ranking needs every attempt, not just this ride's, which is why the caller
 * computes full segment results rather than matching this ride alone: the
 * narrower pass would save nothing, because the place and the gap to a
 * personal best cannot be known without the rest.
 */
internal fun rideRuns(
    segment: StoredSegment,
    results: SegmentResults,
    recordingId: String,
): List<RideSegmentRun> {
    val confirmed = results.attempts()
        .filter { it.quality == StoredAttemptQuality.GOOD }
        .sortedBy { it.elapsedMs }
    val best = confirmed.firstOrNull()?.elapsedMs

    return results.rides
        .asSequence()
        .filter { it.recordingId == recordingId }
        .flatMap { it.attempts.asSequence() }
        .sortedBy { it.startedAtMs }
        .map { attempt ->
            val ranked = attempt.quality == StoredAttemptQuality.GOOD
            RideSegmentRun(
                segmentId = segment.id,
                segmentName = segment.name,
                attempt = attempt,
                // Ties share the better place: two identical times are both
                // "2nd", never an arbitrary winner decided by list order.
                place = if (ranked) confirmed.count { it.elapsedMs < attempt.elapsedMs } + 1 else null,
                confirmedAttempts = confirmed.size,
                behindBestMs = if (ranked && best != null) attempt.elapsedMs - best else null,
            )
        }
        .toList()
}
