package com.nakvali.core.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityPreviewGeometryTest {
    private fun point(
        index: Int,
        lat: Double,
        lon: Double,
        section: Int = 0,
        state: CanonicalActivityState = CanonicalActivityState.UNKNOWN,
        timestampMs: Long = index * 1_000L,
    ) = CanonicalPoint(
        timestampMs = timestampMs,
        lat = lat,
        lon = lon,
        sectionId = section,
        activityState = state,
    )

    @Test fun `a straight line keeps only its ends`() {
        val line = (0..500).map { point(it, 41.0 + it * 1e-5, 44.0 + it * 1e-5) }
        val thinned = ActivityPreviewGeometry.thin(line)
        assertEquals(2, thinned.size)
        assertEquals(line.first().lat, thinned.first().lat, 0.0)
        assertEquals(line.last().lat, thinned.last().lat, 0.0)
    }

    @Test fun `a long wiggly ride is capped for a thumbnail`() {
        val ride = (0..20_000).map {
            point(it, 41.0 + it * 1e-6 + kotlin.math.sin(it / 7.0) * 1e-4, 44.0 + kotlin.math.cos(it / 11.0) * 1e-3)
        }
        val thinned = ActivityPreviewGeometry.thin(ride)
        assertTrue(thinned.size <= ActivityPreviewGeometry.MAX_POINTS)
        assertTrue(thinned.size > 50)
    }

    @Test fun `transport is labelled and joins riding without a gap`() {
        val riding = (0..10).map { point(it, 41.0 + it * 1e-4, 44.0) }
        val shuttle = (11..20).map {
            point(it, 41.0 + it * 1e-4, 44.0 + (it - 10) * 1e-4, state = CanonicalActivityState.LIKELY_MOTORIZED)
        }
        val thinned = ActivityPreviewGeometry.thin(riding + shuttle)
        assertTrue(thinned.any { !it.riding })
        assertTrue(thinned.first().riding)
        assertFalse(thinned.any { it.breakBefore })
    }

    @Test fun `a recording gap is never bridged`() {
        val before = (0..10).map { point(it, 41.0 + it * 1e-4, 44.0) }
        val after = (0..10).map { point(it, 41.01 + it * 1e-4, 44.01, section = 1, timestampMs = 60_000L + it * 1_000L) }
        val thinned = ActivityPreviewGeometry.thin(before + after)
        assertEquals(1, thinned.count { it.breakBefore })
    }
}
