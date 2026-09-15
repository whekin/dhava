package com.nakvali.core.recording

import com.nakvali.fusion.RideRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers what the run picker and a single-run file depend on. The runs
 * themselves are Rust's; these tests pin how Kotlin labels and slices with them.
 */
class TrackExportRunsTest {
    private fun point(time: Long, state: CanonicalActivityState, section: Int = 0) = CanonicalPoint(
        timestampMs = time, lat = 41.7 + time * 1e-9, lon = 44.8,
        altitudeM = 700.0, sectionId = section, activityState = state,
    )

    private fun run(index: Int, from: Int, to: Int, startMs: Long, endMs: Long, distanceM: Double) =
        RideRun(
            index = index.toUInt(), startedAtMs = startMs, endedAtMs = endMs,
            fromIndex = from.toUInt(), toIndex = to.toUInt(),
            distanceM = distanceM, ascentM = 0.0, descentM = 120.0, movingTimeS = 60.0,
        )

    /** Two descents either side of a shuttle, at one point per second. */
    private val source = (0..8).map { second ->
        point(
            second * 1_000L,
            if (second in 3..5) CanonicalActivityState.LIKELY_MOTORIZED
            else CanonicalActivityState.DOWNHILL,
        )
    }
    private val runs = listOf(
        run(0, 0, 3, 0, 2_000, 400.0),
        run(1, 6, 9, 6_000, 8_000, 900.0),
    )
    private val odometer = listOf(0.0, 200.0, 400.0, 400.0, 400.0, 400.0, 400.0, 650.0, 1_300.0)

    @Test fun `lap ids follow Rust's runs rather than being decided again here`() {
        val points = TrackExport.processedPoints(source, excludeTransport = true, runs = runs)

        assertEquals(listOf(0L, 1_000L, 2_000L, 6_000L, 7_000L, 8_000L), points.map { it.timestampMs })
        assertEquals(listOf(0, 0, 0, 1, 1, 1), points.map { it.runId })
    }

    /** A kept shuttle is its own lap, never an extension of either descent. */
    @Test fun `transport between two runs becomes a lap of its own`() {
        val points = TrackExport.processedPoints(source, excludeTransport = false, runs = runs)

        assertEquals(9, points.size)
        assertEquals(listOf(0, 0, 0, 1, 1, 1, 2, 2, 2), points.map { it.runId })
    }

    @Test fun `a trim keeps only the points inside the range Rust reported`() {
        val points = TrackExport.processedPoints(
            source, excludeTransport = true, runs = runs, kept = 6 until 9,
        )

        assertEquals(listOf(6_000L, 7_000L, 8_000L), points.map { it.timestampMs })
    }

    /**
     * The headline reason a single run needs its own step: TCX states an
     * absolute distance per trackpoint, so a run lifted out of the middle of a
     * day would otherwise open at the kilometres that came before it.
     */
    @Test fun `exporting one run rebases its odometer to zero`() {
        val whole = TrackExport.processedPoints(
            source, excludeTransport = true, odometerM = odometer, runs = runs,
        )
        assertEquals(400.0, whole.first { it.timestampMs == 6_000L }.odometerM, 0.001)

        val second = TrackExport.singleRun(whole, runs[1])

        assertEquals(listOf(6_000L, 7_000L, 8_000L), second.map { it.timestampMs })
        assertEquals(listOf(0.0, 250.0, 900.0), second.map { it.odometerM })
        assertTrue("the picked run is the file's only lap", second.all { it.runId == 0 })
    }

    @Test fun `picking a run that no longer exists selects nothing rather than the wrong descent`() {
        val whole = TrackExport.processedPoints(
            source, excludeTransport = true, odometerM = odometer, runs = runs,
        )

        val stale = TrackExport.singleRun(whole, run(0, 0, 3, 90_000, 95_000, 400.0))

        assertTrue(stale.isEmpty())
    }
}
