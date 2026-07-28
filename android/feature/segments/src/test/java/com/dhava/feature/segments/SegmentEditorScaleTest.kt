package com.dhava.feature.segments

import org.junit.Assert.assertEquals
import org.junit.Test

class SegmentEditorScaleTest {

    @Test
    fun `focused window puts a long selection close to both edges`() {
        assertEquals(420f..1_580f, focusedSliderWindow(500f, 1_500f, 10_000))
    }

    @Test
    fun `focused window keeps a usable grab area around a short selection`() {
        assertEquals(90f..115f, focusedSliderWindow(100f, 105f, 1_000))
    }

    @Test
    fun `focused window clamps padding at the ride boundaries`() {
        assertEquals(0f..110f, focusedSliderWindow(0f, 100f, 1_000))
        assertEquals(890f..1_000f, focusedSliderWindow(900f, 1_000f, 1_000))
    }

    @Test
    fun `precision movement applies one tenth of the raw handle delta`() {
        assertEquals(500.5f, scaledMovementValue(500f, 105f, 100f, 0.1f))
        assertEquals(499.5f, scaledMovementValue(500f, 95f, 100f, 0.1f))
    }

    @Test
    fun `map zoom progressively lowers continuous drag sensitivity`() {
        assertEquals(1f, dragSensitivityForMapZoom(16.0))
        assertEquals(0.5f, dragSensitivityForMapZoom(17.0))
        assertEquals(0.25f, dragSensitivityForMapZoom(18.0))
        assertEquals(0.05f, dragSensitivityForMapZoom(24.0))
    }
}
