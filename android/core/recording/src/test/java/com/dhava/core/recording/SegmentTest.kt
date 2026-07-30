package com.dhava.core.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentTest {

    private fun bounds(
        minLat: Double,
        minLon: Double,
        maxLat: Double,
        maxLon: Double,
    ) = StoredBounds(minLat, minLon, maxLat, maxLon)

    private fun attempt(
        elapsedMs: Long,
        startedAtMs: Long,
        quality: StoredAttemptQuality = StoredAttemptQuality.GOOD,
    ) = StoredAttempt(
        recordingId = "ride-$startedAtMs",
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
        matchedGeometryVersion = 1,
        matchVersion = "gates-0.2",
    )

    @Test
    fun `overlapping bounds intersect`() {
        assertTrue(bounds(43.0, 42.0, 43.1, 42.1).intersects(bounds(43.05, 42.05, 43.2, 42.2)))
    }

    @Test
    fun `touching bounds intersect`() {
        assertTrue(bounds(43.0, 42.0, 43.1, 42.1).intersects(bounds(43.1, 42.1, 43.2, 42.2)))
    }

    @Test
    fun `disjoint bounds do not intersect`() {
        assertFalse(bounds(43.0, 42.0, 43.1, 42.1).intersects(bounds(44.0, 42.0, 44.1, 42.1)))
        assertFalse(bounds(43.0, 42.0, 43.1, 42.1).intersects(bounds(43.0, 43.0, 43.1, 43.1)))
    }

    @Test
    fun `gps bounds cover every fix`() {
        val fixes = listOf(
            RecordLine.Gps(timestampMs = 0, lat = 43.0, lon = 42.0),
            RecordLine.Gps(timestampMs = 1_000, lat = 43.2, lon = 41.9),
            RecordLine.Gps(timestampMs = 2_000, lat = 42.9, lon = 42.3),
        )
        assertEquals(bounds(42.9, 41.9, 43.2, 42.3), fixes.gpsBoundsOrNull())
    }

    @Test
    fun `a recording without gps has no bounds`() {
        assertNull(emptyList<RecordLine.Gps>().gpsBoundsOrNull())
    }

    @Test
    fun `the personal record ignores a faster uncertain run`() {
        val attempts = listOf(
            attempt(elapsedMs = 140_000, startedAtMs = 2_000, quality = StoredAttemptQuality.UNCERTAIN),
            attempt(elapsedMs = 151_000, startedAtMs = 1_000),
        )
        assertEquals(151_000L, attempts.personalRecord()?.elapsedMs)
    }

    @Test
    fun `uncertain runs never become a personal record`() {
        val attempts = listOf(
            attempt(elapsedMs = 160_000, startedAtMs = 1_000, quality = StoredAttemptQuality.UNCERTAIN),
            attempt(elapsedMs = 140_000, startedAtMs = 2_000, quality = StoredAttemptQuality.UNCERTAIN),
        )
        assertNull(attempts.personalRecord())
    }

    @Test
    fun `the fastest uncountable run is surfaced only when it leads the record`() {
        val faster = attempt(
            elapsedMs = 140_000,
            startedAtMs = 2_000,
            quality = StoredAttemptQuality.UNCERTAIN,
        )
        val slower = attempt(
            elapsedMs = 158_000,
            startedAtMs = 3_000,
            quality = StoredAttemptQuality.UNCERTAIN,
        )
        val record = attempt(elapsedMs = 151_000, startedAtMs = 1_000)

        assertEquals(
            140_000L,
            listOf(faster, record).fastestUncountableAhead(record)?.elapsedMs,
        )
        assertNull(listOf(slower, record).fastestUncountableAhead(record))
        assertEquals(
            140_000L,
            listOf(faster, slower).fastestUncountableAhead(null)?.elapsedMs,
        )
    }

    @Test
    fun `latest result is the most recent start`() {
        val attempts = listOf(
            attempt(elapsedMs = 151_000, startedAtMs = 1_000),
            attempt(elapsedMs = 149_000, startedAtMs = 9_000),
            attempt(elapsedMs = 155_000, startedAtMs = 5_000),
        )
        assertEquals(9_000L, attempts.latestAttempt()?.startedAtMs)
    }

    @Test
    fun `no attempts yields no record or latest`() {
        assertNull(emptyList<StoredAttempt>().personalRecord())
        assertNull(emptyList<StoredAttempt>().latestAttempt())
    }

    @Test
    fun `results flatten attempts and rejections across rides`() {
        val results = SegmentResults(
            schemaVersion = 1,
            algorithmVersion = "gps-bounded-0.5",
            matchVersion = "gates-0.2",
            geometryVersion = 1,
            generatedAtMs = 10,
            rides = listOf(
                SegmentRideMatch(
                    recordingId = "a",
                    sourceSizeBytes = 1,
                    sourceLastModifiedMs = 1,
                    attempts = listOf(attempt(151_000, 1_000)),
                ),
                SegmentRideMatch(
                    recordingId = "b",
                    sourceSizeBytes = 2,
                    sourceLastModifiedMs = 2,
                    attempts = listOf(attempt(149_000, 2_000)),
                    rejected = listOf(
                        StoredRejection(
                            recordingId = "b",
                            startedAtMs = 3_000,
                            reason = StoredRejectionReason.GAP_INSIDE,
                            detail = "7.4 s recording gap between the gates",
                        ),
                    ),
                ),
            ),
        )
        assertEquals(2, results.attempts().size)
        assertEquals(1, results.rejections().size)
    }
}
