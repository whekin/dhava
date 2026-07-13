package com.dhava.feature.activity

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackMapTest {

    @Test
    fun `manual pause creates separate drawable sections`() {
        val beforePause = MapTrackPoint(41.7, 44.8, sectionId = 0)
        val beforePauseEnd = MapTrackPoint(41.71, 44.81, sectionId = 0)
        val afterResume = MapTrackPoint(42.0, 45.0, sectionId = 1)
        val afterResumeEnd = MapTrackPoint(42.01, 45.01, sectionId = 1)

        val sections = listOf(
            beforePause,
            beforePauseEnd,
            afterResume,
            afterResumeEnd,
        ).continuousSections()

        assertEquals(
            listOf(
                listOf(beforePause, beforePauseEnd),
                listOf(afterResume, afterResumeEnd),
            ),
            sections,
        )
    }
}
