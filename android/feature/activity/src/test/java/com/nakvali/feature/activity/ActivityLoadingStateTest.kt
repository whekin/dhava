package com.nakvali.feature.activity

import com.nakvali.fusion.CanonicalProgress
import com.nakvali.fusion.CanonicalStage
import com.nakvali.fusion.CanonicalPreviewPoint
import org.junit.Assert.*
import org.junit.Test

class ActivityLoadingStateTest {
    @Test fun `stage updates retain preview and show percentage only for file reading`() {
        val point = CanonicalPreviewPoint(1_000, 41.7, 44.8, 5.0)
        val reading = ActivityLoadingState().progress(CanonicalProgress(CanonicalStage.READING, 25uL, 100uL, 1uL, listOf(point)))
        assertEquals(0.25f, reading.readFraction)
        val motion = reading.progress(CanonicalProgress(CanonicalStage.MOTION, 100uL, 100uL, 10uL, emptyList()))
        assertNull(motion.readFraction)
        assertEquals(listOf(point), motion.preview)
        assertEquals(ActivityLoadPhase.MOTION, motion.phase)
    }

    @Test fun `a complete cached preview never shrinks to the first partial batch`() {
        val points = (1..3).map { CanonicalPreviewPoint(it.toLong(), 41.7, 44.8, null) }
        val cached = ActivityLoadingState(preview = points, fromCache = true)
        val updated = cached.progress(CanonicalProgress(CanonicalStage.READING, 10uL, 100uL, 1uL, points.take(1)))
        assertEquals(points, updated.preview)
    }
    @Test fun `retained completed work is a saved preview not a running calculation`() {
        val point = CanonicalPreviewPoint(1_000, 41.7, 44.8, null)
        val old = CanonicalProgress(CanonicalStage.FINALIZING, 100uL, 100uL, 1uL, listOf(point))
        val state = ActivityLoadingState().finishedPreview(old)
        assertEquals(ActivityLoadPhase.PREPARING, state.phase)
        assertTrue(state.fromCache)
        assertNull(state.readFraction)
    }

}
