package com.nakvali.core.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Ranking one ride's segment runs against the rider's own history. */
class RideSegmentRunTest {

    @Test
    fun `every lap of the same segment is its own run, in the order ridden`() {
        val results = results(
            ride("today", attempt(152_000, startedAtMs = 3_000), attempt(148_000, startedAtMs = 1_000)),
        )

        val runs = rideRuns(segment, results, "today")

        assertEquals(2, runs.size)
        assertEquals(listOf(1_000L, 3_000L), runs.map { it.attempt.startedAtMs })
    }

    @Test
    fun `place counts every confirmed attempt the rider has, not just this ride's`() {
        val results = results(
            ride("older", attempt(140_000, startedAtMs = 1_000)),
            ride("today", attempt(150_000, startedAtMs = 9_000)),
            ride("oldest", attempt(160_000, startedAtMs = 500)),
        )

        val run = rideRuns(segment, results, "today").single()

        assertEquals(2, run.place)
        assertEquals(3, run.confirmedAttempts)
        assertEquals(10_000L, run.behindBestMs)
    }

    @Test
    fun `the fastest run is behind nothing`() {
        val results = results(
            ride("today", attempt(140_000, startedAtMs = 9_000)),
            ride("older", attempt(150_000, startedAtMs = 1_000)),
        )

        val run = rideRuns(segment, results, "today").single()

        assertEquals(1, run.place)
        assertEquals(0L, run.behindBestMs)
    }

    @Test
    fun `an uncertain run is listed but never ranked`() {
        // It still happened and the rider should see it. What it must not do is
        // take a place, because the matcher is not sure of its time.
        val results = results(
            ride("today", attempt(100_000, startedAtMs = 9_000, quality = StoredAttemptQuality.UNCERTAIN)),
            ride("older", attempt(150_000, startedAtMs = 1_000)),
        )

        val run = rideRuns(segment, results, "today").single()

        assertNull(run.place)
        assertNull(run.behindBestMs)
        assertEquals(1, run.confirmedAttempts)
    }

    @Test
    fun `an uncertain run cannot set the personal best others are measured against`() {
        val results = results(
            ride("bad-gps", attempt(90_000, startedAtMs = 1_000, quality = StoredAttemptQuality.UNCERTAIN)),
            ride("today", attempt(150_000, startedAtMs = 9_000)),
            ride("older", attempt(140_000, startedAtMs = 2_000)),
        )

        val run = rideRuns(segment, results, "today").single()

        assertEquals(2, run.place)
        assertEquals(10_000L, run.behindBestMs)
    }

    @Test
    fun `identical times share the better place`() {
        val results = results(
            ride("older", attempt(150_000, startedAtMs = 1_000)),
            ride("today", attempt(150_000, startedAtMs = 9_000)),
            ride("oldest", attempt(140_000, startedAtMs = 500)),
        )

        val run = rideRuns(segment, results, "today").single()

        assertEquals(2, run.place)
    }

    @Test
    fun `a ride that never reached the segment contributes nothing`() {
        val results = results(ride("older", attempt(150_000, startedAtMs = 1_000)))

        assertEquals(emptyList<RideSegmentRun>(), rideRuns(segment, results, "today"))
    }

    private val segment = StoredSegment(
        id = "udzo",
        name = "Udzo",
        sourceRecordingId = "older",
        geometryVersion = 3,
        centerline = listOf(StoredLatLon(41.7, 44.7), StoredLatLon(41.69, 44.71)),
        gateHalfWidthM = 6.0,
        corridorM = 18.0,
        lengthM = 420.0,
        createdAtMs = 1,
    )

    private fun results(vararg rides: SegmentRideMatch) = SegmentResults(
        schemaVersion = SegmentStore.RESULTS_SCHEMA_VERSION,
        algorithmVersion = "gps-bounded-0.10",
        matchVersion = "gates-0.2",
        geometryVersion = 3,
        generatedAtMs = 10,
        rides = rides.toList(),
    )

    private fun ride(recordingId: String, vararg attempts: StoredAttempt) = SegmentRideMatch(
        recordingId = recordingId,
        sourceSizeBytes = 1,
        sourceLastModifiedMs = 1,
        attempts = attempts.map { it.copy(recordingId = recordingId) },
    )

    private fun attempt(
        elapsedMs: Long,
        startedAtMs: Long,
        quality: StoredAttemptQuality = StoredAttemptQuality.GOOD,
    ) = StoredAttempt(
        recordingId = "",
        startedAtMs = startedAtMs,
        finishedAtMs = startedAtMs + elapsedMs,
        elapsedMs = elapsedMs,
        uncertaintyMs = 800,
        sectionId = 0,
        startIndex = 0,
        endIndex = 10,
        maxDeviationM = 4.0,
        medianAccuracyM = 6.0,
        quality = quality,
        flags = emptyList(),
        matchedGeometryVersion = 3,
        matchVersion = "gates-0.2",
    )
}
