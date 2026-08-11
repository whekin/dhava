package com.nakvali.core.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SegmentNameTest {

    @Test
    fun `an ordinary trail name is accepted`() {
        assertNull(segmentNameProblem("Ridgeline"))
        assertNull(segmentNameProblem("Ridge 2"))
        assertNull(segmentNameProblem("Upper-Ridge"))
        assertNull(segmentNameProblem("Лесная 3"))
    }

    @Test
    fun `an empty field is not yet a problem`() {
        assertNull(segmentNameProblem(""))
        assertNull(segmentNameProblem("   "))
    }

    @Test
    fun `a name has to say something`() {
        assertNotNull(segmentNameProblem("101"))
        assertNotNull(segmentNameProblem("2 3"))
    }

    @Test
    fun `numbers stand apart from words`() {
        assertNotNull(segmentNameProblem("Trail2"))
        assertNotNull(segmentNameProblem("2Trail"))
    }

    @Test
    fun `one alphabet per name`() {
        assertNotNull(segmentNameProblem("Лесная trail"))
        assertNotNull(segmentNameProblem("Ridgeline лес"))
    }

    @Test
    fun `punctuation is refused`() {
        assertNotNull(segmentNameProblem("Ridge!"))
        assertNotNull(segmentNameProblem("Ridge / Lower"))
    }

    @Test
    fun `normalization collapses spaces and capitalizes`() {
        assertEquals("Ridge 2", normalizeSegmentName("ridge   2"))
        assertEquals("Ridgeline", normalizeSegmentName("  ridgeline"))
        assertEquals("Ridge ", normalizeSegmentName("ridge "))
    }
}
