package com.dhava.feature.segments

import org.junit.Assert.assertEquals
import org.junit.Test

class SegmentEditorScaleTest {

    @Test
    fun `focused window puts a long selection close to both edges`() {
        assertEquals(420..1_580, focusedSliderWindow(500, 1_500, 10_000))
    }

    @Test
    fun `focused window keeps a usable grab area around a short selection`() {
        assertEquals(90..115, focusedSliderWindow(100, 105, 1_000))
    }

    @Test
    fun `focused window clamps padding at the ride boundaries`() {
        assertEquals(0..110, focusedSliderWindow(0, 100, 1_000))
        assertEquals(890..1_000, focusedSliderWindow(900, 1_000, 1_000))
    }
}
