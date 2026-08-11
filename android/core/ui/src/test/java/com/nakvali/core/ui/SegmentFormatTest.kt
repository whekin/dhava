package com.nakvali.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SegmentFormatTest {

    @Test
    fun `elapsed uses minutes seconds and tenths`() {
        assertEquals("2:31.4", SegmentFormat.elapsed(151_400))
        assertEquals("0:58.2", SegmentFormat.elapsed(58_200))
        assertEquals("0:00.0", SegmentFormat.elapsed(0))
    }

    @Test
    fun `elapsed adds hours only when needed`() {
        assertEquals("1:04:02.7", SegmentFormat.elapsed(3_842_700))
        assertEquals("59:59.9", SegmentFormat.elapsed(3_599_900))
    }

    @Test
    fun `elapsed rounds to the nearest tenth`() {
        assertEquals("0:01.0", SegmentFormat.elapsed(951))
        assertEquals("0:00.9", SegmentFormat.elapsed(949))
    }

    @Test
    fun `uncertainty never hides a real margin as zero`() {
        assertEquals("± 0.8 s", SegmentFormat.uncertainty(800))
        assertEquals("± 0.1 s", SegmentFormat.uncertainty(20))
        assertEquals("± 0.0 s", SegmentFormat.uncertainty(0))
    }

    @Test
    fun `result carries its uncertainty`() {
        assertEquals(
            "2:31.4 ± 0.8 s",
            SegmentFormat.elapsedWithUncertainty(151_400, 800),
        )
    }

    @Test
    fun `length switches unit at one kilometer`() {
        assertEquals("480 m", SegmentFormat.length(480.0))
        assertEquals("1.24 km", SegmentFormat.length(1_240.0))
    }

    @Test
    fun `descent is shown as a drop and tolerates either sign`() {
        assertEquals("−182 m", SegmentFormat.descent(182.0))
        assertEquals("−182 m", SegmentFormat.descent(-182.0))
        assertNull(SegmentFormat.descent(null))
    }

    @Test
    fun `ascent is shown separately from descent`() {
        assertEquals("+24 m", SegmentFormat.ascent(24.0))
        assertEquals("+24 m", SegmentFormat.ascent(-24.0))
        assertNull(SegmentFormat.ascent(null))
    }

    @Test
    fun `average speed needs positive length and time`() {
        assertEquals("28.5 km/h", SegmentFormat.averageSpeed(1_200.0, 151_400))
        assertNull(SegmentFormat.averageSpeed(1_200.0, 0))
        assertNull(SegmentFormat.averageSpeed(0.0, 151_400))
    }
}
